package io.github.d3tal.luxin.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun referenceManifest() = ReferencePackManifest(
    id = "example", title = "Example",
    layers = listOf(ReferenceLayer("surface", "Surface", "world", "image.png", "active.png", "context.png", 1.0, 0.0, 0.0))
)

class ReferencePackManifestTest {
    private fun rejects(manifest: ReferencePackManifest) {
        assertThrows(IllegalArgumentException::class.java) { ReferencePackRules.validate(manifest) }
    }

    @Test fun schemaDefaultsAndRoundTrip() {
        val manifest = ReferencePackRules.decode("""{
            "id":"example", "title":"Example", "layers":[{
                "id":"surface", "name":"Surface", "space":"world", "image":"image.png",
                "activeMask":"active.png", "contextMask":"context.png",
                "unitsPerPx":1.0, "originX":0.0, "originY":0.0
            }]
        }""".toByteArray())
        assertEquals(referenceManifest(), manifest)
        assertEquals(manifest, ReferencePackRules.decode(ReferencePackRules.encode(manifest)))
        ReferencePackRules.validate(manifest.copy(mode = "position_only"))
    }

    @Test fun unknownFieldsAndUnsupportedSchemaAreRejected() {
        val json = ReferencePackRules.encode(referenceManifest()).toString(Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            ReferencePackRules.decode(json.replaceFirst("{", "{\"contributors\":[],").toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReferencePackRules.decode(json.replace("\"unitsPerPx\"", "\"users\":[],\"unitsPerPx\"").toByteArray())
        }
        rejects(referenceManifest().copy(schemaVersion = 2))
        rejects(referenceManifest().copy(mode = "other"))
        assertThrows(IllegalArgumentException::class.java) {
            ReferencePackRules.decode(ByteArray(ReferencePackRules.MAX_MANIFEST_BYTES + 1))
        }
    }

    @Test fun layerCountAndIdentityAreBounded() {
        val manifest = referenceManifest()
        val layer = manifest.layers.single()
        rejects(manifest.copy(layers = emptyList()))
        rejects(manifest.copy(layers = List(9) { layer.copy(id = "$it") }))
        rejects(manifest.copy(layers = listOf(layer, layer)))
        ReferencePackRules.validate(manifest.copy(layers = listOf(layer, layer.copy(space = "other"))))
        ReferencePackRules.validate(manifest.copy(layers = List(8) { layer.copy(id = "$it") }))
    }

    @Test fun numbersMustBeFiniteAndScalePositive() {
        val manifest = referenceManifest()
        val layer = manifest.layers.single()
        for (number in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            rejects(manifest.copy(layers = listOf(layer.copy(originX = number))))
            rejects(manifest.copy(layers = listOf(layer.copy(originY = number))))
            rejects(manifest.copy(layers = listOf(layer.copy(unitsPerPx = number))))
        }
        for (scale in listOf(0.0, -1.0)) rejects(manifest.copy(layers = listOf(layer.copy(unitsPerPx = scale))))
    }

    @Test fun textAndFilenamesAreBounded() {
        val manifest = referenceManifest()
        val layer = manifest.layers.single()
        rejects(manifest.copy(id = "a".repeat(129)))
        rejects(manifest.copy(title = "a".repeat(257)))
        rejects(manifest.copy(layers = listOf(layer.copy(name = "a".repeat(257)))))
        rejects(manifest.copy(layers = listOf(layer.copy(space = ""))))
        rejects(manifest.copy(layers = listOf(layer.copy(id = "bad\nname"))))
        for (name in listOf("../x.png", "a..png", "/a.png", "a/b.png", "a\\b.png", ".", "", "a".repeat(129), "ю.png")) {
            assertFalse(name, ReferencePackRules.safeFilename(name))
            rejects(manifest.copy(layers = listOf(layer.copy(image = name))))
        }
        rejects(manifest.copy(layers = listOf(layer.copy(image = "manifest.json"))))
        rejects(manifest.copy(layers = listOf(layer.copy(image = "image.jpg"))))
        assertTrue(ReferencePackRules.safeFilename("Map_01-v2.PNG"))
    }

    @Test fun dimensionsAndMasksMustFitPerLayerBudget() {
        val manifest = referenceManifest()
        ReferencePackRules.validateDimensions(manifest) { 2000 to 2000 }
        for (size in listOf(0 to 20, -1 to 20, 4097 to 1, 1 to 4097, 2001 to 2000)) {
            assertThrows(IllegalArgumentException::class.java) {
                ReferencePackRules.validateDimensions(manifest) { size }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReferencePackRules.validateDimensions(manifest) { if (it == "active.png") 10 to 11 else 10 to 10 }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReferencePackRules.validateDimensions(manifest) { if (it == "context.png") 11 to 10 else 10 to 10 }
        }
    }

    @Test fun totalPixelsCountLayersEvenWhenTheyShareFiles() {
        val manifest = referenceManifest()
        val layer = manifest.layers.single()
        ReferencePackRules.validateDimensions(manifest.copy(layers = List(4) { layer.copy(id = "$it") })) { 2000 to 2000 }
        assertThrows(IllegalArgumentException::class.java) {
            ReferencePackRules.validateDimensions(manifest.copy(layers = List(5) { layer.copy(id = "$it") })) { 2000 to 2000 }
        }
    }
}
