package io.github.d3tal.luxin.tracker

import org.junit.Test
import org.junit.Assert.*

class BigMapLayerDetectorTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun makeGetPixel(
        width: Int,
        height: Int,
        buttonX: Int,
        buttonY: Int,
        buttonSize: Int,
        brightness: Int
    ): (Int, Int) -> Int = { x, y ->
        val inButton = x in buttonX until buttonX + buttonSize && y in buttonY until buttonY + buttonSize
        if (inButton) {
            argb(255, brightness, brightness, brightness)
        } else if (x >= (width * 0.88).toInt() && x <= (width * 0.96).toInt()) {
            argb(255, 30, 30, 30)
        } else {
            argb(255, 10, 10, 10)
        }
    }

    @Test
    fun `lower bright button returns surface`() {
        val decision = BigMapLayerDetector.detectLayer(3120, 1440,
            makeGetPixel(3120, 1440, 2823, 1200, 40, 220))
        assertEquals("surface", decision.mode)
        assertTrue(decision.isSurface)
    }

    @Test
    fun `upper bright button returns layer`() {
        val decision = BigMapLayerDetector.detectLayer(3120, 1440,
            makeGetPixel(3120, 1440, 2823, 600, 40, 220))
        assertEquals("layer", decision.mode)
        assertFalse(decision.isSurface)
    }

    @Test
    fun `empty bitmap returns unknown`() {
        val decision = BigMapLayerDetector.detectLayer(0, 0) { _, _ -> argb(255, 0, 0, 0) }
        assertEquals("unknown", decision.mode)
    }

    @Test
    fun `no bright buttons returns unknown`() {
        val decision = BigMapLayerDetector.detectLayer(3120, 1440) { _, _ -> argb(255, 30, 30, 30) }
        assertEquals("unknown", decision.mode)
    }
}
