package io.github.d3tal.luxin.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

internal enum class DumpPhase {
    IDLE,
    COUNTDOWN,
    CAPTURING,
    ENCODING,
    DONE,
    ERROR
}

internal enum class DumpStopReason {
    USER,
    BUFFER_FULL,
    MAX_DURATION,
    RESIZE,
    CAPTURE_STOPPED,
    ERROR
}

internal data class BigMapDumpConfig(
    val startDelayMs: Long = DEFAULT_START_DELAY_MS,
    val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    val targetHeight: Int = DEFAULT_TARGET_HEIGHT,
    val colorEveryN: Int = DEFAULT_COLOR_EVERY_N,
    val memoryBudgetBytes: Long = DEFAULT_MEMORY_BUDGET_BYTES,
    val maxFramesCap: Int = DEFAULT_MAX_FRAMES_CAP
) {
    init {
        require(startDelayMs >= 0) { "startDelayMs must be non-negative" }
        require(maxDurationMs > 0) { "maxDurationMs must be positive" }
        require(targetHeight > 0) { "targetHeight must be positive" }
        require(colorEveryN >= 0) { "colorEveryN must be non-negative" }
        require(memoryBudgetBytes > 0) { "memoryBudgetBytes must be positive" }
        require(maxFramesCap > 0) { "maxFramesCap must be positive" }
    }

    companion object {
        const val DEFAULT_START_DELAY_MS = 5_000L
        const val DEFAULT_MAX_DURATION_MS = 20_000L
        const val DEFAULT_TARGET_HEIGHT = 720
        const val DEFAULT_COLOR_EVERY_N = 30
        const val DEFAULT_MEMORY_BUDGET_BYTES = 64L * 1024L * 1024L
        const val DEFAULT_MAX_FRAMES_CAP = 600
    }
}

internal data class ScaledDimensions(
    val width: Int,
    val height: Int,
    val scale: Double
)

internal data class BurstPoolPlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val width: Int,
    val height: Int,
    val scale: Double,
    val grayFrameBytes: Long,
    val colorFrameBytes: Long,
    val scratchBytes: Long,
    val maxFrames: Int,
    val colorFrames: Int,
    val totalBytes: Long
)

internal data class CaptureTimingStats(
    val capturedFrames: Int,
    val durationMs: Double,
    val achievedFps: Double,
    val maxGapMs: Double,
    val meanGapMs: Double,
    val droppedFrames: Int
)

internal data class FrameDumpRecord(
    val index: Int,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val generation: Long,
    val screenMode: String,
    val droppedBefore: Int,
    val hasColor: Boolean,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val scale: Double
)

internal object BigMapDumpPlanner {
    // ~3 s at the ~47 fps seen on device: enough for a long swipe plus a full pinch. The first height
    // (720, 540, 360) that fits this many frames in the budget wins, so 360p is normally chosen.
    private const val PREFERRED_BURST_FRAMES = 150

    /** Plan against this process's remaining heap, not the phone's free RAM. */
    fun allocationPlans(sourceWidth: Int, sourceHeight: Int, config: BigMapDumpConfig,
                        heapHeadroomBytes: Long): List<BurstPoolPlan> {
        // Leave headroom for ImageReader clients, tracker buffers and PNG encoding. Headroom
        // estimates are no guarantee of allocation success, so offer progressively smaller pools.
        val budget = minOf(config.memoryBudgetBytes, heapHeadroomBytes.coerceAtLeast(0L) / 3)
        val fullHeight = minOf(sourceHeight, config.targetHeight)
        val heights = listOf(fullHeight, minOf(fullHeight, 540), minOf(fullHeight, 360)).distinct()
        val preferredFrames = minOf(config.maxFramesCap, PREFERRED_BURST_FRAMES)
        return listOf(budget, budget / 2, budget / 4).filter { it > 0L }.mapNotNull { bytes ->
            val candidates = heights.map { height ->
                planBurstPool(sourceWidth, sourceHeight, config.copy(targetHeight = height, memoryBudgetBytes = bytes))
            }
            val plan = candidates.firstOrNull { it.maxFrames >= preferredFrames } ?: candidates.last()
            plan.takeIf { it.maxFrames >= 2 }
        }.distinct()
    }

