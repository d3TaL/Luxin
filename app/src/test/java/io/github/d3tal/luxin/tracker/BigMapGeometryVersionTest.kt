package io.github.d3tal.luxin.tracker

import org.junit.Assert.*
import org.junit.Test

class BigMapGeometryVersionTest {
    // The same fields are flat in v1 and nested under "v2" in the live JNI response.
    private val fields = """"space":"twt702","reference":"test \"ref\" }","frameWidth":3120,"frameHeight":1440,"timestampSeconds":454695.154006952,"inliers":24,"matches":30,"errorPx":0.5,"support":0.4,"matrix":[1,0,100,0,1,200]"""
    private val legacy = """{"mode":"found","phase":"tracking",$fields}"""
    private fun live(phase: String = "tracking", body: String = fields) =
        """{"mode":"found","phase":"$phase","version":2,"v2":{$body}}"""

    @Test fun nativeTrackingEnvelopeReachesHudWithValidatedFrameGeometry() {
        val decoded = BigMapGeometryDecoder.decodeLiveV2(live())
        assertEquals("found", decoded.mode)
        assertEquals("tracking", decoded.phase)
        val g = requireNotNull(decoded.geometry)
        assertEquals(2, g.version)
        assertEquals("test \"ref\" }", g.reference)
        assertEquals(3120, g.frameWidth)
        assertEquals(1440, g.frameHeight)
        assertEquals(454695.154006952, g.timestampSeconds, 0.0)
        assertEquals(100.0 to 200.0, g.project(0.0, 0.0))
        val state = LiveTrackingState(screenMode = "big_map", bigMapGeometry = g, bigMapGeometryMode = decoded.phase)
        for (layer in listOf(null, LayerDecision(null, true, "surface", "brightness heuristic"),
                LayerDecision("layer_460", false, "layer", "brightness heuristic"))) {
            val diagnostic = state.copy(bigMapLayerDecision = layer)
            assertNull(diagnostic.markerLayer)
            val hud = formatTrackingHud(diagnostic)
            assertTrue(hud.contains("Геометрия: tracking"))
            assertTrue(hud.contains("twt702 | опора ${g.reference}"))
        }
    }

    @Test fun onlyTheMatchingWireVersionCanExposeGeometry() {
        assertNotNull(BigMapGeometryDecoder.decodeStrictV1(legacy).geometry)
        assertNull(BigMapGeometryDecoder.decodeLiveV2(legacy).geometry)
        assertNull(BigMapGeometryDecoder.decodeStrictV1(live()).geometry)
        assertEquals(BigMapGeometryDecoder.decodeLiveV2(live()), BigMapGeometryDecoder.decode(live()))
        val unknown = BigMapGeometryDecoder.decodeLiveV2(live().replace("\"version\":2", "\"version\":3"))
        assertEquals(3, unknown.version)
        assertNull(unknown.geometry)
    }

    @Test fun unconfirmedFollowNeverRepublishesTheAnchor() {
        for (phase in listOf("moving", "confirming", "acquiring", "absolute", "unexpected")) {
            val decoded = BigMapGeometryDecoder.decodeLiveV2(live(phase))
            assertNull(decoded.geometry)
            assertEquals(phase, decoded.phase)
        }
        val acquiring = BigMapGeometryDecoder.decodeLiveV2("""{"mode":"rejected","phase":"acquiring","version":2}""")
        assertNull(acquiring.geometry)
        assertEquals("acquiring", acquiring.phase)
        assertNull(BigMapGeometryDecoder.decodeLiveV2(live().replace("\"found\"", "\"ambiguous\"")).geometry)
        assertNull(BigMapGeometryDecoder.decodeLiveV2(live().replace("\"phase\":\"tracking\",", "")).geometry)
        assertNull(BigMapGeometryDecoder.decodeLiveV2(live().replace("\"v2\":", "\"snapshot_revision\":9,\"v2\":")).geometry)
    }

    @Test fun onlyExplicitMeasuredMotionExposesMovingGeometry() {
        fun tagged(phase: String, value: Boolean) = live(phase).replace("\"version\":2", "\"version\":2,\"measuredMotion\":$value")
        val moved = BigMapGeometryDecoder.decodeLiveV2(tagged("moving", true))
        assertEquals("moving", moved.phase)
        assertEquals(2, requireNotNull(moved.geometry).version)
        for (raw in listOf(tagged("moving", false), tagged("confirming", true),
                tagged("moving", true).replace("found", "rejected"))) {
            assertNull(BigMapGeometryDecoder.decodeLiveV2(raw).geometry)
        }
    }

    @Test fun debugInliersUseCurrentFramePixelsAndRequireAcceptedGeometry() {
        val pts = fields + ",\"framePoints\":[125.5,80,3119,1439]"
        val geometry = requireNotNull(BigMapGeometryDecoder.decodeLiveV2(live(body = pts)).geometry)
        assertEquals(listOf(125.5, 80.0, 3119.0, 1439.0), geometry.framePoints)
        assertNull(BigMapGeometryDecoder.decodeLiveV2(live("moving", pts)).geometry)
        for (bad in listOf("0", "-1,1", "0,1440", (0..97).joinToString(",") { "0" })) {
            assertNull(BigMapGeometryDecoder.decodeLiveV2(live(body = fields + ",\"framePoints\":[$bad]")).geometry)
        }
    }

    @Test fun malformedNestedGeometryCannotBorrowOuterFieldsOrTruncateTheMatrix() {
        val matrix = "\"matrix\":[1,0,100,0,1,200]"
        val noMatrix = fields.substringBefore(",$matrix")
        val malformed = listOf(
            """{"mode":"found","phase":"tracking","version":2,$fields}""",
            """{"mode":"found","phase":"tracking","version":2,$matrix,"v2":{$noMatrix}}""",
            live(body = fields.replace(matrix, "\"matrix\":[1,0,100,0,1,200,999]")),
            live(body = fields.replace(matrix, "\"matrix\":[1,0,null,0,1,200]")),
            live(body = fields.replace("\"inliers\":24", "\"inliers\":3"))
        )
        for (raw in malformed) {
            val decoded = BigMapGeometryDecoder.decodeLiveV2(raw)
            assertNull(decoded.geometry)
            assertEquals("rejected", decoded.phase)
            assertEquals(2, decoded.version)
        }
    }
}
