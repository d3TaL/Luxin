package io.github.d3tal.luxin.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BigMapTestProbeTest {

    private fun geometry(
        matrix: List<Double> = listOf(0.5, 0.0, 100.0, 0.0, 0.5, 50.0),
        timestamp: Double = 10.0,
        space: String = "twt702"
    ) = BigMapGeometry(space, "ref", matrix, 3120, 1440, timestamp, 100, 100, 0.5, 0.5)

    private fun panned(base: BigMapGeometry, dx: Double, dy: Double, timestamp: Double) = geometry(
        listOf(base.matrix[0], base.matrix[1], base.matrix[2] + dx, base.matrix[3], base.matrix[4], base.matrix[5] + dy),
        timestamp, base.space)

    @Test
    fun unprojectInvertsProjectIncludingSmallRotation() {
        for (m in listOf(listOf(2.0, 0.0, 100.0, 0.0, 2.0, -50.0), listOf(1.9, 0.3, 10.0, -0.3, 1.9, 20.0))) {
            val g = geometry(m)
            val (kx, ky) = g.unproject(1234.5, 678.9)
            val (fx, fy) = g.project(kx, ky)
            assertEquals(1234.5, fx, 1e-9)
            assertEquals(678.9, fy, 1e-9)
        }
    }

    @Test
    fun niceStepUsesOneTwoFive() {
        assertEquals(0.5, BigMapTestGrid.niceStep(0.3), 1e-12)
        assertEquals(1.0, BigMapTestGrid.niceStep(1.0), 1e-12)
        assertEquals(2.0, BigMapTestGrid.niceStep(1.1), 1e-12)
        assertEquals(5.0, BigMapTestGrid.niceStep(3.0), 1e-12)
        assertEquals(10.0, BigMapTestGrid.niceStep(6.0), 1e-12)
        assertEquals(200.0, BigMapTestGrid.niceStep(173.0), 1e-9)
        assertEquals(200.0, BigMapTestGrid.niceStep(200.0), 1e-9)
        assertEquals(500.0, BigMapTestGrid.niceStep(201.0), 1e-9)
        assertEquals(1000.0, BigMapTestGrid.niceStep(1000.0), 1e-9)
    }

    @Test
    fun gridStepFollowsZoomAndCellsStayReadable() {
        for (scale in listOf(0.05, 0.3, 0.5, 2.0, 8.0)) {
            val g = geometry(listOf(scale, 0.0, 0.0, 0.0, scale, 0.0))
            val step = BigMapTestGrid.spacing(g)
            assertTrue("cell $step units at scale $scale must be >= 12% of the frame", step * scale >= 0.12 * 1440 - 1e-6)
            assertTrue("cell must not be coarser than ~2.5x the minimum", step * scale <= 0.12 * 1440 * 2.5 + 1e-6)
            val lines = BigMapTestGrid.lines(g)
            assertTrue(lines.count { it.vertical } in 1..BigMapTestGrid.MAX_LINES_PER_AXIS)
            assertTrue(lines.count { !it.vertical } in 1..BigMapTestGrid.MAX_LINES_PER_AXIS)
        }
    }

    @Test
    fun gridLinesSitOnWholeStepsAndCoverTheFrame() {
        val g = geometry()
        val step = BigMapTestGrid.spacing(g)
        assertEquals(500.0, step, 1e-9) // 0.12 * 1440 / 0.5 = 345.6 -> 500
        val lines = BigMapTestGrid.lines(g)
        for (line in lines) {
            assertEquals(0.0, line.unit / step - Math.rint(line.unit / step), 1e-9)
            if (line.vertical) assertEquals(line.unit * 0.5 + 100.0, line.x0, 1e-9)
            else assertEquals(line.unit * 0.5 + 50.0, line.y0, 1e-9)
        }
        val xs = lines.filter { it.vertical }.map { it.x0 }
        assertTrue(xs.min() < 3120 * 0.05 + 250 && xs.max() > 3120 * 0.95 - 250)
        assertEquals(step, BigMapTestGrid.stepOf(lines)!!, 1e-9)
    }

    @Test
    fun gridMovesWithTheMapAndKeepsItsCoordinates() {
        val a = geometry()
        val b = panned(a, 80.0, -30.0, 11.0)
        val unitsA = BigMapTestGrid.lines(a).filter { it.vertical }.map { it.unit }.toSet()
        val linesB = BigMapTestGrid.lines(b).filter { it.vertical }
        // Same world lines, shifted by the pan, wherever they are still inside the frame.
        for (line in linesB.filter { it.unit in unitsA }) {
            val old = BigMapTestGrid.lines(a).first { it.vertical && it.unit == line.unit }
            assertEquals(old.x0 + 80.0, line.x0, 1e-9)
        }
    }

    @Test
    fun pinStaysOnTheMapFeatureWhileTheMapPans() {
        val probe = BigMapTestProbe()
        val still = geometry()
        val first = probe.update(still, "tracking", 100.0)!!
        assertEquals(1560.0, first.first, 1e-9)
        assertEquals(720.0, first.second, 1e-9)

        val moved = panned(still, 120.0, -45.0, 10.2)
        val second = probe.update(moved, "moving", 100.2)!!
        assertEquals(1560.0 + 120.0, second.first, 1e-9)
        assertEquals(720.0 - 45.0, second.second, 1e-9)

        val trail = probe.trail()
        assertEquals(2, trail.size)
        assertFalse(trail[0].moving)
        assertTrue(trail[1].moving)
    }

    @Test
    fun pinWaitsForStableTrackingAndSurvivesMovingFrames() {
        val probe = BigMapTestProbe()
        assertNull(probe.update(geometry(), "moving", 1.0))
        assertNull(probe.pin)
        assertNotNull(probe.update(geometry(timestamp = 10.5), "tracking", 1.5))
        val pin = probe.pin!!
        probe.update(panned(geometry(), 300.0, 0.0, 11.0), "moving", 2.0)
        assertEquals(pin, probe.pin)
    }

    @Test
    fun resetAndOtherSpaceReplaceThePin() {
        val probe = BigMapTestProbe()
        probe.update(geometry(), "tracking", 1.0)
        val offCenter = panned(geometry(), 400.0, 0.0, 10.5)
        probe.reset()
        assertNull(probe.pin)
        assertTrue(probe.trail().isEmpty())
        probe.update(offCenter, "tracking", 1.5)
        assertEquals(offCenter.unproject(1560.0, 720.0).first, probe.pin!!.x, 1e-9)

        probe.update(geometry(space = "other", timestamp = 11.0), "tracking", 2.0)
        assertEquals("other", probe.pin!!.space)
        assertEquals(1, probe.trail().size)
    }

    @Test
    fun sameGeometryTimestampAddsOneTrailPointAndOldPointsExpire() {
        val probe = BigMapTestProbe()
        val g = geometry()
        probe.update(g, "tracking", 1.0)
        probe.update(g, "tracking", 1.05)
        assertEquals(1, probe.trail().size)
        for (i in 1..30) probe.update(panned(g, i * 5.0, 0.0, 10.0 + i * 0.2), "moving", 1.0 + i * 0.2)
        val trail = probe.trail()
        assertTrue(trail.size <= BigMapTestProbe.MAX_TRAIL_POINTS)
        assertTrue(trail.last().timestampSeconds - trail.first().timestampSeconds <= BigMapTestProbe.TRAIL_SECONDS + 1e-9)
    }

    @Test
    fun trailIsClearedAfterALongGapWithoutGeometry() {
        val probe = BigMapTestProbe()
        probe.update(geometry(), "tracking", 1.0)
        assertNull(probe.update(null, "acquiring", 1.5))
        assertEquals(1, probe.trail().size)
        assertNull(probe.update(null, "acquiring", 3.5))
        assertTrue(probe.trail().isEmpty())
        assertNotNull(probe.pin) // the world point itself survives the gap
        assertNotNull(probe.update(geometry(timestamp = 20.0), "tracking", 4.0))
        assertEquals(1, probe.trail().size)
    }
}
