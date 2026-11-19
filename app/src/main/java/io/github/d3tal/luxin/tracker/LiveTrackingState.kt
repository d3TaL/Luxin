package io.github.d3tal.luxin.tracker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** These values describe diagnostics only. No item markers are rendered by the live spike. */
data class LivePosition(val space: String, val x: Double, val y: Double, val scale: Double)

/** A transient pause must invalidate an in-flight result even after visibility returns. */
fun bigMapFrameIsCurrent(expectedRevision: Long, revision: Long, expectedGeneration: Long,
                         generation: Long, timestampNs: Long, nowNs: Long): Boolean =
    expectedRevision == revision && expectedGeneration == generation &&
        nowNs - timestampNs in 0L..1_000_000_000L

/** Round up so timer wakeup cannot declare a still-current frame expired early. */
fun bigMapGeometryRemainingMs(timestampSeconds: Double, nowNs: Long): Long {
    if (!timestampSeconds.isFinite() || timestampSeconds < 0) return 0
    val remainingNs = timestampSeconds * 1_000_000_000.0 + 1_000_000_000.0 - nowNs
    return kotlin.math.ceil(remainingNs / 1_000_000.0).coerceIn(0.0, 1000.0).toLong()
}

data class LiveTrackingState(
    val enabled: Boolean = false,
    val status: String = "off",
    val message: String = "Трекер выключен",
    val packTitle: String = "",
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val position: LivePosition? = null,
    val positionMode: String = "unknown",
    val layer: String? = null,
    val layerName: String? = null,
    val layerMode: String = "unknown",
    val inliers: Int = 0,
    val nativeMs: Long? = null,
    val frameAgeMs: Long? = null,
    val actualFps: Double? = null,
    val screenMode: String = "unknown",
    val bigMapGeometry: BigMapGeometry? = null,
    val bigMapGeometryMode: String = "unknown",
    val bigMapLayerDecision: LayerDecision? = null
) {
    internal val markerLayer: LayerDecision?
        get() = bigMapLayerDecision?.takeIf { it.confirmed && screenMode == "big_map" &&
            bigMapGeometryMode == "tracking" && bigMapGeometry != null &&
            ((it.mode == "surface" && it.isSurface && it.layerId == null) ||
                (it.mode == "layer" && !it.isSurface && !it.layerId.isNullOrBlank())) }
}

/** Captured before JNI; never reconstructed from the service's current state after native returns. */
internal data class BigMapFrame(val revision: Long, val generation: Long, val timestampNs: Long,
                                val width: Int, val height: Int)

internal enum class Publication { CURRENT, OBSOLETE, BLOCKED, DROPPED }

/** Called on Main: check the observation and commit to the HUD in the same turn. */
internal fun publishTrackingResult(state: LiveTrackingState, epoch: Long, currentEpoch: Long,
                                   frame: BigMapFrame?, revision: Long, generation: Long, nowNs: Long,
                                   blockedMessage: String?, publish: (LiveTrackingState) -> Unit): Publication {
    if (epoch != currentEpoch) return Publication.DROPPED
    val geometry = state.bigMapGeometry
    val obsolete = (frame != null && !bigMapFrameIsCurrent(frame.revision, revision, frame.generation,
        generation, frame.timestampNs, nowNs)) || (geometry != null && (frame == null ||
        geometry.frameWidth != frame.width || geometry.frameHeight != frame.height ||
        kotlin.math.abs(geometry.timestampSeconds - frame.timestampNs / 1_000_000_000.0) >= 1e-6))
    val reason = if (obsolete) "Привязка устарела; ожидаю обновление" else blockedMessage
    if (reason != null) {
        publish(LiveTrackingState(enabled = true, status = "paused", message = reason, packTitle = state.packTitle))
        return if (obsolete) Publication.OBSOLETE else Publication.BLOCKED
    }
    publish(state)
    return Publication.CURRENT
}