    fun computeScaledDimensions(sourceWidth: Int, sourceHeight: Int, targetHeight: Int): ScaledDimensions {
        require(sourceWidth > 0 && sourceHeight > 0) { "source dimensions must be positive" }
        require(targetHeight > 0) { "targetHeight must be positive" }
        if (sourceHeight <= targetHeight) {
            return ScaledDimensions(sourceWidth, sourceHeight, 1.0)
        }
        val scale = targetHeight.toDouble() / sourceHeight.toDouble()
        val width = maxOf(1, (sourceWidth * scale).roundToInt())
        return ScaledDimensions(width, targetHeight, scale)
    }

    fun isColorKeyframe(index: Int, colorEveryN: Int): Boolean {
        require(index >= 0) { "index must be non-negative" }
        return colorEveryN > 0 && index % colorEveryN == 0
    }

    /**
     * Exact preallocation plan within [BigMapDumpConfig.memoryBudgetBytes], including the single
     * reusable RGBA scratch buffer used during downscale-to-gray conversion.
     */
    fun planBurstPool(sourceWidth: Int, sourceHeight: Int, config: BigMapDumpConfig): BurstPoolPlan {
        val scaled = computeScaledDimensions(sourceWidth, sourceHeight, config.targetHeight)
        val grayBytes = scaled.width.toLong() * scaled.height.toLong()
        val colorBytes = grayBytes * 4L
        val scratchBytes = if (scaled.scale < 1.0) colorBytes else 0L
        var used = scratchBytes
        var frames = 0
        var colorFrames = 0
        while (frames < config.maxFramesCap) {
            val needColor = isColorKeyframe(frames, config.colorEveryN)
            val slotBytes = grayBytes + if (needColor) colorBytes else 0L
            if (used + slotBytes > config.memoryBudgetBytes) break
            used += slotBytes
            frames++
            if (needColor) colorFrames++
        }
        return BurstPoolPlan(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            width = scaled.width,
            height = scaled.height,
            scale = scaled.scale,
            grayFrameBytes = grayBytes,
            colorFrameBytes = colorBytes,
            scratchBytes = scratchBytes,
            maxFrames = frames,
            colorFrames = colorFrames,
            totalBytes = used
        )
    }

    fun computeCaptureStats(timestampsNs: List<Long>, droppedFrames: Int): CaptureTimingStats {
        require(droppedFrames >= 0) { "droppedFrames must be non-negative" }
        val count = timestampsNs.size
        if (count < 2) {
            return CaptureTimingStats(
                capturedFrames = count,
                durationMs = 0.0,
                achievedFps = 0.0,
                maxGapMs = 0.0,
                meanGapMs = 0.0,
                droppedFrames = droppedFrames
            )
        }
        var maxGapNs = 0L
        var sumGapNs = 0L
        var positiveGaps = 0
        for (i in 1 until count) {
            val gap = (timestampsNs[i] - timestampsNs[i - 1]).coerceAtLeast(0L)
            if (gap > maxGapNs) maxGapNs = gap
            sumGapNs += gap
            positiveGaps++
        }
        val spanNs = (timestampsNs.last() - timestampsNs.first()).coerceAtLeast(0L)
        val durationMs = spanNs / 1_000_000.0
        val achievedFps = if (spanNs > 0L) (count - 1).toDouble() * 1_000_000_000.0 / spanNs.toDouble() else 0.0
        val meanGapMs = if (positiveGaps > 0) (sumGapNs.toDouble() / positiveGaps) / 1_000_000.0 else 0.0
        return CaptureTimingStats(
            capturedFrames = count,
            durationMs = durationMs,
            achievedFps = achievedFps,
            maxGapMs = maxGapNs / 1_000_000.0,
            meanGapMs = meanGapMs,
            droppedFrames = droppedFrames
        )
    }
}

