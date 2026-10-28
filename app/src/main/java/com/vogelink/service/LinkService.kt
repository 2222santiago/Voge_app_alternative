package com.vogelink.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vogelink.R
import com.vogelink.ble.TftBleClient
import com.vogelink.ble.TftScanner
import com.vogelink.core.ConnectionInfo
import com.vogelink.core.LinkHub
import com.vogelink.core.LinkState
import com.vogelink.core.Prefs
import com.vogelink.nav.IconMemory
import com.vogelink.nav.NavBridge
import com.vogelink.nav.NavNotificationListener
import com.vogelink.protocol.NavInfo
import com.vogelink.protocol.TftMessage
import com.vogelink.protocol.TftProtocol
import com.vogelink.trip.TripRecorder
import com.vogelink.trip.TripStore
import com.vogelink.trip.TripsHub
import com.vogelink.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime

/**
 * Servicio en primer plano que mantiene la conexión con el cuadro, envía el latido y la navegación
 * y registra los viajes. No depende de ninguna Activity: sigue con la pantalla apagada.
 */
class LinkService : Service() {

    companion object {
        private const val CHANNEL_ID = "link"
        private const val ALERT_CHANNEL_ID = "alerts"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.vogelink.action.STOP"
        private const val HEARTBEAT_MS = 1000L
        private const val NAV_MIN_GAP_MS = 800L
        private const val NAV_REFRESH_MS = 3000L
        private const val FRAME_GAP_MS = 100L
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val NAV_CHECK_MS = 5_000L
        private const val NAV_ALERT_GRACE_MS = 20_000L

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var ble: TftBleClient
    private lateinit var recorder: TripRecorder
    private var wakeLock: PowerManager.WakeLock? = null
    private var locationListener: LocationListener? = null
    private var protocolVersion = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        IconMemory.init(this)
        createChannel()
        if (!startInForeground()) {
            stopSelf()
            return
        }
        ble = TftBleClient(this, scope)
        recorder = TripRecorder(TripStore(this))
        // Sin esto, con la pantalla apagada la CPU puede dormirse y el latido se retrasa.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VogeLink::link")
            .apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        LinkHub.serviceRunning.value = true
        LinkHub.log("Servicio iniciado")
        launchJobs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (::ble.isInitialized) {
            stopLocation()
            recorder.finish()
            ble.release()
        }
        wakeLock?.release()
        scope.cancel()
        cancelAlert()
        LinkHub.serviceRunning.value = false
        LinkHub.connection.value = ConnectionInfo(LinkState.STOPPED)
        LinkHub.log("Servicio detenido")
        super.onDestroy()
    }

    private fun launchJobs() {
        // Moto elegida en Ajustes → conectar; si no hay, intentar encontrarla.
        scope.launch {
            Prefs.settings.map { it.deviceAddress }.distinctUntilChanged().collectLatest { address ->
                if (address != null) {
                    ble.connect(address)
                } else {
                    ble.disconnect()
                    autoDiscover()
                }
            }
        }

        scope.launch {
            var previous = LinkState.STOPPED
            ble.state.collect { s ->
                LinkHub.connection.value = ConnectionInfo(s, Prefs.settings.value.deviceName)
                val now = System.currentTimeMillis()
                if (s == LinkState.READY && previous != LinkState.READY) {
                    recorder.onConnected(now)
                    startLocation()
                } else if (s != LinkState.READY && previous == LinkState.READY) {
                    recorder.onDisconnected(now)
                }
                previous = s
            }
        }

        scope.launch {
            combine(ble.state, NavBridge.listenerConnected, NavBridge.current.map { it != null }.distinctUntilChanged()) { s, l, n ->
                Triple(s, l, n)
            }.collect { (s, listener, navigating) -> updateNotification(s, navStatus(listener, navigating)) }
        }

        scope.launch { navHealthLoop() }

        scope.launch {
            while (isActive) {
                if (ble.isReady) ble.send(TftProtocol.heartbeat(batteryPercent(), LocalTime.now()))
                delay(HEARTBEAT_MS)
            }
        }

        scope.launch {
            ble.incoming.collect { frame -> handleIncoming(frame) }
        }

        scope.launch { navigationLoop() }

        scope.launch {
            while (isActive) {
                delay(30_000)
                recorder.tick(System.currentTimeMillis())
                if (ble.state.value != LinkState.READY) stopLocationIfIdle()
            }
        }
    }

