package io.github.d3tal.luxin.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTrackingCadenceTest {

    @Test
    fun periodMsReturns67ForBigMapAndPendingOtherwise500() {
        assertEquals(67L, LiveTrackingCadence.periodMs("big_map"))
        assertEquals(67L, LiveTrackingCadence.periodMs("big_map_pending"))
        assertEquals(500L, LiveTrackingCadence.periodMs("minimap"))
        assertEquals(500L, LiveTrackingCadence.periodMs("unknown"))
        assertEquals(500L, LiveTrackingCadence.periodMs("paused"))
        assertEquals(500L, LiveTrackingCadence.periodMs(""))
    }

    @Test
    fun delayMsEnforcesPeriodMinusElapsedWithBudgetOverrunFloorOfOneMs() {
        // big_map: 67ms period
        assertEquals(67L, LiveTrackingCadence.delayMs("big_map", 0L))
        assertEquals(47L, LiveTrackingCadence.delayMs("big_map", 20L))
        assertEquals(1L, LiveTrackingCadence.delayMs("big_map", 66L))
        // Exact period budget exhausted -> 1ms yield to avoid busy spin
        assertEquals(1L, LiveTrackingCadence.delayMs("big_map", 67L))
        // Overrun yields minimum 1ms, preventing busy spin or negative delays
        assertEquals(1L, LiveTrackingCadence.delayMs("big_map", 100L))
        assertEquals(1L, LiveTrackingCadence.delayMs("big_map", 1000L))
        // Clock jitter / negative elapsed coerced to 0 -> full period
        assertEquals(67L, LiveTrackingCadence.delayMs("big_map", -50L))

        // default / minimap: 500ms period
        assertEquals(500L, LiveTrackingCadence.delayMs("minimap", 0L))
        assertEquals(100L, LiveTrackingCadence.delayMs("minimap", 400L))
        assertEquals(1L, LiveTrackingCadence.delayMs("minimap", 499L))
        assertEquals(1L, LiveTrackingCadence.delayMs("minimap", 500L))
        assertEquals(1L, LiveTrackingCadence.delayMs("minimap", 750L))
    }

    @Test
    fun keepBigMapAnchorAcceptsFreshAnchorForBigMapAndPending() {
        val anchorNs = 5_000_000_000L
        // Immediate poll at same timestamp
        assertTrue(LiveTrackingCadence.keepBigMapAnchor("big_map", anchorNs, anchorNs, true))
        assertTrue(LiveTrackingCadence.keepBigMapAnchor("big_map_pending", anchorNs, anchorNs, true))

        // 500ms later (well within 1s boundary)
        assertTrue(LiveTrackingCadence.keepBigMapAnchor("big_map", anchorNs, anchorNs + 500_000_000L, true))
        assertTrue(LiveTrackingCadence.keepBigMapAnchor("big_map_pending", anchorNs, anchorNs + 500_000_000L, true))

        // Exactly 1 second (1_000_000_000 ns) boundary
        assertTrue(LiveTrackingCadence.keepBigMapAnchor("big_map", anchorNs, anchorNs + 1_000_000_000L, true))
        assertTrue(LiveTrackingCadence.keepBigMapAnchor("big_map_pending", anchorNs, anchorNs + 1_000_000_000L, true))
    }

    @Test
    fun keepBigMapAnchorRejectsExpiredOrBackwardTimestamps() {
        val anchorNs = 5_000_000_000L

        // Expired past 1 second boundary
        assertFalse(LiveTrackingCadence.keepBigMapAnchor("big_map", anchorNs, anchorNs + 1_000_000_001L, true))
        assertFalse(LiveTrackingCadence.keepBigMapAnchor("big_map_pending", anchorNs, anchorNs + 2_000_000_000L, true))

        // Clock moved backwards (nowNs < lastTimestampNs)
        assertFalse(LiveTrackingCadence.keepBigMapAnchor("big_map", anchorNs, anchorNs - 1L, true))
        assertFalse(LiveTrackingCadence.keepBigMapAnchor("big_map_pending", anchorNs, 0L, true))

        // Non-positive anchor timestamp
        assertFalse(LiveTrackingCadence.keepBigMapAnchor("big_map", 0L, anchorNs, true))
        assertFalse(LiveTrackingCadence.keepBigMapAnchor("big_map", -100L, anchorNs, true))
    }

    @Test
    fun keepBigMapAnchorRejectsInvalidGeometryOrMode() {
        val anchorNs = 5_000_000_000L
        val freshNs = anchorNs + 100_000_000L

        // Discard anchor if geometry changed (e.g. capture resize/restart)
        assertFalse(LiveTrackingCadence.keepBigMapAnchor("big_map", anchorNs, freshNs, sameGeometry = false))
        assertFalse(LiveTrackingCadence.keepBigMapAnchor("big_map_pending", anchorNs, freshNs, sameGeometry = false))

        // Discard anchor for non-big-map modes
        assertFalse(LiveTrackingCadence.keepBigMapAnchor("minimap", anchorNs, freshNs, sameGeometry = true))
        assertFalse(LiveTrackingCadence.keepBigMapAnchor("unknown", anchorNs, freshNs, sameGeometry = true))
        assertFalse(LiveTrackingCadence.keepBigMapAnchor("", anchorNs, freshNs, sameGeometry = true))
    }

    @Test
    fun liveProcessingRateReportsNullUntilTwoSamples() {
        val rate = LiveProcessingRate()
        assertNull(rate.record(100L))
        assertEquals(1, rate.sampleCount)

        val fps = rate.record(200L)
        assertNotNull(fps)
        assertEquals(10.0, fps!!, 0.001)
        assertEquals(2, rate.sampleCount)
    }

    @Test
    fun liveProcessingRateCalculates15FpsAnd2Fps() {
        // 15 FPS cadence (~67ms intervals)
        val rate15 = LiveProcessingRate()
        assertNull(rate15.record(0L))
        val fps15_2 = rate15.record(67L)
        assertNotNull(fps15_2)
        assertEquals(14.925, fps15_2!!, 0.05)

        val fps15_3 = rate15.record(134L)
        assertNotNull(fps15_3)
        assertEquals(14.925, fps15_3!!, 0.05)

        // 2 FPS cadence (500ms intervals)
        val rate2 = LiveProcessingRate()
        assertNull(rate2.record(1000L))
        val fps2_2 = rate2.record(1500L)
        assertNotNull(fps2_2)
        assertEquals(2.0, fps2_2!!, 0.001)

        val fps2_3 = rate2.record(2000L)
        assertNotNull(fps2_3)
        assertEquals(2.0, fps2_3!!, 0.001)

        val fps2_4 = rate2.record(2500L)
        assertNotNull(fps2_4)
        assertEquals(2.0, fps2_4!!, 0.001)
    }

    @Test
    fun liveProcessingRateResetsOnDuplicateBackwardOrGap() {
        val rate = LiveProcessingRate()
        assertNull(rate.record(1000L))
        assertEquals(2.0, rate.record(1500L)!!, 0.001)

        // Duplicate timestamp resets the rate estimator and records duplicate as new sample
        assertNull(rate.record(1500L))
        assertEquals(1, rate.sampleCount)
        assertEquals(2.0, rate.record(2000L)!!, 0.001)

        // Backwards timestamp resets the rate estimator and records backward as new sample
        assertNull(rate.record(1800L))
        assertEquals(1, rate.sampleCount)
        assertEquals(2.0, rate.record(2300L)!!, 0.001)

        // Gap > 1000ms resets the rate estimator
        assertNull(rate.record(2300L + 1001L))
        assertEquals(1, rate.sampleCount)
        assertEquals(2.0, rate.record(3301L + 500L)!!, 0.001)

        // Exactly 1000ms gap does NOT reset
        val fpsAfter1000MsGap = rate.record(3801L + 1000L)
        assertNotNull(fpsAfter1000MsGap)
        // 3 samples: 3301L, 3801L, 4801L -> (3 - 1) * 1000 / (4801 - 3301) = 2000 / 1500 = 1.333 fps
        assertEquals(1.333, fpsAfter1000MsGap!!, 0.01)
    }

    @Test
    fun liveProcessingRateMaintainsBoundedMax16Samples() {
        val rate = LiveProcessingRate()
        // Record 25 monotonic samples with 50ms interval (20 FPS)
        for (i in 0 until 25) {
            rate.record(i * 50L)
            assertTrue(rate.sampleCount <= 16)
        }
        assertEquals(16, rate.sampleCount)
        // Rate over last 16 samples: (16 - 1) * 1000 / (15 * 50) = 20.0 fps
        val fps = rate.record(25 * 50L)
        assertEquals(16, rate.sampleCount)
        assertEquals(20.0, fps!!, 0.001)

        // Transition from 100ms interval (10 FPS) to 50ms interval (20 FPS)
        val rateTransition = LiveProcessingRate()
        var t = 0L
        for (i in 0 until 16) {
            rateTransition.record(t)
            t += 100L
        }
        assertEquals(10.0, rateTransition.record(t)!!, 0.001)

        // Now feed 16 samples at 50ms intervals
        for (i in 0 until 16) {
            t += 50L
            rateTransition.record(t)
        }
        // Old 100ms samples are completely evicted, rate is exactly 20.0 fps
        assertEquals(16, rateTransition.sampleCount)
        val currentFps = rateTransition.record(t + 50L)
        assertEquals(20.0, currentFps!!, 0.001)
    }

    @Test
    fun liveProcessingRateExplicitResetClearsSamples() {
        val rate = LiveProcessingRate()
        rate.record(100L)
        rate.record(200L)
        assertEquals(2, rate.sampleCount)

        rate.reset()
        assertEquals(0, rate.sampleCount)
        assertNull(rate.record(300L))
        assertEquals(1, rate.sampleCount)
    }
}
