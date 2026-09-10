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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * targetSdk 35+ forces edge-to-edge, so content otherwise draws under the status and
 * navigation bars. Also pads for the IME so the composer rides above the keyboard.
 */
fun padForInsets(root: View, pad: Int) = ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
    val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
    v.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
    insets
}

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
    private var pps = 0
    private var tick = 0

    private lateinit var pass: EditText
    private lateinit var input: EditText
    private lateinit var dist: EditText
    private lateinit var log: TextView
    private lateinit var status: TextView
    private lateinit var caps: TextView
    private lateinit var panel: LinearLayout
    private val csv by lazy { File(getExternalFilesDir(null), "link_log.csv") }
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 13f
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1B5E20"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        caps = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f
            setTextColor(Color.parseColor("#37474F")); setBackgroundColor(Color.parseColor("#ECEFF1"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 13f
            setTextColor(Color.BLACK); setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            movementMethod = ScrollingMovementMethod()
            text = "waiting for peer…\n"
        }

        pass = EditText(this).apply { hint = "shared passphrase — must match on both phones"; textSize = 14f; setText(prefs.getString("pass", "anits300")) }
        input = EditText(this).apply { hint = "message"; textSize = 15f; maxLines = 3 }
        dist = EditText(this).apply { hint = "distance label for CSV, e.g. 300m-wall"; textSize = 14f }

        val send = Button(this).apply { text = "Send"; setOnClickListener { send() } }
        val mark = Button(this).apply { text = "Mark distance in CSV"; setOnClickListener { row("MARK"); line("· marked \"${dist.text}\"") } }
        val probe = Button(this).apply { text = "Phase 0 probe"; setOnClickListener { startActivity(Intent(this@MainActivity, ProbeActivity::class.java)) } }

        // Collapsed by default so the log and composer stay usable with the keyboard up.
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            listOf(pass, dist, mark, probe).forEach { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        }
        val gear = Button(this).apply {
            text = "⚙ Setup"
            setOnClickListener { panel.visibility = if (panel.visibility == View.GONE) View.VISIBLE else View.GONE }
        }

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(send, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(gear, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            keepScreenOn = true
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(caps, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // weight 1 => the log takes all remaining height and scrolls internally
            addView(log, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(6); bottomMargin = dp(6) })
            addView(composer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(panel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(root)
        padForInsets(root, dp(8))
        // Light background needs dark system-bar icons, or the clock is white-on-white.
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = true

        radio = Radio(adapter, ::nextPacket) { p, rssi -> ui.post { got(p, rssi) } }

        perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!granted()) requestPermissions(perms, 1)

        if (!csv.exists()) csv.appendText("time,label,event,pps,rssi,phy,granted_tx,sent,delivered\n")
        ui.post(second)
    }

    private lateinit var perms: Array<String>
    private fun granted() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(req: Int, p: Array<out String>, res: IntArray) {
        if (granted()) radio.start()
    }

    // Only one advertising set at a time, so hand the radio over when the probe screen opens.
    override fun onResume() { super.onResume(); if (granted()) radio.start() }
    override fun onPause() { super.onPause(); radio.stop() }

    /**
     * Polled by Radio. ACKs jump the queue, then the current message's symbols.
     * When idle we beacon instead of going silent, so pkt/s and RSSI stay live at
     * distance without anyone having to send a message — that is what the range walk needs.
     */
    private fun nextPacket(): ByteArray {
        acks.removeFirstOrNull()?.let { return it }
        val o = out ?: return beaconPacket(myId)
        if (o.acked || System.currentTimeMillis() - outAt > 30_000) { out = null; return beaconPacket(myId) }
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
            val a = adapter
            status.text = "id=$myId   pkt/s=$pps   rssi=${radio.lastRssi ?: "--"}dBm   phy=${radio.phyName(radio.lastPhy)}\n" +
                    "tx=${radio.grantedTx ?: "--"}dBm   sent=$sent   ack=$delivered" + (radio.error?.let { "   ERR $it" } ?: "")
            caps.text = "bt=${a?.isEnabled}  extAdv=${a?.isLeExtendedAdvertisingSupported}  " +
                    "coded=${a?.isLeCodedPhySupported}  2M=${a?.isLe2MPhySupported}  maxAdv=${a?.leMaximumAdvertisingDataLength}"
            row("")
            ui.postDelayed(this, 1000)
        }
    }

    private fun line(s: String) {
        log.append("${stamp.format(Date())}  $s\n")
        log.post {   // after layout, so lineCount and height are real
            val top = log.layout?.getLineTop(log.lineCount) ?: 0
            log.scrollTo(0, if (top > log.height) top - log.height else 0)
        }
    }

    private fun row(event: String) = runCatching {
        val label = dist.text.toString().replace(',', ' ')   // keep the CSV parseable
        csv.appendText("${stamp.format(Date())},$label,$event,$pps,${radio.lastRssi ?: ""},${radio.phyName(radio.lastPhy)},${radio.grantedTx ?: ""},$sent,$delivered\n")
    }

    override fun onDestroy() { super.onDestroy(); ui.removeCallbacksAndMessages(null); radio.stop() }
}
