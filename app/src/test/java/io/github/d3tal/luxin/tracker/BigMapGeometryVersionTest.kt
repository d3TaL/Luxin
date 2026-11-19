package io.github.d3tal.luxin.tracker

import org.junit.Test
import org.junit.Assert.*

/**
 * P3 decoder version schema tests.
 *
 * Verifies:
 * - Legacy v1 payload (no version field) → decoded as version 1, geometry present.
 * - V2 payload (version=2) → decoded with version=2, geometry present.
 * - Unknown version (e.g. 3) → rejected, no geometry.
 * - decodeStrictV1 rejects v2 payload (null geometry).
 */
class BigMapGeometryVersionTest {

    private fun v1Payload(): String {
        return """{
            "mode":"found",
            "phase":"tracking",
            "space":"twt702",
            "reference":"fontaine_court",
            "matrix":[1.0,0.0,100.0,0.0,1.0,200.0],
            "frameWidth":3120,
            "frameHeight":1440,
            "timestampSeconds":123.456,
            "inliers":100,
            "matches":120,
            "errorPx":0.5,
            "support":0.5
        }"""
    }

    private fun v2Payload(): String {
        return """{
            "mode":"found",
            "phase":"tracking",
            "space":"twt702",
            "reference":"fontaine_court",
            "matrix":[1.0,0.0,100.0,0.0,1.0,200.0],
            "frameWidth":3120,
            "frameHeight":1440,
            "timestampSeconds":123.456,
            "inliers":100,
            "matches":120,
            "errorPx":0.5,
            "support":0.5,
            "version":2
        }"""
    }

    private fun unknownVersionPayload(version: Int): String {
        return """{
            "mode":"found",
            "phase":"tracking",
            "space":"twt702",
            "reference":"fontaine_court",
            "matrix":[1.0,0.0,100.0,0.0,1.0,200.0],
            "frameWidth":3120,
            "frameHeight":1440,
            "timestampSeconds":123.456,
            "inliers":100,
            "matches":120,
            "errorPx":0.5,
            "support":0.5,
            "version":$version
        }"""
    }

    @Test
    fun `v1 payload decodes with version 1 and geometry`() {
        val result = BigMapGeometryDecoder.decode(v1Payload())
        assertEquals("found", result.mode)
        assertEquals(1, result.version)
        assertNotNull(result.geometry)
        assertEquals("twt702", result.geometry?.space)
        assertEquals(100.0, result.geometry?.matrix?.get(2), 0.001)
        assertEquals(200.0, result.geometry?.matrix?.get(5), 0.001)
    }

    @Test
    fun `v2 payload decodes with version 2 and geometry`() {
        val result = BigMapGeometryDecoder.decode(v2Payload())
        assertEquals("found", result.mode)
        assertEquals(2, result.version)
        assertNotNull(result.geometry)
        assertEquals("twt702", result.geometry?.space)
        assertEquals(2, result.geometry?.version)
    }

    @Test
    fun `unknown version rejects without geometry`() {
        val result = BigMapGeometryDecoder.decode(unknownVersionPayload(3))
        assertEquals("rejected", result.mode)
        assertEquals(3, result.version)
        assertNull(result.geometry)
    }

    @Test
    fun `decodeStrictV1 rejects v2 payload`() {
        val result = BigMapGeometryDecoder.decodeStrictV1(v2Payload())
        assertEquals("rejected", result.mode)
        assertEquals(2, result.version)
        assertNull(result.geometry)
    }

    @Test
    fun `decodeStrictV1 passes v1 payload`() {
        val result = BigMapGeometryDecoder.decodeStrictV1(v1Payload())
        assertEquals("found", result.mode)
        assertEquals(1, result.version)
        assertNotNull(result.geometry)
    }

    @Test
    fun `v2 payload with missing matrix rejects`() {
        val payload = """{
            "mode":"found",
            "phase":"tracking",
            "space":"twt702",
            "reference":"fontaine_court",
            "frameWidth":3120,
            "frameHeight":1440,
            "timestampSeconds":123.456,
            "inliers":100,
            "matches":120,
            "errorPx":0.5,
            "support":0.5,
            "version":2
        }"""
        val result = BigMapGeometryDecoder.decode(payload)
        assertEquals("rejected", result.mode)
        assertEquals(2, result.version)
        assertNull(result.geometry)
    }
}