    private fun handleIncoming(frame: ByteArray) {
        if (Prefs.settings.value.logFrames) LinkHub.log("RX ${TftProtocol.toHex(frame)}")
        val now = System.currentTimeMillis()
        when (val msg = TftProtocol.parse(frame, protocolVersion)) {
            is TftMessage.Live -> {
                protocolVersion = msg.data.protocolVersion
                LinkHub.telemetry.value = msg.data
                recorder.onTelemetry(msg.data, now)
            }
            is TftMessage.Trip -> {
                LinkHub.tripCounters.value = msg.data
                recorder.onCounters(msg.data, now)
            }
            is TftMessage.States -> LinkHub.dashStates.value = msg
            is TftMessage.Event -> {
                LinkHub.lastEvent.value = msg.code
                LinkHub.log("Evento del cuadro: ${msg.code}")
            }
            is TftMessage.Unknown -> LinkHub.log("Trama desconocida 0x%02X: %s".format(msg.command, TftProtocol.toHex(frame)))
            null -> LinkHub.log("Trama de ${frame.size} bytes ignorada: ${TftProtocol.toHex(frame)}")
        }
    }

    private suspend fun navigationLoop() {
        var lastSent: NavInfo? = null
        var lastSentAt = 0L
        var navActive = false
        while (scope.isActive) {
            NavBridge.expireStale(System.currentTimeMillis())
            val info = NavBridge.testOverride.value ?: NavBridge.current.value
            val now = SystemClock.elapsedRealtime()
            when {
                !ble.isReady -> {
                    navActive = false
                    lastSent = null
                }
                info != null -> {
                    val changed = info != lastSent
                    if ((changed && now - lastSentAt >= NAV_MIN_GAP_MS) || now - lastSentAt >= NAV_REFRESH_MS) {
                        sendBurst(info)
                        lastSent = info
                        lastSentAt = SystemClock.elapsedRealtime()
                        navActive = true
                    }
                }
                navActive -> {
                    ble.send(TftProtocol.endNavigation())
                    LinkHub.log("Enviado fin de navegación al cuadro")
                    navActive = false
                    lastSent = null
                }
            }
            delay(200)
        }
    }

