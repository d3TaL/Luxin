package io.github.d3tal.luxin.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure Kotlin model representing the geometric alignment of Genshin's big-map screen.
 * Maps Kongying marker coordinates to captured frame pixels via a row-major 2x3 affine matrix:
 * [ matrix[0]  matrix[1]  matrix[2] ]
 * [ matrix[3]  matrix[4]  matrix[5] ]
 */
data class BigMapGeometry(
    val space: String,
    val reference: String,
    val matrix: List<Double>,
    val frameWidth: Int,
    val frameHeight: Int,
    val timestampSeconds: Double,
    val inliers: Int,
    val matches: Int,
    val errorPx: Double,
    val support: Double
) {
    init {
        require(space.isNotBlank()) { "space must not be blank" }
        require(reference.isNotBlank()) { "reference must not be blank" }
        require(matrix.size == 6) { "matrix must have 6 elements, got ${matrix.size}" }
        require(matrix.all { it.isFinite() }) { "matrix elements must be finite" }
        require(frameWidth > 0 && frameHeight > 0) { "frame dimensions must be positive: $frameWidth x $frameHeight" }
        require(timestampSeconds.isFinite() && timestampSeconds >= 0.0) {
            "timestampSeconds must be finite and >= 0: $timestampSeconds"
        }
        require(inliers >= 12) { "inliers must be >= 12: $inliers" }
        require(matches >= inliers) { "matches must be >= inliers: matches=$matches, inliers=$inliers" }
        require(errorPx.isFinite() && errorPx >= 0.0) { "errorPx must be finite and >= 0: $errorPx" }
        require(support.isFinite() && support in 0.0..1.0) { "support must be in 0..1: $support" }

        val det = matrix[0] * matrix[4] - matrix[1] * matrix[3]
        require(det.isFinite() && det > 0.0) { "matrix determinant must be positive: $det" }
        val scale = sqrt(det)
        require(scale > 1e-8) { "matrix scale must be > 1e-8: $scale" }
        val tol = 1e-6 * maxOf(scale, abs(matrix[0]), abs(matrix[4]), abs(matrix[1]), abs(matrix[3]))
        require(abs(matrix[0] - matrix[4]) <= tol) {
            "matrix[0] must equal matrix[4] within relative tolerance 1e-6: ${matrix[0]} vs ${matrix[4]}"
        }
        require(abs(matrix[1] + matrix[3]) <= tol) {
            "matrix[1] must equal -matrix[3] within relative tolerance 1e-6: ${matrix[1]} vs ${matrix[3]}"
        }
    }

    /**
     * Projects Kongying marker coordinates (x, y) into captured frame pixels.
     */
    fun project(x: Double, y: Double): Pair<Double, Double> {
        require(x.isFinite() && y.isFinite()) { "Marker coordinates must be finite: ($x, $y)" }
        val px = matrix[0] * x + matrix[1] * y + matrix[2]
        val py = matrix[3] * x + matrix[4] * y + matrix[5]
        require(px.isFinite() && py.isFinite()) { "Projected frame coordinates must be finite: ($px, $py)" }
        return Pair(px, py)
    }

    /**
     * Projects Kongying marker coordinates (x, y) into display screen pixels.
     * Requires positive display dimensions and aspect ratio matching the captured frame within 0.04.
     * Uses uniform full-screen scaling only.
     */
    fun toDisplay(x: Double, y: Double, displayWidth: Int, displayHeight: Int): Pair<Double, Double> {
        require(displayWidth > 0 && displayHeight > 0) {
            "Display dimensions must be positive: $displayWidth x $displayHeight"
        }
        val displayAspect = displayWidth.toDouble() / displayHeight.toDouble()
        val frameAspect = frameWidth.toDouble() / frameHeight.toDouble()
        require(abs(displayAspect - frameAspect) <= 0.04) {
            "Display aspect ratio $displayAspect does not match frame aspect ratio $frameAspect within 0.04"
        }
        val (px, py) = project(x, y)
        val scale = displayHeight.toDouble() / frameHeight.toDouble()
        val dx = px * scale
        val dy = py * scale
        require(dx.isFinite() && dy.isFinite()) { "Display coordinates must be finite: ($dx, $dy)" }
        return Pair(dx, dy)
    }
}

data class DecodedBigMapGeometry(
    val mode: String,
    val geometry: BigMapGeometry?
)

@Serializable
private data class NativeBigMapPayload(
    val error: String? = null,
    val mode: String = "rejected",
    val space: String? = null,
    val reference: String? = null,
    val matrix: List<Double>? = null,
    val frameWidth: Int? = null,
    val frameHeight: Int? = null,
    val timestampSeconds: Double? = null,
    val inliers: Int? = null,
    val matches: Int? = null,
    val errorPx: Double? = null,
    val support: Double? = null
)

object BigMapGeometryDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String): DecodedBigMapGeometry {
        val payload = try {
            json.decodeFromString(NativeBigMapPayload.serializer(), raw)
        } catch (_: Exception) {
            return DecodedBigMapGeometry("rejected", null)
        }

        if (payload.error != null) {
            return DecodedBigMapGeometry("rejected", null)
        }

        if (payload.mode != "found") {
            return DecodedBigMapGeometry(payload.mode, null)
        }

        val space = payload.space?.takeIf { it.isNotBlank() } ?: return DecodedBigMapGeometry("rejected", null)
        val reference = payload.reference?.takeIf { it.isNotBlank() } ?: return DecodedBigMapGeometry("rejected", null)
        val frameWidth = payload.frameWidth?.takeIf { it > 0 } ?: return DecodedBigMapGeometry("rejected", null)
        val frameHeight = payload.frameHeight?.takeIf { it > 0 } ?: return DecodedBigMapGeometry("rejected", null)
        val timestampSeconds = payload.timestampSeconds?.takeIf { it.isFinite() && it >= 0.0 } ?: return DecodedBigMapGeometry("rejected", null)
        val inliers = payload.inliers?.takeIf { it >= 12 } ?: return DecodedBigMapGeometry("rejected", null)
        val matches = payload.matches?.takeIf { it >= inliers } ?: return DecodedBigMapGeometry("rejected", null)
        val errorPx = payload.errorPx?.takeIf { it.isFinite() && it >= 0.0 } ?: return DecodedBigMapGeometry("rejected", null)
        val support = payload.support?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return DecodedBigMapGeometry("rejected", null)
        val matrix = payload.matrix?.takeIf { it.size == 6 && it.all { v -> v.isFinite() } } ?: return DecodedBigMapGeometry("rejected", null)

        val geometry = try {
            BigMapGeometry(
                space = space,
                reference = reference,
                matrix = matrix,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampSeconds = timestampSeconds,
                inliers = inliers,
                matches = matches,
                errorPx = errorPx,
                support = support
            )
        } catch (_: IllegalArgumentException) {
            return DecodedBigMapGeometry("rejected", null)
        }

        return DecodedBigMapGeometry("found", geometry)
    }
}
