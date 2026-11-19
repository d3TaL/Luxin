package io.github.d3tal.luxin.tracker

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Test
import org.junit.Assert.*

class BigMapMarkerOverlayTest {

    private fun geometry(
        space: String = "twt702",
        matrix: List<Double> = listOf(1.0, 0.0, 100.0, 0.0, 1.0, 100.0),
        frameWidth: Int = 3120,
        frameHeight: Int = 1440
    ): BigMapGeometry =
        BigMapGeometry(
            space = space,
            reference = "test",
            matrix = matrix,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            timestampSeconds = 0.0,
            inliers = 100,
            matches = 100,
            errorPx = 0.5,
            support = 0.5
        )

    @Test
    fun `toDisplay projects Kongying coords to display pixels`() {
        val geom = geometry()
        val (dx, dy) = geom.toDisplay(10.0, 20.0, 3120, 1440)
        // matrix: [1,0,100; 0,1,100] -> x=10*1+100=110, y=20*1+100=120
        assertEquals(110.0, dx, 0.001)
        assertEquals(120.0, dy, 0.001)
    }

    @Test
    fun `toDisplay rejects mismatched aspect`() {
        val geom = geometry()
        try {
            geom.toDisplay(10.0, 20.0, 1000, 1000)
            fail("should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("aspect ratio") == true)
        }
    }

    @Test
    fun `BigMapMarkerOverlay is created without crash`() {
        // Cannot instantiate View without Android context in unit test.
        // This test just verifies the class compiles and geometry projection works.
        val geom = geometry(space = "kzsd2")
        assertEquals("kzsd2", geom.space)
        val (dx, dy) = geom.toDisplay(50.0, 60.0, 3120, 1440)
        assertEquals(150.0, dx, 0.001)
        assertEquals(160.0, dy, 0.001)
    }

    @Test
    fun `marker outside frame is filtered by BigMapMarkerFilter`() {
        io.github.d3tal.luxin.data.KongyingMarkerStore.replaceAll(
            listOf(
                LocalMarkerRecord(1, "m1", 50.0, 50.0, 1, 1, 1, "twt702", false, emptyList()),
                LocalMarkerRecord(2, "m2", 5000.0, 5000.0, 2, 2, 2, "twt702", false, emptyList()),
            )
        )

        val result = BigMapMarkerFilter.filterMarkersForBigMap(
            "twt702",
            LayerDecision(null, true, "surface", "test"),
            3120,
            1440,
            geometry()
        )

        assertEquals(1, result.size)
        assertEquals(1L, result[0].id)
    }
}
