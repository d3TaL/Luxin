package io.github.d3tal.luxin.tracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionRgbaJniTest {
    @Test fun rejectsInvalidBufferAndAcceptsPaddedRowsWithoutTrailingPadding() {
        NativeSplitTracker().use { tracker ->
            val w = 156; val h = 72; val stride = w*4+16
            val bytes = ByteBuffer.allocateDirect((h-1)*stride+w*4)
            fun update(b: ByteBuffer, width: Int = w) = tracker.motionRgba(b,width,h,stride,1L,1L,false)
            assertTrue(update(ByteBuffer.allocateDirect(0)).contains("error"))
            assertTrue(update(bytes,0).contains("error"))
            assertTrue(update(bytes.duplicate().apply { limit(capacity()-1) }).contains("error"))
            assertFalse(update(bytes).contains("error"))
        }
    }
}
