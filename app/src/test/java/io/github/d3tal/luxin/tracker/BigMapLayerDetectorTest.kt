package io.github.d3tal.luxin.tracker

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BigMapLayerDetectorTest {

    private fun bitmapWithBrightColumn(
        width: Int = 3120,
        height: Int = 1440,
        buttonX: Int,
        buttonY: Int,
        buttonSize: Int = 40,
        brightness: Int = 220
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Темный фон UI колонки
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x >= width * 0.88 && x <= width * 0.96) {
                    bmp.setPixel(x, y, Color.rgb(30, 30, 30))
                } else {
                    bmp.setPixel(x, y, Color.rgb(10, 10, 10))
                }
            }
        }
        // Яркая кнопка этажа
        for (y in buttonY until (buttonY + buttonSize)) {
            for (x in buttonX until (buttonX + buttonSize)) {
                if (x < width && y < height) {
                    bmp.setPixel(x, y, Color.rgb(brightness, brightness, brightness))
                }
            }
        }
        return bmp
    }

    @Test
    fun `lower bright button returns surface`() {
        val bmp = bitmapWithBrightColumn(buttonX = 2800, buttonY = 1200, brightness = 220)
        val decision = BigMapLayerDetector.detectLayer(bmp)
        assertEquals("surface", decision.mode)
        assertTrue(decision.isSurface)
    }

    @Test
    fun `upper bright button returns layer`() {
        val bmp = bitmapWithBrightColumn(buttonX = 2800, buttonY = 600, brightness = 220)
        val decision = BigMapLayerDetector.detectLayer(bmp)
        assertEquals("layer", decision.mode)
        assertFalse(decision.isSurface)
    }

    @Test
    fun `empty bitmap returns unknown`() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val decision = BigMapLayerDetector.detectLayer(bmp)
        assertEquals("unknown", decision.mode)
    }

    @Test
    fun `no bright buttons returns unknown`() {
        val bmp = Bitmap.createBitmap(3120, 1440, Bitmap.Config.ARGB_8888)
        for (y in 0 until 1440) {
            for (x in 0 until 3120) {
                bmp.setPixel(x, y, Color.rgb(30, 30, 30))
            }
        }
        val decision = BigMapLayerDetector.detectLayer(bmp)
        assertEquals("unknown", decision.mode)
    }
}