internal object DumpPhaseTransitions {
    fun canTransition(from: DumpPhase, to: DumpPhase): Boolean = when (from) {
        DumpPhase.IDLE, DumpPhase.DONE, DumpPhase.ERROR ->
            to == DumpPhase.COUNTDOWN || to == DumpPhase.CAPTURING || to == DumpPhase.ERROR
        DumpPhase.COUNTDOWN ->
            to == DumpPhase.CAPTURING || to == DumpPhase.IDLE || to == DumpPhase.DONE || to == DumpPhase.ERROR
        DumpPhase.CAPTURING ->
            to == DumpPhase.ENCODING || to == DumpPhase.DONE || to == DumpPhase.ERROR
        DumpPhase.ENCODING ->
            to == DumpPhase.DONE || to == DumpPhase.ERROR
    }

    fun initialActivePhase(startDelayMs: Long): DumpPhase =
        if (startDelayMs > 0L) DumpPhase.COUNTDOWN else DumpPhase.CAPTURING

    fun afterCapture(capturedFrames: Int, hasError: Boolean): DumpPhase = when {
        hasError && capturedFrames == 0 -> DumpPhase.ERROR
        capturedFrames > 0 -> DumpPhase.ENCODING
        else -> DumpPhase.DONE
    }

    fun afterEncode(hasError: Boolean): DumpPhase =
        if (hasError) DumpPhase.ERROR else DumpPhase.DONE
}

internal class BigMapDumpLimiter(
    private val maxDurationMs: Long,
    private val maxFrames: Int,
    private val startTimeMs: Long
) {
    init {
        require(maxDurationMs > 0) { "maxDurationMs must be positive" }
        require(maxFrames > 0) { "maxFrames must be positive" }
    }

    fun shouldStop(nowMs: Long, capturedFrames: Int): DumpStopReason? {
        if (capturedFrames >= maxFrames) return DumpStopReason.BUFFER_FULL
        if (nowMs - startTimeMs >= maxDurationMs) return DumpStopReason.MAX_DURATION
        return null
    }
}

/**
 * Why a burst may end with zero frames. Counted per Image handed to
 * [BigMapFrameDumper.captureFrameFromImage] while the burst was CAPTURING.
 * imagesSeen == 0 means the system never delivered an Image (nothing to convert).
 */
internal data class CaptureRejectStats(
    val imagesSeen: Int = 0,
    val staleTimestamp: Int = 0,
    val badPlane: Int = 0,
    val conversionErrors: Int = 0,
    val lastNote: String? = null
)

internal object BigMapDumpDiagnostics {
    /** Message for a burst that captured nothing. The reason is spelled out so one screenshot is enough to diagnose it. */
    fun zeroFramesMessage(reason: DumpStopReason, durationMs: Long, stats: CaptureRejectStats): String {
        val seconds = String.format(Locale.US, "%.1f", durationMs / 1000.0)
        val cause = when {
            stats.imagesSeen == 0 ->
                "система не прислала ни одного кадра за $seconds с " +
                    "(экран не обновлялся или очередь ImageReader не освобождена)"
            reason == DumpStopReason.RESIZE ->
                "размер кадра изменился до записи первого кадра"
            else ->
                "кадров от системы: ${stats.imagesSeen}, не записано: " +
                    "дубль метки времени ${stats.staleTimestamp}, " +
                    "формат буфера ${stats.badPlane}, " +
                    "ошибка конвертации ${stats.conversionErrors}"
        }
        val note = stats.lastNote?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
        return "Захват завершён: не получено ни одного кадра — $cause (остановка: ${reasonLabel(reason)})$note"
    }

    fun reasonLabel(reason: DumpStopReason): String = when (reason) {
        DumpStopReason.USER -> "по кнопке"
        DumpStopReason.BUFFER_FULL -> "буфер заполнен"
        DumpStopReason.MAX_DURATION -> "лимит времени"
        DumpStopReason.RESIZE -> "смена размера кадра"
        DumpStopReason.CAPTURE_STOPPED -> "захват экрана остановлен"
        DumpStopReason.ERROR -> "ошибка"
    }
}

