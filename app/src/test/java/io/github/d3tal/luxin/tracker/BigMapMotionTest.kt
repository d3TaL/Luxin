package io.github.d3tal.luxin.tracker

import org.junit.Assert.*
import org.junit.Test

class BigMapMotionTest {
    private val body = """"space":"test","reference":"ref","matrix":[1,0,0,0,1,0],
        "frameWidth":780,"frameHeight":360,"timestampSeconds":10.0,
        "inliers":12,"matches":20,"errorPx":0.5,"support":0.2"""
    private val motion = """, "poseTimestampNs":10000000000,"velocity":{"dlogs_per_s":0.2,"dtx_per_s":30,"dty_per_s":-10}"""
    private fun wire(fields: String = body + motion, phase: String = "tracking") =
        """{"version":2,"mode":"found","phase":"$phase","v2":{$fields}}"""

    @Test fun modeDefaultsToLegacyAndRoundTripsStoredValue() {
        for (unknown in listOf(null, "", "motion", "future")) assertEquals(BigMapTrackerMode.LEGACY, BigMapTrackerMode.fromStored(unknown))
        for (mode in BigMapTrackerMode.entries) {
            assertEquals(mode, BigMapTrackerMode.fromStored(mode.name))
            assertEquals(mode, mode.next().next())
            assertNotEquals(mode, mode.next())
        }
    }

    @Test fun motionFieldsAreOptionalForLegacyButValidatedTogether() {
        val legacy = requireNotNull(BigMapGeometryDecoder.decodeLiveV2(wire(body)).geometry)
        assertNull(legacy.velocity)
        assertNull(legacy.poseTimestampNs)
        val g = requireNotNull(BigMapGeometryDecoder.decodeLiveV2(wire()).geometry)
        assertEquals(10000000000L, g.poseTimestampNs)
        assertEquals(BigMapVelocity(.2, 30.0, -10.0), g.velocity)
        val invalid = listOf(
            wire().replace("10000000000", "9000000000"),
            wire().replace("10000000000", "-1"),
            wire().replace("\"dlogs_per_s\":0.2", "\"dlogs_per_s\":1e999"),
            wire(body + ",\"poseTimestampNs\":10000000000"),
            wire(body + ",\"velocity\":{\"dlogs_per_s\":0,\"dtx_per_s\":1,\"dty_per_s\":2}"),
            wire(phase = "acquiring"), wire(phase = "rejected")
        )
        invalid.forEach { assertNull(it, BigMapGeometryDecoder.decodeLiveV2(it).geometry) }
    }

    @Test fun extrapolationUsesFrameCentreAndClampsAt120ms() {
        val identity = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        assertEquals(listOf(1.0, 0.0, 12.0, 0.0, 1.0, -6.0),
            extrapolate(identity, BigMapVelocity(0.0, 100.0, -50.0), .3, 780, 360))
        assertEquals(identity, extrapolate(identity, BigMapVelocity(1.0, 100.0, 50.0), -1.0, 780, 360))
        val zoom = extrapolate(identity, BigMapVelocity(1.0, 0.0, 0.0), .1, 780, 360)
        assertEquals(390.0, zoom[0] * 390 + zoom[2], 1e-9)
        assertEquals(180.0, zoom[4] * 180 + zoom[5], 1e-9)
        assertEquals(zoom[0], zoom[4], 0.0)
        val g = requireNotNull(BigMapGeometryDecoder.decodeLiveV2(wire()).geometry)
        for (phase in listOf("acquiring", "moving", "rejected"))
            assertEquals(g.matrix, extrapolatedBigMapMatrix(g, phase, 10050000000L, true))
        assertEquals(g.matrix, extrapolatedBigMapMatrix(g, "tracking", 11000000001L, true))
        assertEquals(g.matrix, extrapolatedBigMapMatrix(g, "tracking", 9999999999L, true))
        assertEquals(g.matrix, extrapolatedBigMapMatrix(g, "tracking", 10050000000L, false))
        assertNotEquals(g.matrix, extrapolatedBigMapMatrix(g, "tracking", 10050000000L, true))
        assertNull(extrapolatedBigMapMatrix(null, "tracking", 10050000000L, true))
        assertEquals(g.matrix, extrapolatedBigMapMatrix(g.copy(velocity = BigMapVelocity(-1e308, 0.0, 0.0)),
            "tracking", 10100000000L, true))
        val tiny = g.copy(matrix = listOf(1.1e-8, 0.0, 0.0, 0.0, 1.1e-8, 0.0),
            velocity = BigMapVelocity(kotlin.math.ln(.5) / .1, 0.0, 0.0))
        assertEquals(tiny.matrix, extrapolatedBigMapMatrix(tiny, "tracking", 10100000000L, true))
    }

