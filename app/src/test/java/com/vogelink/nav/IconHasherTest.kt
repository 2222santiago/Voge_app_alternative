package com.vogelink.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconHasherTest {

    /** Flecha blanca sobre transparente: una barra vertical, opcionalmente desplazada. */
    private fun bar(column: Int): IntArray = IntArray(256) { i -> if (i % 16 in column..column + 2) 0xFFFFFFFF.toInt() else 0 }

    @Test
    fun sameImageSameHash() {
        assertEquals(IconHasher.hashPixels(bar(6)), IconHasher.hashPixels(bar(6)))
        assertEquals(64, IconHasher.hashPixels(bar(6)).length)
    }

    @Test
    fun differentShapesAreFar() {
        val d = IconHasher.distance(IconHasher.hashPixels(bar(1)), IconHasher.hashPixels(bar(12)))
        assertTrue("distancia $d", d > 20)
    }
}
