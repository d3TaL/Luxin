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
            BigMapGeometry("twt702", "ref", listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), 3120, 1440, 0.0, 0, 0, 0.0, 0.0)
        )

        assertEquals(1, result.size)
        assertEquals(1L, result[0].record.id)
    }

    @Test
    fun `unknown layer excludes all markers`() {
        KongyingMarkerStore.replaceAll(listOf(marker(1, "twt702")))

        val result = BigMapMarkerFilter.filterMarkersForBigMap(
            "twt702", layerDecision("unknown", false, null), 3120, 1440,
            BigMapGeometry("twt702", "ref", listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), 3120, 1440, 0.0, 0, 0, 0.0, 0.0)
        )

        assertEquals(0, result.size)
    }

    @Test
    fun `surface layer excludes underground markers`() {
        KongyingMarkerStore.replaceAll(listOf(
            marker(1, "twt702", isUnderground = true, levels = listOf("SNOWMELT_DRAINAGE_CHANNEL_UG1")),
            marker(2, "twt702"), // empty levels = surface
        ))

        val result = BigMapMarkerFilter.filterMarkersForBigMap(
            "twt702", layerDecision("surface", true, null), 3120, 1440,
            BigMapGeometry("twt702", "ref", listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), 3120, 1440, 0.0, 0, 0, 0.0, 0.0)
        )

        assertEquals(1, result.size)
        assertEquals(2L, result[0].id)
    }

    @Test
    fun `layer matches marker level`() {
        KongyingMarkerStore.replaceAll(listOf(
            marker(1, "twt702", isUnderground = true, levels = listOf("SNOWMELT_DRAINAGE_CHANNEL_UG1")),
            marker(2, "twt702", isUnderground = true, levels = listOf("OTHER_LEVEL")),
        ))

        val result = BigMapMarkerFilter.filterMarkersForBigMap(
            "twt702", layerDecision("layer", false, "SNOWMELT_DRAINAGE_CHANNEL_UG1"), 3120, 1440,
            BigMapGeometry("twt702", "ref", listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), 3120, 1440, 0.0, 0, 0, 0.0, 0.0)
        )

        assertEquals(1, result.size)
        assertEquals(1L, result[0].record.id)
    }

    @Test
    fun `markers outside frame are excluded`() {
        KongyingMarkerStore.replaceAll(listOf(
            marker(1, "twt702", x = 50.0, y = 50.0),
            marker(2, "twt702", x = 5000.0, y = 5000.0),
        ))

        val result = BigMapMarkerFilter.filterMarkersForBigMap(
            "twt702", layerDecision(), 3120, 1440,
            BigMapGeometry("twt702", "ref", listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), 3120, 1440, 0.0, 0, 0, 0.0, 0.0)
        )

        assertEquals(1, result.size)
        assertEquals(1L, result[0].record.id)
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
