package io.github.d3tal.luxin.capture

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RgbaPlaneTest {
    private fun ByteBuffer.bytes(): ByteArray = ByteArray(remaining()).also { duplicate().get(it) }

    @Test fun paddingIsRemovedAndLastRowNeedsNoPadding() {
        val data = ByteArray(20) { it.toByte() }
        val result = RgbaPlane.copyTight(ByteBuffer.wrap(data), 2, 2, 12, 4)
        assertArrayEquals(data.sliceArray(0..7) + data.sliceArray(12..19), result.bytes())
    }

    @Test fun cropOffsetsAreRespected() {
        val data = ByteArray(48) { it.toByte() }
        val result = RgbaPlane.copyTight(ByteBuffer.wrap(data), 2, 2, 16, 4, left = 1, top = 1)
        assertArrayEquals(data.sliceArray(20..27) + data.sliceArray(36..43), result.bytes())
    }

    @Test fun originalBufferPositionIsNotChanged() {
        val data = ByteArray(23) { it.toByte() }
        val source = ByteBuffer.wrap(data).apply { position(3) }
        val result = RgbaPlane.copyTight(source, 2, 2, 12, 4)
        assertEquals(3, source.position())
        assertArrayEquals(data.sliceArray(3..10) + data.sliceArray(15..22), result.bytes())
    }

    @Test fun extraBytesBetweenPixelsAreNotCopied() {
        val data = ByteArray(12) { it.toByte() }
        assertArrayEquals(data.sliceArray(0..3) + data.sliceArray(8..11),
            RgbaPlane.copyTight(ByteBuffer.wrap(data), 2, 1, 16, 8).bytes())
    }

    @Test(expected = IllegalArgumentException::class) fun shortPlaneIsRejected() {
        RgbaPlane.copyTight(ByteBuffer.allocate(19), 2, 2, 12, 4)
    }

    @Test(expected = IllegalArgumentException::class) fun invalidStrideIsRejected() {
        RgbaPlane.copyTight(ByteBuffer.allocate(32), 2, 2, 7, 4)
    }

    @Test fun copyTightIntoReusesBufferWithoutReallocation() {
        val data1 = ByteArray(20) { it.toByte() }
        val data2 = ByteArray(20) { (it + 50).toByte() }
        val reusable = ByteBuffer.allocate(16)
        RgbaPlane.copyTightInto(ByteBuffer.wrap(data1), reusable, 2, 2, 12, 4)
        assertArrayEquals(data1.sliceArray(0..7) + data1.sliceArray(12..19), reusable.bytes())
        RgbaPlane.copyTightInto(ByteBuffer.wrap(data2), reusable, 2, 2, 12, 4)
        assertArrayEquals(data2.sliceArray(0..7) + data2.sliceArray(12..19), reusable.bytes())
    }
}
