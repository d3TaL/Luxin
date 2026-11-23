package io.github.d3tal.luxin.tracker

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/*
 * Diagnostic-only test marker and coordinate grid for the big map.
 *
 * Nothing here depends on item markers, the floor decision (LayerDecision) or the player: it draws
 * straight from the geometry the tracker published for the CURRENT frame. A world-anchored marker that
 * stays glued to one map feature while the map moves means the published geometry follows the map; a
 * marker that slides or jumps against the map shows exactly how the geometry lags or drifts.
 */

/** One grid line in captured-frame pixels. [unit] is the Kongying coordinate of the line. */
data class TestGridLine(val vertical: Boolean, val unit: Double,
                        val x0: Double, val y0: Double, val x1: Double, val y1: Double)

/** A world point in Kongying units of one space. */
data class TestPin(val space: String, val x: Double, val y: Double)

/** Where the pin was drawn for one published geometry, in captured-frame pixels. */
data class TestTrailPoint(val timestampSeconds: Double, val frameX: Double, val frameY: Double, val moving: Boolean)

/** What the overlay needs for one render pass. [geometry] is null while the tracker publishes none. */
data class BigMapTestInfo(
    val geometry: BigMapGeometry?,
    val phase: String,
    val frameAgeMs: Long?,
    val fps: Double?,
    val nowSeconds: Double
)

object BigMapTestGrid {
    /** Grid cell is at least this fraction of the frame height on screen, so a 3120x1440 frame has ~8 rows. */
    internal const val MIN_SPACING_FRAME_FRACTION = 0.12
    internal const val MAX_LINES_PER_AXIS = 40

    /** Smallest 1-2-5 step (times a power of ten) that is >= [minUnits]. */
    fun niceStep(minUnits: Double): Double {
        require(minUnits.isFinite() && minUnits > 0.0) { "minUnits must be finite and > 0: $minUnits" }
        val base = 10.0.pow(floor(log10(minUnits)))
        for (m in doubleArrayOf(1.0, 2.0, 5.0, 10.0)) if (m * base >= minUnits * (1.0 - 1e-12)) return m * base
        return 10.0 * base
    }

    /** Grid step in Kongying units for this geometry: adapts to the zoom, never reads a fixed unit size. */
    fun spacing(geometry: BigMapGeometry): Double =
        niceStep(MIN_SPACING_FRAME_FRACTION * geometry.frameHeight / geometry.frameScale)

    fun label(step: Double, value: Double): String {
        val decimals = (-floor(log10(step)).toInt()).coerceIn(0, 6)
        return String.format(Locale.US, "%.${decimals}f", value)
    }

    /** Lines at multiples of [spacing] in Kongying units, projected through the CURRENT geometry. */
    fun lines(geometry: BigMapGeometry): List<TestGridLine> {
        val w = geometry.frameWidth.toDouble()
        val h = geometry.frameHeight.toDouble()
        val corners = listOf(0.0 to 0.0, w to 0.0, 0.0 to h, w to h).map { geometry.unproject(it.first, it.second) }
        val minX = corners.minOf { it.first }
        val maxX = corners.maxOf { it.first }
        val minY = corners.minOf { it.second }
        val maxY = corners.maxOf { it.second }

        var step = spacing(geometry)
        fun count(lo: Double, hi: Double) = floor(hi / step).toLong() - ceil(lo / step).toLong() + 1
        while (count(minX, maxX) > MAX_LINES_PER_AXIS || count(minY, maxY) > MAX_LINES_PER_AXIS) {
            step = niceStep(step * 1.5)
        }

        val result = ArrayList<TestGridLine>()
        for (i in ceil(minX / step).toLong()..floor(maxX / step).toLong()) {
            val kx = i * step
            val (ax, ay) = geometry.project(kx, minY)
            val (bx, by) = geometry.project(kx, maxY)
            result += TestGridLine(true, kx, ax, ay, bx, by)
        }
        for (j in ceil(minY / step).toLong()..floor(maxY / step).toLong()) {
            val ky = j * step
            val (ax, ay) = geometry.project(minX, ky)
            val (bx, by) = geometry.project(maxX, ky)
            result += TestGridLine(false, ky, ax, ay, bx, by)
        }
        return result
    }

    /** The step [lines] really used (it may be coarser than [spacing] for extreme zoom-outs); null without 2 lines. */
    fun stepOf(lines: List<TestGridLine>): Double? {
        val xs = lines.filter { it.vertical }.map { it.unit }
        val ys = lines.filter { !it.vertical }.map { it.unit }
        return (xs.zipWithNext { a, b -> b - a } + ys.zipWithNext { a, b -> b - a }).filter { it > 0.0 }.minOrNull()
    }
}

/**
 * Holds one world-anchored test pin and a short trail of where it was drawn.
 *
 * The pin is placed under the frame centre of a stable `tracking` geometry, never of a `moving` one,
 * and then stays at its Kongying coordinate. It survives the map being closed and reopened (same
 * space) and is only dropped by [reset] or by a different space.
 */
class BigMapTestProbe {
    var pin: TestPin? = null
        private set
    private val trail = ArrayDeque<TestTrailPoint>()
    private var lastTimestamp = Double.NaN
    private var lastGeometryNow = Double.NaN

    fun trail(): List<TestTrailPoint> = trail.toList()

    /** Drops the pin and trail; the next stable `tracking` geometry places a new pin at the frame centre. */
    fun reset() {
        pin = null
        trail.clear()
        lastTimestamp = Double.NaN
        lastGeometryNow = Double.NaN
    }

    /**
     * @return the pin in current captured-frame pixels, or null while there is no geometry or no pin yet.
     * Repeated calls with the same geometry timestamp do not add trail points.
     */
    fun update(geometry: BigMapGeometry?, phase: String, nowSeconds: Double): Pair<Double, Double>? {
        if (geometry == null) {
            if (!lastGeometryNow.isNaN() && nowSeconds - lastGeometryNow > TRAIL_GAP_SECONDS) {
                trail.clear()
                lastTimestamp = Double.NaN
            }
            return null
        }
        val stable = phase == "tracking"
        var current = pin
        if (current == null || current.space != geometry.space) {
            if (!stable) return null
            val (x, y) = geometry.unproject(geometry.frameWidth / 2.0, geometry.frameHeight / 2.0)
            current = TestPin(geometry.space, x, y)
            pin = current
            trail.clear()
            lastTimestamp = Double.NaN
        }
        val position = geometry.project(current.x, current.y)
        if (geometry.timestampSeconds != lastTimestamp) {
            lastTimestamp = geometry.timestampSeconds
            lastGeometryNow = nowSeconds
            trail.addLast(TestTrailPoint(geometry.timestampSeconds, position.first, position.second, phase == "moving"))
            while (trail.size > MAX_TRAIL_POINTS) trail.removeFirst()
            while (trail.isNotEmpty() && geometry.timestampSeconds - trail.first().timestampSeconds > TRAIL_SECONDS) {
                trail.removeFirst()
            }
        }
        return position
    }

    companion object {
        const val MAX_TRAIL_POINTS = 24
        const val TRAIL_SECONDS = 3.0
        /** No geometry for this long means the trail would connect unrelated motion. */
        const val TRAIL_GAP_SECONDS = 2.0
    }
}
