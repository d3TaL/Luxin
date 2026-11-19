package io.github.d3tal.luxin.tracker

import io.github.d3tal.luxin.data.KongyingMarkerStore
import io.github.d3tal.luxin.data.LocalMarkerRecord
import org.junit.Test
import org.junit.Assert.*

class BigMapMarkerFilterTest {

    private fun marker(
        id: Long,
        space: String = "twt702",
        x: Double = 100.0,
        y: Double = 200.0,
        isUnderground: Boolean = false,
        levels: List<String> = emptyList()
    ): LocalMarkerRecord =
        LocalMarkerRecord(
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

    private fun layerDecision(
        mode: String = "surface",
        isSurface: Boolean = true,
        layerId: String? = null
    ): LayerDecision =
        LayerDecision(layerId, isSurface, mode, "test")

    @Test
    fun `markers of wrong space are excluded`() {
        KongyingMarkerStore.replaceAll(listOf(
            marker(1, "twt702"),
            marker(2, "kzsd2"),
        ))

        val result = BigMapMarkerFilter.filterMarkersForBigMap(
            "twt702", layerDecision(), 3120, 1440,
            BigMapGeometry("twt702", "ref", listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), 3120, 1440, 0.0, 12, 12, 0.5, 0.5)
        )

        assertEquals(1, result.size)
        assertEquals(1L, result[0].record.id)
    }

    @Test
    fun `unknown layer shows all markers, none selected`() {
        KongyingMarkerStore.replaceAll(listOf(marker(1, "twt702")))

        val result = BigMapMarkerFilter.filterMarkersForBigMap(
            "twt702", layerDecision("unknown", false, null), 3120, 1440,
            BigMapGeometry("twt702", "ref", listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), 3120, 1440, 0.0, 12, 12, 0.5, 0.5)
        )

        assertEquals(1, result.size)
        assertFalse(result[0].isSelectedLayer)
    }

    @Test
    fun `surface layer shows all markers, surface ones selected`() {
        KongyingMarkerStore.replaceAll(listOf(
            marker(1, "twt702", isUnderground = true, levels = listOf("SNOWMELT_DRAINAGE_CHANNEL_UG1")),
            marker(2, "twt702"), // empty levels = surface
        ))

        val result = BigMapMarkerFilter.filterMarkersForBigMap(
            "twt702", layerDecision("surface", true, null), 3120, 1440,
            BigMapGeometry("twt702", "ref", listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), 3120, 1440, 0.0, 12, 12, 0.5, 0.5)
        )

        assertEquals(2, result.size)
        assertFalse(result[0].isSelectedLayer)   // marker 1: underground
        assertTrue(result[1].isSelectedLayer)    // marker 2: surface
    }

    @Test
    fun `layer matches marker level`() {
        KongyingMarkerStore.replaceAll(listOf(
            marker(1, "twt702", isUnderground = true, levels = listOf("SNOWMELT_DRAINAGE_CHANNEL_UG1")),
            marker(2, "twt702", isUnderground = true, levels = listOf("OTHER_LEVEL")),
        ))

        val result = BigMapMarkerFilter.filterMarkersForBigMap(
            "twt702", layerDecision("layer", false, "SNOWMELT_DRAINAGE_CHANNEL_UG1"), 3120, 1440,
            BigMapGeometry("twt702", "ref", listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), 3120, 1440, 0.0, 12, 12, 0.5, 0.5)
        )

        assertEquals(2, result.size)
        assertTrue(result[0].isSelectedLayer)   // marker 1: UG1 matches
        assertFalse(result[1].isSelectedLayer)  // marker 2: OTHER_LEVEL doesn't match
    }

    @Test
    fun `markers outside frame are excluded`() {
        KongyingMarkerStore.replaceAll(listOf(
            marker(1, "twt702", x = 50.0, y = 50.0),
            marker(2, "twt702", x = 5000.0, y = 5000.0),
        ))

        val result = BigMapMarkerFilter.filterMarkersForBigMap(
            "twt702", layerDecision(), 3120, 1440,
            BigMapGeometry("twt702", "ref", listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), 3120, 1440, 0.0, 12, 12, 0.5, 0.5)
        )

        assertEquals(1, result.size)
        assertEquals(1L, result[0].record.id)
    }

    @Test
    fun `bad imported coordinates do not hide valid markers or throw`() {
        KongyingMarkerStore.replaceAll(listOf(marker(1, x = Double.NaN),
            marker(2, x = Double.MAX_VALUE), marker(3, x = 10.0)))
        val g = BigMapGeometry("twt702", "ref", listOf(2.0, 0.0, 0.0, 0.0, 2.0, 0.0),
            3120, 1440, 0.0, 12, 12, 0.5, 0.5)
        val visible = BigMapMarkerFilter.filterMarkersForBigMap("twt702", layerDecision(), 3120, 1440, g)
        assertEquals(listOf(3L), visible.map { it.record.id })
    }

    @Test
    fun `null space or geometry returns empty`() {
        KongyingMarkerStore.replaceAll(listOf(marker(1, "twt702")))

        assertEquals(0, BigMapMarkerFilter.filterMarkersForBigMap(
            null, layerDecision(), 3120, 1440, null
        ).size)
        assertEquals(0, BigMapMarkerFilter.filterMarkersForBigMap(
            "twt702", layerDecision(), 3120, 1440, null
        ).size)
    }
}
