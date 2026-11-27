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

/** Pure stage-3 primitive. Rendering hookup is stage 5; the measured pose is never mutated. */
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
