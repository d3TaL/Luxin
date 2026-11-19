package io.github.d3tal.luxin.tracker

import org.junit.Assert.*
import org.junit.Test

class LiveTrackingStateTest {
    private val known = """{"hasPosition":true,"positionMode":"track","space":"twt702", "x":100.5,"y":-200.0,"scale":0.75,"layer":"UG1","layerMode":"known","inliers":12}"""

    @Test fun knownPositionAndLayerAreDecoded() {
        val decoded = TrackingFrameDecoder.decode(known)
        assertEquals(100.5, decoded.position!!.x, 0.001)
        assertEquals("UG1", decoded.layer)
        assertEquals(12, decoded.inliers)
    }

    @Test fun unknownLayerDoesNotDiscardPosition() {
        val decoded = TrackingFrameDecoder.decode(known.replace("\"known\"", "\"pending\""))
        assertNotNull(decoded.position)
        assertNull(decoded.layer)
        assertEquals("pending", decoded.layerMode)
    }

    @Test fun rejectedFrameDoesNotKeepOldCoordinatesOrLayer() {
        TrackingFrameDecoder.decode(known)
        val decoded = TrackingFrameDecoder.decode("""{"hasPosition":false,"positionMode":"rejected","layer":"UG1","layerMode":"known"}""")
        assertNull(decoded.position)
        assertNull(decoded.layer)
        assertEquals("unknown", decoded.layerMode)
    }

    @Test fun emptyKnownLayerIsNormalizedToUnknown() {
        val decoded = TrackingFrameDecoder.decode(known.replace("\"UG1\"", "\"\""))
        assertNull(decoded.layer)
        assertEquals("unknown", decoded.layerMode)
    }

    @Test fun positionOnlyReferenceNeverClaimsActiveFloor() {
        val decoded = TrackingFrameDecoder.decode(known, positionOnly = true)
        assertNotNull(decoded.position)
        assertNull(decoded.layer)
        assertEquals("not_supported", decoded.layerMode)
    }

    @Test(expected = IllegalStateException::class) fun nativeErrorDoesNotBecomeAZeroPosition() {
        TrackingFrameDecoder.decode("""{"error":"invalid crop"}""")
    }

    @Test(expected = IllegalArgumentException::class) fun missingCoordinatesAreRejected() {
        TrackingFrameDecoder.decode("""{"hasPosition":true,"positionMode":"track","space":"twt702"}""")
    }

    @Test(expected = IllegalArgumentException::class) fun nonPositiveScaleIsRejected() {
        TrackingFrameDecoder.decode(known.replace("0.75", "0.0"))
    }

    @Test fun pausedHudHasNoCoordinatesFromPreviousFrame() {
        val text = formatTrackingHud(LiveTrackingState(enabled = true, status = "paused", message = "Нет миникарты"))
        assertFalse(text.contains("x 100"))
        assertTrue(text.contains("неизвестен"))
    }

    @Test fun bigMapGeometryIsNotPlayerPositionOrFloor() {
        val geometry = BigMapGeometryDecoder.decode("""{"mode":"found","space":"twt702","reference":"surface-ref",
            "matrix":[1.0,0.0,20.0,0.0,1.0,30.0],"frameWidth":1560,"frameHeight":720,
            "timestampSeconds":1.0,"inliers":20,"matches":25,"errorPx":0.5,"support":0.4}""").geometry
        val state = LiveTrackingState(enabled = true, screenMode = "big_map", bigMapGeometry = geometry,
            bigMapGeometryMode = "found")
        assertNotNull(state.bigMapGeometry)
        assertNull(state.position)
        assertNull(state.layer)
        val hud = formatTrackingHud(state)
        assertTrue(hud.contains("Геометрия: found"))
        assertTrue(hud.contains("ошибка 0.50 px"))
        assertTrue(hud.contains("неизвестен"))
        assertNull(LiveTrackingState(enabled = true, status = "paused").bigMapGeometry)
    }

    @Test fun transientPauseInvalidatesInFlightGeometryAfterVisibilityReturns() {
        assertTrue(bigMapFrameIsCurrent(4, 4, 2, 2, 100, 500))
        // Hide/show retains capture generation, but the pause revision has changed.
        assertFalse(bigMapFrameIsCurrent(4, 5, 2, 2, 100, 500))
        assertFalse(bigMapFrameIsCurrent(4, 4, 2, 3, 100, 500))
        assertFalse(bigMapFrameIsCurrent(4, 4, 2, 2, 100, 1_000_000_101))
        assertFalse(bigMapFrameIsCurrent(4, 4, 2, 2, 500, 100))
    }

