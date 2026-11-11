package io.github.d3tal.luxin.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class RingCalibrationTest {
    @Test fun measuredDefaultsScaleWithDisplayShortSide() {
        val ring = RingCalibration.defaultFor(1080)
        assertEquals(299.625f, ring.cx, 0.001f)
        assertEquals(155.625f, ring.cy, 0.001f)
        assertEquals(132f, ring.r, 0.001f)
    }

    @Test fun horizontalCorrectionDoesNotAffectRadiusOrVerticalPosition() {
        val ring = RingCalibration.defaultFor(1440).moved(-3f, 0f, 0f)
        assertEquals(396.5f, ring.cx, 0.001f)
        assertEquals(207.5f, ring.cy, 0.001f)
        assertEquals(176f, ring.r, 0.001f)
    }

    @Test fun radiusCannotBecomeNegative() {
        assertEquals(20f, RingCalibration(100f, 100f, 30f).moved(0f, 0f, -100f).r, 0.001f)
    }

    @Test fun ringAndCapturePaddingStayInsideDisplay() {
        val ring = RingCalibration(-100f, 3000f, 800f).constrained(1080, 2340)
        assertEquals(536f, ring.r, 0.001f)
        assertEquals(540f, ring.cx, 0.001f)
        assertEquals(1800f, ring.cy, 0.001f)
    }

    @Test(expected = IllegalArgumentException::class) fun nonFiniteRingIsRejected() {
        RingCalibration(Float.NaN, 100f, 50f)
    }
}
