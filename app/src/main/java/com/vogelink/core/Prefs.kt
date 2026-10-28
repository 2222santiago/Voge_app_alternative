package com.vogelink.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

data class Settings(
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val asciiRoadNames: Boolean = false,
    val logFrames: Boolean = false,
    val recordGps: Boolean = true,
    /** Arrancar el servicio cuando el móvil se conecta por Bluetooth al cuadro. */
    val autoStart: Boolean = true,
    /** Abrir el Panel en ese momento. */
    val openPanel: Boolean = true,
    /** Mostrar ese Panel también sobre la pantalla de bloqueo (móvil en el soporte del manillar). */
    val panelOverLock: Boolean = false,
)

/** Ajustes persistentes. [settings] refleja siempre el último valor guardado. */
object Prefs {
    private lateinit var sp: SharedPreferences
    val settings = MutableStateFlow(Settings())

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("vogelink", Context.MODE_PRIVATE)
        settings.value = Settings(
            deviceAddress = sp.getString("device_address", null),
            deviceName = sp.getString("device_name", null),
            asciiRoadNames = sp.getBoolean("ascii_road_names", false),
            logFrames = sp.getBoolean("log_frames", false),
            recordGps = sp.getBoolean("record_gps", true),
            autoStart = sp.getBoolean("auto_start", true),
            openPanel = sp.getBoolean("open_panel", true),
            panelOverLock = sp.getBoolean("panel_over_lock", false),
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val s = transform(settings.value)
        sp.edit()
            .putString("device_address", s.deviceAddress)
            .putString("device_name", s.deviceName)
            .putBoolean("ascii_road_names", s.asciiRoadNames)
            .putBoolean("log_frames", s.logFrames)
            .putBoolean("record_gps", s.recordGps)
            .putBoolean("auto_start", s.autoStart)
            .putBoolean("open_panel", s.openPanel)
            .putBoolean("panel_over_lock", s.panelOverLock)
            .apply()
        settings.value = s
    }

    fun raw(): SharedPreferences = sp
}
