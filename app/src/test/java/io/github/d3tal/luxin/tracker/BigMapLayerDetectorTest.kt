package io.github.d3tal.luxin.tracker

import android.graphics.Color
import org.junit.Test
import org.junit.Assert.*

class BigMapLayerDetectorTest {

    private fun brightColumn(
        width: Int = 3120,
        height: Int = 1440,
        buttonX: Int,
        buttonY: Int,
        buttonSize: Int = 40,
        brightness: Int = 220
    ): (Int, Int) -> Int = { x, y ->
        if (x >= width * 0.88 && x <= width * 0.96) Color.rgb(30, 30, 30) else Color.rgb(10, 10, 10)
    }.let { base ->
        { x, y ->
            if (x in buttonX until buttonX + buttonSize && y in buttonY until buttonY + buttonSize) {
                Color.rgb(brightness, brightness, brightness)
            } else base(x, y)
        }
    }

    @Test
    fun `lower bright button returns surface`() {
        val decision = BigMapLayerDetector.detectLayer(3120, 1440, brightColumn(buttonX = 2800, buttonY = 1200, brightness = 220))
        assertEquals("surface", decision.mode)
        assertTrue(decision.isSurface)
    }

    @Test
    fun `upper bright button returns layer`() {
        val decision = BigMapLayerDetector.detectLayer(3120, 1440, brightColumn(buttonX = 2800, buttonY = 600, brightness = 220))
        assertEquals("layer", decision.mode)
        assertFalse(decision.isSurface)
    }

    @Test
    fun `empty bitmap returns unknown`() {
        val decision = BigMapLayerDetector.detectLayer(0, 0) { _, _ -> Color.rgb(0, 0, 0) }
        assertEquals("unknown", decision.mode)
    }

    @Test
    fun `no bright buttons returns unknown`() {
        val decision = BigMapLayerDetector.detectLayer(3120, 1440) { _, _ -> Color.rgb(30, 30, 30) }
        assertEquals("unknown", decision.mode)
    }
}
