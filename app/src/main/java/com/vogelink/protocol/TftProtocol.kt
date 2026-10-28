package com.vogelink.protocol

import java.text.Normalizer
import java.time.LocalTime

/** Códigos de icono de maniobra que entiende el TFT (byte 10 de la trama 0x6A). */
enum class Maneuver(val code: Int, val label: String) {
    STRAIGHT(1, "Recto"),
    UTURN(2, "Cambio de sentido"),
    UTURN_MIRRORED(3, "Cambio de sentido (circ. por la izquierda)"),
    LEFT(4, "Izquierda"),
    RIGHT(5, "Derecha"),
    SLIGHT_LEFT(6, "Ligeramente a la izquierda"),
    SLIGHT_RIGHT(7, "Ligeramente a la derecha"),
    SHARP_LEFT(8, "Cerrada a la izquierda"),
    SHARP_RIGHT(9, "Cerrada a la derecha");

    companion object {
        fun fromCode(code: Int): Maneuver? = entries.firstOrNull { it.code == code }
    }
}

/** Estado de navegación tal como se envía al cuadro. */
data class NavInfo(
    val distanceToManeuverM: Int,
    val maneuver: Maneuver,
    val nextRoad: String = "",
    val currentRoad: String = "",
    val routeRemainingM: Int = 0,
    val routeRemainingS: Int = 0,
    val eta: LocalTime? = null,
    /** 0 = no es rotonda; 1..12 = sector de 30° del ángulo recorrido dentro de la rotonda. */
    val roundaboutSector: Int = 0,
    val inTunnel: Boolean = false,
    val leftHandTraffic: Boolean = false,
)

/** Trama 0x4A de telemetría. Valores en bruto y conversiones que dependen de la versión de protocolo. */
data class Telemetry(
    val speedKmh: Int,
    val rpm: Int,
    val gear: Int,
    val averageSpeedKmh: Int,
    val leanAngle: Int,
    val leanDirection: Int,
    val voltageRaw: Int,
    val temperatureRaw: Int,
    val fuelLevel: Int,
    val maxSpeedKmh: Int,
    val historicAverageSpeedKmh: Int,
    val signalRescue: Int,
    val unknown15: Int,
    val protocolVersion: Int,
    val distanceUnit: Int,
) {
    val voltage: Float get() = if (protocolVersion >= 2) voltageRaw / 10f else voltageRaw.toFloat()

    /** °C, o null si el cuadro indica que no hay lectura (bruto > 250). */
    val temperatureC: Int?
        get() = when {
            temperatureRaw > 250 -> null
            protocolVersion >= 2 -> temperatureRaw - 60
            else -> temperatureRaw - 30
        }

    val temperatureLevel: Int get() = if (protocolVersion >= 2) temperatureRaw / 50 else temperatureRaw / 36

    val usesMiles: Boolean get() = distanceUnit == 1
}

/** Trama 0x4B: odómetro, parcial y aceleración. Distancias en km. */
data class TripCounters(
    val totalKm: Float,
    val tripKm: Float,
    val tripTime: Int,
    val accel0to100S: Float,
)

sealed interface TftMessage {
    data class Live(val data: Telemetry) : TftMessage
    data class Trip(val data: TripCounters) : TftMessage
    data class States(val navi: Int, val phone: Int) : TftMessage
    data class Event(val code: Int) : TftMessage
    data class Unknown(val command: Int) : TftMessage
}

/**
 * Protocolo del cuadro VOGE (ver docs/protocolo_ble.md).
 * Tramas de 20 bytes: 01 | cmd | datos[16] | XOR | 04.
 */
object TftProtocol {
    const val FRAME_SIZE = 20

    const val CMD_HEARTBEAT = 0x5A
    const val CMD_NAV = 0x6A
    const val CMD_NEXT_ROAD_A = 0x6B
    const val CMD_NEXT_ROAD_B = 0x6C
    const val CMD_CURRENT_ROAD_A = 0x6D
    const val CMD_CURRENT_ROAD_B = 0x6E

    const val RX_LIVE = 0x4A
    const val RX_TRIP = 0x4B
    const val RX_STATE = 0x4C
    const val RX_EVENT = 0x1A

    private const val CHECKSUM_INDEX = 18
    private const val ROAD_CHUNK = 15
    private const val ROAD_MAX_BYTES = ROAD_CHUNK * 2

    /** XOR de todos los bytes salvo el propio checksum (el original lo calcula con ese byte a 0). */
    fun checksum(frame: ByteArray): Byte {
        var x = 0
        for (i in frame.indices) if (i != CHECKSUM_INDEX) x = x xor (frame[i].toInt() and 0xFF)
        return x.toByte()
    }

    fun hasValidChecksum(frame: ByteArray): Boolean =
        frame.size == FRAME_SIZE && frame[CHECKSUM_INDEX] == checksum(frame)

    private fun frame(command: Int, fill: (ByteArray) -> Unit = {}): ByteArray {
        val b = ByteArray(FRAME_SIZE)
        b[0] = 0x01
        b[1] = command.toByte()
        fill(b)
        b[19] = 0x04
        b[CHECKSUM_INDEX] = checksum(b)
        return b
    }

