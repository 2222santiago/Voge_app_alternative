package com.vogelink.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vogelink.core.LinkHub
import com.vogelink.core.LinkState
import com.vogelink.nav.IconLabel
import com.vogelink.nav.IconMemory
import com.vogelink.nav.NavBridge
import com.vogelink.nav.NotificationParser
import com.vogelink.nav.UnknownIcon
import com.vogelink.protocol.Maneuver
import com.vogelink.protocol.NavInfo
import kotlin.math.roundToInt

@Composable
fun NavScreen() {
    val ctx = LocalContext.current
    val resume = rememberResumeCount()
    val access = remember(resume) { AppPermissions.notificationAccess(ctx) }
    val current by NavBridge.current.collectAsStateWithLifecycle()
    val test by NavBridge.testOverride.collectAsStateWithLifecycle()
    val unknown by NavBridge.unknownIcons.collectAsStateWithLifecycle()
    val learned by IconMemory.labels.collectAsStateWithLifecycle()
    val raw by NavBridge.lastRaw.collectAsStateWithLifecycle()
    val conn by LinkHub.connection.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!access) {
            WarningCard("Permite el acceso a notificaciones para leer Google Maps / Waze / OsmAnd.", "Abrir") {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        InfoCard {
            SectionTitle("Navegación actual")
            val shown = test ?: current
            if (shown == null) {
                Text("No hay navegación activa. Inicia una ruta en Google Maps y aparecerá aquí.")
            } else {
                if (test != null) Text("Modo prueba", color = MaterialTheme.colorScheme.primary)
                NavSummary(shown)
            }
            if (conn.state != LinkState.READY) {
                Text("La moto no está conectada: no se envía nada al cuadro.", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (unknown.isNotEmpty()) {
            InfoCard {
                SectionTitle("Iconos sin identificar")
                Text(
                    "Google Maps dibuja la flecha como imagen. Indica qué maniobra es cada una; solo hay que hacerlo una vez.",
                    style = MaterialTheme.typography.bodySmall,
                )
                for (u in unknown) UnknownIconRow(u)
            }
        }

        InfoCard {
            SectionTitle("Iconos aprendidos: ${learned.size}")
            if (learned.isNotEmpty()) {
                Text(
                    learned.values.groupingBy { it.text }.eachCount().entries.joinToString { "${it.key} (${it.value})" },
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { IconMemory.clear() }) { Text("Olvidar todos") }
            }
        }

        TestPanel(active = test != null)

        raw?.let { r ->
            InfoCard {
                SectionTitle("Última notificación leída")
                Text(
                    buildString {
                        appendLine("app: ${r.packageName}")
                        appendLine("título: ${r.text.title}")
                        appendLine("texto: ${r.text.text}")
                        if (r.text.subText.isNotEmpty()) appendLine("subtexto: ${r.text.subText}")
                        if (r.text.bigText.isNotEmpty()) appendLine("texto largo: ${r.text.bigText}")
                        if (r.text.infoText.isNotEmpty()) appendLine("info: ${r.text.infoText}")
                        append("icono: ${r.iconHash ?: "ninguno"}")
                    },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun NavSummary(n: NavInfo) {
    Text("${n.maneuver.label} en ${formatDistance(n.distanceToManeuverM)}", style = MaterialTheme.typography.titleLarge)
    if (n.roundaboutSector > 0) Text("Rotonda (sector ${n.roundaboutSector})")
    if (n.nextRoad.isNotEmpty()) Text("Hacia: ${n.nextRoad}")
    val parts = buildList {
        if (n.routeRemainingM > 0) add(formatDistance(n.routeRemainingM))
        if (n.routeRemainingS > 0) add("${n.routeRemainingS / 60} min")
        n.eta?.let { add("llegada ${"%02d:%02d".format(it.hour, it.minute)}") }
    }
    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
}

internal fun formatDistance(m: Int) = if (m >= 1000) "%.1f km".format(m / 1000f) else "$m m"

@Composable
private fun UnknownIconRow(u: UnknownIcon) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Fondo gris: las flechas de Maps suelen ser blancas sobre transparente.
        Box(Modifier.size(56.dp).background(Color(0xFF455A64)), contentAlignment = Alignment.Center) {
            Image(u.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(48.dp))
        }
        Text(u.sample, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(onClick = { open = true }) { Text("Asignar") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (choice in IconLabel.choices) {
                    DropdownMenuItem(text = { Text(choice.text) }, onClick = {
                        open = false
                        NavBridge.relabel(u.hash, choice)
                    })
                }
            }
        }
    }
}

/** Envía instrucciones inventadas para comprobar cómo las pinta el cuadro. */
@Composable
private fun TestPanel(active: Boolean) {
    var maneuver by rememberSaveable { mutableIntStateOf(Maneuver.RIGHT.code) }
    var distance by rememberSaveable { mutableFloatStateOf(250f) }
    var exit by rememberSaveable { mutableIntStateOf(0) }
    var road by rememberSaveable { mutableStateOf("Calle de Prueba") }
    var menu by remember { mutableStateOf(false) }

    fun build() = NavInfo(
        distanceToManeuverM = distance.roundToInt(),
        maneuver = Maneuver.fromCode(maneuver) ?: Maneuver.STRAIGHT,
        nextRoad = road,
        currentRoad = "Avenida Actual",
        routeRemainingM = 12_300,
        routeRemainingS = 17 * 60,
        roundaboutSector = if (exit > 0) NotificationParser.sectorForExit(exit) else 0,
    )

    InfoCard {
        SectionTitle("Prueba en el cuadro")
        Text(
            "Sirve para comprobar qué icono muestra el cuadro con cada código, sin navegar de verdad.",
            style = MaterialTheme.typography.bodySmall,
        )
        Box {
            OutlinedButton(onClick = { menu = true }) {
                Text("Maniobra: ${Maneuver.fromCode(maneuver)?.label} (código $maneuver)")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for (m in Maneuver.entries) {
                    DropdownMenuItem(text = { Text("${m.code} · ${m.label}") }, onClick = {
                        maneuver = m.code
                        menu = false
                    })
                }
            }
        }
        Text("Distancia: ${distance.roundToInt()} m")
        Slider(value = distance, onValueChange = { distance = it }, valueRange = 0f..2000f)
        Text(if (exit == 0) "Sin rotonda" else "Rotonda, salida $exit")
        Slider(value = exit.toFloat(), onValueChange = { exit = it.roundToInt() }, valueRange = 0f..6f, steps = 5)
        OutlinedTextField(value = road, onValueChange = { road = it }, label = { Text("Nombre de calle") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { NavBridge.testOverride.value = build() }) { Text(if (active) "Actualizar" else "Enviar") }
            if (active) OutlinedButton(onClick = { NavBridge.testOverride.value = null }) { Text("Terminar prueba") }
        }
    }
}
