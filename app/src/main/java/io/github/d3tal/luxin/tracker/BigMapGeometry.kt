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
    val geometry: BigMapGeometry?,
    val phase: String = "absolute",
    val version: Int = 1
)

@Serializable
private data class NativeBigMapPayload(
    val error: String? = null,
    val mode: String = "rejected",
    val phase: String = "absolute",
    val space: String? = null,
    val reference: String? = null,
    val matrix: List<Double>? = null,
    val frameWidth: Int? = null,
    val frameHeight: Int? = null,
    val timestampSeconds: Double? = null,
    val inliers: Int? = null,
    val matches: Int? = null,
    val errorPx: Double? = null,
    val support: Double? = null,
    val version: Int? = null
)

object BigMapGeometryDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String): DecodedBigMapGeometry {
        val payload = try {
            json.decodeFromString(NativeBigMapPayload.serializer(), raw)
        } catch (_: Exception) {
            return DecodedBigMapGeometry("rejected", null, "rejected", version = 1)
        }

        if (payload.error != null) {
            return DecodedBigMapGeometry("rejected", null, "rejected", version = payload.version ?: 1)
        }

        // V2 envelope: explicit version dispatch. Unknown version → hidden without geometry.
        val version = payload.version ?: 1
        if (version != 1 && version != 2) {
            return DecodedBigMapGeometry("rejected", null, "rejected", version = version)
        }

        if (payload.mode != "found") {
            return DecodedBigMapGeometry(payload.mode, null, payload.phase, version = version)
        }

        if (payload.phase != "tracking" && payload.phase != "absolute") {
            return DecodedBigMapGeometry(payload.mode, null, payload.phase, version = version)
        }

        // Extract fields: V1 uses top-level fields, V2 wraps them inside "v2" object.
        val space = payload.space?.takeIf { it.isNotBlank() }
        val reference = payload.reference?.takeIf { it.isNotBlank() }
        val frameWidth = payload.frameWidth
        val frameHeight = payload.frameHeight
        val timestampSeconds = payload.timestampSeconds
        val inliers = payload.inliers
        val matches = payload.matches
        val errorPx = payload.errorPx
        val support = payload.support
        val matrix = payload.matrix

        // If fields are missing at top level and we have v2, try to parse from v2 object in raw.
        // This handles the case where nativeRegisterBigMapV2 emits {"v2":{...}}.
        val spaceV2 = if (space == null && version == 2) extractV2Field(raw, "space") else null
        val referenceV2 = if (reference == null && version == 2) extractV2Field(raw, "reference") else null
        val frameWidthV2 = if (frameWidth == null && version == 2) extractV2IntField(raw, "frameWidth") else null
        val frameHeightV2 = if (frameHeight == null && version == 2) extractV2IntField(raw, "frameHeight") else null
        val timestampSecondsV2 = if (timestampSeconds == null && version == 2) extractV2DoubleField(raw, "timestampSeconds") else null
        val inliersV2 = if (inliers == null && version == 2) extractV2IntField(raw, "inliers") else null
        val matchesV2 = if (matches == null && version == 2) extractV2IntField(raw, "matches") else null
        val errorPxV2 = if (errorPx == null && version == 2) extractV2DoubleField(raw, "errorPx") else null
        val supportV2 = if (support == null && version == 2) extractV2DoubleField(raw, "support") else null
        val matrixV2 = if (matrix == null && version == 2) extractV2MatrixField(raw) else null

        val finalSpace = space ?: spaceV2 ?: return DecodedBigMapGeometry("rejected", null, "rejected", version = version)
        val finalReference = reference ?: referenceV2 ?: return DecodedBigMapGeometry("rejected", null, "rejected", version = version)
        val finalFrameWidth = frameWidth ?: frameWidthV2 ?: return DecodedBigMapGeometry("rejected", null, "rejected", version = version)
        val finalFrameHeight = frameHeight ?: frameHeightV2 ?: return DecodedBigMapGeometry("rejected", null, "rejected", version = version)
        val finalTimestampSeconds = timestampSeconds ?: timestampSecondsV2 ?: return DecodedBigMapGeometry("rejected", null, "rejected", version = version)
        val finalInliers = inliers ?: inliersV2 ?: return DecodedBigMapGeometry("rejected", null, "rejected", version = version)
        val finalMatches = matches ?: matchesV2 ?: return DecodedBigMapGeometry("rejected", null, "rejected", version = version)
        val finalErrorPx = errorPx ?: errorPxV2 ?: return DecodedBigMapGeometry("rejected", null, "rejected", version = version)
        val finalSupport = support ?: supportV2 ?: return DecodedBigMapGeometry("rejected", null, "rejected", version = version)
        val finalMatrix = matrix ?: matrixV2 ?: return DecodedBigMapGeometry("rejected", null, "rejected", version = version)