fun formatTrackingHud(s: LiveTrackingState): String = buildString {
    append(s.message)
    append("\nЭкран: " + when (s.screenMode) {
        "big_map" -> "большая карта"
        "big_map_pending" -> "большая карта?"
        "minimap" -> "миникарта"
        else -> "не определён"
    })
    if (s.packTitle.isNotBlank()) append("\n${s.packTitle}")
    if (s.screenMode == "big_map") {
        append("\nГеометрия: ${s.bigMapGeometryMode}")
        s.bigMapGeometry?.let {
            append(String.format(java.util.Locale.US, "\n%s | опора %s | ошибка %.2f px | покрытие %.2f",
                it.space, it.reference, it.errorPx, it.support))
        }
    }
    s.position?.let {
        append(String.format(java.util.Locale.US, "\n%s: x %.1f  y %.1f  масштаб %.3f", it.space, it.x, it.y, it.scale))
    }
    append("\nПозиция: ${s.positionMode}")
    append("\nЭтаж: " + (s.layerName ?: if (s.layerMode == "not_supported") "не определяется этим набором" else "неизвестен (${s.layerMode})"))
    if (s.frameWidth > 0) append("\nКадр ${s.frameWidth}×${s.frameHeight}")
    s.nativeMs?.let { append(" | C++ $it мс | точек ${s.inliers}") }
    s.actualFps?.let { append(String.format(java.util.Locale.US, "\nОбработка %.1f кадр/с", it)) }
    s.frameAgeMs?.let { append(" | возраст кадра $it мс") }
}

object LiveTrackingStatus {
    private val mutable = MutableStateFlow(LiveTrackingState())
    val state: StateFlow<LiveTrackingState> = mutable.asStateFlow()
    internal fun publish(value: LiveTrackingState) { mutable.value = value }
}

@Serializable
private data class NativeFrameResult(
    val error: String? = null,
    val hasPosition: Boolean = false,
    val positionMode: String = "rejected",
    val space: String = "",
    val x: Double? = null,
    val y: Double? = null,
    val scale: Double? = null,
    val layer: String = "",
    val layerMode: String = "unknown",
    val inliers: Int = 0
)

data class DecodedTrackingFrame(
    val position: LivePosition?, val positionMode: String,
    val layer: String?, val layerMode: String, val inliers: Int
)

object TrackingFrameDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String, positionOnly: Boolean = false): DecodedTrackingFrame {
        val r = json.decodeFromString(NativeFrameResult.serializer(), raw)
        check(r.error == null) { r.error ?: "Native tracker error" }
        val position = if (r.hasPosition) {
            require(r.positionMode == "found" || r.positionMode == "track") { "Invalid position state" }
            require(r.space.isNotBlank() && r.x != null && r.y != null && r.scale != null)
            require(r.x.isFinite() && r.y.isFinite() && r.scale.isFinite() && r.scale > 0)
            LivePosition(r.space, r.x, r.y, r.scale)
        } else null
        val layer = r.layer.takeIf { !positionOnly && position != null && r.layerMode == "known" && it.isNotBlank() }
        return DecodedTrackingFrame(position, r.positionMode, layer,
            if (positionOnly) "not_supported" else if (position == null || (r.layerMode == "known" && layer == null)) "unknown"
            else r.layerMode, r.inliers.coerceAtLeast(0))
    }
}

/** Pure colour check matching the earlier OpenCV HSV arrow gate (H=88..102, S>=150,V>=200). */
object PlayerArrowSignal {
    fun isArrowPixel(argb: Int): Boolean {
        val r = (argb ushr 16) and 255
        val g = (argb ushr 8) and 255
        val b = argb and 255
        val hi = maxOf(r, g, b)
        val lo = minOf(r, g, b)
        val delta = hi - lo
        if (hi < 200 || delta == 0 || delta * 255 < 150 * hi) return false
        val hue = when (hi) {
            r -> 60f * (g - b) / delta + if (g < b) 360f else 0f
            g -> 60f * (b - r) / delta + 120f
            else -> 60f * (r - g) / delta + 240f
        }
        return hue in 176f..204f
    }

    fun present(pixels: IntArray, frameHeight: Int): Boolean {
        val k = frameHeight / 1440.0
        return pixels.count(::isArrowPixel) >= 60 * k * k
    }
}
