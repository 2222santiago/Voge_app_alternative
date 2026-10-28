package com.vogelink.nav

import android.graphics.Bitmap
import com.vogelink.core.LinkHub
import com.vogelink.protocol.Maneuver
import com.vogelink.protocol.NavInfo
import kotlinx.coroutines.flow.MutableStateFlow

data class RawNotification(
    val packageName: String,
    val text: NotificationText,
    val icon: Bitmap?,
    val iconHash: String?,
    val timeMs: Long,
)

data class UnknownIcon(val hash: String, val icon: Bitmap, val sample: String)

/** Convierte las notificaciones de la app de navegación en [NavInfo] para el cuadro. */
object NavBridge {
    private const val MAX_UNKNOWN = 24
    private const val MAX_HISTORY = 40
    private const val STALE_MS = 30_000L

    /** Navegación real detectada en notificaciones; null si no hay ninguna activa. */
    val current = MutableStateFlow<NavInfo?>(null)

    /** Si no es null, se envía esto en lugar de [current] (pantalla de pruebas). */
    val testOverride = MutableStateFlow<NavInfo?>(null)

    val lastRaw = MutableStateFlow<RawNotification?>(null)
    val unknownIcons = MutableStateFlow<List<UnknownIcon>>(emptyList())
    val listenerConnected = MutableStateFlow(false)
    val history = MutableStateFlow<List<RawNotification>>(emptyList())

    /** Momento (ms) de la última notificación en curso de una app de navegación, se entienda o no. */
    val lastCandidateMs = MutableStateFlow(0L)

    /** Momento (ms) de la última notificación que se pudo interpretar. */
    val lastParsedMs = MutableStateFlow(0L)

    /** Desde cuándo la notificación activa no trae instrucciones reconocibles; null si sí las trae. */
    val unparsedSinceMs = MutableStateFlow<Long?>(null)

    /** Devuelve true si la notificación se ha reconocido como navegación activa. */
    @Synchronized
    fun onNotification(raw: RawNotification): Boolean {
        lastRaw.value = raw
        lastCandidateMs.value = raw.timeMs
        history.value = (history.value + raw).takeLast(MAX_HISTORY)
        val parsed = NotificationParser.parse(raw.text)
        val learned = raw.iconHash?.let(IconMemory::lookup)
        if (parsed.distanceM == null && parsed.maneuver == null && learned == null) return false

        if (learned == null && raw.icon != null && raw.iconHash != null) rememberUnknown(raw)

        val exit = learned?.roundaboutExit ?: parsed.roundaboutExit
        val info = NavInfo(
            distanceToManeuverM = parsed.distanceM ?: current.value?.distanceToManeuverM ?: 0,
            maneuver = learned?.maneuver ?: parsed.maneuver ?: Maneuver.STRAIGHT,
            nextRoad = parsed.road,
            routeRemainingM = parsed.remainingM ?: 0,
            routeRemainingS = parsed.remainingS ?: 0,
            eta = parsed.eta,
            roundaboutSector = exit?.let(NotificationParser::sectorForExit) ?: 0,
        )
        if (info != current.value) {
            LinkHub.log("Nav: ${info.maneuver.label} a ${info.distanceToManeuverM} m · «${info.nextRoad}»" +
                if (learned != null) " (icono aprendido)" else "")
        }
        current.value = info
        lastParsedMs.value = raw.timeMs
        unparsedSinceMs.value = null
        return true
    }

    /**
     * La notificación activa sigue ahí pero sin instrucciones (p. ej. «Recalculando…»).
     * Se mantiene la última maniobra un rato en vez de cortar la navegación en el cuadro.
     */
    @Synchronized
    fun onUnparsedUpdate(now: Long) {
        if (current.value == null || unparsedSinceMs.value != null) return
        unparsedSinceMs.value = now
        LinkHub.log("Notificación de navegación sin instrucciones; se mantiene la última maniobra")
    }

    /** Si la notificación activa lleva [STALE_MS] sin instrucciones, deja de enviarlas. */
    @Synchronized
    fun expireStale(now: Long) {
        val since = unparsedSinceMs.value ?: return
        if (now - since >= STALE_MS) clear("${STALE_MS / 1000} s sin instrucciones en la notificación")
    }

    /** Texto de las últimas notificaciones leídas, para compartirlo al depurar el parser. */
    fun historyText(): String = history.value.joinToString("\n\n") { r ->
        val t = r.text
        "[${r.packageName}] título=«${t.title}» texto=«${t.text}» sub=«${t.subText}» largo=«${t.bigText}» " +
            "info=«${t.infoText}» resumen=«${t.summaryText}» icono=${r.iconHash ?: "-"}"
    }

    @Synchronized
    fun clear(reason: String) {
        if (current.value != null) LinkHub.log("Fin de navegación: $reason")
        current.value = null
        unparsedSinceMs.value = null
    }

    /** Tras etiquetar un icono, recalcula el estado con la última notificación. */
    fun relabel(hash: String, label: IconLabel) {
        IconMemory.save(hash, label)
        unknownIcons.value = unknownIcons.value.filterNot { IconHasher.distance(it.hash, hash) <= 20 }
        val raw = lastRaw.value
        if (raw != null && current.value != null) onNotification(raw)
    }

    private fun rememberUnknown(raw: RawNotification) {
        val hash = raw.iconHash ?: return
        val icon = raw.icon ?: return
        val list = unknownIcons.value
        if (list.any { IconHasher.distance(it.hash, hash) <= 20 }) return
        val sample = listOf(raw.text.title, raw.text.text).filter { it.isNotBlank() }.joinToString(" — ")
        unknownIcons.value = (list + UnknownIcon(hash, icon, sample)).takeLast(MAX_UNKNOWN)
    }
}
