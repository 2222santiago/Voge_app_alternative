package com.vogelink.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.vogelink.R
import com.vogelink.core.LinkHub
import com.vogelink.core.Prefs
import com.vogelink.ui.AppPermissions
import com.vogelink.ui.MainActivity

/**
 * Al poner el contacto, el cuadro se conecta al móvil por Bluetooth clásico («VOGE-xxxx»).
 * Esa conexión arranca VogeLink y abre el Panel, sin tener que abrir la app a mano.
 */
class BikeConnectReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "alerts"
        private const val NOTIFICATION_ID = 3
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        // Si el servicio ya corre, esta conexión puede ser la propia BLE de VogeLink: no hay nada que hacer.
        if (LinkHub.serviceRunning.value) return
        Prefs.init(context)
        val settings = Prefs.settings.value
        if (!settings.autoStart && !settings.openPanel) return
        if (!AppPermissions.canUseBluetooth(context)) return

        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        } ?: return
        val isBike = name.startsWith("VOGE-", ignoreCase = true) ||
            settings.deviceName?.contains(name, ignoreCase = true) == true
        if (!isBike) return
        LinkHub.log("Moto detectada por Bluetooth: $name")

        // Abrir la pantalla primero: con la app visible, Android siempre deja arrancar el servicio.
        val opened = settings.openPanel && Settings.canDrawOverlays(context) && try {
            MainActivity.openFromBike(context)
            true
        } catch (e: Exception) {
            LinkHub.log("No se pudo abrir el Panel: ${e.message}")
            false
        }
        val started = !settings.autoStart || try {
            LinkService.start(context)
            true
        } catch (e: Exception) {
            // Android 12+ sin exención de batería ni «mostrar sobre otras apps».
            LinkHub.log("Android no deja arrancar el servicio en segundo plano: ${e.message}")
            false
        }
        if (!opened && (settings.openPanel || !started)) notifyDetected(context, name)
    }

    private fun notifyDetected(context: Context, name: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context, 2, MainActivity.fromBikeIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(context.getString(R.string.alert_bike_title, name))
            .setContentText(context.getString(R.string.alert_bike_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT < 33 || AppPermissions.granted(context, android.Manifest.permission.POST_NOTIFICATIONS)) {
            nm.notify(NOTIFICATION_ID, n)
        }
    }
}
