package com.vogelink.core

import com.vogelink.protocol.Telemetry
import com.vogelink.protocol.TftMessage
import com.vogelink.protocol.TripCounters
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class LinkState(val label: String) {
    STOPPED("Detenido"),
    NO_DEVICE("Sin moto configurada"),
    SCANNING("Buscando la moto…"),
    CONNECTING("Conectando…"),
    DISCOVERING("Descubriendo servicios…"),
    READY("Conectado"),
    WAITING_RETRY("Reintentando…"),
    ERROR("Error"),
}

data class ConnectionInfo(
    val state: LinkState = LinkState.STOPPED,
    val deviceName: String? = null,
    val detail: String = "",
)

/** Estado compartido entre el servicio, el lector de notificaciones y la interfaz. */
object LinkHub {
    val connection = MutableStateFlow(ConnectionInfo())
    val serviceRunning = MutableStateFlow(false)
    val telemetry = MutableStateFlow<Telemetry?>(null)
    val tripCounters = MutableStateFlow<TripCounters?>(null)
    val dashStates = MutableStateFlow<TftMessage.States?>(null)
    val lastEvent = MutableStateFlow<Int?>(null)

    private const val MAX_LOG = 400
    private val logLines = ArrayDeque<String>()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    val logRevision = MutableStateFlow(0)

    fun log(message: String) {
        synchronized(logLines) {
            logLines.addLast("${LocalTime.now().format(timeFormat)}  $message")
            while (logLines.size > MAX_LOG) logLines.removeFirst()
        }
        logRevision.value++
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    fun clearLog() {
        synchronized(logLines) { logLines.clear() }
        logRevision.value++
    }
}
