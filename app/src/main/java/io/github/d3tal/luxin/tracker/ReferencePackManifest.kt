package io.github.d3tal.luxin.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ReferencePackManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val title: String,
    val mode: String = "layered",
    val layers: List<ReferenceLayer>
)

@Serializable
data class ReferenceLayer(
    val id: String,
    val name: String,
    val space: String,
    val image: String,
    val activeMask: String,
    val contextMask: String,
    val unitsPerPx: Double,
    val originX: Double,
    val originY: Double
)

/** Shared by the Android store and JVM-only ZIP tests. Unknown JSON fields are rejected. */
internal object ReferencePackRules {
    const val MANIFEST = "manifest.json"
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_ENTRIES = 128
    const val MAX_BYTES = 128L * 1024 * 1024
    const val MAX_LAYER_PIXELS = 4_000_000L
    const val MAX_TOTAL_PIXELS = 32_000_000L
    private val filename = Regex("[a-zA-Z0-9_.-]{1,128}")
    private val json = Json { ignoreUnknownKeys = false }

    fun safeFilename(value: String): Boolean =
        filename.matches(value) && !value.contains("..") && value != "."

    private fun text(value: String, max: Int) {
        require(value.isNotBlank() && value.length <= max && value.none { it.isISOControl() }) {
            "Invalid or oversized manifest text"
        }
    }

    fun decode(bytes: ByteArray): ReferencePackManifest {
        require(bytes.size <= MAX_MANIFEST_BYTES) { "Manifest too large" }
        return json.decodeFromString(ReferencePackManifest.serializer(), bytes.toString(Charsets.UTF_8))
            .also(::validate)
    }

    fun encode(manifest: ReferencePackManifest): ByteArray =
        json.encodeToString(ReferencePackManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)

    fun validate(manifest: ReferencePackManifest) {
        require(manifest.schemaVersion == 1) { "Unsupported reference pack schema" }
        text(manifest.id, 128)
        text(manifest.title, 256)
        require(manifest.mode == "layered" || manifest.mode == "position_only" || manifest.mode == "composite") { "Unsupported mode" }
        require(manifest.layers.size in 1..24) { "Expected 1..24 layers" }
        require(manifest.layers.map { it.space to it.id }.toSet().size == manifest.layers.size) {
            "Duplicate (space, id)"
        }
        for (layer in manifest.layers) {
            text(layer.id, 128)
            text(layer.space, 128)
            text(layer.name, 256)
            require(layer.unitsPerPx.isFinite() && layer.unitsPerPx > 0) { "Invalid unitsPerPx" }
            require(layer.originX.isFinite() && layer.originY.isFinite()) { "Invalid origin" }
            for (name in listOf(layer.image, layer.activeMask, layer.contextMask)) {
                require(safeFilename(name) && name != MANIFEST && name.endsWith(".png", ignoreCase = true)) {
                    "Expected a simple PNG filename"
                }
            }
        }
    }

    fun filenames(manifest: ReferencePackManifest): Set<String> = buildSet {
        add(MANIFEST)
        manifest.layers.forEach { add(it.image); add(it.activeMask); add(it.contextMask) }
    }

    fun validateDimensions(manifest: ReferencePackManifest, dimensions: (String) -> Pair<Int, Int>) {
        val sizes = filenames(manifest).filter { it != MANIFEST }.associateWith(dimensions)
        var total = 0L
        for (layer in manifest.layers) {
            val size = sizes.getValue(layer.image)
            val (width, height) = size
            val pixels = width.toLong() * height
            require(width in 1..4096 && height in 1..4096 && pixels <= MAX_LAYER_PIXELS) {
                "PNG dimensions exceed layer limits"
            }
            require(sizes.getValue(layer.activeMask) == size && sizes.getValue(layer.contextMask) == size) {
                "Image and masks must have identical dimensions"
            }
            total += pixels
            require(total <= MAX_TOTAL_PIXELS) { "Total layer pixels exceeded" }
        }
    }
}
