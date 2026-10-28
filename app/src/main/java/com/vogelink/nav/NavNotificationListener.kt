package com.vogelink.nav

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import com.vogelink.core.LinkHub
import com.vogelink.core.Prefs
import java.lang.ref.WeakReference

/**
 * Lee las notificaciones de las apps de navegación. Al ser un servicio del sistema,
 * sigue funcionando con la pantalla bloqueada.
 */
class NavNotificationListener : NotificationListenerService() {

    companion object {
        const val GOOGLE_MAPS = "com.google.android.apps.maps"
        val PACKAGES = setOf(
            GOOGLE_MAPS,
            "com.waze",
            "net.osmand",
            "net.osmand.plus",
        )

        @Volatile
        private var instance: WeakReference<NavNotificationListener>? = null

        fun hasAccess(ctx: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

        /**
         * Vuelve a leer las notificaciones que ya están en la barra. Llamar desde el hilo principal.
         * Devuelve false si el lector no está conectado.
         */
        fun rescan(): Boolean = instance?.get()?.rescanActive() ?: false

        /** Pide a Android que vuelva a enlazar el lector si tiene acceso pero no está conectado. */
        fun requestRebindIfNeeded(ctx: Context): Boolean {
            if (NavBridge.listenerConnected.value || !hasAccess(ctx)) return false
            requestRebind(ComponentName(ctx, NavNotificationListener::class.java))
            return true
        }

        /**
         * Remedio más fuerte para cuando [requestRebind] no basta (pasa en algunas capas de fabricante):
         * desactivar y reactivar el componente obliga al sistema a enlazarlo de nuevo.
         */
        fun forceRebind(ctx: Context) {
            if (!hasAccess(ctx)) return
            val component = ComponentName(ctx, NavNotificationListener::class.java)
            val pm = ctx.packageManager
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            requestRebind(component)
            LinkHub.log("Forzado el reenlace del lector de notificaciones")
        }
    }

    private var activeKey: String? = null

    override fun onListenerConnected() {
        Prefs.init(this)
        IconMemory.init(this)
        instance = WeakReference(this)
        NavBridge.listenerConnected.value = true
        LinkHub.log("Lector de notificaciones conectado")
        rescanActive()
    }

    override fun onListenerDisconnected() {
        instance = null
        NavBridge.listenerConnected.value = false
        LinkHub.log("Lector de notificaciones desconectado; se pide reenlazarlo")
        requestRebind(ComponentName(this, NavNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == activeKey) {
            activeKey = null
            NavBridge.clear("notificación cerrada en ${sbn.packageName}")
        }
    }

    private fun rescanActive(): Boolean {
        val active = try {
            activeNotifications ?: return false
        } catch (e: Exception) {
            // Sucede si el sistema ya ha desenlazado el lector aunque no haya avisado.
            LinkHub.log("No se pudieron leer las notificaciones activas: ${e.message}")
            return false
        }
        active.forEach(::handle)
        if (activeKey != null && active.none { it.key == activeKey }) {
            activeKey = null
            NavBridge.clear("la notificación ya no está en la barra")
        }
        return true
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in PACKAGES) return
        val n = sbn.notification
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (!ongoing && n.category != Notification.CATEGORY_NAVIGATION) return

        val e = n.extras
        fun cs(key: String) = e.getCharSequence(key)?.toString()?.trim().orEmpty()
        val icon = loadIcon(n, e)
        val raw = RawNotification(
            packageName = sbn.packageName,
            text = NotificationText(
                title = cs(Notification.EXTRA_TITLE),
                text = cs(Notification.EXTRA_TEXT),
                subText = cs(Notification.EXTRA_SUB_TEXT),
                bigText = cs(Notification.EXTRA_BIG_TEXT),
                infoText = cs(Notification.EXTRA_INFO_TEXT),
                summaryText = cs(Notification.EXTRA_SUMMARY_TEXT),
            ),
            icon = icon,
            iconHash = icon?.let(IconHasher::hash),
            timeMs = System.currentTimeMillis(),
        )
        if (NavBridge.onNotification(raw)) {
            activeKey = sbn.key
        } else if (sbn.key == activeKey) {
            // Sigue siendo la notificación de la ruta, solo que ahora sin instrucción («Recalculando…»).
            // Si dura demasiado, NavBridge.expireStale termina la navegación.
            NavBridge.onUnparsedUpdate(raw.timeMs)
        }
    }

    @Suppress("DEPRECATION")
    private fun loadIcon(n: Notification, e: Bundle): Bitmap? = try {
        n.getLargeIcon()?.loadDrawable(this)?.toBitmap(64, 64, Bitmap.Config.ARGB_8888)
            ?: e.get(Notification.EXTRA_LARGE_ICON) as? Bitmap
    } catch (_: Exception) {
        null
    }
}