internal object BigMapDumpFormat {
    const val CSV_HEADER =
        "index,timestampNs,width,height,generation,screenMode,dropped_before,has_color,source_width,source_height,scale"

    fun folderName(epochMs: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            this.timeZone = timeZone
        }.format(Date(epochMs))
        return "bigmap_dump_$stamp"
    }

    fun grayFrameFileName(index: Int, timestampNs: Long): String {
        require(index >= 0) { "index must be non-negative" }
        require(timestampNs >= 0) { "timestampNs must be non-negative" }
        return String.format(Locale.US, "f_%06d_%d.png", index, timestampNs)
    }

    fun colorFrameFileName(index: Int, timestampNs: Long): String {
        require(index >= 0) { "index must be non-negative" }
        require(timestampNs >= 0) { "timestampNs must be non-negative" }
        return String.format(Locale.US, "c_%06d_%d.png", index, timestampNs)
    }

    fun sanitizeScreenMode(screenMode: String): String {
        val cleaned = screenMode.trim().filter { ch ->
            !ch.isISOControl() && ch != ',' && ch != '"' && !ch.isWhitespace()
        }
        return cleaned.ifEmpty { "unknown" }
    }

    fun csvRow(record: FrameDumpRecord): String {
        require(record.index >= 0) { "index must be non-negative" }
        require(record.timestampNs >= 0) { "timestampNs must be non-negative" }
        require(record.width > 0 && record.height > 0) { "frame dimensions must be positive" }
        require(record.sourceWidth > 0 && record.sourceHeight > 0) { "source dimensions must be positive" }
        require(record.scale.isFinite() && record.scale > 0.0) { "scale must be positive finite" }
        require(record.droppedBefore >= 0) { "droppedBefore must be non-negative" }
        val mode = sanitizeScreenMode(record.screenMode)
        val hasColorInt = if (record.hasColor) 1 else 0
        val scaleStr = String.format(Locale.US, "%.6f", record.scale)
        return "${record.index},${record.timestampNs},${record.width},${record.height}," +
            "${record.generation},$mode,${record.droppedBefore},$hasColorInt," +
            "${record.sourceWidth},${record.sourceHeight},$scaleStr"
    }

    fun formatCsv(records: List<FrameDumpRecord>): String = buildString {
        append(CSV_HEADER)
        append('\n')
        for (record in records) {
            append(csvRow(record))
            append('\n')
        }
    }

    fun formatSummaryTxt(
        folderName: String,
        plan: BurstPoolPlan,
        stats: CaptureTimingStats,
        colorKeyframesSaved: Int,
        stopReason: DumpStopReason,
        logExportNote: String?
    ): String = buildString {
        appendLine("folder=$folderName")
        appendLine("source_size=${plan.sourceWidth}x${plan.sourceHeight}")
        appendLine("dump_size=${plan.width}x${plan.height}")
        appendLine(String.format(Locale.US, "scale=%.6f", plan.scale))
        appendLine("pool_capacity_frames=${plan.maxFrames}")
        appendLine("pool_bytes=${plan.totalBytes}")
        appendLine("captured_frames=${stats.capturedFrames}")
        appendLine("color_keyframes=$colorKeyframesSaved")
        appendLine("dropped_frames=${stats.droppedFrames}")
        appendLine(String.format(Locale.US, "duration_ms=%.2f", stats.durationMs))
        appendLine(String.format(Locale.US, "achieved_fps=%.2f", stats.achievedFps))
        appendLine(String.format(Locale.US, "mean_gap_ms=%.2f", stats.meanGapMs))
        appendLine(String.format(Locale.US, "max_gap_ms=%.2f", stats.maxGapMs))
        appendLine("stop_reason=${stopReason.name.lowercase(Locale.US)}")
        if (!logExportNote.isNullOrBlank()) {
            appendLine("tracking_log=$logExportNote")
        }
    }
}
