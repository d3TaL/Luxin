package io.github.d3tal.luxin.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class BigMapFrameDumpCoreTest {

    @Test
    fun scalesDimensionsToTargetHeightWithoutUpscaling() {
        val qhd = BigMapDumpPlanner.computeScaledDimensions(3120, 1440, 720)
        assertEquals(1560, qhd.width)
        assertEquals(720, qhd.height)
        assertEquals(0.5, qhd.scale, 1e-6)

        val fhd = BigMapDumpPlanner.computeScaledDimensions(2340, 1080, 720)
        assertEquals(1560, fhd.width)
        assertEquals(720, fhd.height)
        assertEquals(720.0 / 1080.0, fhd.scale, 1e-6)

        val hd = BigMapDumpPlanner.computeScaledDimensions(1280, 720, 720)
        assertEquals(1280, hd.width)
        assertEquals(720, hd.height)
        assertEquals(1.0, hd.scale, 1e-6)

        val small = BigMapDumpPlanner.computeScaledDimensions(800, 480, 720)
        assertEquals(800, small.width)
        assertEquals(480, small.height)
        assertEquals(1.0, small.scale, 1e-6)
    }

    @Test
    fun selectsColorKeyframesEveryNFrames() {
        assertTrue(BigMapDumpPlanner.isColorKeyframe(0, 10))
        assertFalse(BigMapDumpPlanner.isColorKeyframe(1, 10))
        assertFalse(BigMapDumpPlanner.isColorKeyframe(9, 10))
        assertTrue(BigMapDumpPlanner.isColorKeyframe(10, 10))
        assertTrue(BigMapDumpPlanner.isColorKeyframe(20, 10))

        assertFalse(BigMapDumpPlanner.isColorKeyframe(0, 0))
        assertFalse(BigMapDumpPlanner.isColorKeyframe(10, 0))

        assertTrue(BigMapDumpPlanner.isColorKeyframe(0, 1))
        assertTrue(BigMapDumpPlanner.isColorKeyframe(1, 1))
    }

    @Test
    fun plansBurstPoolStrictlyWithinMemoryBudget() {
        val config = BigMapDumpConfig() // 256 MiB, targetHeight=720, colorEveryN=10
        val plan = BigMapDumpPlanner.planBurstPool(3120, 1440, config)
        assertEquals(1560, plan.width)
        assertEquals(720, plan.height)
        assertEquals(1560L * 720L, plan.grayFrameBytes)
        assertEquals(1560L * 720L * 4L, plan.colorFrameBytes)
        assertEquals(plan.colorFrameBytes, plan.scratchBytes)
        assertTrue(plan.maxFrames in 150..180)
        assertTrue(plan.totalBytes <= config.memoryBudgetBytes)
        // Next frame would exceed the budget.
        val nextFrameBytes = plan.grayFrameBytes +
            if (BigMapDumpPlanner.isColorKeyframe(plan.maxFrames, config.colorEveryN)) plan.colorFrameBytes else 0L
        assertTrue(plan.totalBytes + nextFrameBytes > config.memoryBudgetBytes)
        assertEquals((plan.maxFrames + 9) / 10, plan.colorFrames)

        // When source height <= targetHeight, no RGBA scratch buffer is needed.
        val noScalePlan = BigMapDumpPlanner.planBurstPool(1280, 720, config)
        assertEquals(0L, noScalePlan.scratchBytes)

        // Tiny budget produces zero frames without throwing.
        val tinyPlan = BigMapDumpPlanner.planBurstPool(3120, 1440, config.copy(memoryBudgetBytes = 1024L))
        assertEquals(0, tinyPlan.maxFrames)
    }

    @Test
    fun formatsFolderAndGrayAndColorFileNames() {
        val epochMs = 1795530909_000L // 2026-11-24 14:35:09 UTC
        assertEquals(
            "bigmap_dump_20261124_143509",
            BigMapDumpFormat.folderName(epochMs, TimeZone.getTimeZone("UTC"))
        )
        assertEquals("f_000000_0.png", BigMapDumpFormat.grayFrameFileName(0, 0L))
        assertEquals("f_000042_815123456789.png", BigMapDumpFormat.grayFrameFileName(42, 815123456789L))
        assertEquals("c_000000_815123456789.png", BigMapDumpFormat.colorFrameFileName(0, 815123456789L))
        assertEquals("c_000010_999.png", BigMapDumpFormat.colorFrameFileName(10, 999L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeFrameIndex() {
        BigMapDumpFormat.grayFrameFileName(-1, 100L)
    }

    @Test
    fun formatsUpdatedCsvWithColorAndScaleColumns() {
        val records = listOf(
            FrameDumpRecord(0, 1_000_000L, 1560, 720, 1L, "big_map", 0, true, 3120, 1440, 0.5),
            FrameDumpRecord(1, 17_666_666L, 1560, 720, 1L, " big_map,bad\n\"mode\" ", 2, false, 3120, 1440, 0.5),
            FrameDumpRecord(2, 34_333_333L, 1560, 720, 1L, "  ", 0, false, 3120, 1440, 0.5)
        )
        val csv = BigMapDumpFormat.formatCsv(records)
        val lines = csv.lines()
        assertEquals(
            "index,timestampNs,width,height,generation,screenMode,dropped_before,has_color,source_width,source_height,scale",
            lines[0]
        )
        assertEquals("0,1000000,1560,720,1,big_map,0,1,3120,1440,0.500000", lines[1])
        assertEquals("1,17666666,1560,720,1,big_mapbadmode,2,0,3120,1440,0.500000", lines[2])
        assertEquals("2,34333333,1560,720,1,unknown,0,0,3120,1440,0.500000", lines[3])
        assertEquals("", lines[4])
    }

    @Test
    fun computesInterArrivalCaptureTimingStatsAndSummaryTxt() {
        val empty = BigMapDumpPlanner.computeCaptureStats(emptyList(), 0)
        assertEquals(0, empty.capturedFrames)
        assertEquals(0.0, empty.achievedFps, 1e-6)

        val single = BigMapDumpPlanner.computeCaptureStats(listOf(1_000_000L), 1)
        assertEquals(1, single.capturedFrames)
        assertEquals(1, single.droppedFrames)
        assertEquals(0.0, single.maxGapMs, 1e-6)

        // 4 frames: gaps of 16ms, 32ms, 16ms -> span = 64ms -> fps = 3 / 0.064 = 46.875
        val ts = listOf(100_000_000L, 116_000_000L, 148_000_000L, 164_000_000L)
        val stats = BigMapDumpPlanner.computeCaptureStats(ts, droppedFrames = 2)
        assertEquals(4, stats.capturedFrames)
        assertEquals(64.0, stats.durationMs, 1e-6)
        assertEquals(46.875, stats.achievedFps, 1e-3)
        assertEquals(32.0, stats.maxGapMs, 1e-6)
        assertEquals(64.0 / 3.0, stats.meanGapMs, 1e-6)
        assertEquals(2, stats.droppedFrames)

        val plan = BigMapDumpPlanner.planBurstPool(3120, 1440, BigMapDumpConfig())
        val summary = BigMapDumpFormat.formatSummaryTxt(
            folderName = "bigmap_dump_20261124_143509",
            plan = plan,
            stats = stats,
            colorKeyframesSaved = 1,
            stopReason = DumpStopReason.BUFFER_FULL,
            logExportNote = "luxin-tracking-test.log (12 зап.)"
        )
        assertTrue(summary.contains("source_size=3120x1440"))
        assertTrue(summary.contains("dump_size=1560x720"))
        assertTrue(summary.contains("captured_frames=4"))
        assertTrue(summary.contains("achieved_fps=46.88"))
        assertTrue(summary.contains("max_gap_ms=32.00"))
        assertTrue(summary.contains("dropped_frames=2"))
        assertTrue(summary.contains("stop_reason=buffer_full"))
    }

    @Test
    fun phaseTransitionsAndLimiterBehaveAsExpected() {
        assertEquals(DumpPhase.COUNTDOWN, DumpPhaseTransitions.initialActivePhase(5_000L))
        assertEquals(DumpPhase.CAPTURING, DumpPhaseTransitions.initialActivePhase(0L))

        assertTrue(DumpPhaseTransitions.canTransition(DumpPhase.IDLE, DumpPhase.COUNTDOWN))
        assertTrue(DumpPhaseTransitions.canTransition(DumpPhase.COUNTDOWN, DumpPhase.CAPTURING))
        assertTrue(DumpPhaseTransitions.canTransition(DumpPhase.COUNTDOWN, DumpPhase.IDLE))
        assertTrue(DumpPhaseTransitions.canTransition(DumpPhase.CAPTURING, DumpPhase.ENCODING))
        assertTrue(DumpPhaseTransitions.canTransition(DumpPhase.ENCODING, DumpPhase.DONE))
        assertFalse(DumpPhaseTransitions.canTransition(DumpPhase.IDLE, DumpPhase.ENCODING))
        assertFalse(DumpPhaseTransitions.canTransition(DumpPhase.ENCODING, DumpPhase.CAPTURING))

        assertEquals(DumpPhase.ENCODING, DumpPhaseTransitions.afterCapture(capturedFrames = 10, hasError = false))
        assertEquals(DumpPhase.DONE, DumpPhaseTransitions.afterCapture(capturedFrames = 0, hasError = false))
        assertEquals(DumpPhase.ERROR, DumpPhaseTransitions.afterCapture(capturedFrames = 0, hasError = true))
        assertEquals(DumpPhase.DONE, DumpPhaseTransitions.afterEncode(hasError = false))
        assertEquals(DumpPhase.ERROR, DumpPhaseTransitions.afterEncode(hasError = true))

        val limiter = BigMapDumpLimiter(maxDurationMs = 20_000L, maxFrames = 160, startTimeMs = 1_000L)
        assertNull(limiter.shouldStop(nowMs = 20_999L, capturedFrames = 159))
        assertEquals(DumpStopReason.BUFFER_FULL, limiter.shouldStop(nowMs = 5_000L, capturedFrames = 160))
        assertEquals(DumpStopReason.MAX_DURATION, limiter.shouldStop(nowMs = 21_000L, capturedFrames = 10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun configRejectsNegativeDelay() {
        BigMapDumpConfig(startDelayMs = -1L)
    }
}
