package io.github.d3tal.luxin.diagnostics

import android.content.ContentResolver
import android.content.Context
import android.media.Image
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class BigMapFrameDumpState(
    val phase: DumpPhase = DumpPhase.IDLE,
    val countdownRemainingSec: Int = 0,
    val folderName: String? = null,
    val capturedFrames: Int = 0,
    val poolCapacityFrames: Int = 0,
    val encodedFrames: Int = 0,
    val droppedFrames: Int = 0,
    val achievedFps: Double? = null,
    val maxGapMs: Double? = null,
    val dumpWidth: Int = 0,
    val dumpHeight: Int = 0,
    val startDelaySec: Int = (BigMapDumpConfig.DEFAULT_START_DELAY_MS / 1000L).toInt(),
    val maxDurationSec: Int = (BigMapDumpConfig.DEFAULT_MAX_DURATION_MS / 1000L).toInt(),
    val memoryBudgetMb: Int = (BigMapDumpConfig.DEFAULT_MEMORY_BUDGET_BYTES / (1024L * 1024L)).toInt(),
    val logExportNote: String? = null,
    val stopReason: DumpStopReason? = null,
    val message: String? = null,
    val error: String? = null
) {
    val isToggleChecked: Boolean
        get() = phase == DumpPhase.COUNTDOWN || phase == DumpPhase.CAPTURING
    val isBusyEncoding: Boolean
        get() = phase == DumpPhase.ENCODING
}

internal object BigMapFrameDumpStatus {
    private val mutable = MutableStateFlow(BigMapFrameDumpState())
    val state: StateFlow<BigMapFrameDumpState> = mutable.asStateFlow()

    internal fun publish(value: BigMapFrameDumpState) {
        mutable.value = value
    }
}

private object OpenCvRuntimeLoader {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("opencv_java4")
            loaded = true
        }
    }
}

/**
 * Two-phase RAM burst diagnostic dumper:
 *  Phase 1 (COUNTDOWN -> CAPTURING): preallocates direct ByteBuffers within a RAM budget (~256 MB),
 *    then downscales each captured Image directly via OpenCV (INTER_AREA + RGBA2GRAY at 720p, plus
 *    RGBA keyframes every 10th frame) with zero per-frame allocations and zero PNG encoding during capture.
 *  Phase 2 (ENCODING): after capture stops, encodes buffered frames to PNG on Dispatchers.IO,
 *    writes frames.csv, summary.txt, and exports the filtered tracking log into the SAF subfolder.
 */
