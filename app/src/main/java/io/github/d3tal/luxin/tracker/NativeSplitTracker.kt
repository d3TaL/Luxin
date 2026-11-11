package io.github.d3tal.luxin.tracker

import android.graphics.Bitmap
import java.io.Closeable

class NativeSplitTracker(unitScale: Double = 0.5625) : Closeable {

    private var handle: Long = nativeCreate(unitScale)

    fun reset() {
        if (handle != 0L) {
            nativeReset(handle)
        }
    }

    fun addLayerReference(
        name: String,
        space: String,
        layer: String,
        grayPath: String,
        activeMaskPath: String,
        contextMaskPath: String,
        unitsPerPx: Double,
        originX: Double,
        originY: Double
    ): Boolean {
        if (handle == 0L) return false
        return nativeAddLayerReferenceFromFiles(
            handle, name, space, layer,
            grayPath, activeMaskPath, contextMaskPath,
            unitsPerPx, originX, originY
        )
    }

    fun updateFrameJson(
        frameBitmap: Bitmap,
        timestampSeconds: Double,
        excludedCirclesXyr: FloatArray? = null
    ): String {
        if (handle == 0L) return "{\"error\":\"closed tracker\"}"
        return nativeUpdateFrame(handle, frameBitmap, timestampSeconds, excludedCirclesXyr)
    }

    fun runSelfTest(): String = nativeRunSelfTest()

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(unitScale: Double): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeAddLayerReferenceFromFiles(
        handle: Long,
        name: String,
        space: String,
        layer: String,
        grayPath: String,
        activeMaskPath: String,
        contextMaskPath: String,
        unitsPerPx: Double,
        originX: Double,
        originY: Double
    ): Boolean

    private external fun nativeUpdateFrame(
        handle: Long,
        frameBitmap: Bitmap,
        timestampSeconds: Double,
        excludedCirclesXyr: FloatArray?
    ): String

    private external fun nativeRunSelfTest(): String

    companion object {
        init {
            System.loadLibrary("opencv_java4")
            System.loadLibrary("luxin_tracker_jni")
        }
    }
}
