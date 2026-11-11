package io.github.d3tal.luxin.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import io.github.d3tal.luxin.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CaptureResult(
    val timestamp: String,
    val width: Int,
    val height: Int,
    val overlayLeaked: Boolean,
    val magentaPixelCount: Int,
    val captureMs: Long,
    val filePath: String,
    val error: String? = null
)

class CaptureService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture()
        }
    }

    companion object {
        const val ACTION_START = "io.github.d3tal.luxin.capture.ACTION_START"
        const val ACTION_STOP = "io.github.d3tal.luxin.capture.ACTION_STOP"
        const val ACTION_TAKE_SNAPSHOT = "io.github.d3tal.luxin.capture.ACTION_TAKE_SNAPSHOT"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_HIDE_OVERLAY = "extra_hide_overlay"

        private const val NOTIFICATION_CHANNEL_ID = "luxin_capture_channel"
        private const val NOTIFICATION_ID = 1001

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _latestResult = MutableStateFlow<CaptureResult?>(null)
        val latestResult: StateFlow<CaptureResult?> = _latestResult.asStateFlow()

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).apply {
                action = ACTION_STOP
            })
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
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != 0 && data != null) {
                    startCapture(resultCode, data)
                }
            }
            ACTION_STOP -> stopCapture()
            ACTION_TAKE_SNAPSHOT -> {
                val hideOverlay = intent.getBooleanExtra(EXTRA_HIDE_OVERLAY, false)
                captureFrame(hideOverlay)
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Luxin Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active foreground capture for game sync"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Luxin Capture Active")
            .setContentText("Захват экрана активен. Используйте кнопку HUD поверх игры.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, data) ?: run {
                _isRunning.value = false
                return
            }

            // Required on Android 14+ (targetSdk 34+) before createVirtualDisplay
            projection.registerCallback(projectionCallback, mainHandler)
            mediaProjection = projection

            recreateVirtualDisplay()
            _isRunning.value = true

            scope.launch {
                delay(800)
                captureFrame(hideOverlay = false)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            _latestResult.value = CaptureResult(
                timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                width = 0,
                height = 0,
                overlayLeaked = false,
                magentaPixelCount = 0,
                captureMs = 0,
                filePath = "",
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
            stopCapture()
        }
    }

    private fun recreateVirtualDisplay() {
        val projection = mediaProjection ?: return
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        virtualDisplay?.release()
        imageReader?.close()

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "LuxinCaptureSpike",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )
    }

    private fun captureFrame(hideOverlay: Boolean = false) {
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            try {
                if (hideOverlay) {
                    OverlayService.setMarkerTemporaryVisibility(false)
                    delay(45) // Wait ~2-3 display frames at 60/120Hz for compositor to clear overlay
                }

                val reader = imageReader ?: run {
                    if (hideOverlay) OverlayService.setMarkerTemporaryVisibility(true)
                    return@launch
                }

                // Drain older buffered frame if present so we inspect the freshest frame
                var image = reader.acquireLatestImage()
                if (image == null) {
                    delay(50)
                    image = reader.acquireLatestImage()
                }

                if (hideOverlay) {
                    OverlayService.setMarkerTemporaryVisibility(true)
                }

                if (image == null) {
                    _latestResult.value = CaptureResult(
                        timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                        width = 0,
                        height = 0,
                        overlayLeaked = false,
                        magentaPixelCount = 0,
                        captureMs = SystemClock.elapsedRealtime() - t0,
                        filePath = "",
                        error = "Кадр не обновился (image == null). Подвигайте экран и повторите."
                    )
                    return@launch
                }

                val width = image.width
                val height = image.height
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val cleanBitmap = if (rowPadding == 0) {
                    bitmap
                } else {
                    Bitmap.createBitmap(bitmap, 0, 0, width, height)
                }

                // Check for test overlay leakage: inspect top-left minimap region [x: 100..470, y: 10..360]
                var magentaCount = 0
                val xStart = 100.coerceAtMost(width - 1)
                val xEnd = 470.coerceAtMost(width)
                val yStart = 10.coerceAtMost(height - 1)
                val yEnd = 360.coerceAtMost(height)

                for (y in yStart until yEnd) {
                    for (x in xStart until xEnd) {
                        val px = cleanBitmap.getPixel(x, y)
                        val r = Color.red(px)
                        val g = Color.green(px)
                        val b = Color.blue(px)
                        if (r > 180 && b > 180 && g < 90) {
                            magentaCount++
                        }
                    }
                }

                val captureMs = SystemClock.elapsedRealtime() - t0

                // Save captured frame to app files directory for inspection
                val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val modeTag = if (hideOverlay) "hidden" else "normal"
                val outFile = File(filesDir, "capture_${modeTag}_$timeStr.png")
                FileOutputStream(outFile).use { out ->
                    cleanBitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }

                _latestResult.value = CaptureResult(
                    timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                    width = width,
                    height = height,
                    overlayLeaked = magentaCount > 50,
                    magentaPixelCount = magentaCount,
                    captureMs = captureMs,
                    filePath = outFile.absolutePath
                )

            } catch (e: Throwable) {
                if (hideOverlay) {
                    OverlayService.setMarkerTemporaryVisibility(true)
                }
                e.printStackTrace()
                _latestResult.value = CaptureResult(
                    timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                    width = 0,
                    height = 0,
                    overlayLeaked = false,
                    magentaPixelCount = 0,
                    captureMs = SystemClock.elapsedRealtime() - t0,
                    filePath = "",
                    error = "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    private fun stopCapture() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Throwable) {
        }
        _isRunning.value = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        stopCapture()
        super.onDestroy()
    }
}
