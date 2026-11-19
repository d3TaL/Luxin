package io.github.d3tal.luxin.tracker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    @Test fun closingMapWithdrawsTrackingBeforeMinimapReturns() = runBlocking {
        val gate = BigMapConfirmation()
        repeat(2) { gate.observeLive(true) { error("unexpected map exit") } }
        val geometry = BigMapGeometry("twt702", "ref", listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0),
            3120, 1440, 1.0, 20, 24, 0.4, 0.5)
        var hud = LiveTrackingState(enabled = true, screenMode = "big_map", bigMapGeometryMode = "tracking", bigMapGeometry = geometry)
        val returnFromMinimap = CompletableDeferred<Unit>()
        val next = launch(start = CoroutineStart.UNDISPATCHED) {
            assertEquals("unknown", gate.observeLive(false) {
                publishTrackingResult(LiveTrackingState(enabled = true, status = "searching"), 1, 1, null,
                    1, 1, 1_100_000_000, null) { hud = it }
            })
            returnFromMinimap.await()
        }
        assertTrue(next.isActive)
        assertNull(hud.bigMapGeometry)
        assertTrue(!formatTrackingHud(hud).contains("Геометрия: tracking"))
        returnFromMinimap.complete(Unit)
        next.join()
        gate.observeLive(false) { error("minimap frames must not repeatedly clear the HUD") }
        Unit
    }
}
