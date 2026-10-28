package com.vogelink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.vogelink.core.LinkHub
import com.vogelink.core.LinkState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Cliente GATT del cuadro. Todo (callbacks de Android incluidos) corre en un único hilo propio,
 * así que el estado interno no necesita sincronización.
 */
@SuppressLint("MissingPermission")
class TftBleClient(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        // Subcadenas aceptadas por la app oficial (tb/e.java); cubren varios fabricantes de cuadro.
        val SERVICE_KEYS = listOf(
            "fe695f12-fd7b-4f9b-98cc-ee6cf57a776e", "00003719-0000-1000-8000-00805f9b34fb",
            "5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e", "5456534d", "0000fff0", "0000b360",
        )
        val WRITE_KEYS = listOf("6052202a-2928-4131-a2d0-456d5673ed2f", "00005352", "0000fff1")
        val NOTIFY_KEYS = listOf(
            "ab9938d5-c354-4e2b-94f4-364e16ebcd33", "00005354", "0000fff2", "0000b362", "0000fff1",
        )
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val RETRY_DELAYS_S = longArrayOf(2, 3, 5, 10, 15, 30)

        // Tras estos intentos directos se pasa a autoConnect: Android espera a que la moto aparezca sin límite de tiempo.
        private const val DIRECT_ATTEMPTS = 3
        private const val OP_TIMEOUT_MS = 1500L

        fun matches(uuid: UUID, keys: List<String>): Boolean {
            val s = uuid.toString()
            return keys.any { s.contains(it, ignoreCase = true) }
        }
    }

    private sealed class GattOp {
        val done = CompletableDeferred<Boolean>()

        class Write(val data: ByteArray) : GattOp()
        class Descriptor(val descriptor: BluetoothGattDescriptor, val value: ByteArray) : GattOp()
    }

    val state = MutableStateFlow(LinkState.STOPPED)
    val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private val thread = HandlerThread("tft-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher()

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val ops = Channel<GattOp>(Channel.UNLIMITED)
    private var pending: CompletableDeferred<Boolean>? = null
    private var targetAddress: String? = null
    private var attempts = 0
    private var retryJob: Job? = null

    init {
        scope.launch(dispatcher) { processOps() }
    }

    val isReady: Boolean get() = state.value == LinkState.READY

    fun connect(address: String) {
        scope.launch(dispatcher) {
            val busy = state.value in setOf(LinkState.CONNECTING, LinkState.DISCOVERING, LinkState.READY)
            if (targetAddress == address && busy) return@launch
            closeGatt()
            targetAddress = address
            attempts = 0
            open()
        }
    }

    fun disconnect() {
        scope.launch(dispatcher) {
            targetAddress = null
            retryJob?.cancel()
            closeGatt()
            state.value = LinkState.STOPPED
        }
    }

    fun release() {
        disconnect()
        handler.post { thread.quitSafely() }
    }

    /** Encola una escritura y espera a que el cuadro la confirme. */
    suspend fun send(frame: ByteArray): Boolean {
        if (!isReady) return false
        val op = GattOp.Write(frame)
        ops.send(op)
        return op.done.await()
    }

    private fun open() {
        val address = targetAddress ?: return
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            LinkHub.log("Bluetooth desactivado; se reintentará")
            scheduleRetry()
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            LinkHub.log("Dirección no válida: $address")
            state.value = LinkState.ERROR
            return
        }
        val passive = attempts >= DIRECT_ATTEMPTS
        state.value = LinkState.CONNECTING
        LinkHub.log("Conectando a $address (intento ${attempts + 1}${if (passive) ", en espera pasiva" else ""})")
        gatt = device.connectGatt(
            context, passive, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler,
        )
    }

    private fun scheduleRetry() {
        if (targetAddress == null) return
        val delayS = RETRY_DELAYS_S[minOf(attempts, RETRY_DELAYS_S.lastIndex)]
        attempts++
        state.value = LinkState.WAITING_RETRY
        retryJob?.cancel()
        retryJob = scope.launch(dispatcher) {
            delay(delayS * 1000)
            open()
        }
    }

    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        writeChar = null
        pending?.complete(false)
        try {
            g.disconnect()
            g.close()
        } catch (_: SecurityException) {
        }
    }

    private suspend fun processOps() {
        for (op in ops) {
            val g = gatt
            if (g == null || state.value != LinkState.READY && op is GattOp.Write) {
                op.done.complete(false)
                continue
            }
            val result = CompletableDeferred<Boolean>()
            pending = result
            val started = try {
                when (op) {
                    is GattOp.Write -> writeChar?.let { writeCharacteristic(g, it, op.data) } ?: false
                    is GattOp.Descriptor -> writeDescriptor(g, op.descriptor, op.value)
                }
            } catch (_: SecurityException) {
                false
            }
            val ok = started && (withTimeoutOrNull(OP_TIMEOUT_MS) { result.await() } ?: false)
            pending = null
            op.done.complete(ok)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type
            c.value = data
            g.writeCharacteristic(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    private fun setupCharacteristics(g: BluetoothGatt) {
        val services = g.services.filter { matches(it.uuid, SERVICE_KEYS) }
        var write: BluetoothGattCharacteristic? = null
        val notify = mutableListOf<BluetoothGattCharacteristic>()
        for (service in services) {
            for (c in service.characteristics) {
                if (matches(c.uuid, WRITE_KEYS)) write = c
                if (matches(c.uuid, NOTIFY_KEYS)) notify += c
            }
        }
        if (write == null) {
            LinkHub.log("No es un cuadro compatible. Servicios: " + g.services.joinToString { it.uuid.toString() })
            targetAddress = null
            closeGatt()
            state.value = LinkState.ERROR
            return
        }
        writeChar = write
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        for (c in notify) {
            g.setCharacteristicNotification(c, true)
            val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            val descriptors = c.getDescriptor(CCCD)?.let { listOf(it) } ?: c.descriptors
            for (d in descriptors) ops.trySend(GattOp.Descriptor(d, value))
        }
        state.value = LinkState.READY
        LinkHub.log("Listo. Escritura: ${write.uuid}; notificaciones: ${notify.joinToString { it.uuid.toString() }}")
    }

    private fun deliver(value: ByteArray?) {
        if (value != null) incoming.tryEmit(value.copyOf())
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) {
                g.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                attempts = 0
                state.value = LinkState.DISCOVERING
                LinkHub.log("Conectado; descubriendo servicios")
                // Pequeña espera: algunos módulos BLE fallan si se descubre nada más conectar.
                handler.postDelayed({ if (gatt == g) g.discoverServices() }, 400)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                LinkHub.log("Desconectado (estado $status)")
                closeGatt()
                scheduleRetry()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g != gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                LinkHub.log("Fallo al descubrir servicios (estado $status)")
                g.disconnect()
                return
            }
            setupCharacteristics(g)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            // En API 33+ Android llama a la variante nueva (que por defecto delega en esta); aquí solo cuenta en versiones antiguas.
            if (Build.VERSION.SDK_INT < 33) deliver(c.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(value)
        }
    }
}
