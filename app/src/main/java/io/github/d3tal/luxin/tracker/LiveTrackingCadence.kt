package io.github.d3tal.luxin.tracker

import java.util.ArrayDeque

object LiveTrackingCadence {
    const val BIG_MAP_PERIOD_MS = 67L
    const val DEFAULT_PERIOD_MS = 500L
    const val MAX_ANCHOR_AGE_NS = 1_000_000_000L

    fun periodMs(screenMode: String): Long = when (screenMode) {
        "big_map", "big_map_pending" -> BIG_MAP_PERIOD_MS
        else -> DEFAULT_PERIOD_MS
    }

    fun delayMs(screenMode: String, elapsedMs: Long): Long {
        val period = periodMs(screenMode)
        return (period - elapsedMs.coerceAtLeast(0L)).coerceAtLeast(1L)
    }

    fun keepBigMapAnchor(
        screenMode: String,
        lastTimestampNs: Long,
        nowNs: Long,
        sameGeometry: Boolean
    ): Boolean {
        val validMode = screenMode == "big_map" || screenMode == "big_map_pending"
        return validMode &&
            lastTimestampNs > 0L &&
            nowNs >= lastTimestampNs &&
            (nowNs - lastTimestampNs) <= MAX_ANCHOR_AGE_NS &&
            sameGeometry
    }
}

class LiveProcessingRate {
    companion object {
        const val MAX_SAMPLES = 16
        const val MAX_GAP_MS = 1000L
    }

    private val samples = ArrayDeque<Long>(MAX_SAMPLES)

    val sampleCount: Int
        get() = samples.size

    fun record(nowMs: Long): Double? {
        val last = samples.peekLast()
        if (last != null && (nowMs <= last || (nowMs - last) > MAX_GAP_MS)) {
            samples.clear()
        }
        if (samples.size >= MAX_SAMPLES) {
            samples.removeFirst()
        }
        samples.addLast(nowMs)
        val count = samples.size
        if (count < 2) {
            return null
        }
        val first = samples.peekFirst() ?: return null
        val durationMs = nowMs - first
        if (durationMs <= 0L) {
            return null
        }
        return ((count - 1) * 1000.0) / durationMs
    }

    fun reset() {
        samples.clear()
    }
}
