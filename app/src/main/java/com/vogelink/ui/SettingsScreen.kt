package com.vogelink.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vogelink.ble.ScanHit
import com.vogelink.ble.TftScanner
import com.vogelink.core.LinkHub
import com.vogelink.core.Prefs
import com.vogelink.nav.NavBridge
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val settings by Prefs.settings.collectAsStateWithLifecycle()
    val resume = rememberResumeCount()
    val missing = remember(resume) { AppPermissions.missing(ctx) }
    val canBt = remember(resume) { AppPermissions.canUseBluetooth(ctx) }
    val notifAccess = remember(resume) { AppPermissions.notificationAccess(ctx) }
    val battery = remember(resume) { AppPermissions.ignoringBatteryOptimizations(ctx) }
    val overlay = remember(resume) { AppPermissions.canOpenFromBackground(ctx) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoCard {
            SectionTitle("Moto")
            Text(settings.deviceName?.let { "$it (${settings.deviceAddress})" } ?: "Ninguna elegida: se buscará el cuadro «VOGE-…» emparejado.")
            if (settings.deviceAddress != null) {
                TextButton(onClick = { Prefs.update { it.copy(deviceAddress = null, deviceName = null) } }) { Text("Olvidar moto") }
            }
            DeviceScanner(enabled = canBt)
        }

        InfoCard {
            SectionTitle("Al encender la moto")
            ToggleRow(
                "Conectar sola",
                "Cuando el móvil se conecta por Bluetooth al cuadro «VOGE-…», arranca VogeLink. " +
                    "En Android 12 o posterior necesita «Sin restricción de batería».",
                settings.autoStart,
            ) { v -> Prefs.update { it.copy(autoStart = v) } }
            ToggleRow(
                "Abrir el Panel",
                "Necesita el permiso «Mostrar sobre otras apps»; sin él verás una notificación para abrirlo.",
                settings.openPanel,
            ) { v -> Prefs.update { it.copy(openPanel = v) } }
            ToggleRow(
                "Mostrarlo con el móvil bloqueado",
                "Enciende la pantalla y enseña el Panel sobre el bloqueo. Útil con el móvil en el soporte del manillar.",
                settings.panelOverLock,
            ) { v -> Prefs.update { it.copy(panelOverLock = v) } }
        }

        InfoCard {
            SectionTitle("Opciones")
            ToggleRow("Quitar tildes en los nombres de calle", "Úsalo si el cuadro muestra símbolos raros.", settings.asciiRoadNames) { v ->
                Prefs.update { it.copy(asciiRoadNames = v) }
            }
            ToggleRow("Guardar trazado GPS de los viajes", "Necesario para exportar GPX.", settings.recordGps) { v ->
                Prefs.update { it.copy(recordGps = v) }
            }
            ToggleRow("Registrar tramas en el log", "Para depurar el protocolo.", settings.logFrames) { v ->
                Prefs.update { it.copy(logFrames = v) }
            }
        }

        InfoCard {
            SectionTitle("Permisos")
            PermissionRow("Bluetooth, ubicación y notificaciones", missing.isEmpty()) { launcher.launch(AppPermissions.runtime()) }
            PermissionRow("Acceso a notificaciones (navegación)", notifAccess) {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            PermissionRow("Sin restricción de batería", battery) {
                @Suppress("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                ctx.startActivity(intent)
            }
            PermissionRow("Mostrar sobre otras apps (abrir el Panel al encender la moto)", overlay) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
        }

        LogCard()
    }
}

@Composable
private fun DeviceScanner(enabled: Boolean) {
    val ctx = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val hits = remember { mutableStateMapOf<String, ScanHit>() }
    val bonded = remember(scanning) { TftScanner(ctx).bondedTftNames() }

    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        hits.clear()
        error = null
        try {
            withTimeout(20_000) { TftScanner(ctx).scan().collect { hits[it.address] = it } }
        } catch (_: TimeoutCancellationException) {
        } catch (e: IllegalStateException) {
            error = e.message
        }
        scanning = false
    }

    if (bonded.isNotEmpty()) Text("Emparejados: ${bonded.joinToString()}", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { scanning = !scanning }, enabled = enabled) { Text(if (scanning) "Parar búsqueda" else "Buscar motos") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val sorted = hits.values.sortedWith(compareByDescending<ScanHit> { it.looksLikeTft }.thenByDescending { it.rssi })
    for (h in sorted.take(30)) {
        Column(
            Modifier.fillMaxWidth().clickable {
                Prefs.update { it.copy(deviceAddress = h.address, deviceName = h.name ?: h.address) }
                scanning = false
            }.padding(vertical = 6.dp),
        ) {
            Text(
                (h.name ?: "(sin nombre)") + if (h.looksLikeTft) "  ← parece el cuadro" else "",
                fontWeight = if (h.looksLikeTft) FontWeight.Bold else FontWeight.Normal,
            )
            Text("${h.address} · ${h.rssi} dBm", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionRow(title: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text((if (ok) "✓ " else "✗ ") + title, Modifier.weight(1f))
        if (!ok) TextButton(onClick = onFix) { Text("Arreglar") }
    }
}

@Composable
private fun LogCard() {
    val ctx = LocalContext.current
    val revision by LinkHub.logRevision.collectAsStateWithLifecycle()
    val text = remember(revision) { LinkHub.logText().lines().takeLast(60).joinToString("\n") }
    InfoCard {
        SectionTitle("Registro")
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE))) {
            Text(text.ifEmpty { "(vacío)" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val body = LinkHub.logText() + "\n\n--- Notificaciones de navegación ---\n" + NavBridge.historyText()
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
                ctx.startActivity(Intent.createChooser(send, "Compartir registro"))
            }) { Text("Compartir") }
            TextButton(onClick = { LinkHub.clearLog() }) { Text("Borrar") }
        }
    }
}