    /** Escribe [value] en big-endian en [length] bytes, recortado al rango del campo. */
    private fun putBE(b: ByteArray, offset: Int, length: Int, value: Int) {
        val max = (1L shl (8 * length)) - 1
        val v = value.toLong().coerceIn(0, max)
        for (i in 0 until length) b[offset + length - 1 - i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    private fun readBE(b: ByteArray, offset: Int, length: Int): Int {
        var v = 0
        for (i in 0 until length) v = (v shl 8) or (b[offset + i].toInt() and 0xFF)
        return v
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /** Trama 0x5A: la app oficial la envía cada segundo mientras hay conexión. */
    fun heartbeat(batteryPercent: Int, now: LocalTime): ByteArray = frame(CMD_HEARTBEAT) { b ->
        b[2] = 0
        b[3] = batteryPercent.coerceIn(0, 100).toByte()
        b[4] = 50
        b[5] = now.hour.toByte()
        b[6] = now.minute.toByte()
        b[7] = now.second.toByte()
        b[8] = if (now.hour >= 12) 1 else 0
        b[9] = 0
        b[10] = 2
    }

    fun navigation(info: NavInfo, now: LocalTime): ByteArray = frame(CMD_NAV) { b ->
        putBE(b, 2, 3, info.distanceToManeuverM)
        putBE(b, 5, 2, info.routeRemainingS)
        putBE(b, 7, 3, info.routeRemainingM)
        b[10] = info.maneuver.code.toByte()
        val eta = info.eta ?: now.plusSeconds(info.routeRemainingS.toLong())
        b[11] = eta.hour.toByte()
        b[12] = eta.minute.toByte()
        b[13] = if (eta.hour >= 12) 1 else 0
        b[14] = when {
            info.inTunnel -> 1
            info.roundaboutSector > 0 -> 4
            else -> 0
        }
        b[15] = 0
        b[16] = when {
            info.roundaboutSector <= 0 -> 0
            info.leftHandTraffic -> info.roundaboutSector.coerceAtMost(12) + 50
            else -> info.roundaboutSector.coerceAtMost(12)
        }.toByte()
    }

    /** Indica al cuadro que la navegación ha terminado (byte 15 = 1). */
    fun endNavigation(): ByteArray = frame(CMD_NAV) { b -> b[15] = 1 }

    /**
     * Parte un nombre en dos tramas de 15 bytes UTF-8, igual que el original. A diferencia de él, recorta
     * a 30 bytes sin partir un carácter multibyte al final.
     */
    fun roadFrames(commandA: Int, commandB: Int, name: String, asciiOnly: Boolean, chinese: Boolean = false): List<ByteArray> {
        val text = if (asciiOnly) stripAccents(name) else name
        val bytes = truncateUtf8(text.trim(), ROAD_MAX_BYTES)
        val lang: Byte = if (chinese) 1 else 0
        val first = frame(commandA) { b ->
            b[2] = lang
            bytes.copyInto(b, 3, 0, minOf(ROAD_CHUNK, bytes.size))
        }
        val second = frame(commandB) { b ->
            b[2] = lang
            if (bytes.size > ROAD_CHUNK) bytes.copyInto(b, 3, ROAD_CHUNK, bytes.size)
        }
        return listOf(first, second)
    }

    /** Ráfaga completa en el orden en que la envía la app oficial: 6A, 6B, 6C, 6D, 6E. */
    fun navigationBurst(info: NavInfo, now: LocalTime, asciiOnly: Boolean): List<ByteArray> =
        listOf(navigation(info, now)) +
            roadFrames(CMD_NEXT_ROAD_A, CMD_NEXT_ROAD_B, info.nextRoad, asciiOnly) +
            roadFrames(CMD_CURRENT_ROAD_A, CMD_CURRENT_ROAD_B, info.currentRoad, asciiOnly)

    fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        var end = maxBytes
        // Retrocede mientras el primer byte que queda fuera sea de continuación (10xxxxxx).
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOf(end)
    }

    fun stripAccents(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\x20-\\x7E]"), "?")

    /**
     * Interpreta una trama del cuadro. [protocolVersion] es la última vista en 0x4A; hace falta para escalar 0x4B.
     * Igual que la app original, no descarta tramas con checksum incorrecto.
     */
    fun parse(frame: ByteArray, protocolVersion: Int): TftMessage? {
        if (frame.size != FRAME_SIZE) return null
        return when (val cmd = u8(frame, 1)) {
            RX_LIVE -> TftMessage.Live(
                Telemetry(
                    speedKmh = u8(frame, 2),
                    rpm = readBE(frame, 3, 2),
                    gear = u8(frame, 5),
                    averageSpeedKmh = u8(frame, 6),
                    leanAngle = u8(frame, 7),
                    leanDirection = u8(frame, 8),
                    voltageRaw = u8(frame, 9),
                    temperatureRaw = u8(frame, 10),
                    fuelLevel = u8(frame, 11),
                    maxSpeedKmh = u8(frame, 12),
                    historicAverageSpeedKmh = u8(frame, 13),
                    signalRescue = u8(frame, 14),
                    unknown15 = u8(frame, 15),
                    protocolVersion = u8(frame, 16),
                    distanceUnit = u8(frame, 17),
                )
            )
            RX_TRIP -> {
                val scale = if (protocolVersion >= 2) 10f else 1f
                TftMessage.Trip(
                    TripCounters(
                        totalKm = readBE(frame, 2, 3) / scale,
                        tripKm = readBE(frame, 5, 3) / scale,
                        tripTime = readBE(frame, 8, 2),
                        accel0to100S = u8(frame, 10) / 10f,
                    )
                )
            }
            RX_STATE -> TftMessage.States(navi = u8(frame, 2), phone = u8(frame, 3))
            RX_EVENT -> TftMessage.Event(u8(frame, 2))
            else -> TftMessage.Unknown(cmd)
        }
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