    @Test fun lateNativeGeometryRemainsInvalidAfterBriefPauseAndResume() {
        val old = BigMapFrame(7, 2, 10_000_000_000L, 1560, 720)
        val raw = """{"mode":"found","phase":"tracking","version":2,"v2":{
            "space":"twt702","reference":"test","matrix":[1.0,0.0,0.0,0.0,1.0,0.0],
            "frameWidth":1560,"frameHeight":720,"timestampSeconds":10.0,
            "inliers":24,"matches":30,"errorPx":0.5,"support":0.4}}"""
        var epoch = 3L
        var revision = old.revision
        val updates = mutableListOf<LiveTrackingState>()
        fun start(frame: BigMapFrame, response: String): () -> Publication {
            val startedEpoch = epoch
            return { // Deliver JNI only when the test releases this pending callback.
                val result = BigMapGeometryDecoder.decodeLiveV2(response)
                val state = LiveTrackingState(enabled = true, screenMode = "big_map", bigMapGeometryMode = result.phase,
                    bigMapGeometry = requireNotNull(result.geometry))
                publishTrackingResult(state, startedEpoch, epoch, frame, revision, old.generation,
                    10_300_000_000L, blockedMessage = null) { updates.add(it) }
            }
        }
        val beforePause = start(old, raw)
        revision++ // hidden, then visible again before native returns; generation unchanged
        assertEquals(Publication.OBSOLETE, beforePause())
        assertNull(updates.single().bigMapGeometry)

        val fresh = old.copy(revision = revision, timestampNs = 10_250_000_000L)
        val freshResponse = raw.replace("\"timestampSeconds\":10.0", "\"timestampSeconds\":10.25")
        assertEquals(Publication.OBSOLETE, start(fresh, raw)()) // payload belongs to the older frame
        assertNull(updates.last().bigMapGeometry)
        val beforeStop = start(fresh, freshResponse)
        epoch++ // Stop
        epoch++ // Start, still using the same capture generation
        val countAfterRestart = updates.size
        assertEquals(Publication.DROPPED, beforeStop())
        assertEquals(countAfterRestart, updates.size)
        assertEquals(Publication.CURRENT, start(fresh, freshResponse)())
        assertTrue(formatTrackingHud(updates.last()).contains("Геометрия: tracking"))
        assertNotNull(updates.last().bigMapGeometry)
    }

    @Test fun lateFrameFromStoppedCaptureCannotPassRestartedGeneration() {
        // Predicate regression only: CaptureService's separate epoch/cancellation gates
        // still require Android runtime coverage for a tracker-only Stop/Start.
        val frameNs = 10_000_000_000L
        assertFalse(bigMapFrameIsCurrent(7, 7, 2, 3, frameNs, frameNs + 100_000_000))
        assertTrue(bigMapFrameIsCurrent(7, 7, 3, 3, frameNs + 150_000_000, frameNs + 200_000_000))
    }

    @Test fun acquisitionSettlingNeverMakesGeometryCurrentAcrossPauseOrRestart() {
        // Settling is only a native scheduling gate, not permission to publish a matrix.
        val acquiring = BigMapGeometryDecoder.decode("""{"mode":"rejected","phase":"acquiring"}""")
        assertNull(acquiring.geometry)
        assertEquals("acquiring", acquiring.phase)
        val frameNs = 10_000_000_000L
        val decoded = BigMapGeometryDecoder.decode("""{"mode":"found","phase":"tracking",
            "space":"twt702","reference":"settled-test","matrix":[1.0,0.0,0.0,0.0,1.0,0.0],
            "frameWidth":1560,"frameHeight":720,"timestampSeconds":10.0,
            "inliers":24,"matches":30,"errorPx":0.5,"support":0.4}""")
        assertNotNull(decoded.geometry)
        // A brief hide/show after settling and before native completion changes revision.
        assertFalse(bigMapFrameIsCurrent(7, 8, 2, 2, frameNs, frameNs + 200_000_000))
        // Capture Stop/Start also changes generation; the old result stays ineligible.
        assertFalse(bigMapFrameIsCurrent(7, 8, 2, 3, frameNs, frameNs + 200_000_000))
        // Neither settling nor successful decoding extends the one-second frame lifetime.
        assertFalse(bigMapFrameIsCurrent(8, 8, 3, 3, frameNs, frameNs + 1_000_000_001))
    }

    @Test fun geometryExpiryUsesFrameTimeNotPublicationTime() {
        assertEquals(850L, bigMapGeometryRemainingMs(10.0, 10_150_000_000L))
        assertEquals(0L, bigMapGeometryRemainingMs(10.0, 11_000_000_001L))
        assertEquals(1L, bigMapGeometryRemainingMs(10.0, 10_999_999_999L))
        assertEquals(0L, bigMapGeometryRemainingMs(Double.NaN, 10_000_000_000L))
        // A new frame gets only its own remaining lifetime, not the old timer's deadline.
        assertEquals(850L, bigMapGeometryRemainingMs(10.5, 10_650_000_000L))
        assertEquals(350L, bigMapGeometryRemainingMs(10.0, 10_650_000_000L))
    }

    @Test fun arrowColourAndResolutionThresholdMatchTheSpike() {
        val cyan = 0xff00ffff.toInt()
        assertTrue(PlayerArrowSignal.isArrowPixel(cyan))
        assertFalse(PlayerArrowSignal.isArrowPixel(0xff0000ff.toInt()))
        assertFalse(PlayerArrowSignal.isArrowPixel(0xffffffff.toInt()))
        assertTrue(PlayerArrowSignal.present(IntArray(60) { cyan }, 1440))
        assertFalse(PlayerArrowSignal.present(IntArray(59) { cyan }, 1440))
        assertTrue(PlayerArrowSignal.present(IntArray(34) { cyan }, 1080))
        assertFalse(PlayerArrowSignal.present(IntArray(33) { cyan }, 1080))
    }
}
