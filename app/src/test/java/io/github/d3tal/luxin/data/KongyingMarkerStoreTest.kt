package io.github.d3tal.luxin.data

import org.junit.Test
import org.junit.Assert.*

class KongyingMarkerStoreTest {

    private fun marker(
        id: Long,
        space: String = "twt702",
        x: Double = 100.0,
        y: Double = 200.0
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
            isUnderground = false,
            levels = emptyList()
        )

    @Test
    fun `replaceAll rebuilds space index`() {
        KongyingMarkerStore.replaceAll(listOf(
            marker(1, "twt702"),
            marker(2, "twt702"),
            marker(3, "kzsd2"),
        ))

        assertEquals(3, KongyingMarkerStore.size)
        assertEquals(2, KongyingMarkerStore.markersForSpace("twt702").size)
        assertEquals(1, KongyingMarkerStore.markersForSpace("kzsd2").size)
        assertEquals(0, KongyingMarkerStore.markersForSpace("nonexistent").size)
    }

    @Test
    fun `upsert updates existing record`() {
        KongyingMarkerStore.replaceAll(listOf(marker(1, "twt702", 100.0, 200.0)))

        KongyingMarkerStore.upsert(marker(1, "twt702", 999.0, 888.0))

        val updated = KongyingMarkerStore.markerById(1)
        requireNotNull(updated)
        assertEquals(999.0, updated.x, 0.001)
        assertEquals(888.0, updated.y, 0.001)
        assertEquals(1, KongyingMarkerStore.markersForSpace("twt702").size)
    }

    @Test
    fun `upsert moves marker between spaces`() {
        KongyingMarkerStore.replaceAll(listOf(marker(1, "twt702")))

        KongyingMarkerStore.upsert(marker(1, "kzsd2"))

        assertEquals(0, KongyingMarkerStore.markersForSpace("twt702").size)
        assertEquals(1, KongyingMarkerStore.markersForSpace("kzsd2").size)
        assertEquals(1, KongyingMarkerStore.size)
    }

    @Test
    fun `remove deletes from space index`() {
        KongyingMarkerStore.replaceAll(listOf(
            marker(1, "twt702"),
            marker(2, "twt702"),
            marker(3, "kzsd2"),
        ))

        KongyingMarkerStore.remove(2)

        assertEquals(2, KongyingMarkerStore.size)
        assertEquals(1, KongyingMarkerStore.markersForSpace("twt702").size)
        assertEquals(1, KongyingMarkerStore.markersForSpace("kzsd2").size)
        assertFalse(KongyingMarkerStore.markersForSpace("twt702").any { it.id == 2L })
    }

    @Test
    fun `remove cleans up empty space indexes`() {
        KongyingMarkerStore.replaceAll(listOf(marker(1, "twt702")))

        KongyingMarkerStore.remove(1)

        assertEquals(0, KongyingMarkerStore.size)
        assertEquals(0, KongyingMarkerStore.markersForSpace("twt702").size)
    }

    @Test
    fun `allMarkers returns copy`() {
        KongyingMarkerStore.replaceAll(listOf(marker(1, "twt702"), marker(2, "kzsd2")))

        assertEquals(2, KongyingMarkerStore.allMarkers.value.size)
    }
}