internal class BigMapFrameDumper(
    private val context: Context,
    private val scope: CoroutineScope,
    private val readCurrentSourceSize: () -> Pair<Int, Int>,
    private val config: BigMapDumpConfig = BigMapDumpConfig(),
    private val onCapturePhaseReady: () -> Unit,
    private val onCapturePhaseFinished: () -> Unit,
    private val onSessionFinished: () -> Unit
) {
    private class FrameSlot(
        val grayBuf: ByteBuffer,
        val rgbaBuf: ByteBuffer?
    ) {
        var timestampNs: Long = 0L
        var generation: Long = 0L
        var screenMode: String = "unknown"
        var droppedBefore: Int = 0
    }

    private val started = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    private val stopReason = AtomicReference<DumpStopReason?>(null)
    private val captureDone = CompletableDeferred<DumpStopReason>()
    private val captureLock = Any()

    @Volatile private var watchdogJob: Job? = null

    // Guarded by captureLock during CAPTURING; owned exclusively by encoder after onCapturePhaseFinished().
    private var plan: BurstPoolPlan? = null
    private var slots: Array<FrameSlot?> = emptyArray()
    private var scratchRgbaBuf: ByteBuffer? = null
    private var capturedCount = 0
    private var totalDropped = 0
    private var lastCapturedTimestampNs = Long.MIN_VALUE
    private var lastStatusPublishMs = 0L
    private var limiter: BigMapDumpLimiter? = null

    fun isCapturing(): Boolean = capturing.get()

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return false
        scope.launch(Dispatchers.IO) {
            runSession()
        }
        return true
    }

    fun stop(reason: DumpStopReason = DumpStopReason.USER) {
        stopReason.compareAndSet(null, reason)
        capturing.set(false)
        captureDone.complete(reason)
        watchdogJob?.cancel()
    }

    /**
     * Converts [image] directly into the next preallocated 720p direct buffer before image.close().
     * Never allocates per-frame buffers and never calls back into CaptureService under captureLock.
     */
    fun captureFrameFromImage(
        image: Image,
        generation: Long,
        screenMode: String,
        droppedBefore: Int
    ) {
        if (!capturing.get()) return
        synchronized(captureLock) {
            if (!capturing.get()) return
            val activePlan = plan ?: return
            val activeLimiter = limiter
            val nowMs = SystemClock.elapsedRealtime()
            if (activeLimiter != null) {
                activeLimiter.shouldStop(nowMs, capturedCount)?.let { reason ->
                    signalCaptureStopLocked(reason)
                    return
                }
            }
            if (capturedCount >= activePlan.maxFrames) {
                signalCaptureStopLocked(DumpStopReason.BUFFER_FULL)
                return
            }
            if (image.timestamp <= lastCapturedTimestampNs) {
                totalDropped += droppedBefore.coerceAtLeast(0)
                return
            }

            val crop = image.cropRect
            if (crop.width() != activePlan.sourceWidth || crop.height() != activePlan.sourceHeight) {
                signalCaptureStopLocked(DumpStopReason.RESIZE)
                return
            }

            val plane = image.planes[0]
            val planeBuf = plane.buffer
            if (!planeBuf.isDirect || plane.pixelStride != 4 || plane.rowStride < image.width * 4) {
                totalDropped += droppedBefore.coerceAtLeast(0) + 1
                Log.w(
                    TAG,
                    "Unsupported Image plane: direct=${planeBuf.isDirect} pixelStride=${plane.pixelStride} " +
                        "rowStride=${plane.rowStride} width=${image.width}"
                )
                return
            }

            val slot = slots[capturedCount] ?: return
            var srcFull: Mat? = null
            var srcRoi: Mat? = null
            var dstGrayMat: Mat? = null
            var dstRgbaMat: Mat? = null
            var scratchMat: Mat? = null
            try {
                srcFull = Mat(image.height, image.width, CvType.CV_8UC4, planeBuf, plane.rowStride.toLong())
                val needsCrop = crop.left != 0 || crop.top != 0 ||
                    crop.width() != image.width || crop.height() != image.height
                srcRoi = if (needsCrop) {
                    srcFull.submat(Rect(crop.left, crop.top, crop.width(), crop.height()))
                } else {
                    srcFull
                }

                dstGrayMat = Mat(activePlan.height, activePlan.width, CvType.CV_8UC1, slot.grayBuf)
                val targetSize = Size(activePlan.width.toDouble(), activePlan.height.toDouble())
                val downscale = activePlan.scale < 1.0

                val colorTargetBuf = slot.rgbaBuf
                if (colorTargetBuf != null) {
                    dstRgbaMat = Mat(activePlan.height, activePlan.width, CvType.CV_8UC4, colorTargetBuf)
                    if (downscale) {
                        Imgproc.resize(srcRoi, dstRgbaMat, targetSize, 0.0, 0.0, Imgproc.INTER_AREA)
                    } else {
                        srcRoi.copyTo(dstRgbaMat)
                    }
                    Imgproc.cvtColor(dstRgbaMat, dstGrayMat, Imgproc.COLOR_RGBA2GRAY)
                } else if (downscale) {
                    val scratch = scratchRgbaBuf ?: return
                    scratchMat = Mat(activePlan.height, activePlan.width, CvType.CV_8UC4, scratch)
                    Imgproc.resize(srcRoi, scratchMat, targetSize, 0.0, 0.0, Imgproc.INTER_AREA)
                    Imgproc.cvtColor(scratchMat, dstGrayMat, Imgproc.COLOR_RGBA2GRAY)
                } else {
                    Imgproc.cvtColor(srcRoi, dstGrayMat, Imgproc.COLOR_RGBA2GRAY)
                }

                slot.timestampNs = image.timestamp
                slot.generation = generation
                slot.screenMode = screenMode
                slot.droppedBefore = droppedBefore.coerceAtLeast(0)
                lastCapturedTimestampNs = image.timestamp
                totalDropped += slot.droppedBefore
                capturedCount++

                if (capturedCount >= activePlan.maxFrames) {
                    signalCaptureStopLocked(DumpStopReason.BUFFER_FULL)
                } else if (nowMs - lastStatusPublishMs >= 150L) {
                    lastStatusPublishMs = nowMs
                    BigMapFrameDumpStatus.publish(
                        baseState(activePlan).copy(
                            phase = DumpPhase.CAPTURING,
                            capturedFrames = capturedCount,
                            droppedFrames = totalDropped,
                            message = "Идёт захват в RAM (без кодирования PNG)…"
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame conversion failed during burst capture", e)
                totalDropped += droppedBefore.coerceAtLeast(0) + 1
            } finally {
                scratchMat?.release()
                dstRgbaMat?.release()
                dstGrayMat?.release()
                if (srcRoi != null && srcRoi !== srcFull) srcRoi.release()
                srcFull?.release()
            }
        }
    }

    private fun signalCaptureStopLocked(reason: DumpStopReason) {
        stopReason.compareAndSet(null, reason)
        capturing.set(false)
        captureDone.complete(reason)
    }

    private suspend fun runSession() {
        var activePlan: BurstPoolPlan? = null
        var folderName: String? = null
        var logExportNote: String? = null
        var failureMessage: String? = null
        var stats: CaptureTimingStats? = null
        var encodedCount = 0
        var captureListenerAttached = false

        try {
            val destination = DiagnosticLogExporter.loadDestination(context)
                ?: throw IOException("Сначала выберите папку в карточке «Логи трекинга»")

            try {
                OpenCvRuntimeLoader.ensureLoaded()
            } catch (e: Throwable) {
                throw IOException("Не удалось загрузить OpenCV (libopencv_java4): ${e.message ?: e.javaClass.simpleName}", e)
            }

            // Run countdown BEFORE sizing/allocating the pool so portrait->landscape switch into
            // the game settles before preallocating the 720p direct buffers.
            if (config.startDelayMs > 0L) {
                var remainingMs = config.startDelayMs
                while (remainingMs > 0L) {
                    if (captureDone.isCompleted) break
                    val sec = ((remainingMs + 999L) / 1000L).toInt()
                    BigMapFrameDumpStatus.publish(
                        defaultState().copy(
                            phase = DumpPhase.COUNTDOWN,
                            countdownRemainingSec = sec,
                            message = "Переключитесь в игру: старт через $sec с…"
                        )
                    )
                    val step = minOf(remainingMs, 250L)
                    delay(step)
                    remainingMs -= step
                }
            }

            if (captureDone.isCompleted) {
                // Stopped during countdown before capture began.
                val reason = stopReason.get() ?: DumpStopReason.USER
                BigMapFrameDumpStatus.publish(
                    defaultState().copy(
                        phase = DumpPhase.DONE,
                        stopReason = reason,
                        message = if (reason == DumpStopReason.CAPTURE_STOPPED) {
                            "Дамп отменён: захват экрана остановлен"
                        } else {
                            "Дамп отменён до начала записи"
                        }
                    )
                )
                return
            }

            val (sourceWidth, sourceHeight) = readCurrentSourceSize()
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                throw IOException("Неизвестный размер кадра захвата")
            }
            val computedPlan = BigMapDumpPlanner.planBurstPool(sourceWidth, sourceHeight, config)
            if (computedPlan.maxFrames <= 0) {
                val budgetMb = config.memoryBudgetBytes / (1024L * 1024L)
                throw IOException("Бюджета памяти ($budgetMb МБ) недостаточно для кадра ${computedPlan.width}×${computedPlan.height}")
            }
            activePlan = computedPlan

            BigMapFrameDumpStatus.publish(
                baseState(computedPlan).copy(
                    phase = DumpPhase.CAPTURING,
                    message = "Выделение буфера RAM (${computedPlan.totalBytes / (1024L * 1024L)} МБ)…"
                )
            )

            // Preallocate all direct ByteBuffers before attaching the capture listener.
            val allocatedSlots = arrayOfNulls<FrameSlot>(computedPlan.maxFrames)
            val allocatedScratch: ByteBuffer?
            try {
                allocatedScratch = if (computedPlan.scratchBytes > 0L) {
                    ByteBuffer.allocateDirect(computedPlan.scratchBytes.toInt())
                } else {
                    null
                }
                for (i in 0 until computedPlan.maxFrames) {
                    val gray = ByteBuffer.allocateDirect(computedPlan.grayFrameBytes.toInt())
                    val rgba = if (BigMapDumpPlanner.isColorKeyframe(i, config.colorEveryN)) {
                        ByteBuffer.allocateDirect(computedPlan.colorFrameBytes.toInt())
                    } else {
                        null
                    }
                    allocatedSlots[i] = FrameSlot(gray, rgba)
                }
            } catch (e: OutOfMemoryError) {
                allocatedSlots.fill(null)
                val needMb = computedPlan.totalBytes / (1024L * 1024L)
                throw IOException("Недостаточно памяти для буфера дампа ($needMb МБ). Закройте лишние приложения и повторите.", e)
            }

            if (captureDone.isCompleted) {
                allocatedSlots.fill(null)
                val reason = stopReason.get() ?: DumpStopReason.USER
                BigMapFrameDumpStatus.publish(
                    baseState(computedPlan).copy(
                        phase = DumpPhase.DONE,
                        stopReason = reason,
                        message = "Дамп отменён до начала записи"
                    )
                )
                return
            }

            synchronized(captureLock) {
                plan = computedPlan
                slots = allocatedSlots
                scratchRgbaBuf = allocatedScratch
                capturedCount = 0
                totalDropped = 0
                lastCapturedTimestampNs = Long.MIN_VALUE
                lastStatusPublishMs = 0L
            }

            // Phase 1: CAPTURING into preallocated RAM pool.
            val captureStartMs = SystemClock.elapsedRealtime()
            synchronized(captureLock) {
                limiter = BigMapDumpLimiter(config.maxDurationMs, computedPlan.maxFrames, captureStartMs)
                capturing.set(true)
            }
            BigMapFrameDumpStatus.publish(
                baseState(computedPlan).copy(
                    phase = DumpPhase.CAPTURING,
                    capturedFrames = 0,
                    droppedFrames = 0,
                    message = "Идёт захват в RAM (без кодирования PNG)…"
                )
            )

            watchdogJob = scope.launch(Dispatchers.Default) {
                delay(config.maxDurationMs)
                stop(DumpStopReason.MAX_DURATION)
            }
            onCapturePhaseReady()
            captureListenerAttached = true

            val reason = captureDone.await()
            stopReason.compareAndSet(null, reason)
            capturing.set(false)
            watchdogJob?.cancel()
            watchdogJob = null
            onCapturePhaseFinished()
            captureListenerAttached = false

            val finalCaptured: Int
            val finalDropped: Int
            synchronized(captureLock) {
                scratchRgbaBuf = null
                finalCaptured = capturedCount
                finalDropped = totalDropped
            }

            val timestamps = ArrayList<Long>(finalCaptured)
            for (i in 0 until finalCaptured) {
                slots[i]?.let { timestamps.add(it.timestampNs) }
            }
            val computedStats = BigMapDumpPlanner.computeCaptureStats(timestamps, finalDropped)
            stats = computedStats

            val nextPhase = DumpPhaseTransitions.afterCapture(finalCaptured, hasError = false)
            if (nextPhase != DumpPhase.ENCODING) {
                BigMapFrameDumpStatus.publish(
                    baseState(computedPlan).copy(
                        phase = DumpPhase.DONE,
                        capturedFrames = 0,
                        droppedFrames = finalDropped,
                        achievedFps = 0.0,
                        maxGapMs = 0.0,
                        stopReason = stopReason.get() ?: DumpStopReason.USER,
                        message = "Захват завершён: не получено ни одного кадра"
                    )
                )
                return
            }

            // Phase 2: ENCODING PNGs, frames.csv, summary.txt and tracking log on Dispatchers.IO.
            withContext(NonCancellable) {
                BigMapFrameDumpStatus.publish(
                    baseState(computedPlan).copy(
                        phase = DumpPhase.ENCODING,
                        capturedFrames = finalCaptured,
                        encodedFrames = 0,
                        droppedFrames = finalDropped,
                        achievedFps = computedStats.achievedFps,
                        maxGapMs = computedStats.maxGapMs,
                        message = "Кодирование PNG: 0/$finalCaptured…"
                    )
                )

                val resolver = context.contentResolver
                val treeRoot = DocumentsContract.buildDocumentUriUsingTree(
                    destination.uri,
                    DocumentsContract.getTreeDocumentId(destination.uri)
                )
                val name = BigMapDumpFormat.folderName(System.currentTimeMillis())
                val folderUri = DocumentsContract.createDocument(
                    resolver,
                    treeRoot,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    name
                ) ?: throw IOException("Не удалось создать папку дампа $name")
                folderName = name

                val records = ArrayList<FrameDumpRecord>(finalCaptured)
                var colorSaved = 0
                val encodedBuf = MatOfByte()
                val bgrMat = Mat(computedPlan.height, computedPlan.width, CvType.CV_8UC3)
                try {
                    for (i in 0 until finalCaptured) {
                        val slot = slots[i] ?: continue
                        val grayFile = BigMapDumpFormat.grayFrameFileName(i, slot.timestampNs)
                        val grayMat = Mat(computedPlan.height, computedPlan.width, CvType.CV_8UC1, slot.grayBuf)
                        try {
                            encodeMatToPngFile(resolver, folderUri, grayFile, grayMat, encodedBuf)
                        } finally {
                            grayMat.release()
                        }

                        val colorBuf = slot.rgbaBuf
                        val hasColor = colorBuf != null
                        if (colorBuf != null) {
                            val colorFile = BigMapDumpFormat.colorFrameFileName(i, slot.timestampNs)
                            val rgbaMat = Mat(computedPlan.height, computedPlan.width, CvType.CV_8UC4, colorBuf)
                            try {
                                Imgproc.cvtColor(rgbaMat, bgrMat, Imgproc.COLOR_RGBA2BGR)
                                encodeMatToPngFile(resolver, folderUri, colorFile, bgrMat, encodedBuf)
                                colorSaved++
                            } finally {
                                rgbaMat.release()
                            }
                        }

                        records.add(
                            FrameDumpRecord(
                                index = i,
                                timestampNs = slot.timestampNs,
                                width = computedPlan.width,
                                height = computedPlan.height,
                                generation = slot.generation,
                                screenMode = slot.screenMode,
                                droppedBefore = slot.droppedBefore,
                                hasColor = hasColor,
                                sourceWidth = computedPlan.sourceWidth,
                                sourceHeight = computedPlan.sourceHeight,
                                scale = computedPlan.scale
                            )
                        )
                        // Release direct buffers incrementally as frames finish encoding.
                        slots[i] = null
                        encodedCount = i + 1

                        if (encodedCount == finalCaptured || encodedCount % 5 == 0) {
                            BigMapFrameDumpStatus.publish(
                                baseState(computedPlan).copy(
                                    phase = DumpPhase.ENCODING,
                                    folderName = name,
                                    capturedFrames = finalCaptured,
                                    encodedFrames = encodedCount,
                                    droppedFrames = finalDropped,
                                    achievedFps = computedStats.achievedFps,
                                    maxGapMs = computedStats.maxGapMs,
                                    message = "Кодирование PNG: $encodedCount/$finalCaptured…"
                                )
                            )
                        }
                    }
                } finally {
                    bgrMat.release()
                    encodedBuf.release()
                }

                writeTextFile(resolver, folderUri, "frames.csv", "text/csv", BigMapDumpFormat.formatCsv(records))

                logExportNote = try {
                    val exported = DiagnosticLogExporter.exportToDirectory(context, folderUri)
                    "Лог трекинга: ${exported.filename} (${exported.records} зап.)" +
                        if (exported.limited) " [ограничен]" else ""
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "Tracking log not exported with dump: ${e.message}")
                    "Лог трекинга не сохранён (нет записей трекера в буфере)"
                }

                val finalReason = stopReason.get() ?: DumpStopReason.USER
                val summaryText = BigMapDumpFormat.formatSummaryTxt(
                    folderName = name,
                    plan = computedPlan,
                    stats = computedStats,
                    colorKeyframesSaved = colorSaved,
                    stopReason = finalReason,
                    logExportNote = logExportNote
                )
                writeTextFile(resolver, folderUri, "summary.txt", "text/plain", summaryText)

                BigMapFrameDumpStatus.publish(
                    baseState(computedPlan).copy(
                        phase = DumpPhaseTransitions.afterEncode(hasError = false),
                        folderName = name,
                        capturedFrames = finalCaptured,
                        encodedFrames = encodedCount,
                        droppedFrames = finalDropped,
                        achievedFps = computedStats.achievedFps,
                        maxGapMs = computedStats.maxGapMs,
                        logExportNote = logExportNote,
                        stopReason = finalReason,
                        message = formatDoneMessage(finalReason, computedPlan, computedStats, colorSaved)
                    )
                )
            }
        } catch (e: CancellationException) {
            stopReason.compareAndSet(null, DumpStopReason.CAPTURE_STOPPED)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "BigMap burst dump failed", e)
            stopReason.compareAndSet(null, DumpStopReason.ERROR)
            failureMessage = e.message ?: e.javaClass.simpleName
        } finally {
            withContext(NonCancellable) {
                capturing.set(false)
                watchdogJob?.cancel()
                watchdogJob = null
                if (captureListenerAttached) {
                    onCapturePhaseFinished()
                }
                synchronized(captureLock) {
                    scratchRgbaBuf = null
                    for (i in slots.indices) slots[i] = null
                    slots = emptyArray()
                    limiter = null
                }
                if (failureMessage != null) {
                    val st = activePlan?.let { baseState(it) } ?: defaultState()
                    BigMapFrameDumpStatus.publish(
                        st.copy(
                            phase = DumpPhase.ERROR,
                            folderName = folderName,
                            capturedFrames = stats?.capturedFrames ?: capturedCount,
                            encodedFrames = encodedCount,
                            droppedFrames = stats?.droppedFrames ?: totalDropped,
                            achievedFps = stats?.achievedFps,
                            maxGapMs = stats?.maxGapMs,
                            logExportNote = logExportNote,
                            stopReason = DumpStopReason.ERROR,
                            message = null,
                            error = failureMessage
                        )
                    )
                }
                onSessionFinished()
            }
        }
    }

    private fun defaultState() = BigMapFrameDumpState(
        startDelaySec = (config.startDelayMs / 1000L).toInt(),
        maxDurationSec = (config.maxDurationMs / 1000L).toInt(),
        memoryBudgetMb = (config.memoryBudgetBytes / (1024L * 1024L)).toInt()
    )

    private fun baseState(plan: BurstPoolPlan) = defaultState().copy(
        poolCapacityFrames = plan.maxFrames,
        dumpWidth = plan.width,
        dumpHeight = plan.height
    )

    private fun formatDoneMessage(
        reason: DumpStopReason,
        plan: BurstPoolPlan,
        stats: CaptureTimingStats,
        colorSaved: Int
    ): String {
        val reasonText = when (reason) {
            DumpStopReason.BUFFER_FULL -> "Автостоп: заполнен буфер RAM (${plan.maxFrames} кадр.)"
            DumpStopReason.MAX_DURATION -> "Автостоп: прошло ${config.maxDurationMs / 1000L} с"
            DumpStopReason.RESIZE -> "Автостоп: изменился размер кадра"
            DumpStopReason.CAPTURE_STOPPED -> "Остановлено вместе с захватом экрана"
            else -> "Дамп завершён"
        }
        return String.format(
            Locale.US,
            "%s. Сохранено %d серых + %d цветных PNG (%.1f fps, макс. разрыв %.1f мс, пропущено %d).",
            reasonText,
            stats.capturedFrames,
            colorSaved,
            stats.achievedFps,
            stats.maxGapMs,
            stats.droppedFrames
        )
    }

    private fun encodeMatToPngFile(
        resolver: ContentResolver,
        folderUri: Uri,
        fileName: String,
        mat: Mat,
        outBuf: MatOfByte
    ) {
        if (!Imgcodecs.imencode(".png", mat, outBuf)) {
            throw IOException("Не удалось закодировать PNG $fileName")
        }
        val bytes = outBuf.toArray()
        var created: Uri? = null
        var complete = false
        try {
            val document = DocumentsContract.createDocument(resolver, folderUri, "image/png", fileName)
                ?: throw IOException("Не удалось создать файл $fileName")
            created = document
            resolver.openOutputStream(document, "w")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw IOException("Не удалось открыть поток записи для $fileName")
            complete = true
        } finally {
            val partial = created
            if (!complete && partial != null) {
                runCatching { DocumentsContract.deleteDocument(resolver, partial) }
            }
        }
    }

    private fun writeTextFile(
        resolver: ContentResolver,
        folderUri: Uri,
        fileName: String,
        mimeType: String,
        content: String
    ) {
        var created: Uri? = null
        var complete = false
        try {
            val document = DocumentsContract.createDocument(resolver, folderUri, mimeType, fileName)
                ?: throw IOException("Не удалось создать файл $fileName")
            created = document
            resolver.openOutputStream(document, "w")?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: throw IOException("Не удалось открыть поток записи для $fileName")
            complete = true
        } finally {
            val partial = created
            if (!complete && partial != null) {
                runCatching { DocumentsContract.deleteDocument(resolver, partial) }
            }
        }
    }

    companion object {
        private const val TAG = "LuxinFrameDump"
    }
}
