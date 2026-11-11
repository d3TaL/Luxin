package io.github.d3tal.luxin.overlay

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimap ring in full-display pixels. A capture must have matching geometry before these
 * coordinates can be used directly; app-only/cropped/letterboxed frames need an explicit mapping.
 * The overlay and tracker share this calibration rather than maintaining separate offsets.
 */
data class RingCalibration(val cx: Float, val cy: Float, val r: Float) {
    init {
        require(cx.isFinite() && cy.isFinite() && r.isFinite() && r >= MIN_RADIUS)
    }

    fun moved(dx: Float, dy: Float, dr: Float) =
        RingCalibration(cx + dx, cy + dy, (r + dr).coerceAtLeast(MIN_RADIUS))

    fun constrained(width: Int, height: Int): RingCalibration {
        require(width >= 2 * MIN_RADIUS + 8 && height >= 2 * MIN_RADIUS + 8)
        val radius = r.coerceIn(MIN_RADIUS, minOf(width, height) / 2f - 4f)
        return RingCalibration(cx.coerceIn(radius + 4f, width - radius - 4f),
            cy.coerceIn(radius + 4f, height - radius - 4f), radius)
    }

    companion object {
        const val MIN_RADIUS = 20f

        /** Measured on 3120x1440 screenshots (spike/measure_minimap.py); scales with screen height. */
        fun defaultFor(shortSidePx: Int): RingCalibration {
            val k = shortSidePx / 1440f
            return RingCalibration(399.5f * k, 207.5f * k, 176f * k)
        }
    }
}

object MinimapCalibrationStore {
    private const val PREFS = "luxin_minimap_calibration"
    private const val KEY_CX = "cx"
    private const val KEY_CY = "cy"
    private const val KEY_R = "r"

    private val _current = MutableStateFlow<RingCalibration?>(null)
    val current: StateFlow<RingCalibration?> = _current.asStateFlow()

    /** Saved calibration, or the default for this screen. Values from another screen size are dropped. */
    fun load(context: Context, shortSidePx: Int): RingCalibration {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = if (prefs.contains(KEY_CX) && prefs.getInt("short_side", -1) == shortSidePx) {
            runCatching {
                RingCalibration(prefs.getFloat(KEY_CX, 0f), prefs.getFloat(KEY_CY, 0f),
                    prefs.getFloat(KEY_R, 0f))
            }.getOrNull()
        } else {
            null
        }
        val result = saved ?: RingCalibration.defaultFor(shortSidePx)
        _current.value = result
        return result
    }

    fun save(context: Context, calibration: RingCalibration, shortSidePx: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_CX, calibration.cx)
            .putFloat(KEY_CY, calibration.cy)
            .putFloat(KEY_R, calibration.r)
            .putInt("short_side", shortSidePx)
            .apply()
        _current.value = calibration
    }

    fun reset(context: Context, shortSidePx: Int): RingCalibration {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        val default = RingCalibration.defaultFor(shortSidePx)
        _current.value = default
        return default
    }
}
