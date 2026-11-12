package io.github.d3tal.luxin.tracker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BigMapGeometryTest {

    private val validFoundJson = """
        {
            "mode": "found",
            "space": "twt702",
            "reference": "surface-ref",
            "matrix": [1.5, 0.0, 100.0, 0.0, 1.5, 200.0],
            "frameWidth": 1560,
            "frameHeight": 720,
            "timestampSeconds": 10.5,
            "inliers": 24,
            "matches": 30,
            "errorPx": 0.85,
            "support": 0.35
        }
    """.trimIndent()

    @Test
    fun validPayloadDecodesSuccessfully() {
        val decoded = BigMapGeometryDecoder.decode(validFoundJson)
        assertEquals("found", decoded.mode)
        val geometry = decoded.geometry
        assertNotNull(geometry)
        assertEquals("twt702", geometry!!.space)
        assertEquals("surface-ref", geometry.reference)
        assertEquals(1560, geometry.frameWidth)
        assertEquals(720, geometry.frameHeight)
        assertEquals(10.5, geometry.timestampSeconds, 1e-6)
        assertEquals(24, geometry.inliers)
        assertEquals(30, geometry.matches)
        assertEquals(0.85, geometry.errorPx, 1e-6)
        assertEquals(0.35, geometry.support, 1e-6)
    }

    @Test
    fun directionWithNonzeroOriginsEncodedViaTranslation() {
        // Kongying reference origin: (originX = 1200.0, originY = -800.0).
        // Scale: 2.0 frame pixels per Kongying unit.
        // At origin, frame coordinates should be (cx = 600.0, cy = 400.0).
        // Translation components:
        // tx = cx - s * originX = 600.0 - 2.0 * 1200.0 = -1800.0
        // ty = cy - s * originY = 400.0 - 2.0 * (-800.0) = 2000.0
        val matrix = listOf(2.0, 0.0, -1800.0, 0.0, 2.0, 2000.0)
        val geometry = BigMapGeometry(
            space = "twt702",
            reference = "origin-test",
            matrix = matrix,
            frameWidth = 1560,
            frameHeight = 720,
            timestampSeconds = 1.0,
            inliers = 15,
            matches = 20,
            errorPx = 0.5,
            support = 0.25
        )

        // 1. Origin point maps exactly to (cx, cy)
        val (ox, oy) = geometry.project(1200.0, -800.0)
        assertEquals(600.0, ox, 1e-6)
        assertEquals(400.0, oy, 1e-6)

        // 2. East direction (+x in Kongying) translates linearly to +x in frame pixels
        val (eastX, eastY) = geometry.project(1250.0, -800.0)
        assertEquals(700.0, eastX, 1e-6) // +50 units * 2.0 px/unit = +100 px
        assertEquals(400.0, eastY, 1e-6)

        // 3. South direction (+y in Kongying) translates linearly to +y in frame pixels
        val (southX, southY) = geometry.project(1200.0, -750.0)
        assertEquals(600.0, southX, 1e-6)
        assertEquals(500.0, southY, 1e-6) // +50 units * 2.0 px/unit = +100 px
    }

    @Test
    fun scalingDisplayProjectsUniformlyToScreen() {
        val geometry = BigMapGeometry(
            space = "twt702",
            reference = "ref",
            matrix = listOf(1.5, 0.0, 100.0, 0.0, 1.5, 200.0),
            frameWidth = 1560,
            frameHeight = 720,
            timestampSeconds = 2.0,
            inliers = 16,
            matches = 20,
            errorPx = 1.0,
            support = 0.4
        )

        val (fx, fy) = geometry.project(10.0, 20.0)
        assertEquals(115.0, fx, 1e-6)
        assertEquals(230.0, fy, 1e-6)

        // Fullscreen display 3120x1440 has uniform 2.0x scale relative to 1560x720 frame
        val (dx, dy) = geometry.toDisplay(10.0, 20.0, displayWidth = 3120, displayHeight = 1440)
        assertEquals(230.0, dx, 1e-6) // 115.0 * 2.0
        assertEquals(460.0, dy, 1e-6) // 230.0 * 2.0
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectionInvalidDisplayDimensionsNonPositive() {
        val geometry = BigMapGeometryDecoder.decode(validFoundJson).geometry!!
        geometry.toDisplay(10.0, 20.0, displayWidth = 0, displayHeight = 1440)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectionInvalidDisplayAspectMismatch() {
        val geometry = BigMapGeometryDecoder.decode(validFoundJson).geometry!!
        // Frame aspect = 1560 / 720 = 2.1667. Display 1080x1080 aspect = 1.0. Diff > 0.04.
        geometry.toDisplay(10.0, 20.0, displayWidth = 1080, displayHeight = 1080)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectionNonFiniteProjectCoordinates() {
        val geometry = BigMapGeometryDecoder.decode(validFoundJson).geometry!!
        geometry.project(Double.NaN, 20.0)
    }

    @Test
    fun rejectionNaNMatrixInDecoder() {
        val json = validFoundJson.replace("1.5,", "NaN,")
        val decoded = BigMapGeometryDecoder.decode(json)
        assertNull(decoded.geometry)
        assertEquals("rejected", decoded.mode)
    }

    @Test
    fun rejectionInvalidFrameDimensionsInDecoder() {
        val zeroWidth = validFoundJson.replace("\"frameWidth\": 1560", "\"frameWidth\": 0")
        assertNull(BigMapGeometryDecoder.decode(zeroWidth).geometry)

        val negHeight = validFoundJson.replace("\"frameHeight\": 720", "\"frameHeight\": -720")
        assertNull(BigMapGeometryDecoder.decode(negHeight).geometry)
    }

    @Test
    fun unknownModesReturnNullGeometryPreservingMode() {
        for (unknownMode in listOf("unknown", "pending", "custom_mode")) {
            val json = """{"mode":"$unknownMode"}"""
            val decoded = BigMapGeometryDecoder.decode(json)
            assertEquals(unknownMode, decoded.mode)
            assertNull(decoded.geometry)
        }
    }

    @Test
    fun malformedFoundMissingQualityYieldsNullGeometry() {
        // Missing inliers
        val missingInliers = validFoundJson.replace("\"inliers\": 24,", "")
        assertNull(BigMapGeometryDecoder.decode(missingInliers).geometry)

        // Missing matches
        val missingMatches = validFoundJson.replace("\"matches\": 30,", "")
        assertNull(BigMapGeometryDecoder.decode(missingMatches).geometry)

        // Missing errorPx
        val missingError = validFoundJson.replace("\"errorPx\": 0.85,", "")
        assertNull(BigMapGeometryDecoder.decode(missingError).geometry)

        // Missing support (valid JSON without support property)
        val parsedJson = Json.parseToJsonElement(validFoundJson).jsonObject
        val missingSupport = JsonObject(parsedJson.filterKeys { it != "support" }).toString()
        val parsedMissingSupport = Json.parseToJsonElement(missingSupport).jsonObject
        assertTrue("support" !in parsedMissingSupport)
        assertNull(BigMapGeometryDecoder.decode(missingSupport).geometry)

        // Inliers below minimum 12
        val lowInliers = validFoundJson.replace("\"inliers\": 24", "\"inliers\": 11")
        assertNull(BigMapGeometryDecoder.decode(lowInliers).geometry)

        // Matches fewer than inliers
        val matchesLessThanInliers = validFoundJson.replace("\"matches\": 30", "\"matches\": 20")
            .replace("\"inliers\": 24", "\"inliers\": 25")
        assertNull(BigMapGeometryDecoder.decode(matchesLessThanInliers).geometry)

        // Negative errorPx
        val negativeError = validFoundJson.replace("\"errorPx\": 0.85", "\"errorPx\": -0.1")
        assertNull(BigMapGeometryDecoder.decode(negativeError).geometry)

        // Support out of bounds [0, 1]
        val highSupport = validFoundJson.replace("\"support\": 0.35", "\"support\": 1.2")
        assertNull(BigMapGeometryDecoder.decode(highSupport).geometry)

        val negSupport = validFoundJson.replace("\"support\": 0.35", "\"support\": -0.05")
        assertNull(BigMapGeometryDecoder.decode(negSupport).geometry)

        // Blank space / reference
        val blankSpace = validFoundJson.replace("\"space\": \"twt702\"", "\"space\": \"   \"")
        assertNull(BigMapGeometryDecoder.decode(blankSpace).geometry)

        // Negative timestamp
        val negTimestamp = validFoundJson.replace("\"timestampSeconds\": 10.5", "\"timestampSeconds\": -1.0")
        assertNull(BigMapGeometryDecoder.decode(negTimestamp).geometry)
    }

    @Test
    fun rejectedStalePayloadDropped() {
        val staleRejected = validFoundJson.replace("\"mode\": \"found\"", "\"mode\": \"rejected\"")
        val decoded = BigMapGeometryDecoder.decode(staleRejected)
        assertEquals("rejected", decoded.mode)
        assertNull(decoded.geometry)

        val staleAmbiguous = validFoundJson.replace("\"mode\": \"found\"", "\"mode\": \"ambiguous\"")
        val decodedAmbiguous = BigMapGeometryDecoder.decode(staleAmbiguous)
        assertEquals("ambiguous", decodedAmbiguous.mode)
        assertNull(decodedAmbiguous.geometry)
    }

    @Test
    fun nonSimilarityMatrixIsRejected() {
        // Unequal diagonal scaling (m00 != m11)
        val shearMatrix = validFoundJson.replace(
            "\"matrix\": [1.5, 0.0, 100.0, 0.0, 1.5, 200.0]",
            "\"matrix\": [1.5, 0.0, 100.0, 0.0, 2.0, 200.0]"
        )
        assertNull(BigMapGeometryDecoder.decode(shearMatrix).geometry)

        // Non-skew-symmetric off-diagonals (m01 != -m10)
        val asymmetricMatrix = validFoundJson.replace(
            "\"matrix\": [1.5, 0.0, 100.0, 0.0, 1.5, 200.0]",
            "\"matrix\": [1.5, 0.2, 100.0, 0.2, 1.5, 200.0]"
        )
        assertNull(BigMapGeometryDecoder.decode(asymmetricMatrix).geometry)

        // Reflection (negative determinant)
        val reflectionMatrix = validFoundJson.replace(
            "\"matrix\": [1.5, 0.0, 100.0, 0.0, 1.5, 200.0]",
            "\"matrix\": [1.5, 0.0, 100.0, 0.0, -1.5, 200.0]"
        )
        assertNull(BigMapGeometryDecoder.decode(reflectionMatrix).geometry)

        // Degenerate scale (<= 1e-8)
        val tinyScaleMatrix = validFoundJson.replace(
            "\"matrix\": [1.5, 0.0, 100.0, 0.0, 1.5, 200.0]",
            "\"matrix\": [1e-9, 0.0, 100.0, 0.0, 1e-9, 200.0]"
        )
        assertNull(BigMapGeometryDecoder.decode(tinyScaleMatrix).geometry)
    }

    @Test
    fun rotatedSimilarityMatrixIsAccepted() {
        // Rotation by 30 degrees (pi/6), scale = 1.2
        val angle = Math.PI / 6.0
        val scale = 1.2
        val cos = scale * Math.cos(angle)
        val sin = scale * Math.sin(angle)
        val matrixStr = String.format(
            java.util.Locale.US,
            "\"matrix\": [%.8f, %.8f, 50.0, %.8f, %.8f, 75.0]",
            cos, -sin, sin, cos
        )
        val rotatedJson = validFoundJson.replace(
            "\"matrix\": [1.5, 0.0, 100.0, 0.0, 1.5, 200.0]",
            matrixStr
        )
        val decoded = BigMapGeometryDecoder.decode(rotatedJson)
        assertEquals("found", decoded.mode)
        assertNotNull(decoded.geometry)
    }

    @Test
    fun nativeErrorPayloadReturnsRejectedWithNullGeometry() {
        val errorJson = """{"error": "cannot read bitmap"}"""
        val decoded = BigMapGeometryDecoder.decode(errorJson)
        assertEquals("rejected", decoded.mode)
        assertNull(decoded.geometry)
    }

    @Test
    fun malformedJsonStringReturnsRejectedWithNullGeometry() {
        val decoded = BigMapGeometryDecoder.decode("{not a valid json")
        assertEquals("rejected", decoded.mode)
        assertNull(decoded.geometry)
    }
}
