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

    private fun marker(
        id: Long,
        space: String = "twt702",
        x: Double = 100.0,
        y: Double = 200.0,
        isUnderground: Boolean = false,
        levels: List<String> = emptyList()
    ): io.github.d3tal.luxin.data.LocalMarkerRecord =
        io.github.d3tal.luxin.data.LocalMarkerRecord(
            id = id,
            title = "marker $id",
            x = x,
            y = y,
            itemId = id,
            iconId = 1L,
            areaId = 1L,
            space = space,
            isUnderground = isUnderground,
            levels = levels
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
    fun `BigMapMarkerFilter marks selected layer blue, others gray`() {
        io.github.d3tal.luxin.data.KongyingMarkerStore.replaceAll(
            listOf(
                marker(1, "twt702", 50.0, 50.0, isUnderground = false, levels = emptyList()),       // surface
                marker(2, "twt702", 150.0, 150.0, isUnderground = true, levels = listOf("UG1")),   // underground
            )
        )

        // Surface selected: marker 1 selected, marker 2 not selected
        val surfaceResult = BigMapMarkerFilter.filterMarkersForBigMap(
            "twt702",
            LayerDecision(null, true, "surface", "test"),
            3120,
            1440,
            geometry()
        )
        assertEquals(2, surfaceResult.size)
        assertTrue(surfaceResult[0].isSelectedLayer)   // marker 1: surface
        assertFalse(surfaceResult[1].isSelectedLayer)  // marker 2: underground

        // UG1 layer selected: marker 2 selected, marker 1 not selected
        val ugResult = BigMapMarkerFilter.filterMarkersForBigMap(
            "twt702",
            LayerDecision("UG1", false, "layer", "test"),
            3120,
            1440,
            geometry()
        )
        assertEquals(2, ugResult.size)
        assertFalse(ugResult[0].isSelectedLayer)   // marker 1: surface
        assertTrue(ugResult[1].isSelectedLayer)    // marker 2: UG1

        // Unknown layer: both not selected
        val unknownResult = BigMapMarkerFilter.filterMarkersForBigMap(
            "twt702",
            LayerDecision(null, false, "unknown", "test"),
            3120,
            1440,
            geometry()
        )
        assertEquals(2, unknownResult.size)
        assertFalse(unknownResult[0].isSelectedLayer)
        assertFalse(unknownResult[1].isSelectedLayer)
    }

    @Test
    fun `marker outside frame is filtered`() {
        io.github.d3tal.luxin.data.KongyingMarkerStore.replaceAll(
            listOf(
                marker(1, "twt702", 50.0, 50.0),
                marker(2, "twt702", 5000.0, 5000.0),
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
        assertEquals(1L, result[0].record.id)
    }

    @Test
    fun `null space or geometry returns empty`() {
        io.github.d3tal.luxin.data.KongyingMarkerStore.replaceAll(listOf(marker(1, "twt702")))

        assertEquals(0, BigMapMarkerFilter.filterMarkersForBigMap(
            null, LayerDecision(null, true, "surface", "test"), 3120, 1440, null
        ).size)
        assertEquals(0, BigMapMarkerFilter.filterMarkersForBigMap(
            "twt702", LayerDecision(null, true, "surface", "test"), 3120, 1440, null
        ).size)
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
}
