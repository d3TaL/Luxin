package io.github.d3tal.luxin.tracker

import org.junit.Test
import org.junit.Assert.*

class BigMapLayerDetectorTest {

    private fun makeGetPixel(
        width: Int,
        height: Int,
        buttonX: Int,
        buttonY: Int,
        buttonSize: Int,
        brightness: Int
    ): (Int, Int) -> Int = { x, y ->
        val inButton = x >= buttonX && x < buttonX + buttonSize && y >= buttonY && y < buttonY + buttonSize
        if (inButton) {
            brightness shl 16 or brightness shl 8 or brightness or 0xFF000000
        } else if (x >= width * 0.88 && x <= width * 0.96) {
            0xFF1E1E1E
        } else {
            0xFF0A0A0A
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
        val decision = BigMapLayerDetector.detectLayer(0, 0) { _, _ -> 0 }
        assertEquals("unknown", decision.mode)
    }

    @Test
    fun `no bright buttons returns unknown`() {
        val decision = BigMapLayerDetector.detectLayer(3120, 1440) { _, _ -> 0xFF1E1E1E }
        assertEquals("unknown", decision.mode)
    }
}
