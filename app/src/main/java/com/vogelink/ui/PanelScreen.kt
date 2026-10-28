package com.vogelink.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vogelink.core.LinkHub
import com.vogelink.protocol.Telemetry
import com.vogelink.protocol.TripCounters
import com.vogelink.service.LinkService
import kotlin.math.roundToInt

@Composable
fun PanelScreen() {
    val ctx = LocalContext.current
    val conn by LinkHub.connection.collectAsStateWithLifecycle()
    val running by LinkHub.serviceRunning.collectAsStateWithLifecycle()
    val telemetry by LinkHub.telemetry.collectAsStateWithLifecycle()
    val counters by LinkHub.tripCounters.collectAsStateWithLifecycle()
    val resume = rememberResumeCount()
    val missing = remember(resume) { AppPermissions.missing(ctx) }
    val canBt = remember(resume) { AppPermissions.canUseBluetooth(ctx) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (missing.isNotEmpty()) {
            WarningCard("Faltan permisos (Bluetooth, ubicación o notificaciones).", "Conceder") {
                launcher.launch(AppPermissions.runtime())
            }
        }
        InfoCard {
            SectionTitle("Conexión con la moto")
            Text(conn.state.label + (conn.deviceName?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!running) {
                    Button(onClick = { LinkService.start(ctx) }, enabled = canBt) { Text("Conectar") }
                } else {
                    OutlinedButton(onClick = { LinkService.stop(ctx) }) { Text("Detener") }
                }
            }
            if (!canBt) Text("Concede los permisos de Bluetooth para conectar.", style = MaterialTheme.typography.bodySmall)
        }
        PreRideCheck()
        TelemetryGrid(telemetry, counters)
    }
}

@Composable
private fun TelemetryGrid(t: Telemetry?, c: TripCounters?) {
    if (t == null && c == null) {
        Text(
            "Sin datos de la moto todavía. Aparecerán al conectar con el contacto puesto.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val miles = t?.usesMiles == true
    val speedUnit = if (miles) "mph" else "km/h"
    val distUnit = if (miles) "mi" else "km"
    fun speed(kmh: Int) = if (miles) (kmh * 0.621371).roundToInt() else kmh
    fun dist(km: Float) = "%.1f".format(if (miles) km * 0.621371f else km)

    val tiles = buildList {
        if (t != null) {
            add(Triple("Velocidad", "${speed(t.speedKmh)}", speedUnit))
            add(Triple("Revoluciones", "${t.rpm}", "rpm"))
            add(Triple("Marcha", if (t.gear == 0) "N" else "${t.gear}", ""))
            add(Triple("Inclinación", "${t.leanAngle}°", "lado ${t.leanDirection}"))
            add(Triple("Batería", "%.1f".format(t.voltage), "V"))
            add(Triple("Temperatura", t.temperatureC?.toString() ?: "—", "°C · nivel ${t.temperatureLevel}"))
            add(Triple("Combustible", "${t.fuelLevel}", "nivel"))
            add(Triple("Vel. máxima", "${speed(t.maxSpeedKmh)}", speedUnit))
            add(Triple("Vel. media", "${speed(t.averageSpeedKmh)}", speedUnit))
            add(Triple("Media histórica", "${speed(t.historicAverageSpeedKmh)}", speedUnit))
        }
        if (c != null) {
            add(Triple("Odómetro", dist(c.totalKm), distUnit))
            add(Triple("Parcial", dist(c.tripKm), distUnit))
            add(Triple("Tiempo parcial", "${c.tripTime}", ""))
            add(Triple("0–100", "%.1f".format(c.accel0to100S), "s"))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in tiles.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, value, unit) in row) Tile(label, value, unit, Modifier.weight(1f))
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (t != null) {
            Text(
                "Protocolo v${t.protocolVersion} · rescate ${t.signalRescue} · byte15 ${t.unknown15}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Tile(label: String, value: String, unit: String, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) Text(unit, style = MaterialTheme.typography.bodySmall)
        }
    }
}
