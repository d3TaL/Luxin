package io.github.d3tal.luxin.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BigMapConfirmationTest {
    @Test fun requiresTwoObservationsAndClearsImmediately() {
        val gate = BigMapConfirmation()
        assertEquals("unknown", gate.observe(false))
        assertEquals("big_map_pending", gate.observe(true))
        assertEquals("big_map", gate.observe(true))
        repeat(10) { assertEquals("big_map", gate.observe(true)) }
        assertEquals("unknown", gate.observe(false))
        assertEquals("big_map_pending", gate.observe(true))
    }

    @Test fun interruptedObservationsCannotConfirm() {
        val gate = BigMapConfirmation()
        repeat(5) {
            assertEquals("big_map_pending", gate.observe(true))
            gate.reset() // stale frame, content hidden, snapshot, geometry change
        }
        assertEquals("big_map_pending", gate.observe(true))
        assertEquals("unknown", gate.observe(false))
        assertEquals("big_map_pending", gate.observe(true))
    }

    @Test fun mapStateDoesNotContainMinimapFix() {
        val state = LiveTrackingState(enabled = true, status = "paused", screenMode = "big_map",
            message = "Открыта большая карта")
        assertNull(state.position)
        assertNull(state.layer)
        assertTrue(formatTrackingHud(state).contains("Экран: большая карта"))
    }
}
