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
