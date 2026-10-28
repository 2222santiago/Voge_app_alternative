package com.vogelink.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vogelink.core.LinkHub
import com.vogelink.core.Prefs
import com.vogelink.nav.IconMemory
import com.vogelink.service.LinkService

class MainActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_FROM_BIKE = "from_bike"

        fun fromBikeIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_FROM_BIKE, true)

        /** Abre el Panel al conectarse la moto (desde segundo plano necesita «mostrar sobre otras apps»). */
        fun openFromBike(context: Context) = context.startActivity(fromBikeIntent(context))
    }

    /** Se incrementa cada vez que hay que saltar a la pestaña Panel. */
    private var panelRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        IconMemory.init(this)
        enableEdgeToEdge()
        // Al girar la pantalla se recrea con el mismo intent: no volver a saltar al Panel.
        if (savedInstanceState == null) handleIntent(intent)
        setContent { VogeLinkTheme { App(panelRequests) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val fromBike = intent?.getBooleanExtra(EXTRA_FROM_BIKE, false) == true
        if (fromBike) {
            panelRequests++
            if (Prefs.settings.value.autoStart && !LinkHub.serviceRunning.value && AppPermissions.canUseBluetooth(this)) {
                LinkService.start(this)
            }
        }
        showOverLockScreen(fromBike && Prefs.settings.value.panelOverLock)
    }

    private fun showOverLockScreen(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
    }
}

private val Orange = Color(0xFFFF7A1A)

@Composable
fun VogeLinkTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Orange, secondary = Color(0xFFFFB37A))
    } else {
        lightColorScheme(primary = Color(0xFFC75100), secondary = Color(0xFF8A4B1C))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Panel", Icons.Filled.Home),
    Tab("Navegación", Icons.Filled.Place),
    Tab("Viajes", Icons.Filled.DateRange),
    Tab("Ajustes", Icons.Filled.Settings),
)

@Composable
fun App(panelRequests: Int) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(panelRequests) { if (panelRequests > 0) tab = 0 }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> PanelScreen()
                1 -> NavScreen()
                2 -> TripsScreen()
                else -> SettingsScreen()
            }
        }
    }
}

/** Permisos y accesos especiales que necesita la app. */
object AppPermissions {
    fun bluetooth(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyList()
        }

    fun runtime(): Array<String> = buildList {
        addAll(bluetooth())
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun missing(ctx: Context): List<String> = runtime().filterNot { granted(ctx, it) }

    /** En Android < 12 el escaneo BLE exige permiso de ubicación. */
    fun canUseBluetooth(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) bluetooth().all { granted(ctx, it) }
        else granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun notificationAccess(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** Necesario para abrir el Panel desde segundo plano al conectarse la moto. */
    fun canOpenFromBackground(ctx: Context): Boolean = android.provider.Settings.canDrawOverlays(ctx)

    fun ignoringBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
}

/** Se incrementa cada vez que la pantalla vuelve a primer plano (p. ej. al volver de Ajustes del sistema). */
@Composable
fun rememberResumeCount(): Int {
    val owner = LocalLifecycleOwner.current
    var count by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) count++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return count
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
fun WarningCard(text: String, action: String, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun InfoCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
