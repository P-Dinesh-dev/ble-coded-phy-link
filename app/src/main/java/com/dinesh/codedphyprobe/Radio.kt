package com.dinesh.codedphyprobe

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper

const val COMPANY_ID = 0xFFFF

/**
 * Connectionless BLE link: advertises on Coded PHY and scans at the same time.
 * No pairing, no bonding, no connection — one side shouts, the other listens.
 *
 * [nextPacket] is polled for the next payload to broadcast; return null when idle.
 * [onPacket] fires for every payload heard from a peer.
 */
@SuppressLint("MissingPermission")
class Radio(
    private val adapter: BluetoothAdapter?,
    private val nextPacket: () -> ByteArray?,
    private val onPacket: (ByteArray, Int) -> Unit
) {
    var grantedTx: Int? = null; private set
    var lastRssi: Int? = null; private set
    var lastPhy = 0; private set
    var seen = 0; private set
    var error: String? = null; private set

    var codedPrimary = true
    var reqTx = 1

    private val h = Handler(Looper.getMainLooper())
    private var set: AdvertisingSet? = null

    private val advCb = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(s: AdvertisingSet?, tx: Int, status: Int) {
            set = s; grantedTx = tx
            if (status != ADVERTISE_SUCCESS) error = "adv start $status" else pump()
        }

        // Self-clocking: queue the next symbol once the controller confirms this one,
        // paced to the ~100 ms advertising interval so every symbol actually goes out.
        override fun onAdvertisingDataSet(s: AdvertisingSet?, status: Int) = pump(100)
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val d = r.scanRecord?.getManufacturerSpecificData(COMPANY_ID) ?: return
            seen++; lastRssi = r.rssi; lastPhy = r.primaryPhy
            onPacket(d, r.rssi)
        }

        override fun onScanFailed(code: Int) { error = "scan $code" }
    }

    private fun pump(delay: Long = 0) {
        h.postDelayed({
            val p = nextPacket()
            if (p == null) pump(200)
            else set?.setAdvertisingData(AdvertiseData.Builder().addManufacturerData(COMPANY_ID, p).build())
        }, delay)
    }

    fun start() {
        val a = adapter ?: return
        val advertiser = a.bluetoothLeAdvertiser ?: run { error = "bluetooth off"; return }
        val scanner = a.bluetoothLeScanner ?: run { error = "bluetooth off"; return }
        stop()
        error = null

        advertiser.startAdvertisingSet(
            AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setConnectable(false)
                .setScannable(false)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(reqTx)
                .setPrimaryPhy(if (codedPrimary) BluetoothDevice.PHY_LE_CODED else BluetoothDevice.PHY_LE_1M)
                .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                .build(),
            AdvertiseData.Builder().addManufacturerData(COMPANY_ID, ByteArray(HDR)).build(),
            null, null, null, advCb
        )

        scanner.startScan(
            listOf(ScanFilter.Builder().setManufacturerData(COMPANY_ID, byteArrayOf()).build()),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setLegacy(false)                       // default true silently drops all extended advs
                .setPhy(if (a.isLeCodedPhySupported) ScanSettings.PHY_LE_ALL_SUPPORTED else BluetoothDevice.PHY_LE_1M)
                .build(),
            scanCb
        )
    }

    fun stop() {
        h.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advCb) }
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    fun phyName(p: Int) = when (p) { 1 -> "1M"; 2 -> "2M"; 3 -> "CODED"; else -> "-" }
}
