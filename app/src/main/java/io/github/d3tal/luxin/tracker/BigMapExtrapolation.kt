package io.github.d3tal.luxin.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.sqrt

private fun validSimilarity(m: List<Double>): Boolean {
    if (m.size != 6 || m.any { !it.isFinite() }) return false
    val det = m[0] * m[4] - m[1] * m[3]
    if (!det.isFinite() || det <= 0.0) return false
    val scale = sqrt(det)
    val tolerance = 1e-6 * maxOf(scale, abs(m[0]), abs(m[4]), abs(m[1]), abs(m[3]))
    return scale > 1e-8 && abs(m[0] - m[4]) <= tolerance && abs(m[1] + m[3]) <= tolerance
}

/** Frame-centre velocity; never player coordinates. */
@Serializable
data class BigMapVelocity(
    @SerialName("dlogs_per_s") val dlogsPerSecond: Double,
    @SerialName("dtx_per_s") val dxPerSecond: Double,
    @SerialName("dty_per_s") val dyPerSecond: Double
) {
    init { require(dlogsPerSecond.isFinite() && dxPerSecond.isFinite() && dyPerSecond.isFinite()) }
}

/** The measured pose is never mutated. */
fun extrapolate(matrix: List<Double>, velocity: BigMapVelocity, dtSeconds: Double,
                frameWidth: Int, frameHeight: Int): List<Double> {
    require(validSimilarity(matrix) && frameWidth > 0 && frameHeight > 0)
    require(dtSeconds.isFinite())
    val dt = dtSeconds.coerceIn(0.0, 0.120)
    val s = exp(velocity.dlogsPerSecond * dt)
    require(s.isFinite() && s > 1e-8)
    val tx = velocity.dxPerSecond * dt + (1 - s) * frameWidth * .5
    val ty = velocity.dyPerSecond * dt + (1 - s) * frameHeight * .5
    return listOf(s * matrix[0], s * matrix[1], s * matrix[2] + tx,
        s * matrix[3], s * matrix[4], s * matrix[5] + ty).also { require(validSimilarity(it)) }
}

internal const val DEFAULT_DRAW_PREDICTION = false

/** Updated once per measured pose, never once per redraw. */
internal class DrawMotionHistory {
    private var previous: BigMapGeometry? = null
    private var delta: DoubleArray? = null
    var damped = false
        private set

    fun observe(g: BigMapGeometry?, phase: String) {
        val v = g?.velocity
        if (g == null || v == null || phase != "tracking") {
            previous = null; delta = null; damped = false; return
        }
        val old = previous
        if (old === g) return
        previous = g
        val ns = g.poseTimestampNs!!
        val last = old?.poseTimestampNs
        if (old == null || last == null || ns <= last || ns-last > 1_000_000_000L || old.space != g.space ||
            old.frameWidth != g.frameWidth || old.frameHeight != g.frameHeight) {
            delta = null; damped = false; return
        }
        val w = old.velocity!!
        val d = doubleArrayOf(v.dlogsPerSecond-w.dlogsPerSecond,v.dxPerSecond-w.dxPerSecond,v.dyPerSecond-w.dyPerSecond)
        val prevDelta = delta
        damped = prevDelta != null && d.indices.any { d[it]*prevDelta[it] < 0 }
        delta = DoubleArray(d.size) { i -> if (d[i] != 0.0) d[i] else prevDelta?.get(i) ?: 0.0 }
    }
}

internal data class DrawPose(val geometry: BigMapGeometry?, val reason: String, val ageMs: Long?, val dtMs: Double = 0.0)

internal fun poseForDraw(g: BigMapGeometry?, phase: String, nowNs: Long, enabled: Boolean, damped: Boolean): DrawPose {
    if (g == null || phase != "tracking" && g.velocity != null) return DrawPose(null,"no_pose",null)
    val ts = g.poseTimestampNs ?: run {
        val ns = g.timestampSeconds * 1e9
        if (!ns.isFinite() || ns < 0 || ns >= Long.MAX_VALUE.toDouble()) return DrawPose(null,"invalid_age",null)
        Math.round(ns)
    }
    if (nowNs < ts || nowNs-ts > 1_000_000_000L) return DrawPose(null,"invalid_age",null)
    val age = nowNs-ts
    if (g.poseTimestampNs == null) return DrawPose(g,"non_motion",age/1_000_000)
    if (!enabled) return DrawPose(g,"disabled",age/1_000_000)
    val dt = minOf(age,120_000_000L) * (if (damped) .5 else 1.0)
    val matrix = extrapolatedBigMapMatrix(g,phase,ts+dt.toLong(),true)
    if (matrix == null || matrix === g.matrix || dt <= 0.0) return DrawPose(g,"measured",age/1_000_000)
    return DrawPose(g.copy(matrix=matrix),if (damped) "damped" else "predicted",age/1_000_000,dt/1e6)
}

fun extrapolatedBigMapMatrix(geometry: BigMapGeometry?, phase: String, nowNs: Long,
                             enabled: Boolean): List<Double>? {
    geometry ?: return null
    val poseNs = geometry.poseTimestampNs ?: return geometry.matrix
    val velocity = geometry.velocity ?: return geometry.matrix
    if (!enabled || phase != "tracking" || nowNs < poseNs || nowNs - poseNs > 1_000_000_000L)
        return geometry.matrix
    return try {
        extrapolate(geometry.matrix, velocity, (nowNs - poseNs) / 1e9, geometry.frameWidth, geometry.frameHeight)
    } catch (_: IllegalArgumentException) { geometry.matrix }
}
