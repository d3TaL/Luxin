package io.github.d3tal.luxin.tracker

import android.graphics.Bitmap
import io.github.d3tal.luxin.overlay.MinimapCalibrationStore
import java.io.Closeable

/** A zero unitScale selects 0.75 * bitmap height / 1440; positive values override it. */
class NativeSplitTracker(unitScale: Double = 0.0) : Closeable {

    init {
        require(unitScale.isFinite() && unitScale >= 0.0) { "Invalid unitScale" }
    }

    private var handle: Long = nativeCreate(unitScale)

    @Synchronized
    fun reset() {
        if (handle != 0L) {
            nativeReset(handle)
        }
    }

    @Synchronized
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

    /**
     * ringXyr is [cx, cy, radius] in frameBitmap pixels; null uses height-based geometry.
     * The default reads the current calibration. For app-only capture, crop or letterbox,
     * the caller must map display calibration coordinates into bitmap coordinates first.
     */
    @Synchronized
    fun updateFrameJson(
        frameBitmap: Bitmap,
        timestampSeconds: Double,
        excludedCirclesXyr: FloatArray? = null,
        ringXyr: FloatArray? = MinimapCalibrationStore.current.value?.let {
            floatArrayOf(it.cx, it.cy, it.r)
        }
    ): String {
        if (handle == 0L) return "{\"error\":\"closed tracker\"}"
        val ring = ringXyr?.copyOf()
        if (ring != null && (ring.size != 3 || ring.any { !it.isFinite() } || ring[2] <= 0f)) {
            return "{\"error\":\"invalid minimap ring\"}"
        }
        return nativeUpdateFrame(handle, frameBitmap, timestampSeconds, excludedCirclesXyr, ring)
    }

    /** Cheap UI-only gate, before minimap matching; independent of loaded reference packs. */
    fun isBigMap(frameBitmap: Bitmap): Boolean = nativeIsBigMap(frameBitmap)

    private external fun nativeIsBigMap(frameBitmap: Bitmap): Boolean

    /**
     * Legacy v1 big-map registration. Returns JSON with mode=found/tracking/absolute and
     * matrix in original frame pixels. Kept for explicit rollback between sessions.
     */
    @Synchronized
    fun registerBigMapJson(frameBitmap: Bitmap, timestampSeconds: Double): String {
        if (handle == 0L) return "{\"error\":\"closed tracker\"}"
        require(timestampSeconds.isFinite() && timestampSeconds >= 0)
        return nativeRegisterBigMap(handle, frameBitmap, timestampSeconds)
    }

    /**
     * V2 big-map registration with explicit version dispatch.
     * Returns JSON envelope with version field. Unknown version to old consumer → null geometry.
     * This is the only entry point for production v2; legacy found path is preserved for rollback.
     */
    @Synchronized
    fun registerBigMapV2Json(frameBitmap: Bitmap, timestampSeconds: Double): String {
        if (handle == 0L) return "{\"error\":\"closed tracker\"}"
        require(timestampSeconds.isFinite() && timestampSeconds >= 0)
        return nativeRegisterBigMapV2(handle, frameBitmap, timestampSeconds)
    }

    private external fun nativeRegisterBigMap(handle: Long, frameBitmap: Bitmap, timestampSeconds: Double): String

    /** V2 native entry point with version=2 in payload. */
    private external fun nativeRegisterBigMapV2(handle: Long, frameBitmap: Bitmap, timestampSeconds: Double): String

    @Synchronized
    fun resetBigMap() {
        if (handle != 0L) nativeResetBigMap(handle)
    }

    private external fun nativeResetBigMap(handle: Long)

    fun runSelfTest(): String = nativeRunSelfTest()

    @Synchronized
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
        excludedCirclesXyr: FloatArray?,
        ringXyr: FloatArray?
    ): String

    private external fun nativeRunSelfTest(): String

    companion object {
        init {
            System.loadLibrary("opencv_java4")
            System.loadLibrary("luxin_tracker_jni")
        }
    }
}