        val geometry = try {
            BigMapGeometry(
                space = finalSpace,
                reference = finalReference,
                matrix = finalMatrix,
                frameWidth = finalFrameWidth,
                frameHeight = finalFrameHeight,
                timestampSeconds = finalTimestampSeconds,
                inliers = finalInliers,
                matches = finalMatches,
                errorPx = finalErrorPx,
                support = finalSupport,
                version = version
            )
        } catch (_: IllegalArgumentException) {
            return DecodedBigMapGeometry("rejected", null, "rejected", version = version)
        }

        return DecodedBigMapGeometry("found", geometry, payload.phase, version = version)
    }

    /** Negative compatibility: old decoder must reject v2 payload with unknown version. */
    fun decodeStrictV1(raw: String): DecodedBigMapGeometry {
        val result = decode(raw)
        return if (result.version != 1) {
            DecodedBigMapGeometry("rejected", null, "rejected", version = result.version)
        } else result
    }

    // --- V2 envelope extraction helpers (used when native emits {"v2":{...}}) ---

    private fun extractV2Field(raw: String, field: String): String? {
        val v2Start = raw.indexOf("\"v2\":{")
        if (v2Start < 0) return null
        val v2BodyStart = v2Start + 6
        val endBrace = findMatchingBrace(raw, v2BodyStart)
        if (endBrace < 0) return null
        val v2Body = raw.substring(v2BodyStart, endBrace)
        val pattern = "\"$field\":"
        val idx = v2Body.indexOf(pattern)
        if (idx < 0) return null
        val valueStart = idx + pattern.length
        return extractJsonStringValue(v2Body, valueStart)
    }

    private fun extractV2IntField(raw: String, field: String): Int? {
        val v2Start = raw.indexOf("\"v2\":{")
        if (v2Start < 0) return null
        val v2BodyStart = v2Start + 6
        val endBrace = findMatchingBrace(raw, v2BodyStart)
        if (endBrace < 0) return null
        val v2Body = raw.substring(v2BodyStart, endBrace)
        val pattern = "\"$field\":"
        val idx = v2Body.indexOf(pattern)
        if (idx < 0) return null
        val valueStart = idx + pattern.length
        return extractJsonIntValue(v2Body, valueStart)
    }

    private fun extractV2DoubleField(raw: String, field: String): Double? {
        val v2Start = raw.indexOf("\"v2\":{")
        if (v2Start < 0) return null
        val v2BodyStart = v2Start + 6
        val endBrace = findMatchingBrace(raw, v2BodyStart)
        if (endBrace < 0) return null
        val v2Body = raw.substring(v2BodyStart, endBrace)
        val pattern = "\"$field\":"
        val idx = v2Body.indexOf(pattern)
        if (idx < 0) return null
        val valueStart = idx + pattern.length
        return extractJsonDoubleValue(v2Body, valueStart)
    }

    private fun extractV2MatrixField(raw: String): List<Double>? {
        val v2Start = raw.indexOf("\"v2\":{")
        if (v2Start < 0) return null
        val v2BodyStart = v2Start + 6
        val endBrace = findMatchingBrace(raw, v2BodyStart)
        if (endBrace < 0) return null
        val v2Body = raw.substring(v2BodyStart, endBrace)
        val pattern = "\"matrix\":"
        val idx = v2Body.indexOf(pattern)
        if (idx < 0) return null
        val arrStart = v2Body.indexOf('[', idx)
        if (arrStart < 0) return null
        val arrEnd = findMatchingBracket(v2Body, arrStart)
        if (arrEnd < 0) return null
        val arrBody = v2Body.substring(arrStart + 1, arrEnd)
        return arrBody.split(',')
            .map { it.trim().toDoubleOrNull() }
            .filterNotNull()
            .take(6)
            .let { if (it.size == 6) it else null }
    }

    private fun findMatchingBrace(s: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\') { escaped = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun findMatchingBracket(s: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\') { escaped = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun extractJsonStringValue(s: String, start: Int): String? {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length || s[i] != '"') return null
        i++ // skip opening quote
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                i++
                if (i < s.length) sb.append(s[i])
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c)
            }
            i++
        }
        return null
    }

    private fun extractJsonIntValue(s: String, start: Int): Int? {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        val startNum = i
        while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+')) i++
        if (i == startNum) return null
        return s.substring(startNum, i).toIntOrNull()
    }

    private fun extractJsonDoubleValue(s: String, start: Int): Double? {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        val startNum = i
        while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
        if (i == startNum) return null
        return s.substring(startNum, i).toDoubleOrNull()
    }
}
