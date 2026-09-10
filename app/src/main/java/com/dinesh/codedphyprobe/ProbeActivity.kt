package com.dinesh.codedphyprobe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random



@SuppressLint("MissingPermission")
class ProbeActivity : Activity() {

    private val adapter: BluetoothAdapter? by lazy { getSystemService(BluetoothManager::class.java)?.adapter }
    private val myId = Random.nextInt(1, 256)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var out: TextView
    private lateinit var toggle: Button

    private var codedPrimary = true   // false = 1M-primary fallback (~6 dB penalty)
    private var reqTx = 1             // TX_POWER_HIGH == 1 dBm; probe whether more is granted
    private var grantedTx: Int? = null
    private var advError: Int? = null
    private var scanError: Int? = null

    private var count = 0             // packets seen since last tick
    private var pps = 0
    private var total = 0
    private var rssi: Int? = null
    private var rssiMin: Int? = null
    private var rssiMax: Int? = null
    private var primaryPhy = 0
    private var secondaryPhy = 0
    private var peerId: Int? = null
    private var peerTx: Int? = null

    private val advCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            grantedTx = txPower
            advError = if (status == ADVERTISE_SUCCESS) null else status
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            count++; total++
            rssi = r.rssi
            rssiMin = minOf(r.rssi, rssiMin ?: r.rssi)
            rssiMax = maxOf(r.rssi, rssiMax ?: r.rssi)
            primaryPhy = r.primaryPhy
            secondaryPhy = r.secondaryPhy
            peerTx = r.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT }
            peerId = r.scanRecord?.getManufacturerSpecificData(COMPANY_ID)?.firstOrNull()?.toInt()?.and(0xFF)
        }

        override fun onScanFailed(code: Int) { scanError = code }
    }

    private val tick = object : Runnable {
        override fun run() { pps = count; count = 0; render(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        out = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(Color.BLACK) }
        toggle = Button(this).apply { text = "Toggle primary PHY"; setOnClickListener { codedPrimary = !codedPrimary; reset(); start() } }
        val reset = Button(this).apply { text = "Restart (keep PHY)"; setOnClickListener { reset(); start(); render() } }
        val txStep = Button(this).apply {
            text = "Request more TX power"
            setOnClickListener { reqTx = when (reqTx) { 1 -> 4; 4 -> 7; 7 -> 10; 10 -> 13; else -> 1 }; reset(); start() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            keepScreenOn = true
            addView(out, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(toggle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(reset, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(txStep, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(root)
        padForInsets(root, (16 * resources.displayMetrics.density).toInt())

        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED })
            requestPermissions(perms, 1) else start()

        ui.post(tick)
    }

    override fun onRequestPermissionsResult(req: Int, p: Array<out String>, res: IntArray) {
        if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) start()
    }

    private fun reset() { count = 0; pps = 0; total = 0; rssi = null; rssiMin = null; rssiMax = null; peerId = null }

    private fun start() {
        val a = adapter ?: return
        val advertiser = a.bluetoothLeAdvertiser ?: return
        val scanner = a.bluetoothLeScanner ?: return

        runCatching { advertiser.stopAdvertisingSet(advCallback) }
        runCatching { scanner.stopScan(scanCallback) }
        grantedTx = null; advError = null; scanError = null

        advertiser.startAdvertisingSet(
            AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setConnectable(false)
                .setScannable(false)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(reqTx)
                .setPrimaryPhy(if (codedPrimary) BluetoothDevice.PHY_LE_CODED else BluetoothDevice.PHY_LE_1M)
                .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setIncludeTxPower(true)
                .build(),
            AdvertiseData.Builder().addManufacturerData(COMPANY_ID, byteArrayOf(myId.toByte())).build(),
            null, null, null, advCallback
        )

        scanner.startScan(
            listOf(ScanFilter.Builder().setManufacturerData(COMPANY_ID, byteArrayOf()).build()),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setLegacy(false)                       // MANDATORY: default true drops all extended advs
                .setPhy(if (a.isLeCodedPhySupported) ScanSettings.PHY_LE_ALL_SUPPORTED else BluetoothDevice.PHY_LE_1M)
                .build(),
            scanCallback
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advCallback) }
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun phy(p: Int) = when (p) { 1 -> "1M"; 2 -> "2M"; 3 -> "CODED"; else -> "-" }

    private fun render() {
        val a = adapter
        out.text = """
            === CAPABILITY (this phone) ===
            Bluetooth on   : ${a?.isEnabled}
            ExtendedAdv    : ${a?.isLeExtendedAdvertisingSupported}
            CodedPHY       : ${a?.isLeCodedPhySupported}   <- claims only
            2M PHY         : ${a?.isLe2MPhySupported}
            MaxAdvDataLen  : ${a?.leMaximumAdvertisingDataLength}

            === TX ===
            My ID          : $myId
            Primary PHY    : ${if (codedPrimary) "CODED" else "1M (fallback)"}
            Secondary PHY  : CODED
            Requested TX   : $reqTx dBm
            Granted TX pwr : ${grantedTx?.let { "$it dBm" } ?: "(pending)"}
            Adv error      : ${advError ?: "none"}

            === RX ===
            Scan error     : ${scanError ?: "none"}
            Packets/sec    : $pps
            Total received : $total
            Peer ID        : ${peerId ?: "-"}
            RSSI           : ${rssi?.let { "$it dBm" } ?: "-"}  (min ${rssiMin ?: "-"} / max ${rssiMax ?: "-"})
            Arrived on     : primary=${phy(primaryPhy)} secondary=${phy(secondaryPhy)}
            Peer adv TX    : ${peerTx?.let { "$it dBm" } ?: "-"}
        """.trimIndent()
    }
}
