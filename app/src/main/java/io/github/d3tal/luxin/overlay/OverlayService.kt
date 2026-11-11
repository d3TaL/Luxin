package io.github.d3tal.luxin.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.d3tal.luxin.capture.CaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var markerOverlayView: View? = null
    private var controlHudView: View? = null

    companion object {
        const val ACTION_SHOW = "io.github.d3tal.luxin.overlay.ACTION_SHOW"
        const val ACTION_HIDE = "io.github.d3tal.luxin.overlay.ACTION_HIDE"

        @Volatile
        private var instance: OverlayService? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        // Distinctive marker color (bright magenta) used to verify if overlay leaks into capture
        val TEST_MARKER_COLOR = Color.rgb(255, 0, 255)

        private val _isShowing = MutableStateFlow(false)
        val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

        fun start(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW
            })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).apply {
                action = ACTION_HIDE
            })
        }

        fun setMarkerTemporaryVisibility(visible: Boolean) {
            mainHandler.post {
                instance?.markerOverlayView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> hideOverlay()
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            _isShowing.value = false
            return
        }

        if (markerOverlayView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 1. Untouchable marker overlay over the top-left minimap area
        val markerParams = WindowManager.LayoutParams(
            350,
            350,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 120
            y = 10
            // Android 12+ untouchable application overlays require alpha <= 0.8f
            alpha = 0.8f
        }

        markerOverlayView = object : View(this) {
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TEST_MARKER_COLOR
                style = Paint.Style.STROKE
                strokeWidth = 10f
            }

            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(70, 255, 0, 255)
                style = Paint.Style.FILL
            }

            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 26f
                textAlign = Paint.Align.CENTER
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f
                val cy = height / 2f
                val r = (width.coerceAtMost(height) / 2f) - 15f

                canvas.drawCircle(cx, cy, r, fillPaint)
                canvas.drawCircle(cx, cy, r, strokePaint)
                canvas.drawText("LUXIN SPIKE", cx, cy - 10f, textPaint)
                canvas.drawText("TEST OVERLAY", cx, cy + 28f, textPaint)
            }
        }

        // 2. Small floating HUD button (top-right) to trigger snapshot over the game
        val hudParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 140
        }

        val statusText = TextView(this).apply {
            text = "Luxin HUD: готов"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(8, 4, 8, 8)
        }

        val snapButton = Button(this).apply {
            text = "📸 Снимок (обычный)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setOnClickListener {
                CaptureService.takeSnapshot(context, hideOverlayDuringCapture = false)
            }
        }

        val cleanSnapButton = Button(this).apply {
            text = "✨ Снимок (скрыть метку)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setOnClickListener {
                CaptureService.takeSnapshot(context, hideOverlayDuringCapture = true)
            }
        }

        controlHudView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 24, 24, 28))
            setPadding(16, 12, 16, 12)
            addView(statusText)
            addView(snapButton)
            addView(cleanSnapButton)
        }

        try {
            windowManager?.addView(markerOverlayView, markerParams)
            windowManager?.addView(controlHudView, hudParams)
            _isShowing.value = true

            scope.launch {
                CaptureService.latestResult.collect { res ->
                    if (res != null) {
                        statusText.text = if (res.error != null) {
                            "Ошибка: ${res.error}"
                        } else if (res.overlayLeaked) {
                            "⚠️ В КАДРЕ (${res.magentaPixelCount} px, ${res.captureMs} мс)"
                        } else {
                            "✅ ЧИСТО (${res.magentaPixelCount} px, ${res.captureMs} мс)"
                        }
                        statusText.setTextColor(
                            if (res.error != null || res.overlayLeaked) Color.rgb(255, 110, 110)
                            else Color.rgb(110, 255, 130)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _isShowing.value = false
            markerOverlayView = null
            controlHudView = null
        }
    }

    private fun hideOverlay() {
        markerOverlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        controlHudView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        markerOverlayView = null
        controlHudView = null
        _isShowing.value = false
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        hideOverlay()
        super.onDestroy()
    }
}
