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
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import io.github.d3tal.luxin.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val filePath: String,
    val error: String? = null
)

class CaptureService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    companion object {
        const val ACTION_START = "io.github.d3tal.luxin.capture.ACTION_START"
        const val ACTION_STOP = "io.github.d3tal.luxin.capture.ACTION_STOP"
        const val ACTION_TAKE_SNAPSHOT = "io.github.d3tal.luxin.capture.ACTION_TAKE_SNAPSHOT"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

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

        fun takeSnapshot(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).apply {
                action = ACTION_TAKE_SNAPSHOT
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
            ACTION_TAKE_SNAPSHOT -> captureFrame()
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
            .setContentText("Screen capture is active. Tap app to inspect frames.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    private fun startCapture(resultCode: Int, data: Intent) {
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
        mediaProjection = projection

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "LuxinCaptureSpike",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        _isRunning.value = true

        // Capture initial frame after short delay for virtual display to initialize
        scope.launch {
            kotlinx.coroutines.delay(1000)
            captureFrame()
        }
    }

    private fun captureFrame() {
        val reader = imageReader ?: return
        scope.launch {
            try {
                val image = reader.acquireLatestImage()
                if (image == null) {
                    _latestResult.value = CaptureResult(
                        timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                        width = 0,
                        height = 0,
                        overlayLeaked = false,
                        magentaPixelCount = 0,
                        filePath = "",
                        error = "Кадр не готов (image == null). Убедитесь, что экран активен."
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

                // Check for test overlay leakage: inspect top-left minimap region [x: 100..450, y: 10..360]
                // The overlay draws bright magenta Color.rgb(255, 0, 255)
                var magentaCount = 0
                val targetColor = OverlayService.TEST_MARKER_COLOR
                val tr = Color.red(targetColor)
                val tg = Color.green(targetColor)
                val tb = Color.blue(targetColor)

                val xStart = 100.coerceAtMost(width - 1)
                val xEnd = 450.coerceAtMost(width)
                val yStart = 10.coerceAtMost(height - 1)
                val yEnd = 360.coerceAtMost(height)

                for (y in yStart until yEnd) {
                    for (x in xStart until xEnd) {
                        val px = cleanBitmap.getPixel(x, y)
                        val r = Color.red(px)
                        val g = Color.green(px)
                        val b = Color.blue(px)
                        // Magenta check: high red and blue, low green
                        if (r > 200 && b > 200 && g < 80) {
                            magentaCount++
                        }
                    }
                }

                // Save captured frame to app files directory for user inspection
                val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val outFile = File(filesDir, "capture_$timeStr.png")
                FileOutputStream(outFile).use { out ->
                    cleanBitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }

                _latestResult.value = CaptureResult(
                    timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                    width = width,
                    height = height,
                    overlayLeaked = magentaCount > 50,
                    magentaPixelCount = magentaCount,
                    filePath = outFile.absolutePath
                )

            } catch (e: Exception) {
                e.printStackTrace()
                _latestResult.value = CaptureResult(
                    timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                    width = 0,
                    height = 0,
                    overlayLeaked = false,
                    magentaPixelCount = 0,
                    filePath = "",
                    error = "Ошибка захвата: ${e.message}"
                )
            }
        }
    }

    private fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        _isRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