    @Test fun motionResponseCannotPublishAfterModeSwitchOrBriefPauseResume() {
        val g = requireNotNull(BigMapGeometryDecoder.decodeLiveV2(wire()).geometry)
        val state = LiveTrackingState(enabled = true, screenMode = "big_map", bigMapGeometry = g, bigMapGeometryMode = "tracking")
        val frame = BigMapFrame(10, 5, 10000000000L, 780, 360)
        var published: LiveTrackingState? = null
        fun deliver(epoch: Long = 7, revision: Long = 11) = publishTrackingResult(state, 7, epoch,
            frame, revision, 5, 10100000000L, null) { published = it }
        // Either mode toggle or brief hide/show increments revision, even when generation stays the same.
        assertEquals(Publication.OBSOLETE, deliver())
        assertNull(published?.bigMapGeometry)
        assertEquals(Publication.OBSOLETE, deliver(revision = 12)) // toggle there and back before completion
        assertNull(published?.bigMapGeometry)
        assertEquals(Publication.DROPPED, deliver(epoch = 8))
        assertEquals(Publication.CURRENT, deliver(revision = 10))
        assertNotNull(published?.bigMapGeometry)
        assertEquals(900L, bigMapGeometryRemainingMs(g.timestampSeconds, 10100000000L))
        val mismatchedNs = state.copy(bigMapGeometry = g.copy(poseTimestampNs = frame.timestampNs + 1))
        assertEquals(Publication.OBSOLETE, publishTrackingResult(mismatchedNs, 7, 7, frame, 10, 5,
            10100000000L, null) { published = it })
        assertNull(published?.bigMapGeometry)
    }

    @Test fun realModeTransitionInvalidatesHeldResultAndResetsBeforeNextFrame() {
        val selection = BigMapModeSelection()
        val worker = BigMapWorkerRevision()
        var revision = 1L
        val events = mutableListOf<String>()
        fun prepare() = worker.observe(revision, selection) { events.add("reset_both") }
        fun switch(mode: BigMapTrackerMode) = selection.change(mode) {
            revision++
            events.add("invalidate")
        }
        assertTrue(prepare())
        val frame = BigMapFrame(worker.revision, 5, 10000000000L, 780, 360)
        val state = LiveTrackingState(enabled = true, bigMapGeometry =
            BigMapGeometryDecoder.decodeLiveV2(wire()).geometry)
        val completion = {
            publishTrackingResult(state, 7, 7, frame, revision, 5, 10100000000L, null) {
                assertNull(it.bigMapGeometry)
            }
        }
        assertTrue(switch(BigMapTrackerMode.MOTION))
        assertTrue(switch(BigMapTrackerMode.LEGACY))
        assertFalse(switch(BigMapTrackerMode.LEGACY))
        assertEquals(Publication.OBSOLETE, completion()) // double toggle before completion
        assertTrue(prepare())
        assertFalse(prepare())
        assertEquals(listOf("reset_both", "invalidate", "invalidate", "reset_both"), events)
        assertEquals(revision, worker.revision)
        switch(BigMapTrackerMode.MOTION)
        assertTrue(prepare())
        assertEquals(BigMapTrackerMode.MOTION, worker.mode)
        val fresh = frame.copy(revision = worker.revision)
        assertEquals(Publication.CURRENT, publishTrackingResult(state, 7, 7, fresh, revision, 5,
            10100000000L, null) { assertNotNull(it.bigMapGeometry) })
    }
}
