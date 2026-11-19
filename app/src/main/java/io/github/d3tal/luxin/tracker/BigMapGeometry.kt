package io.github.d3tal.luxin.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
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
    val support: Double,
    val version: Int = 1
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
        require(version >= 1) { "version must be >= 1: $version" }

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
        require(displayWidth > 0 && displayHeight > 0) { "Display dimensions must be positive: $displayWidth x $displayHeight" }
        require(fitsDisplay(displayWidth, displayHeight)) {
            "Display aspect ratio for $displayWidth x $displayHeight does not match frame $frameWidth x $frameHeight within 0.04"
        }
        val (px, py) = project(x, y)
        val scale = displayHeight.toDouble() / frameHeight.toDouble()
        val dx = px * scale
        val dy = py * scale
        require(dx.isFinite() && dy.isFinite()) { "Display coordinates must be finite: ($dx, $dy)" }
        return Pair(dx, dy)
    }

    internal fun fitsDisplay(width: Int, height: Int): Boolean = width > 0 && height > 0 &&
        abs(width.toDouble() / height - frameWidth.toDouble() / frameHeight) <= 0.04
}

data class DecodedBigMapGeometry(
    val mode: String,
    val geometry: BigMapGeometry?,
    val phase: String = "absolute",
    val version: Int = 1
)

@Serializable
private data class NativeBigMapHeader(
    val mode: String = "rejected",
    val phase: String? = null,
    val version: Int = 1 // Legacy JNI did not require an explicit version.
)

@Serializable
private data class NativeBigMapGeometry(
    val space: String, val reference: String, val matrix: List<Double>,
    val frameWidth: Int, val frameHeight: Int, val timestampSeconds: Double,
    val inliers: Int, val matches: Int, val errorPx: Double, val support: Double
) {
    fun validated(version: Int) = BigMapGeometry(space, reference, matrix, frameWidth, frameHeight,
        timestampSeconds, inliers, matches, errorPx, support, version)
}

object BigMapGeometryDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String) = decode(raw, requiredVersion = null)
    fun decodeStrictV1(raw: String) = decode(raw, requiredVersion = 1)

    /** Only live registerBigMapV2Json results, never a legacy result or a cached snapshot. */
    fun decodeLiveV2(raw: String) = decode(raw, requiredVersion = 2)

    private fun rejected(version: Int) = DecodedBigMapGeometry("rejected", null, "rejected", version)

    private fun decode(raw: String, requiredVersion: Int?): DecodedBigMapGeometry {
        var version = 1
        return try {
            val root = json.parseToJsonElement(raw) as? JsonObject ?: return rejected(version)
            val header = json.decodeFromJsonElement<NativeBigMapHeader>(root)
            version = header.version
            if (version !in 1..2 || (requiredVersion != null && version != requiredVersion) ||
                "error" in root || "snapshot_revision" in root) return rejected(version)

            val phase = header.phase ?: if (version == 1) "absolute" else return rejected(version)
            // V2 can echo the previous anchor as found/moving or found/confirming after a failed follow.
            // Only tracking identifies a confirmed registration of this frame. V1 also had absolute fixes.
            val confirmed = phase == "tracking" || (version == 1 && phase == "absolute")
            if (header.mode != "found" || !confirmed) return DecodedBigMapGeometry(header.mode, null, phase, version)

            val body = if (version == 1) root else root["v2"] as? JsonObject ?: return rejected(version)
            val geometry = json.decodeFromJsonElement<NativeBigMapGeometry>(body).validated(version)
            DecodedBigMapGeometry("found", geometry, phase, version)
        } catch (_: IllegalArgumentException) {
            rejected(version)
        }
    }
}
