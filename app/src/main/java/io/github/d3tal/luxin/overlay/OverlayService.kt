package io.github.d3tal.luxin.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    companion object {
        const val ACTION_SHOW = "io.github.d3tal.luxin.overlay.ACTION_SHOW"
        const val ACTION_HIDE = "io.github.d3tal.luxin.overlay.ACTION_HIDE"

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

        if (overlayView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Create overlay over the in-game minimap area (top-left)
        val params = WindowManager.LayoutParams(
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
        }

        overlayView = object : View(this) {
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TEST_MARKER_COLOR
                style = Paint.Style.STROKE
                strokeWidth = 10f
            }

            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(60, 255, 0, 255)
                style = Paint.Style.FILL
            }

            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 28f
                textAlign = Paint.Align.CENTER
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f
                val cy = height / 2f
                val r = (width.coerceAtMost(height) / 2f) - 15f

                // Draw test ring and text
                canvas.drawCircle(cx, cy, r, fillPaint)
                canvas.drawCircle(cx, cy, r, strokePaint)
                canvas.drawText("LUXIN SPIKE", cx, cy - 10f, textPaint)
                canvas.drawText("TEST OVERLAY", cx, cy + 30f, textPaint)
            }
        }

        try {
            windowManager?.addView(overlayView, params)
            _isShowing.value = true
        } catch (e: Exception) {
            e.printStackTrace()
            _isShowing.value = false
            overlayView = null
        }
    }

    private fun hideOverlay() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        overlayView = null
        _isShowing.value = false
        stopSelf()
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }
}
