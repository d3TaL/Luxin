package io.github.d3tal.luxin.diagnostics

import java.io.Reader
import java.io.Writer

internal data class TrackingLogSummary(val records: Int, val limited: Boolean)

/** Export only known scalar diagnostics, never arbitrary log text, paths or minimap positions. */
internal object TrackingLogFilter {
    const val MAX_RECORDS = 20_000
    const val MAX_INPUT_CHARS = 16 * 1024 * 1024
    const val MAX_LINE_CHARS = 8192

    // logcat -v threadtime -v monotonic: uptime, process, thread, priority, tag, message.
    private val header = Regex("""^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(LuxinCapture|LuxinTrackerJNI)\s*:\s*(.*)$""")
    private val field = Regex("""(?:^|\s)([A-Za-z][A-Za-z0-9_]*)=([^\s]+)""")
    private val number = Regex("""-?(?:\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|nan|inf|Infinity|NaN)""")
    private val dimensions = Regex("""\d+x\d+""")
    private val rectangle = Regex("""-?\d+,-?\d+,-?\d+,-?\d+""")
    private val pair = Regex("""-?\d+/-?\d+""")
    private val numericKeys = setOf(
        "ts", "tsNs", "epoch", "rev", "gen", "expected", "current", "old", "new", "lastTsNs",
        "absolute", "throttled", "anchorExpired", "anchorAgeS", "gapS", "sinceAttemptS", "sparseOk",
        "suspended", "suspendedStill", "followMs", "movementPx", "stable", "convertMs", "features",
        "match", "validate", "keypoints", "sessionMs", "settleMovementPx", "settleStill", "settleSpanS",
        "settleMs", "measuredMotion", "tolerated", "matches", "inliers", "ratio", "grid", "errorPx",
        "scale", "angleDeg", "hullFrac", "overlap", "overlapFrac", "denseChanged", "denseFrac",
        "tilesVisited", "tileChanged", "tileValid", "tileFrac", "roi", "mean", "changed", "pixels",
        "frac", "inputAgeMs", "readMs", "bitmapMs", "gateMs", "callDecodeMs", "nativeMs", "ageMs",
        "actualFps", "targetFps", "version", "ok", "corners", "tracked", "scaleStep", "txStep", "tyStep",
        "lkMs", "ransacMs", "totalMs", "pathSinceAbs", "zoomSinceAbs", "absAgeS", "bridge", "chainEpoch",
        "hidden", "jumpPx", "conflicts", "screenFixed", "coverage", "warpedResidual", "fixedResidual", "warpedFrac", "fixedFrac",
        "staticLatchFrames", "staticReleaseFrames"
    )
    private val booleanKeys = setOf("visibility", "resetBigMap", "resetAll", "obsolete", "blocked", "geometry")
    private val enumKeys = setOf("stage", "phase", "mode", "reset", "reason", "follow", "settle",
        "outcome", "pixelOutcome", "refresh", "waiting", "screen", "lifecycle", "invalidate", "latchReason")
    private val enumValues = setOf(
        "none", "begin", "before_absolute", "return", "native_return", "checked", "publish",
        "publish_dropped", "error", "reset", "tracking", "confirming", "moving", "acquiring",
        "rejected", "found", "ambiguous", "unknown", "big_map", "big_map_pending", "minimap", "stale",
        "absolute", "not_attempted", "accepted", "insufficient_descriptors", "insufficient_matches",
        "invalid_transform", "scale_bounds", "rotation_bounds", "insufficient_inliers", "inlier_ratio",
        "grid_support", "reprojection_error", "hull_support", "invalid_comparison_mask", "overlap",
        "dense_change", "tile_change", "controls_change", "started", "adopted", "discarded", "conflict",
        "follow_failed", "frame_size", "observation_gap", "invalid_input_or_timestamp", "composed_transform",
        "closed_tracker", "null_bitmap", "bitmap_read_failed", "registration_exception", "tracker_destroy",
        "tracker_reset", "bigmap_reset", "reference_added", "revision", "generation", "ui_gate",
        "enter_bigmap", "visibility_or_snapshot", "no_fresh_frame", "input_age", "unsupported_geometry",
        "generation_after_gate", "ui_pending", "generation_or_age_after_native", "minimap_bounds",
        "minimap_arrow", "generation_after_minimap", "start", "stop", "closed", "cancelled", "snapshot",
        "expiry", "epoch", "init", "step", "control", "abs_request", "abs_result", "abs_apply", "abs_conflict",
        "lost", "map", "overlay", "unverifiable", "static_pixels", "no_previous_frame", "insufficient_corners",
        "insufficient_fb_matches", "degenerate_inliers", "bridge_expired", "invalid_chain", "invalid_frame",
        "invalid_timestamp", "old_generation", "search_failed", "space_change", "suspect", "initial", "blend_small",
        "blend_three", "tracker_mode", "ok", "screen_change", "static_latch", "static_latch_pending", "static_release", "static_release_pending"
    )
    private val nativeKinds = setOf("BigMap", "BigMapFollow", "BigMapControl", "BigMapV2", "BigMapSnapshot", "BigMapMotion")

