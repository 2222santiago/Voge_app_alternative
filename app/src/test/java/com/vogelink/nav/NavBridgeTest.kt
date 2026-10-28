package com.vogelink.nav

import com.vogelink.protocol.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NavBridgeTest {

    private fun raw(title: String, text: String, timeMs: Long) =
        RawNotification("com.google.android.apps.maps", NotificationText(title = title, text = text), null, null, timeMs)

    @Before
    fun reset() = NavBridge.clear("test")

    @Test
    fun unparsedUpdateKeepsLastManeuverUntilStale() {
        assertTrue(NavBridge.onNotification(raw("300 m", "Gira a la derecha", 1_000)))
        assertFalse(NavBridge.onNotification(raw("Recalculando…", "", 2_000)))
        NavBridge.onUnparsedUpdate(2_000)

        NavBridge.expireStale(20_000)
        assertEquals(Maneuver.RIGHT, NavBridge.current.value?.maneuver)
        assertNotNull(NavBridge.unparsedSinceMs.value)

        NavBridge.expireStale(32_000)
        assertNull(NavBridge.current.value)
    }

    @Test
    fun parsedUpdateCancelsStaleTimer() {
        NavBridge.onNotification(raw("300 m", "Gira a la derecha", 1_000))
        NavBridge.onUnparsedUpdate(2_000)
        NavBridge.onNotification(raw("150 m", "Gira a la izquierda", 10_000))
        assertNull(NavBridge.unparsedSinceMs.value)

        NavBridge.expireStale(60_000)
        assertEquals(Maneuver.LEFT, NavBridge.current.value?.maneuver)
        assertEquals(10_000, NavBridge.lastParsedMs.value)
    }
}
