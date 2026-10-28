package com.vogelink.nav

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import com.vogelink.protocol.Maneuver
import kotlinx.coroutines.flow.MutableStateFlow

/** Lo que el usuario ha dicho que significa un icono: una maniobra o una salida de rotonda. */
data class IconLabel(val maneuver: Maneuver, val roundaboutExit: Int? = null) {
    val text: String get() = if (roundaboutExit != null) "Rotonda, salida $roundaboutExit" else maneuver.label

    fun encode(): Int = if (roundaboutExit != null) 100 + roundaboutExit else maneuver.code

    companion object {
        fun decode(code: Int): IconLabel? = when {
            code in 101..112 -> IconLabel(roundaboutManeuver(code - 100), code - 100)
            else -> Maneuver.fromCode(code)?.let { IconLabel(it) }
        }

        fun roundaboutManeuver(exit: Int): Maneuver = when (exit) {
            1 -> Maneuver.RIGHT
            2 -> Maneuver.STRAIGHT
            else -> Maneuver.LEFT
        }

        /** Opciones que se ofrecen al asignar un icono. */
        val choices: List<IconLabel> = Maneuver.entries.map { IconLabel(it) } + (1..6).map { IconLabel(roundaboutManeuver(it), it) }
    }
}

/** Huella perceptual de 256 bits (16×16) de un icono de maniobra. */
object IconHasher {
    private const val SIZE = 16

    fun hash(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap.copy(Bitmap.Config.ARGB_8888, false), SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        return hashPixels(px)
    }

    /** Separado de [hash] para poder probarlo sin Android. */
    fun hashPixels(argb: IntArray): String {
        // Desplazamientos de bits en vez de android.graphics.Color para que funcione en tests de JVM.
        fun channel(c: Int, shift: Int) = (c ushr shift) and 0xFF
        val opaque = argb.count { channel(it, 24) > 230 } > argb.size * 0.9
        val values = argb.map { c ->
            val lum = (0.299 * channel(c, 16) + 0.587 * channel(c, 8) + 0.114 * channel(c, 0)) / 255.0
            // Iconos transparentes: cuenta lo que se pinta. Iconos opacos: la luminancia.
            if (opaque) lum else channel(c, 24) / 255.0 * (0.25 + 0.75 * lum)
        }
        val mean = values.average()
        val sb = StringBuilder()
        for (i in values.indices step 4) {
            var nibble = 0
            for (j in 0 until 4) if (values[i + j] > mean) nibble = nibble or (1 shl (3 - j))
            sb.append("0123456789abcdef"[nibble])
        }
        return sb.toString()
    }

    fun distance(a: String, b: String): Int {
        if (a.length != b.length) return Int.MAX_VALUE
        var d = 0
        for (i in a.indices) d += Integer.bitCount(Character.digit(a[i], 16) xor Character.digit(b[i], 16))
        return d
    }
}

/** Iconos ya etiquetados por el usuario, persistidos en SharedPreferences. */
object IconMemory {
    private const val MAX_DISTANCE = 20
    private lateinit var sp: SharedPreferences
    val labels = MutableStateFlow<Map<String, IconLabel>>(emptyMap())

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("icon_labels", Context.MODE_PRIVATE)
        labels.value = sp.all.mapNotNull { (k, v) -> (v as? Int)?.let(IconLabel::decode)?.let { k to it } }.toMap()
    }

    fun lookup(hash: String): IconLabel? {
        val best = labels.value.minByOrNull { IconHasher.distance(it.key, hash) } ?: return null
        return if (IconHasher.distance(best.key, hash) <= MAX_DISTANCE) best.value else null
    }

    fun save(hash: String, label: IconLabel) {
        sp.edit().putInt(hash, label.encode()).apply()
        labels.value = labels.value + (hash to label)
    }

    fun forget(hash: String) {
        sp.edit().remove(hash).apply()
        labels.value = labels.value - hash
    }

    fun clear() {
        sp.edit().clear().apply()
        labels.value = emptyMap()
    }
}
