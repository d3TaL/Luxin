package io.github.d3tal.luxin.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.d3tal.luxin.capture.CaptureService
import io.github.d3tal.luxin.tracker.BigMapGeometry
import io.github.d3tal.luxin.tracker.BigMapGeometryDecoder
import io.github.d3tal.luxin.tracker.BigMapLayerDetector
import io.github.d3tal.luxin.tracker.BigMapMarkerFilter
import io.github.d3tal.luxin.tracker.BigMapMarkerOverlay
import io.github.d3tal.luxin.tracker.LayerDecision
import io.github.d3tal.luxin.tracker.LiveTrackingState
import io.github.d3tal.luxin.tracker.LiveTrackingStatus
import io.github.d3tal.luxin.tracker.formatTrackingHud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

class OverlayService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var markerOverlayView: View? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private var bigMapMarkerOverlayView: View? = null
    private var bigMapMarkerOverlayParams: WindowManager.LayoutParams? = null
    private var bigMapMarkerOverlay: BigMapMarkerOverlay? = null
    private var controlHudView: View? = null
    private var calibrationText: TextView? = null
    private var stepButton: Button? = null

    private var shortSide = 1440
    private var calibration = RingCalibration.defaultFor(1440)
    private var stepIndex = 1

    companion object {
        const val ACTION_SHOW = "io.github.d3tal.luxin.overlay.ACTION_SHOW"
        const val ACTION_HIDE = "io.github.d3tal.luxin.overlay.ACTION_HIDE"

        private val STEPS_PX = intArrayOf(1, 3, 10, 30)

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

        /** Waits for the UI change, not for a compositor fence; capture must still await a fresh frame. */
        suspend fun setMarkerTemporaryVisibilityAwait(visible: Boolean) = withContext(Dispatchers.Main.immediate) {
            instance?.markerOverlayView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
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

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val bounds = wm.maximumWindowMetrics.bounds
        shortSide = minOf(bounds.width(), bounds.height())
        calibration = MinimapCalibrationStore.load(this, shortSide).constrained(bounds.width(), bounds.height())

        // 1. Untouchable ring over the minimap. Coordinates are real-display pixels, so the window
        //    must ignore the display cutout and system bars - otherwise it is shifted against the capture.
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Android 12+ untouchable application overlays require alpha <= 0.8f
            alpha = 0.8f
        }
        markerParams = params
        applyCalibrationToParams(params)

        val marker = object : View(this) {
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TEST_MARKER_COLOR
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }

            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(35, 255, 0, 255)
                style = Paint.Style.FILL
            }

            private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 255, 255, 0)
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f
                val cy = height / 2f
                val r = calibration.r

                canvas.drawCircle(cx, cy, r, fillPaint)
                canvas.drawCircle(cx, cy, r, strokePaint)
                canvas.drawLine(cx - 24f, cy, cx + 24f, cy, crossPaint)
                canvas.drawLine(cx, cy - 24f, cx, cy + 24f, crossPaint)
            }
        }
        markerOverlayView = marker

        // 3. Full-screen big-map marker overlay. Not touchable, transparent, coordinates match capture.
        val bigMapParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            alpha = 0.8f
        }
        bigMapMarkerOverlay = BigMapMarkerOverlay(this)
        bigMapMarkerOverlay?.setDisplayMetrics(resources.displayMetrics)
        bigMapMarkerOverlayView = bigMapMarkerOverlay
        bigMapMarkerOverlayParams = bigMapParams

        // 2. Floating HUD: manual calibration of the ring plus snapshot buttons
        val hudParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            x = 24
            y = 140
        }

        val statusText = TextView(this).apply {
            text = "Luxin HUD: готов"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(8, 4, 8, 8)
        }

        val calText = TextView(this).apply {
            setTextColor(Color.rgb(255, 230, 120))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(8, 0, 8, 6)
        }
        calibrationText = calText
        updateCalibrationLabel()

        fun hudButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onClick() }
        }

        fun row(vararg buttons: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { b ->
                addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        val stepBtn = hudButton("Шаг: ${STEPS_PX[stepIndex]} px") {
            stepIndex = (stepIndex + 1) % STEPS_PX.size
            stepButton?.text = "Шаг: ${STEPS_PX[stepIndex]} px"
        }
        stepButton = stepBtn

        val trackingText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxWidth = 850
            setPadding(8, 4, 8, 8)
            text = formatTrackingHud(LiveTrackingStatus.state.value)
        }
        val trackButton = hudButton("▶ Трекер") {
            if (LiveTrackingStatus.state.value.enabled) CaptureService.stopTracking(this)
            else CaptureService.startTracking(this)
        }
        trackButton.isEnabled = CaptureService.isRunning.value

        val snapButton = hudButton("📸 Снимок (обычный)") {
            CaptureService.takeSnapshot(this, hideOverlayDuringCapture = false)
        }
        val cleanSnapButton = hudButton("✨ Снимок (скрыть метку)") {
            CaptureService.takeSnapshot(this, hideOverlayDuringCapture = true)
        }

        val calibrationPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(row(
                hudButton("◀") { adjust(-1f, 0f, 0f) },
                hudButton("▲") { adjust(0f, -1f, 0f) },
                hudButton("▼") { adjust(0f, 1f, 0f) },
                hudButton("▶") { adjust(1f, 0f, 0f) }
            ))
            addView(row(hudButton("R −") { adjust(0f, 0f, -1f) },
                hudButton("R +") { adjust(0f, 0f, 1f) }, stepBtn))
            addView(hudButton("Сброс калибровки") { resetCalibration() })
        }

        var isCollapsed = false

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val collapsedSummaryText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            setPadding(4, 4, 8, 4)
            visibility = View.GONE
            text = "Luxin HUD"
        }

        val toggleCollapseButton = hudButton("▾ Свернуть") { }

        headerRow.addView(collapsedSummaryText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(toggleCollapseButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val controlsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(trackingText)
            addView(trackButton)
            addView(statusText)
            addView(calText)
            addView(hudButton("Настроить кольцо / Готово") {
                calibrationPanel.visibility = if (calibrationPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            })
            addView(calibrationPanel)
            addView(snapButton)
            addView(cleanSnapButton)
        }

        toggleCollapseButton.setOnClickListener {
            isCollapsed = !isCollapsed
            if (isCollapsed) {
                controlsContainer.visibility = View.GONE
                collapsedSummaryText.visibility = View.VISIBLE
                toggleCollapseButton.text = "▸ Развернуть"
            } else {
                controlsContainer.visibility = View.VISIBLE
                collapsedSummaryText.visibility = View.GONE
                toggleCollapseButton.text = "▾ Свернуть"
            }
        }

        val hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(210, 24, 24, 28))
            setPadding(14, 10, 14, 10)
            addView(headerRow)
            addView(controlsContainer)
        }
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = (wm.maximumWindowMetrics.bounds.height() - 200).coerceAtLeast(200)
                val available = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: limit
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(minOf(limit, available), MeasureSpec.AT_MOST))
            }
        }.apply { addView(hud) }
        controlHudView = scroll

        try {
            wm.addView(marker, params)
            wm.addView(scroll, hudParams)
            wm.addView(bigMapMarkerOverlayView, bigMapMarkerOverlayParams)
            _isShowing.value = true

            scope.launch {
                LiveTrackingStatus.state.collect { s ->
                    trackingText.text = formatTrackingHud(s)
                    trackingText.setTextColor(if (s.status == "error") Color.rgb(255, 110, 110) else Color.WHITE)
                    trackButton.text = if (s.enabled) "■ Остановить трекер" else "▶ Трекер"
                    trackButton.isEnabled = CaptureService.isRunning.value

                    val layerStr = s.layerName ?: s.layer ?: ""
                    val posStr = if (s.position != null) "(${s.position.x.toInt()}, ${s.position.y.toInt()})" else ""
                    collapsedSummaryText.text = when {
                        !s.enabled -> "Трекер: выкл"
                        s.position != null && layerStr.isNotBlank() -> "$layerStr $posStr"
                        s.position != null -> "${s.position.space} $posStr"
                        else -> s.message
                    }
                }
            }
            scope.launch {
                CaptureService.isRunning.collect { trackButton.isEnabled = it }
            }
            scope.launch {
                CaptureService.latestResult.collect { res ->
                    if (res != null) {
                        statusText.text = if (res.error != null) {
                            "Ошибка: ${res.error}"
                        } else if (!res.markerChecked) {
                            "Проверка недоступна: ${res.markerCheckNote}"
                        } else if (res.overlayLeaked) {
                            "Метка в кадре: ${res.magentaPixelCount} px (${res.width}×${res.height})"
                        } else {
                            "Метка не найдена: ${res.magentaPixelCount} px (${res.width}×${res.height})"
                        }
                        statusText.setTextColor(
                            if (res.error != null || res.overlayLeaked) Color.rgb(255, 110, 110)
                            else Color.rgb(255, 230, 120)
                        )
                    }
                }
            }

            // Big-map marker overlay: collect filtered markers and repaint on geometry change.
            // Markers always move with the map: show in tracking/moving/confirming phases
            // whenever we have a valid geometry (last known matrix).
            scope.launch {
                LiveTrackingStatus.state.collect { s ->
                    if (s.screenMode == "big_map" && s.bigMapGeometry != null &&
                        (s.bigMapGeometryMode == "tracking" || s.bigMapGeometryMode == "moving" || s.bigMapGeometryMode == "confirming")) {
                        val layerDecision = s.bigMapLayerDecision
                            ?: LayerDecision(null, false, "unknown", "no layer detection")
                        val filtered = BigMapMarkerFilter.filterMarkersForBigMap(
                            s.bigMapGeometry.space,
                            layerDecision,
                            s.frameWidth,
                            s.frameHeight,
                            s.bigMapGeometry
                        )
                        bigMapMarkerOverlay?.setMarkers(filtered, s.bigMapGeometry)
                    } else {
                        bigMapMarkerOverlay?.clear()
                    }
                }
            }
        }

        private fun applyCalibrationToParams(params: WindowManager.LayoutParams) {
            val size = ceil(calibration.r * 2f + 8f).toInt()
            params.width = size
            params.height = size
            params.x = (calibration.cx - size / 2f).roundToInt()
            params.y = (calibration.cy - size / 2f).roundToInt()
        }

    private fun adjust(dxSign: Float, dySign: Float, drSign: Float) {
        val step = STEPS_PX[stepIndex].toFloat()
        val bounds = requireNotNull(getSystemService(WindowManager::class.java)).maximumWindowMetrics.bounds
        calibration = calibration.moved(dxSign * step, dySign * step, drSign * step)
            .constrained(bounds.width(), bounds.height())
        MinimapCalibrationStore.save(this, calibration, shortSide)
        refreshMarker()
    }

    private fun resetCalibration() {
        calibration = MinimapCalibrationStore.reset(this, shortSide)
        refreshMarker()
    }

    private fun refreshMarker() {
        val params = markerParams
        val view = markerOverlayView
        if (params != null && view != null) {
            applyCalibrationToParams(params)
            try {
                windowManager?.updateViewLayout(view, params)
                view.invalidate()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        updateCalibrationLabel()
    }

    private fun updateCalibrationLabel() {
        calibrationText?.text =
            "центр ${calibration.cx.toInt()}, ${calibration.cy.toInt()}  R ${calibration.r.toInt()}  (экран ${shortSide})"
    }

    private fun hideOverlay() {
        markerOverlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        bigMapMarkerOverlayView?.let { view ->
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
        markerParams = null
        bigMapMarkerOverlayView = null
        bigMapMarkerOverlayParams = null
        bigMapMarkerOverlay = null
        controlHudView = null
        calibrationText = null
        stepButton = null
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
