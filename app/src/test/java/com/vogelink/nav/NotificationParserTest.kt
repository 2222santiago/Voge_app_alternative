package com.vogelink.nav

import com.vogelink.protocol.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class NotificationParserTest {

    private fun parse(title: String, text: String = "", sub: String = "") =
        NotificationParser.parse(NotificationText(title = title, text = text, subText = sub))

    @Test
    fun spanishTurnWithSummary() {
        val p = parse("300 m", "Gira a la derecha hacia Calle Mayor", "12 min · 4,2 km · Llegada: 10:43")
        assertEquals(300, p.distanceM)
        assertEquals(Maneuver.RIGHT, p.maneuver)
        assertEquals("Calle Mayor", p.road)
        assertEquals(720, p.remainingS)
        assertEquals(4200, p.remainingM)
        assertEquals(LocalTime.of(10, 43), p.eta)
    }

    @Test
    fun distances() {
        assertEquals(1200, NotificationParser.parseDistance("1,2 km"))
        assertEquals(482, NotificationParser.parseDistance("0.3 mi"))
        assertEquals(152, NotificationParser.parseDistance("500 ft"))
        assertEquals(304, NotificationParser.parseDistance("1,000 ft"))
        assertNull(NotificationParser.parseDistance("12 min"))
        assertEquals(0, parse("Ahora", "Gira a la izquierda").distanceM)
    }

    @Test
    fun roundaboutExit() {
        val p = parse("150 m", "En la rotonda, toma la tercera salida hacia A-6")
        assertEquals(Maneuver.LEFT, p.maneuver)
        assertEquals(3, p.roundaboutExit)
        assertEquals("A-6", p.road)
        assertEquals(1, parse("80 m", "En la glorieta, toma la 1.ª salida").roundaboutExit)
        assertEquals(2, parse("80 m", "At the roundabout, take the 2nd exit onto Elm St").roundaboutExit)
    }

    @Test
    fun maneuverKeywords() {
        assertEquals(Maneuver.SLIGHT_LEFT, parse("1 km", "Mantente a la izquierda").maneuver)
        assertEquals(Maneuver.SHARP_RIGHT, parse("50 m", "Gira bruscamente a la derecha").maneuver)
        assertEquals(Maneuver.UTURN, parse("200 m", "Cambia de sentido").maneuver)
        assertEquals(Maneuver.UTURN, parse("200 ft", "Make a U-turn").maneuver)
        assertEquals(Maneuver.LEFT, parse("0.2 mi", "Turn left onto Main St").maneuver)
        assertEquals("Main St", parse("0.2 mi", "Turn left onto Main St").road)
        assertEquals(Maneuver.SLIGHT_RIGHT, parse("2 km", "Toma la salida 25 hacia Madrid").maneuver)
        assertEquals(Maneuver.STRAIGHT, parse("3 km", "Continúa recto por la N-II").maneuver)
    }

    @Test
    fun streetOnlyText() {
        val p = parse("200 m", "Calle de Alcalá")
        assertNull(p.maneuver)
        assertEquals("Calle de Alcalá", p.road)
    }

    @Test
    fun summaryIsNotManeuverDistance() {
        val p = parse("Calle Mayor", "12 min · 4,2 km")
        assertNull(p.distanceM)
        assertEquals(4200, p.remainingM)
    }

    @Test
    fun durationsAndClock() {
        assertEquals(3900, NotificationParser.parseDuration("1 h 5 min · 98 km · 13:20"))
        assertEquals(LocalTime.of(22, 43), NotificationParser.parseClock("Llegada 10:43 p. m."))
        assertEquals(LocalTime.of(0, 15), NotificationParser.parseClock("12:15 AM ETA"))
        assertEquals(LocalTime.of(13, 20), NotificationParser.parseClock("98 km · 13:20"))
    }

    @Test
    fun exitSectors() {
        assertEquals(listOf(3, 6, 9, 11, 12), (1..5).map(NotificationParser::sectorForExit))
    }
}
