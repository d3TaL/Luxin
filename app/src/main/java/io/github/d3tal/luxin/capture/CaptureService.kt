package io.github.d3tal.luxin.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import android.view.WindowManager
import io.github.d3tal.luxin.overlay.MinimapCalibrationStore
import io.github.d3tal.luxin.overlay.OverlayService
import io.github.d3tal.luxin.overlay.RingCalibration
import io.github.d3tal.luxin.tracker.LiveTrackingState
import io.github.d3tal.luxin.tracker.LiveTrackingStatus
import io.github.d3tal.luxin.tracker.NativeSplitTracker
import io.github.d3tal.luxin.tracker.PlayerArrowSignal
import io.github.d3tal.luxin.tracker.BigMapConfirmation
import io.github.d3tal.luxin.tracker.BigMapGeometryDecoder
import io.github.d3tal.luxin.tracker.bigMapFrameIsCurrent
import io.github.d3tal.luxin.tracker.bigMapGeometryRemainingMs
import io.github.d3tal.luxin.tracker.LiveTrackingCadence
import io.github.d3tal.luxin.tracker.LiveProcessingRate
import io.github.d3tal.luxin.tracker.ReferencePackStore
import io.github.d3tal.luxin.tracker.TrackingFrameDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor

// captureMs is snapshot processing INCLUDING the wait and PNG write, not end-to-end display latency.
data class CaptureResult(
    val timestamp: String,
    val width: Int,
    val height: Int,
    val overlayLeaked: Boolean,
    val magentaPixelCount: Int,
    val captureMs: Long,
    val filePath: String,
    val error: String? = null,
    val markerChecked: Boolean = false,
    val markerCheckNote: String = "",
    val hiddenRequested: Boolean = false,
    val frameTimestampNs: Long = 0
)

