package com.vogelink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class ScanHit(val name: String?, val address: String, val rssi: Int, val looksLikeTft: Boolean)

/** Búsqueda de cuadros: por emparejamiento clásico («VOGE-…») y por escaneo BLE. */
@SuppressLint("MissingPermission")
class TftScanner(context: Context) {

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** El cuadro se empareja como manos libres con nombre «VOGE-xxxx»; el BLE anuncia ese mismo nombre. */
    fun bondedTftNames(): List<String> = try {
        adapter?.bondedDevices.orEmpty().mapNotNull { it.name }.filter { it.startsWith("VOGE-", ignoreCase = true) }
    } catch (_: SecurityException) {
        emptyList()
    }

    fun scan(): Flow<ScanHit> = callbackFlow {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth no disponible o desactivado"))
            return@callbackFlow
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                val advertisesService = result.scanRecord?.serviceUuids.orEmpty()
                    .any { TftBleClient.matches(it.uuid, TftBleClient.SERVICE_KEYS) }
                val looksLike = advertisesService || (name?.contains("VOGE", ignoreCase = true) == true)
                trySend(ScanHit(name, result.device.address, result.rssi, looksLike))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("El escaneo falló (código $errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        awaitClose {
            try {
                scanner.stopScan(cb)
            } catch (_: Exception) {
            }
        }
    }
}