    fun sanitize(line: String, processId: Int): String? {
        if (line.length > MAX_LINE_CHARS || line.any { it.isISOControl() }) return null
        val match = header.matchEntire(line) ?: return null
        val (time, pid, tid, priority, tag, message) = match.destructured
        if (pid.toIntOrNull() != processId) return null
        val kind = message.substringBefore(' ')
        if (tag == "LuxinTrackerJNI" && kind !in nativeKinds) return null
        if (tag == "LuxinCapture" && kind != "BigMap" && kind != "BigMapV2") return null
        val fields = field.findAll(message.substringAfter(' ', "")).mapNotNull { value ->
            val key = value.groupValues[1]
            val text = value.groupValues[2]
            val accepted = when {
                key in numericKeys -> number.matches(text) || (key == "actualFps" && text == "null")
                key in booleanKeys -> text == "true" || text == "false"
                key in enumKeys -> text in enumValues
                key == "frame" || key == "work" -> dimensions.matches(text)
                key == "rect" || key == "worstVisitedTile" -> rectangle.matches(text)
                key == "kp" || key == "desc" -> pair.matches(text)
                key == "opencv" -> Regex("""\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?""").matches(text)
                else -> false
            }
            if (accepted) "$key=$text" else null
        }.toMutableList()
        // LayerDecision.toString contains free-form reason text; keep only its boolean signal.
        if (kind == "BigMapV2" && tag == "LuxinCapture" && message.startsWith("BigMapV2 layer=")) {
            Regex("""\bconfirmed=(true|false)\)""").find(message)?.let {
                fields.add("layerConfirmed=${it.groupValues[1]}")
            }
        }
        if (fields.isEmpty()) return null
        return "$time $pid $tid $priority $tag: $kind ${fields.joinToString(" ")}"
    }

    /** Bounded streaming read, including oversized and rejected lines; never builds an unbounded line. */
    fun copy(input: Reader, output: Writer, processId: Int,
             maxInputChars: Int = MAX_INPUT_CHARS, maxRecords: Int = MAX_RECORDS): TrackingLogSummary {
        require(processId > 0 && maxInputChars > 0 && maxRecords > 0)
        val line = StringBuilder()
        var oversized = false
        var records = 0
        var consumed = 0
        fun emit() {
            if (!oversized) sanitize(line.toString(), processId)?.let {
                output.write(it)
                output.write("\n")
                records++
            }
            line.setLength(0)
            oversized = false
        }
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val value = input.read()
            if (value < 0) {
                if (line.isNotEmpty() || oversized) emit()
                return TrackingLogSummary(records, false)
            }
            if (++consumed > maxInputChars) return TrackingLogSummary(records, true)
            if (value == '\n'.code) {
                emit()
                if (records >= maxRecords) return TrackingLogSummary(records, true)
            } else if (!oversized) {
                if (line.length == MAX_LINE_CHARS) {
                    line.setLength(0)
                    oversized = true
                } else {
                    line.append(value.toChar())
                }
            }
        }
    }
}