class CaptureService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val imageLock = Any()
    private val snapshotBusy = AtomicBoolean(false)
    private var snapshotJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    // Only accessed under imageLock, including acquisition/copy/close.
    private var imageReader: ImageReader? = null
    private var readerWidth = 0
    private var readerHeight = 0
    private var generation = 0L
    private var stopping = false
    private var receivedContentSize = false
    @Volatile private var capturedContentVisible = true
    @Volatile private var trackingRevision = 0L
    private var trackingJob: Job? = null
    private var geometryExpiryJob: Job? = null
    // Epoch and status publications are confined to Main, preventing late native results after Stop.
    private var trackingEpoch = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = stopCapture()

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            capturedContentVisible = isVisible
            if (!isVisible) trackingRevision++
            val previous = LiveTrackingStatus.state.value
            if (!isVisible && previous.enabled) {
                LiveTrackingStatus.publish(LiveTrackingState(enabled = true, status = "paused",
                    message = "Захватываемое приложение не видно", packTitle = previous.packTitle))
            }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (stopping || mediaProjection == null) return
            receivedContentSize = true
            try {
                resizeCapture(width, height)
            } catch (e: Exception) {
                reportError("Изменение размера: ${e.message}")
                stopCapture()
            }
        }
    }

    companion object {
        private const val TAG = "LuxinCapture"
        const val ACTION_START = "io.github.d3tal.luxin.capture.ACTION_START"
        const val ACTION_STOP = "io.github.d3tal.luxin.capture.ACTION_STOP"
        const val ACTION_TAKE_SNAPSHOT = "io.github.d3tal.luxin.capture.ACTION_TAKE_SNAPSHOT"
        const val ACTION_TRACK_START = "io.github.d3tal.luxin.capture.ACTION_TRACK_START"
        const val ACTION_TRACK_STOP = "io.github.d3tal.luxin.capture.ACTION_TRACK_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_HIDE_OVERLAY = "extra_hide_overlay"
        private const val CHANNEL = "luxin_capture_channel"
        private const val NOTIFICATION_ID = 1001
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        private val _latestResult = MutableStateFlow<CaptureResult?>(null)
        val latestResult: StateFlow<CaptureResult?> = _latestResult.asStateFlow()

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).apply { action = ACTION_STOP })
        }

        fun startTracking(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).apply { action = ACTION_TRACK_START })
        }

        fun stopTracking(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).apply { action = ACTION_TRACK_STOP })
        }

        fun takeSnapshot(context: Context, hideOverlayDuringCapture: Boolean = false) {
            context.startService(Intent(context, CaptureService::class.java).apply {
                action = ACTION_TAKE_SNAPSHOT
                putExtra(EXTRA_HIDE_OVERLAY, hideOverlayDuringCapture)
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        requireNotNull(getSystemService(NotificationManager::class.java)).createNotificationChannel(
            NotificationChannel(CHANNEL, "Luxin Screen Capture", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                if (code != 0 && data != null) startCapture(code, data) else {
                    reportError("Не получено разрешение MediaProjection")
                    stopSelf()
                }
            }
            ACTION_STOP -> stopCapture()
            ACTION_TRACK_START -> {
                if (_isRunning.value) startLiveTracking() else {
                    LiveTrackingStatus.publish(LiveTrackingState(status = "error", message = "Сначала запустите захват экрана"))
                    stopSelf()
                }
            }
            ACTION_TRACK_STOP -> {
                stopLiveTracking()
                if (!_isRunning.value) stopSelf()
            }
            ACTION_TAKE_SNAPSHOT -> {
                if (_isRunning.value) captureFrame(intent.getBooleanExtra(EXTRA_HIDE_OVERLAY, false)) else {
                    reportError("Сначала запустите захват экрана")
                    stopSelf()
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (mediaProjection != null) return // Never reuse one consent token to create a second display.
        stopping = false
        receivedContentSize = false
        capturedContentVisible = true
        try {
            val notification = Notification.Builder(this, CHANNEL)
                .setContentTitle("Luxin Capture Active")
                .setContentText("Снимок доступен через HUD поверх игры")
                .setSmallIcon(android.R.drawable.ic_menu_camera).build()
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val manager = requireNotNull(getSystemService(MediaProjectionManager::class.java))
            val projection = requireNotNull(manager.getMediaProjection(resultCode, data))
            projection.registerCallback(projectionCallback, mainHandler)
            mediaProjection = projection
            val bounds = requireNotNull(getSystemService(WindowManager::class.java)).maximumWindowMetrics.bounds
            val dpi = resources.displayMetrics.densityDpi
            synchronized(imageLock) {
                val reader = ImageReader.newInstance(bounds.width(), bounds.height(), PixelFormat.RGBA_8888, 3)
                imageReader = reader
                readerWidth = bounds.width()
                readerHeight = bounds.height()
                virtualDisplay = projection.createVirtualDisplay(
                    "LuxinCaptureSpike", readerWidth, readerHeight, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, mainHandler
                )
                generation++
            }
            _isRunning.value = true
            mainHandler.postDelayed({ if (_isRunning.value) captureFrame(false) }, 800)
        } catch (e: Exception) {
            reportError("${e.javaClass.simpleName}: ${e.message}")
            stopCapture()
        }
    }

    /** Resize the EXISTING display; Android 14+ permits only one createVirtualDisplay per grant. */
    private fun resizeCapture(width: Int, height: Int) {
        require(width > 0 && height > 0)
        synchronized(imageLock) {
            val display = virtualDisplay ?: return
            if (readerWidth == width && readerHeight == height) return
            val replacement = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            try {
                display.resize(width, height, resources.displayMetrics.densityDpi)
                display.surface = replacement.surface
            } catch (e: Exception) {
                replacement.close()
                throw e
            }
            val old = imageReader
            imageReader = replacement
            readerWidth = width
            readerHeight = height
            generation++
            old?.close()
            trackingRevision++
            val previous = LiveTrackingStatus.state.value
            if (previous.bigMapGeometry != null) {
                LiveTrackingStatus.publish(LiveTrackingState(enabled = previous.enabled, status = "paused",
                    message = "Размер кадра изменился", packTitle = previous.packTitle))
            }
            Log.i(TAG, "Capture resized to ${width}x$height, generation=$generation")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // App-only capture may not match the display: its own callback takes precedence.
        if (_isRunning.value && !receivedContentSize) {
            val b = requireNotNull(getSystemService(WindowManager::class.java)).maximumWindowMetrics.bounds
            try {
                resizeCapture(b.width(), b.height())
            } catch (e: Exception) {
                reportError("Ориентация: ${e.message}")
                stopCapture()
            }
        }
    }

    private data class Pixels(val width: Int, val height: Int, val bytes: ByteBuffer,
                              val timestampNs: Long, val generation: Long)

    private fun readFreshPixels(afterNs: Long): Pixels? = synchronized(imageLock) {
        val image = imageReader?.acquireLatestImage() ?: return@synchronized null
        try {
            if (image.timestamp <= afterNs) return@synchronized null
            val crop = image.cropRect
            val plane = image.planes[0]
            Pixels(crop.width(), crop.height(), RgbaPlane.copyTight(plane.buffer, crop.width(), crop.height(),
                plane.rowStride, plane.pixelStride, crop.left, crop.top), image.timestamp, generation)
        } finally {
            image.close()
        }
    }

    private fun captureFrame(hideOverlay: Boolean) {
        // Avoid concurrent ImageReader acquisition, hide/show races and same-second PNG overwrites.
        if (!snapshotBusy.compareAndSet(false, true)) {
            Log.i(TAG, "Snapshot already in progress; ignoring duplicate request")
            return
        }
        trackingRevision++
        val previous = LiveTrackingStatus.state.value
        if (previous.enabled) LiveTrackingStatus.publish(LiveTrackingState(enabled = true, status = "paused",
            message = "Пауза: ручной снимок", packTitle = previous.packTitle))
        snapshotJob = scope.launch {
            val startMs = SystemClock.elapsedRealtime()
            var bitmap: Bitmap? = null
            var markerHidden = false
            try {
                val markerWasShown = OverlayService.isShowing.value
                if (hideOverlay) {
                    OverlayService.setMarkerTemporaryVisibilityAwait(false)
                    markerHidden = true
                }
                // Flush the pre-request image. Waiting alone does not guarantee a fresh frame.
                val barrier = System.nanoTime()
                synchronized(imageLock) { imageReader?.acquireLatestImage()?.close() }
                if (hideOverlay) delay(45) // Diagnostic delay, NOT a guarantee of compositor latency.
                val deadline = SystemClock.elapsedRealtime() + 1000
                var pixels: Pixels? = null
                while (pixels == null && SystemClock.elapsedRealtime() < deadline) {
                    ensureActive()
                    pixels = readFreshPixels(barrier)
                    if (pixels == null) delay(16)
                }
                val frame = pixels ?: error("Нет свежего кадра. Подвигайте экран и повторите снимок.")
                if (markerHidden) {
                    OverlayService.setMarkerTemporaryVisibilityAwait(true)
                    markerHidden = false
                }
                val cleanBitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                bitmap = cleanBitmap
                cleanBitmap.copyPixelsFromBuffer(frame.bytes)

                val display = requireNotNull(getSystemService(WindowManager::class.java)).maximumWindowMetrics.bounds
                val ring = MinimapCalibrationStore.current.value
                    ?: MinimapCalibrationStore.load(this@CaptureService, minOf(display.width(), display.height()))
                val left = floor(ring.cx - ring.r).toInt()
                val top = floor(ring.cy - ring.r).toInt()
                val right = ceil(ring.cx + ring.r).toInt()
                val bottom = ceil(ring.cy + ring.r).toInt()
                val sameGeometry = display.width() == frame.width && display.height() == frame.height
                val inside = left >= 0 && top >= 0 && right <= frame.width && bottom <= frame.height
                val checked = markerWasShown && sameGeometry && inside
                var count = 0
                if (checked) {
                    val region = IntArray((right - left) * (bottom - top))
                    cleanBitmap.getPixels(region, 0, right - left, left, top, right - left, bottom - top)
                    count = region.count { Color.red(it) > 180 && Color.blue(it) > 180 && Color.green(it) < 90 }
                }
                val note = when {
                    !markerWasShown -> "Тестовая метка не включена"
                    !sameGeometry -> "Кадр и экран имеют разную геометрию: нужна привязка координат"
                    !inside -> "Калиброванное кольцо вне границ кадра"
                    hideOverlay -> "Скрывалась только тестовая метка; остальные окна не проверены"
                    else -> "Проверена область кольца; отсутствие метки не доказывает изоляцию захвата"
                }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val tag = if (hideOverlay) "hidden" else "normal"
                val file = File(filesDir, "capture_${tag}_${stamp}_${UUID.randomUUID()}.png")
                withContext(Dispatchers.IO) {
                    val atomic = AtomicFile(file)
                    val stream = atomic.startWrite()
                    try {
                        check(cleanBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG encoding failed" }
                        atomic.finishWrite(stream)
                    } catch (e: Exception) {
                        atomic.failWrite(stream)
                        throw e
                    }
                }
                ensureActive()
                check(synchronized(imageLock) { generation == frame.generation }) { "Размер захвата изменился; повторите снимок" }
                _latestResult.value = CaptureResult(
                    timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                    width = frame.width, height = frame.height, overlayLeaked = checked && count > 50,
                    magentaPixelCount = count, captureMs = SystemClock.elapsedRealtime() - startMs,
                    filePath = file.absolutePath, markerChecked = checked, markerCheckNote = note,
                    hiddenRequested = hideOverlay, frameTimestampNs = frame.timestampNs
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("${e.javaClass.simpleName}: ${e.message}", SystemClock.elapsedRealtime() - startMs)
            } finally {
                if (markerHidden) withContext(NonCancellable) { OverlayService.setMarkerTemporaryVisibilityAwait(true) }
                bitmap?.recycle()
                snapshotBusy.set(false)
            }
        }
    }

    private fun startLiveTracking() {
        if (trackingJob?.isActive == true) return
        val epoch = ++trackingEpoch
        LiveTrackingStatus.publish(LiveTrackingState(enabled = true, status = "loading", message = "Загрузка эталонов…"))
        trackingJob = scope.launch {
            var native: NativeSplitTracker? = null
            try {
                val pack = withContext(Dispatchers.IO) { ReferencePackStore.loadInstalled(this@CaptureService) }
                    ?: error("Импортируйте ZIP-набор эталонов на экране Luxin")
                val engine = NativeSplitTracker()
                native = engine
                for ((index, layer) in pack.manifest.layers.withIndex()) {
                    ensureActive()
                    publishLive(epoch, LiveTrackingState(enabled = true, status = "loading", packTitle = pack.manifest.title,
                        message = "Эталон ${index + 1}/${pack.manifest.layers.size}: ${layer.name}"))
                    check(engine.addLayerReference(layer.name, layer.space, layer.id,
                        File(pack.directory, layer.image).absolutePath,
                        File(pack.directory, layer.activeMask).absolutePath,
                        File(pack.directory, layer.contextMask).absolutePath,
                        layer.unitsPerPx, layer.originX, layer.originY)) { "Не удалось загрузить эталон ${layer.name}" }
                }
                val names = pack.manifest.layers.associate { "${it.space}/${it.id}" to it.name }
                var lastTimestamp = 0L
                var lastGeneration = -1L
                var lastRevision = -1L
                var previousProcessMs = 0L
                var displayShortSide = -1
                var paused = true
                var lastScreenMode = "unknown"
                val bigMapRate = LiveProcessingRate()
                val bigMap = BigMapConfirmation()
                suspend fun waiting(message: String, width: Int = 0, height: Int = 0, screenMode: String = "unknown") {
                    if (screenMode != "big_map" && screenMode != "big_map_pending") {
                        bigMap.reset()
                        engine.resetBigMap()
                        lastScreenMode = "unknown"
                        bigMapRate.reset()
                    }
                    if (!paused) engine.reset()
                    paused = true
                    previousProcessMs = 0
                    publishLive(epoch, LiveTrackingState(enabled = true, status = "paused", message = message,
                        packTitle = pack.manifest.title, frameWidth = width, frameHeight = height, screenMode = screenMode))
                }
                while (_isRunning.value) {
                    ensureActive()
                    val loopStart = SystemClock.elapsedRealtime()
                    val frameRevision = trackingRevision
                    if (lastRevision != frameRevision) {
                        engine.reset()
                        bigMap.reset()
                        lastScreenMode = "unknown"
                        bigMapRate.reset()
                        lastRevision = frameRevision
                    }
                    if (!capturedContentVisible || snapshotBusy.get()) {
                        waiting(if (!capturedContentVisible) "Захватываемое приложение не видно" else "Пауза: ручной снимок")
                        delay(100)
                        continue
                    }
                    val frame = readFreshPixels(lastTimestamp)
                    if (frame == null) {
                        // acquireLatestImage may have no NEW frame at 15 Hz. Do not treat a
                        // short supply gap as a mode rejection or run the same image twice.
                        val sameGeometry = synchronized(imageLock) { generation == lastGeneration }
                        if (LiveTrackingCadence.keepBigMapAnchor(lastScreenMode, lastTimestamp,
                                System.nanoTime(), sameGeometry)) {
                            delay(LiveTrackingCadence.delayMs(lastScreenMode,
                                SystemClock.elapsedRealtime() - loopStart))
                            continue
                        }
                        waiting("Ожидание нового кадра")
                        delay(100)
                        continue
                    }
                    lastTimestamp = frame.timestampNs
                    val ageNs = System.nanoTime() - frame.timestampNs
                    if (ageNs !in 0L..1_000_000_000L) {
                        waiting("Кадр устарел; ожидаю обновление", frame.width, frame.height)
                        delay(100)
                        continue
                    }
                    val bounds = requireNotNull(getSystemService(WindowManager::class.java)).maximumWindowMetrics.bounds
                    val shortSide = minOf(bounds.width(), bounds.height())
                    val displayAspect = maxOf(bounds.width(), bounds.height()).toDouble() / shortSide
                    if (frame.width <= frame.height || kotlin.math.abs(frame.width.toDouble() / frame.height - displayAspect) > 0.04) {
                        waiting("Нужна полноэкранная альбомная игра без чёрных полей", frame.width, frame.height)
                        delay(250)
                        continue
                    }
                    if (displayShortSide != shortSide) {
                        MinimapCalibrationStore.load(this@CaptureService, shortSide)
                        displayShortSide = shortSide
                    }
                    val screenRing = MinimapCalibrationStore.current.value ?: RingCalibration.defaultFor(shortSide)
                    // This spike supports a full-screen landscape game with uniform downscaling.
                    // Aspect checking is not a general crop/inset-origin detector; windowed capture is untested.
                    val k = frame.height.toFloat() / shortSide
                    val ring = RingCalibration(screenRing.cx * k, screenRing.cy * k, screenRing.r * k)
                    val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.copyPixelsFromBuffer(frame.bytes)
                        if (lastGeneration != frame.generation) {
                            engine.reset()
                            bigMap.reset()
                            bigMapRate.reset()
                            lastGeneration = frame.generation
                        }
                        val screenMode = bigMap.observe(engine.isBigMap(bitmap))
                        lastScreenMode = screenMode
                        if (screenMode == "unknown") {
                            engine.resetBigMap()
                            bigMapRate.reset()
                        }
                        ensureActive()
                        if (synchronized(imageLock) { generation != frame.generation }) {
                            waiting("Размер кадра изменился", frame.width, frame.height)
                            continue
                        }
                        if (screenMode == "big_map" || screenMode == "big_map_pending") {
                            if (screenMode == "big_map_pending") {
                                bigMapRate.reset()
                                waiting("Подтверждение большой карты", frame.width, frame.height, screenMode)
                            }
                            // Do not publish a temporary unknown before every follow result.
                            // Clear minimap state on entry only; final results below remain fail-closed.
                            if (!paused) engine.reset()
                            paused = true
                            previousProcessMs = 0
                            if (screenMode == "big_map") {
                                val bigMapFps = bigMapRate.record(loopStart)
                                val start = SystemClock.elapsedRealtime()
                                val decoded = BigMapGeometryDecoder.decode(engine.registerBigMapJson(
                                    bitmap, frame.timestampNs / 1_000_000_000.0))
                                ensureActive()
                                decoded.geometry?.let {
                                    check(it.frameWidth == frame.width && it.frameHeight == frame.height &&
                                        kotlin.math.abs(it.timestampSeconds - frame.timestampNs / 1_000_000_000.0) < 1e-6) {
                                        "Геометрия не соответствует захваченному кадру"
                                    }
                                }
                                val currentAge = System.nanoTime() - frame.timestampNs
                                Log.d(TAG, "BigMap phase=${decoded.phase} mode=${decoded.mode} " +
                                    "nativeMs=${SystemClock.elapsedRealtime() - start} ageMs=${currentAge / 1_000_000} " +
                                    "actualFps=$bigMapFps targetFps=15")
                                if (synchronized(imageLock) { generation != frame.generation } ||
                                    currentAge !in 0L..1_000_000_000L) {
                                    waiting("Привязка устарела; ожидаю обновление", frame.width, frame.height,
                                        screenMode = "big_map")
                                } else {
                                    publishLive(epoch, LiveTrackingState(enabled = true, status = "paused",
                                        message = when {
                                            decoded.geometry != null -> "Большая карта подтверждена (этаж неизвестен)"
                                            decoded.phase == "confirming" -> "Привязка-кандидат; проверяю свежий кадр"
                                            decoded.phase == "moving" -> "Карта движется; геометрия скрыта"
                                            decoded.phase == "acquiring" -> "Ожидаю повторной привязки"
                                            else -> "Открыта большая карта; привязка ${decoded.mode} (${decoded.phase})"
                                        },
                                        packTitle = pack.manifest.title, frameWidth = frame.width, frameHeight = frame.height,
                                        screenMode = screenMode, bigMapGeometry = decoded.geometry,
                                        bigMapGeometryMode = decoded.phase, inliers = decoded.geometry?.inliers ?: 0,
                                        nativeMs = SystemClock.elapsedRealtime() - start, frameAgeMs = currentAge / 1_000_000,
                                        actualFps = bigMapFps),
                                        expectedGeneration = frame.generation, timestampNs = frame.timestampNs,
                                        expectedRevision = frameRevision)
                                }
                            }
                            delay(LiveTrackingCadence.delayMs(screenMode, SystemClock.elapsedRealtime() - loopStart))
                            continue
                        }
                        val a = (26f * frame.height / 1440f).toInt().coerceAtLeast(2)
                        val x = ring.cx.toInt() - a
                        val y = ring.cy.toInt() - a
                        val side = 2 * a + 1
                        if (x < 0 || y < 0 || x + side > frame.width || y + side > frame.height) {
                            waiting("Калибровка вне границ кадра", frame.width, frame.height)
                            delay(250)
                            continue
                        }
                        val arrow = IntArray(side * side)
                        bitmap.getPixels(arrow, 0, side, x, y, side, side)
                        if (!PlayerArrowSignal.present(arrow, frame.height)) {
                            waiting("Миникарта скрыта или стрелка игрока не найдена", frame.width, frame.height)
                            delay(250)
                            continue
                        }
                        paused = false
                        val startNative = SystemClock.elapsedRealtime()
                        val raw = engine.updateFrameJson(bitmap, frame.timestampNs / 1_000_000_000.0,
                            ringXyr = floatArrayOf(ring.cx, ring.cy, ring.r))
                        val nativeMs = SystemClock.elapsedRealtime() - startNative
                        ensureActive()
                        if (synchronized(imageLock) { generation != frame.generation }) {
                            waiting("Размер кадра изменился", frame.width, frame.height)
                            continue
                        }
                        val decoded = TrackingFrameDecoder.decode(raw, pack.manifest.mode == "position_only")
                        val fps = if (previousProcessMs > 0 && loopStart > previousProcessMs)
                            1000.0 / (loopStart - previousProcessMs) else null
                        previousProcessMs = loopStart
                        val position = decoded.position
                        Log.d(TAG, "Live ${frame.width}x${frame.height} pos=${decoded.positionMode} " +
                            "x=${position?.x} y=${position?.y} layer=${decoded.layer ?: "unknown"} nativeMs=$nativeMs")
                        publishLive(epoch, LiveTrackingState(enabled = true,
                            status = if (position != null) "tracking" else "searching",
                            message = when {
                                position != null -> "Позиция найдена"
                                decoded.positionMode == "pending" -> "Подтверждение позиции"
                                decoded.positionMode == "ambiguous" -> "Неоднозначная позиция"
                                else -> "Поиск в выбранном наборе"
                            }, packTitle = pack.manifest.title, frameWidth = frame.width, frameHeight = frame.height,
                            position = position, positionMode = decoded.positionMode, layer = decoded.layer,
                            layerName = decoded.layer?.let { names["${position?.space}/$it"] ?: it },
                            layerMode = decoded.layerMode, inliers = decoded.inliers, nativeMs = nativeMs,
                            frameAgeMs = if (ageNs >= 0) ageNs / 1_000_000 else null, actualFps = fps, screenMode = "minimap"))
                    } finally {
                        bitmap.recycle()
                    }
                    delay(LiveTrackingCadence.delayMs("minimap", SystemClock.elapsedRealtime() - loopStart))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Live tracker failed", e)
                publishLive(epoch, LiveTrackingState(status = "error", message = e.message ?: e.javaClass.simpleName))
            } catch (e: LinkageError) {
                Log.e(TAG, "Native library unavailable", e)
                publishLive(epoch, LiveTrackingState(status = "error", message = "Нативная библиотека недоступна: ${e.message}"))
            } finally {
                native?.close()
            }
        }
    }

    private suspend fun publishLive(epoch: Long, state: LiveTrackingState,
                                   expectedGeneration: Long? = null, timestampNs: Long? = null,
                                   expectedRevision: Long? = null) = withContext(Dispatchers.Main.immediate) {
        if (trackingEpoch == epoch) {
            geometryExpiryJob?.cancel()
            val blocked = state.enabled && state.status != "loading" && (!capturedContentVisible || snapshotBusy.get())
            val obsolete = expectedGeneration != null && (timestampNs == null || expectedRevision == null ||
                !bigMapFrameIsCurrent(expectedRevision, trackingRevision, expectedGeneration,
                    synchronized(imageLock) { generation }, timestampNs, System.nanoTime()))
            LiveTrackingStatus.publish(if (obsolete) LiveTrackingState(enabled = true, status = "paused",
                message = "Привязка устарела; ожидаю обновление", packTitle = state.packTitle)
            else if (blocked) LiveTrackingState(enabled = true, status = "paused",
                message = if (!capturedContentVisible) "Захватываемое приложение не видно" else "Пауза: ручной снимок",
                packTitle = state.packTitle) else state)
            val published = LiveTrackingStatus.state.value
            val geometry = published.bigMapGeometry
            if (geometry != null) {
                geometryExpiryJob = scope.launch(Dispatchers.Main.immediate) {
                    val remaining = bigMapGeometryRemainingMs(geometry.timestampSeconds, System.nanoTime())
                    delay(remaining)
                    if (trackingEpoch == epoch && LiveTrackingStatus.state.value === published) {
                        LiveTrackingStatus.publish(published.copy(bigMapGeometry = null, bigMapGeometryMode = "stale",
                            message = "Привязка устарела; ожидаю обновление"))
                    }
                }
            }
        }
    }

    private fun stopLiveTracking() {
        trackingEpoch++
        geometryExpiryJob?.cancel()
        geometryExpiryJob = null
        trackingJob?.cancel()
        trackingJob = null
        LiveTrackingStatus.publish(LiveTrackingState())
    }

    private fun reportError(message: String, durationMs: Long = 0) {
        Log.e(TAG, message)
        _latestResult.value = CaptureResult(
            timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()), width = 0, height = 0,
            overlayLeaked = false, magentaPixelCount = 0, captureMs = durationMs, filePath = "", error = message
        )
    }

    private fun stopCapture() {
        if (stopping) return
        stopping = true
        _isRunning.value = false
        stopLiveTracking()
        mainHandler.removeCallbacksAndMessages(null)
        snapshotJob?.cancel()
        synchronized(imageLock) {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            generation++
        }
        val projection = mediaProjection
        mediaProjection = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        scope.cancel()
        super.onDestroy()
    }
}
