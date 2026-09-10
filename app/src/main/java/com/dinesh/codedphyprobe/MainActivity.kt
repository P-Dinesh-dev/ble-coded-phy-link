package com.dinesh.codedphyprobe

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : Activity() {

    private val adapter: BluetoothAdapter? by lazy { getSystemService(BluetoothManager::class.java)?.adapter }
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("blelink", Context.MODE_PRIVATE) }
    private val myId by lazy {
        prefs.getInt("id", 0).takeIf { it != 0 } ?: Random.nextInt(1, 256).also { prefs.edit().putInt("id", it).apply() }
    }

    private lateinit var radio: Radio
    private val inbox = Inbox()
    private val acks = ArrayDeque<ByteArray>()
    private var out: Outbox? = null
    private var outAt = 0L
    private var msgId = 0

    private var sent = 0
    private var delivered = 0
    private var lastRxAt = 0L
    private var pps = 0
    private var tick = 0

    private lateinit var pass: EditText
    private lateinit var input: EditText
    private lateinit var dist: EditText
    private lateinit var log: TextView
    private lateinit var status: TextView
    private val csv by lazy { File(getExternalFilesDir(null), "link_log.csv") }
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pass = EditText(this).apply { hint = "shared passphrase (must match on both phones)"; setText(prefs.getString("pass", "anits300")) }
        input = EditText(this).apply { hint = "message" }
        dist = EditText(this).apply { hint = "distance label for CSV, e.g. 300m-wall" }
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(Color.BLACK); movementMethod = ScrollingMovementMethod() }
        status = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(Color.DKGRAY) }

        val send = Button(this).apply { text = "Send"; setOnClickListener { send() } }
        val mark = Button(this).apply { text = "Mark distance in CSV"; setOnClickListener { row("MARK") } }
        val probe = Button(this).apply { text = "Open Phase 0 probe"; setOnClickListener { startActivity(Intent(this@MainActivity, ProbeActivity::class.java)) } }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            keepScreenOn = true
            setPadding(28, 40, 28, 28)
            listOf(status, pass, input, send, log, dist, mark, probe).forEach {
                addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        })

        radio = Radio(adapter, ::nextPacket) { p, rssi -> ui.post { got(p, rssi) } }

        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        this.perms = perms
        if (!granted()) requestPermissions(perms, 1)

        if (!csv.exists()) csv.appendText("time,label,event,pps,rssi,phy,granted_tx,sent,delivered\n")
        ui.post(second)
    }

    private lateinit var perms: Array<String>
    private fun granted() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(req: Int, p: Array<out String>, res: IntArray) {
        if (granted()) radio.start()
    }

    // Only one advertising set can run at a time, so hand the radio over when the probe screen opens.
    override fun onResume() { super.onResume(); if (granted()) radio.start() }
    override fun onPause() { super.onPause(); radio.stop() }

    /** Polled by Radio. ACKs jump the queue; otherwise cycle the current message's symbols. */
    private fun nextPacket(): ByteArray? {
        acks.removeFirstOrNull()?.let { return it }
        val o = out ?: return null
        if (o.acked || System.currentTimeMillis() - outAt > 30_000) { out = null; return null }
        return o.next()
    }

    private fun send() {
        val text = input.text.toString().ifBlank { return }
        val blob = seal(keyFrom(pass.text.toString()), text)
        if (blob.size > MAX_BLOB) { line("! too long (${blob.size}B, max $MAX_BLOB) — shorten it"); return }
        msgId = (msgId + 1) and 0xFF
        out = Outbox(myId, msgId, blob)
        outAt = System.currentTimeMillis()
        sent++
        prefs.edit().putString("pass", pass.text.toString()).apply()
        input.setText("")
        line("> $text   [${blob.size}B, k=${out!!.k}]")
        row("TX")
    }

    private fun got(p: ByteArray, rssi: Int) {
        lastRxAt = System.currentTimeMillis()
        if (p[0].toInt() and 0xFF == myId) return                 // our own packet echoed back

        if (isAck(p)) {
            val acked = p[1].toInt() and 0xFF
            if (out?.msgId == acked) { out!!.acked = true; delivered++; line("  ✓ delivered"); row("ACK") }
            return
        }

        val blob = inbox.add(p) ?: return
        val text = open(keyFrom(pass.text.toString()), blob)
        val sender = p[0].toInt() and 0xFF
        if (text == null) { line("! auth failed from $sender — discarded"); row("BADTAG"); return }
        acks.addLast(ackPacket(myId, p[1].toInt() and 0xFF))
        line("< $text   [$sender, ${rssi}dBm]")
        row("RX")
    }

    private val second = object : Runnable {
        override fun run() {
            pps = radio.seen - tick; tick = radio.seen
            status.text = "id=$myId  pkt/s=$pps  rssi=${radio.lastRssi ?: "-"}  phy=${radio.phyName(radio.lastPhy)}  " +
                    "tx=${radio.grantedTx ?: "-"}dBm  sent=$sent  ack=$delivered" + (radio.error?.let { "  ERR $it" } ?: "")
            row("")
            ui.postDelayed(this, 1000)
        }
    }

    private fun line(s: String) {
        log.append("${stamp.format(Date())}  $s\n")
        (log.layout?.getLineTop(log.lineCount) ?: 0).let { if (it > log.height) log.scrollTo(0, it - log.height) }
    }

    private fun row(event: String) = runCatching {
        val label = dist.text.toString().replace(',', ' ')   // keep the CSV parseable
        csv.appendText("${stamp.format(Date())},$label,$event,$pps,${radio.lastRssi ?: ""},${radio.phyName(radio.lastPhy)},${radio.grantedTx ?: ""},$sent,$delivered\n")
    }

    override fun onDestroy() { super.onDestroy(); ui.removeCallbacksAndMessages(null); radio.stop() }
}
