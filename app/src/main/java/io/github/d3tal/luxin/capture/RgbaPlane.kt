package io.github.d3tal.luxin.capture

import java.nio.ByteBuffer

/** Detach RGBA pixels from an Image plane before closing it, without assuming last-row padding. */
object RgbaPlane {
    fun copyTight(
        source: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        left: Int = 0,
        top: Int = 0
    ): ByteBuffer {
        val output = ByteBuffer.allocate(Math.multiplyExact(Math.multiplyExact(width, height), 4))
        copyTightInto(source, output, width, height, rowStride, pixelStride, left, top)
        return output
    }

    /** Copy tight RGBA pixels into a preallocated buffer (cleared and flipped on return). */
    fun copyTightInto(
        source: ByteBuffer,
        output: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        left: Int = 0,
        top: Int = 0
    ) {
        require(width > 0 && height > 0 && left >= 0 && top >= 0)
        require(pixelStride >= 4 && rowStride > 0)
        require((left.toLong() + width) * pixelStride <= rowStride)
        val requiredBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4)
        require(output.capacity() >= requiredBytes) { "Output buffer is smaller than width*height*4" }
        val base = source.position().toLong() + top.toLong() * rowStride + left.toLong() * pixelStride
        val end = base + (height - 1L) * rowStride + (width - 1L) * pixelStride + 4
        require(end <= source.limit()) { "RGBA plane is shorter than its dimensions/strides" }
        output.clear()
        for (y in 0 until height) {
            val row = (base + y.toLong() * rowStride).toInt()
            val input = source.duplicate()
            if (pixelStride == 4) {
                input.limit(row + width * 4)
                input.position(row)
                output.put(input)
            } else {
                for (x in 0 until width) {
                    val p = row + x * pixelStride
                    for (channel in 0..3) output.put(input.get(p + channel))
                }
            }
        }
        output.flip()
    }
}
