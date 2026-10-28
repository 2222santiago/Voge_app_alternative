package com.vogelink.nav

import com.vogelink.protocol.Maneuver
import java.text.Normalizer
import java.time.LocalTime

/** Lo que se ha podido sacar del texto de una notificación de navegación. */
data class ParsedNav(
    val distanceM: Int? = null,
    val maneuver: Maneuver? = null,
    val roundaboutExit: Int? = null,
    val road: String = "",
    val remainingS: Int? = null,
    val remainingM: Int? = null,
    val eta: LocalTime? = null,
)

/** Campos de texto de la notificación (extras estándar de Android). */
data class NotificationText(
    val title: String = "",
    val text: String = "",
    val subText: String = "",
    val bigText: String = "",
    val infoText: String = "",
    val summaryText: String = "",
)

/**
 * Interpreta notificaciones de navegación (Google Maps, Waze, OsmAnd) en español e inglés.
 * El formato exacto varía entre versiones, así que todo es tolerante: lo que no se reconoce queda a null.
 */
object NotificationParser {

    private const val NUM = """(\d+(?:[.,]\d+)?)"""
    private val DISTANCE = Regex("""(?<![\d.,])$NUM\s*(km|m|mi|ft|yd)\b""", RegexOption.IGNORE_CASE)
    private val HOURS = Regex("""(\d+)\s*(?:h|hr|hrs|hora|horas|hour|hours)\b""", RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("""(\d+)\s*(?:min|mins|minuto|minutos|minute|minutes)\b""", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("""\b(\d{1,2}):(\d{2})\s*([ap])?\.?\s?(m\.?)?""", RegexOption.IGNORE_CASE)
    private val NOW_WORDS = listOf("ahora", "now")

    private val ORDINALS = mapOf(
        "primera" to 1, "segunda" to 2, "tercera" to 3, "cuarta" to 4, "quinta" to 5, "sexta" to 6,
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
    )
    private val EXIT_NUMBER = listOf(
        Regex("""(\d+)\.?\s*(?:a|o|st|nd|rd|th)?\.?\s+salida"""),
        Regex("""salida\s+(\d+)"""),
        Regex("""(\d+)\s*(?:st|nd|rd|th)\s+exit"""),
        Regex("""exit\s+(\d+)"""),
    )

    private val UTURN = listOf("cambio de sentido", "cambia de sentido", "media vuelta", "da la vuelta", "u-turn", "u turn", "uturn")
    private val ROUNDABOUT = listOf("rotonda", "glorieta", "roundabout", "traffic circle")
    private val SHARP = listOf("bruscamente", "cerrad", "pronunciad", "sharp")
    private val SLIGHT = listOf(
        "ligeramente", "levemente", "suavemente", "mantente", "mantengase", "incorporate", "toma la salida",
        "slight", "keep", "bear", "merge", "take the exit", "take exit",
    )
    private val LEFT = listOf("izquierda", "left")
    private val RIGHT = listOf("derecha", "right")
    private val STRAIGHT = listOf("recto", "continua", "sigue", "dirigete", "straight", "continue", "head ")
    private val ARRIVE = listOf("destino", "has llegado", "llegaras", "arrive", "destination")
    private val ROAD_PREFERRED = listOf(" hacia ", " toward ", " towards ", " onto ", " into ")
    private val ROAD_FALLBACK = listOf(" por ", " en ", " on ")

    fun parse(n: NotificationText): ParsedNav {
        val instruction = listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(" ")
        val norm = normalize(instruction)

        val textIsSummary = MINUTES.containsMatchIn(n.text) || HOURS.containsMatchIn(n.text)
        val distance = parseDistance(n.title)
            ?: (if (textIsSummary) null else parseDistance(n.text))
            ?: if (NOW_WORDS.any { normalize(n.title).startsWith(it) }) 0 else null

        val summary = listOf(n.subText, n.infoText, n.summaryText, n.text, n.bigText)
            .firstOrNull { it.isNotBlank() && (MINUTES.containsMatchIn(it) || HOURS.containsMatchIn(it)) && it != n.title }
            .orEmpty()

        val (maneuver, exit) = parseManeuver(norm)
        return ParsedNav(
            distanceM = distance,
            maneuver = maneuver,
            roundaboutExit = exit,
            road = parseRoad(n.title, n.text),
            remainingS = parseDuration(summary),
            remainingM = if (summary.isNotEmpty()) parseDistance(summary) else null,
            eta = parseClock(summary),
        )
    }

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{Mn}+"), "").lowercase()

    fun parseDistance(s: String): Int? {
        val m = DISTANCE.find(s) ?: return null
        val unit = m.groupValues[2].lowercase()
        val value = parseNumber(m.groupValues[1], unit) ?: return null
        val meters = when (unit) {
            "km" -> value * 1000
            "mi" -> value * 1609.344
            "ft" -> value * 0.3048
            "yd" -> value * 0.9144
            else -> value
        }
        return meters.toInt()
    }

    /** "1,2" y "1.2" son decimales; "1,000 ft" es separador de miles. */
    private fun parseNumber(raw: String, unit: String): Double? {
        val thousands = Regex("""^\d{1,3}[.,]\d{3}$""").matches(raw) && unit in setOf("m", "ft", "yd")
        val cleaned = if (thousands) raw.replace(",", "").replace(".", "") else raw.replace(',', '.')
        return cleaned.toDoubleOrNull()
    }

    fun parseDuration(s: String): Int? {
        if (s.isBlank()) return null
        val h = HOURS.find(s)?.groupValues?.get(1)?.toIntOrNull()
        val m = MINUTES.find(s)?.groupValues?.get(1)?.toIntOrNull()
        if (h == null && m == null) return null
        return (h ?: 0) * 3600 + (m ?: 0) * 60
    }

    fun parseClock(s: String): LocalTime? {
        val m = CLOCK.find(s) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        val ampm = m.groupValues[3].lowercase()
        if (ampm.isNotEmpty() && m.groupValues[4].isNotEmpty()) {
            if (ampm == "p" && hour < 12) hour += 12
            if (ampm == "a" && hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    fun parseManeuver(norm: String): Pair<Maneuver?, Int?> {
        fun has(words: List<String>) = words.any { w -> if (w.length <= 5) Regex("\\b${Regex.escape(w.trim())}\\b").containsMatchIn(norm) else norm.contains(w) }
        val left = has(LEFT)
        val right = has(RIGHT)
        return when {
            has(UTURN) -> Maneuver.UTURN to null
            has(ROUNDABOUT) -> {
                val exit = parseExit(norm)
                val m = when (exit) {
                    null -> if (left) Maneuver.LEFT else if (right) Maneuver.RIGHT else Maneuver.STRAIGHT
                    1 -> Maneuver.RIGHT
                    2 -> Maneuver.STRAIGHT
                    else -> Maneuver.LEFT
                }
                m to (exit ?: 2)
            }
            has(SHARP) && left -> Maneuver.SHARP_LEFT to null
            has(SHARP) && right -> Maneuver.SHARP_RIGHT to null
            has(SLIGHT) && left -> Maneuver.SLIGHT_LEFT to null
            has(SLIGHT) && right -> Maneuver.SLIGHT_RIGHT to null
            left -> Maneuver.LEFT to null
            right -> Maneuver.RIGHT to null
            has(SLIGHT) -> Maneuver.SLIGHT_RIGHT to null
            has(STRAIGHT) || has(ARRIVE) -> Maneuver.STRAIGHT to null
            else -> null to null
        }
    }

    private fun parseExit(norm: String): Int? {
        for ((word, n) in ORDINALS) if (Regex("\\b$word\\b").containsMatchIn(norm)) return n
        for (r in EXIT_NUMBER) r.find(norm)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    /** Busca el nombre de la vía: tras "hacia/toward…", y si no, el texto entero si no es una instrucción. */
    fun parseRoad(title: String, text: String): String {
        for (source in listOf(text, title)) {
            val padded = " ${source.substringBefore(" · ")} "
            val lower = padded.lowercase()
            for (prep in ROAD_PREFERRED) {
                val i = lower.lastIndexOf(prep)
                if (i >= 0) return padded.substring(i + prep.length).trim()
            }
            for (prep in ROAD_FALLBACK) {
                val i = lower.lastIndexOf(prep)
                if (i >= 0) {
                    val rest = padded.substring(i + prep.length).trim()
                    val (m, _) = parseManeuver(normalize(rest))
                    if (m == null && rest.isNotEmpty()) return rest
                }
            }
        }
        val plain = text.substringBefore(" · ").trim()
        val isInstruction = parseManeuver(normalize(plain)).first != null || parseDistance(plain) != null
        return if (plain.isNotEmpty() && !isInstruction && !MINUTES.containsMatchIn(plain)) plain else ""
    }

    /** Sector de 30° aproximado para el número de salida (circulación por la derecha). */
    fun sectorForExit(exit: Int): Int = when (exit) {
        1 -> 3
        2 -> 6
        3 -> 9
        4 -> 11
        else -> 12
    }
}
