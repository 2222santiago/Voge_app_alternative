package com.vogelink.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class TftProtocolTest {

    /** Reimplementación literal de gc.a.a() de la app oficial: XOR de los 20 bytes con el byte 18 a 0. */
    private fun originalChecksum(frame: ByteArray): Byte {
        val copy = frame.copyOf()
        copy[18] = 0
        var x = 0
        for (b in copy) x = x xor (b.toInt() and 0xFF)
        return x.toByte()
    }

    private fun u(b: Byte) = b.toInt() and 0xFF

    private fun assertFrame(frame: ByteArray, cmd: Int) {
        assertEquals(20, frame.size)
        assertEquals(1, u(frame[0]))
        assertEquals(cmd, u(frame[1]))
        assertEquals(4, u(frame[19]))
        assertEquals(originalChecksum(frame), frame[18])
        assertTrue(TftProtocol.hasValidChecksum(frame))
    }

    @Test
    fun heartbeatLayout() {
        val f = TftProtocol.heartbeat(87, LocalTime.of(14, 5, 9))
        assertFrame(f, 0x5A)
        assertEquals(listOf(0, 87, 50, 14, 5, 9, 1, 0, 2), (2..10).map { u(f[it]) })
    }

    @Test
    fun heartbeatMorningAndClamp() {
        val f = TftProtocol.heartbeat(150, LocalTime.of(9, 0, 0))
        assertEquals(100, u(f[3]))
        assertEquals(0, u(f[8]))
    }

    @Test
    fun navigationLayout() {
        val info = NavInfo(
            distanceToManeuverM = 1234,
            maneuver = Maneuver.SLIGHT_RIGHT,
            routeRemainingM = 98_765,
            routeRemainingS = 3600,
        )
        val f = TftProtocol.navigation(info, LocalTime.of(23, 30))
        assertFrame(f, 0x6A)
        assertArrayEquals(byteArrayOf(0x00, 0x04, 0xD2.toByte()), f.copyOfRange(2, 5))
        assertArrayEquals(byteArrayOf(0x0E, 0x10), f.copyOfRange(5, 7))
        assertArrayEquals(byteArrayOf(0x01, 0x81.toByte(), 0xCD.toByte()), f.copyOfRange(7, 10))
        assertEquals(7, u(f[10]))
        // Llegada = 23:30 + 1 h = 00:30, AM.
        assertEquals(listOf(0, 30, 0), listOf(u(f[11]), u(f[12]), u(f[13])))
        assertEquals(listOf(0, 0, 0), listOf(u(f[14]), u(f[15]), u(f[16])))
    }

    @Test
    fun navigationFlags() {
        val roundabout = NavInfo(100, Maneuver.LEFT, roundaboutSector = 9, eta = LocalTime.of(18, 45))
        val f = TftProtocol.navigation(roundabout, LocalTime.NOON)
        assertEquals(listOf(18, 45, 1), listOf(u(f[11]), u(f[12]), u(f[13])))
        assertEquals(4, u(f[14]))
        assertEquals(9, u(f[16]))

        val left = TftProtocol.navigation(roundabout.copy(leftHandTraffic = true), LocalTime.NOON)
        assertEquals(59, u(left[16]))

        val tunnel = TftProtocol.navigation(roundabout.copy(inTunnel = true), LocalTime.NOON)
        assertEquals(1, u(tunnel[14]))
    }

    @Test
    fun navigationClampsHugeValues() {
        val f = TftProtocol.navigation(NavInfo(50_000_000, Maneuver.STRAIGHT, routeRemainingS = 200_000), LocalTime.NOON)
        assertArrayEquals(byteArrayOf(-1, -1, -1), f.copyOfRange(2, 5))
        assertArrayEquals(byteArrayOf(-1, -1), f.copyOfRange(5, 7))
    }

    @Test
    fun endNavigation() {
        val f = TftProtocol.endNavigation()
        assertFrame(f, 0x6A)
        assertEquals(1, u(f[15]))
    }

    @Test
    fun roadNameSplitsLikeOriginal() {
        val name = "Avenida de la Constitución Española"
        val frames = TftProtocol.roadFrames(0x6B, 0x6C, name, asciiOnly = false)
        assertFrame(frames[0], 0x6B)
        assertFrame(frames[1], 0x6C)
        val bytes = name.toByteArray(Charsets.UTF_8)
        assertArrayEquals(bytes.copyOfRange(0, 15), frames[0].copyOfRange(3, 18))
        val joined = frames[0].copyOfRange(3, 18) + frames[1].copyOfRange(3, 18)
        val decoded = String(joined, Charsets.UTF_8).trimEnd('\u0000')
        assertTrue(name.startsWith(decoded))
        // Nunca deja un carácter multibyte a medias.
        assertTrue(!decoded.contains('�'))
    }

    @Test
    fun truncateUtf8KeepsCharacters() {
        val t = TftProtocol.truncateUtf8("ññññññññññññññññ", 31)
        assertEquals(30, t.size)
        assertEquals("ñ".repeat(15), String(t, Charsets.UTF_8))
    }

    @Test
    fun stripAccents() {
        assertEquals("Calle Nunez de Balboa, Malaga", TftProtocol.stripAccents("Calle Núñez de Balboa, Málaga"))
    }

    @Test
    fun burstOrder() {
        val burst = TftProtocol.navigationBurst(NavInfo(10, Maneuver.RIGHT, "A", "B"), LocalTime.NOON, false)
        assertEquals(listOf(0x6A, 0x6B, 0x6C, 0x6D, 0x6E), burst.map { u(it[1]) })
    }

    private fun incoming(cmd: Int, vararg payload: Int): ByteArray {
        val b = ByteArray(20)
        b[0] = 1
        b[1] = cmd.toByte()
        payload.forEachIndexed { i, v -> b[2 + i] = v.toByte() }
        b[19] = 4
        b[18] = TftProtocol.checksum(b)
        return b
    }

    @Test
    fun parseLiveVersion2() {
        val f = incoming(0x4A, 87, 0x1F, 0x40, 4, 60, 35, 1, 128, 140, 5, 120, 70, 0, 0, 2, 0)
        val t = (TftProtocol.parse(f, 0) as TftMessage.Live).data
        assertEquals(87, t.speedKmh)
        assertEquals(8000, t.rpm)
        assertEquals(4, t.gear)
        assertEquals(35, t.leanAngle)
        assertEquals(12.8f, t.voltage, 0.001f)
        assertEquals(80, t.temperatureC)
        assertEquals(2, t.temperatureLevel)
        assertEquals(2, t.protocolVersion)
    }

    @Test
    fun parseLiveVersion1AndInvalidTemp() {
        val f = incoming(0x4A, 0, 0, 0, 0, 0, 0, 0, 13, 251, 0, 0, 0, 0, 0, 1, 1)
        val t = (TftProtocol.parse(f, 0) as TftMessage.Live).data
        assertEquals(13f, t.voltage, 0.001f)
        assertNull(t.temperatureC)
        assertTrue(t.usesMiles)
    }

    @Test
    fun parseTripScalesByVersion() {
        val f = incoming(0x4B, 0x01, 0xE2, 0x40, 0x00, 0x03, 0xE8, 0x00, 0x2D, 42)
        val v2 = (TftProtocol.parse(f, 2) as TftMessage.Trip).data
        assertEquals(12345.6f, v2.totalKm, 0.01f)
        assertEquals(100f, v2.tripKm, 0.01f)
        assertEquals(45, v2.tripTime)
        assertEquals(4.2f, v2.accel0to100S, 0.001f)
        val v1 = (TftProtocol.parse(f, 1) as TftMessage.Trip).data
        assertEquals(123456f, v1.totalKm, 0.01f)
    }

    @Test
    fun parseOthers() {
        assertEquals(TftMessage.States(1, 2), TftProtocol.parse(incoming(0x4C, 1, 2), 0))
        assertEquals(TftMessage.Event(4), TftProtocol.parse(incoming(0x1A, 4), 0))
        assertEquals(TftMessage.Unknown(0x99), TftProtocol.parse(incoming(0x99), 0))
        assertNull(TftProtocol.parse(ByteArray(5), 0))
    }
}