    /**
     * Mientras la moto está conectada comprueba que el lector de notificaciones sigue vivo:
     * si Android lo ha desenlazado lo vuelve a pedir y, si no se recupera, avisa una vez por conexión
     * para que el problema se vea antes de salir y no a mitad de ruta.
     */
    private suspend fun navHealthLoop() {
        var readySince = 0L
        var alerted = false
        var rebindAttempts = 0
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            if (ble.state.value != LinkState.READY) {
                readySince = 0L
                alerted = false
                rebindAttempts = 0
            } else {
                if (readySince == 0L) readySince = now
                val access = NavNotificationListener.hasAccess(this)
                val listener = NavBridge.listenerConnected.value
                if (listener) {
                    rebindAttempts = 0
                } else if (access) {
                    rebindAttempts++
                    // Primero la petición normal; si no basta, el reenlace forzado (una vez).
                    if (rebindAttempts == 3) NavNotificationListener.forceRebind(this)
                    else NavNotificationListener.requestRebindIfNeeded(this)
                }
                val problem = when {
                    !access -> "VogeLink no tiene acceso a las notificaciones: Google Maps no llegará al cuadro."
                    !listener -> "Android ha desconectado el lector de notificaciones: Google Maps no llegará al cuadro."
                    else -> null
                }
                if (problem == null) {
                    if (alerted) cancelAlert()
                    alerted = false
                } else if (!alerted && now - readySince >= NAV_ALERT_GRACE_MS) {
                    LinkHub.log(problem)
                    showAlert(problem)
                    alerted = true
                }
            }
            delay(NAV_CHECK_MS)
        }
    }

    private fun navStatus(listener: Boolean, navigating: Boolean): String = when {
        !NavNotificationListener.hasAccess(this) -> "⚠ sin acceso a notificaciones"
        !listener -> "⚠ lector de Maps desconectado"
        navigating -> "navegando"
        else -> "Maps listo"
    }

    private suspend fun sendBurst(info: NavInfo) {
        val log = Prefs.settings.value.logFrames
        val frames = TftProtocol.navigationBurst(info, LocalTime.now(), Prefs.settings.value.asciiRoadNames)
        for (f in frames) {
            val ok = ble.send(f)
            if (log) LinkHub.log("TX ${TftProtocol.toHex(f)}${if (ok) "" else " (fallo)"}")
            delay(FRAME_GAP_MS)
        }
    }

    private suspend fun autoDiscover() {
        val scanner = TftScanner(this)
        val names = scanner.bondedTftNames()
        if (names.isEmpty()) {
            ble.state.value = LinkState.NO_DEVICE
            LinkHub.log("No hay ningún cuadro «VOGE-…» emparejado por Bluetooth. Elige la moto en Ajustes.")
            return
        }
        ble.state.value = LinkState.SCANNING
        LinkHub.log("Buscando por BLE: ${names.joinToString()}")
        val hit = try {
            withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                scanner.scan().first { h -> h.name != null && names.any { n -> h.name.contains(n, ignoreCase = true) } }
            }
        } catch (e: Exception) {
            LinkHub.log("Escaneo fallido: ${e.message}")
            null
        }
        if (hit != null) {
            LinkHub.log("Encontrada ${hit.name} (${hit.address})")
            Prefs.update { it.copy(deviceAddress = hit.address, deviceName = hit.name) }
        } else {
            ble.state.value = LinkState.NO_DEVICE
            LinkHub.log("No se encontró la moto. ¿Está el contacto puesto?")
        }
    }

    private fun batteryPercent(): Int =
        getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startLocation() {
        if (locationListener != null || !Prefs.settings.value.recordGps) return
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        // Se implementan los cuatro métodos: en API < 30 no tienen implementación por defecto.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                scope.launch(Dispatchers.IO) { recorder.onLocation(location, recordPoint = true) }
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("API < 29")
            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, listener, Looper.getMainLooper())
            locationListener = listener
        } catch (e: SecurityException) {
            LinkHub.log("Sin permiso de ubicación: ${e.message}")
        }
    }

    private fun stopLocation() {
        val l = locationListener ?: return
        getSystemService(LocationManager::class.java)?.removeUpdates(l)
        locationListener = null
    }

    /** Tras cerrarse el viaje por inactividad deja de usar el GPS. */
    private fun stopLocationIfIdle() {
        if (TripsHub.active.value == null) stopLocation()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_link), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun openAppIntent() =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun showAlert(text: String) {
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(getString(R.string.alert_nav_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text Toca para revisarlo."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID, n)
    }

    private fun cancelAlert() = getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIFICATION_ID)

    private fun buildNotification(state: LinkState, nav: String? = null) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_link)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(
            state.label + (Prefs.settings.value.deviceName?.let { " · $it" } ?: "") +
                (nav?.takeIf { state == LinkState.READY }?.let { " · $it" } ?: ""),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openAppIntent())
        .addAction(
            0, getString(R.string.action_stop),
            PendingIntent.getService(
                this, 1, Intent(this, LinkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun updateNotification(state: LinkState, nav: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state, nav))
    }

    private fun startInForeground(): Boolean {
        val notification = buildNotification(LinkState.CONNECTING)
        val base = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        val withLocation = if (Build.VERSION.SDK_INT >= 29 && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            base
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, withLocation)
            true
        } catch (e: Exception) {
            // P. ej. reinicio en segundo plano: Android no deja iniciar el tipo «location» desde ahí.
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, base)
                true
            } catch (e2: Exception) {
                LinkHub.log("No se pudo iniciar el servicio en primer plano: ${e2.message}")
                false
            }
        }
    }
}
