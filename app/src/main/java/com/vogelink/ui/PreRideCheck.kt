package com.vogelink.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vogelink.core.LinkHub
import com.vogelink.core.LinkState
import com.vogelink.nav.NavBridge
import com.vogelink.nav.NavNotificationListener
import kotlinx.coroutines.delay

private enum class Mark { OK, PENDING, FAIL }

private class CheckItem(
    val mark: Mark,
    val title: String,
    val detail: String? = null,
    val action: String? = null,
    val onAction: (() -> Unit)? = null,
)

/** Notificación vista pero no entendida: solo cuenta si es reciente (Maps la actualiza a menudo). */
private const val UNPARSED_RECENT_MS = 120_000L

/**
 * Comprobación antes de salir: acceso a notificaciones, lector conectado, Google Maps leído y moto conectada.
 * Se repite sola al volver a la app, así que basta con iniciar la ruta en Maps y regresar aquí.
 */
@Composable
fun PreRideCheck() {
    val ctx = LocalContext.current
    val resume = rememberResumeCount()
    val access = remember(resume) { NavNotificationListener.hasAccess(ctx) }
    val listener by NavBridge.listenerConnected.collectAsStateWithLifecycle()
    val current by NavBridge.current.collectAsStateWithLifecycle()
    val lastCandidate by NavBridge.lastCandidateMs.collectAsStateWithLifecycle()
    val lastParsed by NavBridge.lastParsedMs.collectAsStateWithLifecycle()
    val unparsedSince by NavBridge.unparsedSinceMs.collectAsStateWithLifecycle()
    val raw by NavBridge.lastRaw.collectAsStateWithLifecycle()
    val conn by LinkHub.connection.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }
    var runs by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(5_000)
        }
    }

    LaunchedEffect(resume, runs) {
        checking = true
        if (!NavNotificationListener.rescan() && NavNotificationListener.requestRebindIfNeeded(ctx)) {
            delay(3_000)
            if (!NavBridge.listenerConnected.value) NavNotificationListener.forceRebind(ctx)
            delay(2_000)
            NavNotificationListener.rescan()
        }
        now = System.currentTimeMillis()
        checking = false
    }

    val openMaps: () -> Unit = {
        ctx.packageManager.getLaunchIntentForPackage(NavNotificationListener.GOOGLE_MAPS)?.let(ctx::startActivity)
    }

    val items = buildList {
        add(
            if (access) {
                CheckItem(Mark.OK, "Acceso a notificaciones")
            } else {
                CheckItem(
                    Mark.FAIL, "Acceso a notificaciones",
                    "Sin él no se puede leer Google Maps." + if (Build.VERSION.SDK_INT >= 33) {
                        " Si Android lo bloquea como «ajuste restringido»: Ajustes → Apps → VogeLink → ⋮ → " +
                            "Permitir ajustes restringidos, y vuelve a intentarlo."
                    } else {
                        ""
                    },
                    "Dar acceso",
                ) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            },
        )
        add(
            when {
                !access -> CheckItem(Mark.PENDING, "Lector de notificaciones", "Necesita el acceso anterior.")
                listener -> CheckItem(Mark.OK, "Lector de notificaciones activo")
                checking -> CheckItem(Mark.PENDING, "Lector de notificaciones", "Reconectando…")
                else -> CheckItem(
                    Mark.FAIL, "Lector de notificaciones desconectado",
                    "Android lo ha parado. Si «Reiniciar» no lo arregla, quita el acceso a notificaciones y vuelve a darlo.",
                    "Reiniciar",
                ) { runs++ }
            },
        )
        val nav = current
        add(
            when {
                nav != null && unparsedSince == null -> CheckItem(
                    Mark.OK, "Leyendo ${appName(raw?.packageName)}",
                    "${nav.maneuver.label} en ${formatDistance(nav.distanceToManeuverM)}" +
                        if (nav.nextRoad.isNotEmpty()) " · ${nav.nextRoad}" else "",
                )
                nav != null -> CheckItem(
                    Mark.PENDING, "${appName(raw?.packageName)} sin instrucciones ahora mismo",
                    "Suele ser «Recalculando…» o falta de GPS. Se mantiene la última maniobra en el cuadro.",
                )
                lastCandidate > lastParsed && now - lastCandidate < UNPARSED_RECENT_MS -> CheckItem(
                    Mark.FAIL, "Se ve la notificación de ${appName(raw?.packageName)} pero no se entiende",
                    "Texto: «${raw?.text?.title.orEmpty()} ${raw?.text?.text.orEmpty()}». " +
                        "Comparte el registro desde Ajustes para ajustar el lector.",
                )
                else -> CheckItem(
                    Mark.PENDING, "Inicia la ruta en Google Maps",
                    "Al volver aquí se comprueba solo. Si con la ruta en marcha sigue sin aparecer, revisa que " +
                        "Google Maps tenga las notificaciones activadas (Ajustes → Apps → Maps → Notificaciones).",
                )
            },
        )
        add(
            if (conn.state == LinkState.READY) {
                CheckItem(Mark.OK, "Moto conectada" + (conn.deviceName?.let { " ($it)" } ?: ""))
            } else {
                CheckItem(Mark.PENDING, "Moto: ${conn.state.label.lowercase()}", "Pon el contacto y pulsa «Conectar».")
            },
        )
    }

    val ready = items.all { it.mark == Mark.OK }
    InfoCard {
        SectionTitle("Antes de salir")
        Text(
            if (ready) "Todo listo: puedes salir." else "Falta: ${items.count { it.mark != Mark.OK }} de ${items.size}",
            color = if (ready) okColor() else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (ready) FontWeight.Bold else FontWeight.Normal,
        )
        for (item in items) CheckRow(item)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runs++ }, enabled = !checking) { Text(if (checking) "Comprobando…" else "Comprobar") }
            if (current == null) OutlinedButton(onClick = openMaps) { Text("Abrir Maps") }
        }
    }
}

@Composable
private fun CheckRow(item: CheckItem) {
    Row(verticalAlignment = Alignment.Top) {
        val (glyph, color) = when (item.mark) {
            Mark.OK -> "✓" to okColor()
            Mark.PENDING -> "○" to MaterialTheme.colorScheme.onSurfaceVariant
            Mark.FAIL -> "✗" to MaterialTheme.colorScheme.error
        }
        Text(glyph, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = if (item.mark == Mark.FAIL) color else Color.Unspecified)
            item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (item.action != null && item.onAction != null && item.mark == Mark.FAIL) {
            TextButton(onClick = item.onAction) { Text(item.action) }
        }
    }
}

@Composable
private fun okColor() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF2E7D32)

private fun appName(pkg: String?) = when (pkg) {
    NavNotificationListener.GOOGLE_MAPS, null -> "Google Maps"
    "com.waze" -> "Waze"
    else -> "OsmAnd"
}
