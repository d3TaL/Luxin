package io.github.d3tal.luxin.tracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import io.github.d3tal.luxin.data.LocalMarkerRecord
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Полноэкранный оверлей маркеров grande карты.
 *
 * Рисует метки Kongying, спроецированные через [BigMapGeometry.toDisplay] из координат
 * Kongying в пиксели экрана. Оверлей не касаемый, прозрачный, координаты совпадают с
 * захватом MediaProjection.
 *
 * Размер метки фиксирован в пикселях экрана и не зависит от масштаба карты (зума).
 *
 * Метки выбранного этажа — синие, метки других этажей — серые с серой обводкой.
 *
 * Отдельно, независимо от этажа и маркеров, рисуется диагностический слой: сетка в координатах Kongying,
 * тестовая метка, привязанная к точке мира, её след по кадрам и строка состояния (см. [BigMapTestProbe]).
 */
class BigMapMarkerOverlay(context: Context) : View(context) {

    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLUE
        style = Paint.Style.FILL
    }
    private val selectedOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val otherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(150, 150, 150) // gray
        style = Paint.Style.FILL
        alpha = 180
    }
    private val otherOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(100, 100, 100)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 180
    }

    private var markers: List<BigMapMarkerWithLayer> = emptyList()
    private var geometry: BigMapGeometry? = null
    private var showPoints = false
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.GREEN }
    private val pointOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }

    /** Фиксированный радиус маркера в пикселях экрана (не меняется при зуме карты). */
    private val markerRadiusPx = 8f

    // Диагностический слой «тест-метка и сетка». Окно оверлея полупрозрачно (alpha), поэтому цвета яркие.
    private val probe = BigMapTestProbe()
    private var testInfo: BigMapTestInfo? = null
    private var testPinFrame: Pair<Double, Double>? = null
    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = sp(11f)
    }
    private val textOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        textSize = sp(11f)
    }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val pinOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val badgeBackground = Paint().apply { color = android.graphics.Color.argb(200, 0, 0, 0) }
    private val arrowPath = Path()

    /** Сохраняет метрики дисплея (зарезервировано для масштабирования меток под плотность экрана). */
    fun setDisplayMetrics(metrics: DisplayMetrics) {
        // Reserved for future density-based scaling; current implementation uses
        // canvas.width/height in onDraw() directly.
    }

    fun setMarkers(markers: List<BigMapMarkerWithLayer>, geometry: BigMapGeometry?, debugPoints: Boolean = false) {
        this.markers = markers
        this.geometry = geometry
        showPoints = debugPoints
        invalidate()
    }

    fun clear() {
        markers = emptyList()
        geometry = null
        showPoints = false
        invalidate()
    }

    /** Включает или (null) выключает диагностический слой. Метка и след при этом сохраняются. */
    fun setTest(info: BigMapTestInfo?) {
        testInfo = info
        testPinFrame = info?.let { probe.update(it.geometry, it.phase, it.nowSeconds) }
        invalidate()
    }

    /** Сбрасывает тест-метку: следующая стабильная геометрия `tracking` ставит её в центр кадра. */
    fun resetTestPin() {
        probe.reset()
        testPinFrame = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawTest(canvas)
        val geom = geometry ?: return
        // A redraw can observe resized canvas bounds before capture publishes its invalidation.
        if (!geom.fitsDisplay(canvas.width, canvas.height)) return

        if (showPoints) {
            val scale = canvas.height.toDouble() / geom.frameHeight
            val radius = 2f * resources.displayMetrics.density
            for (i in geom.framePoints.indices step 2) {
                val x = (geom.framePoints[i] * scale).toFloat()
                val y = (geom.framePoints[i + 1] * scale).toFloat()
                canvas.drawCircle(x, y, radius + 1.5f, pointOutline)
                canvas.drawCircle(x, y, radius, pointPaint)
            }
        }

        for (markerWithLayer in markers) {
            val (dx, dy) = geom.toDisplay(markerWithLayer.record.x, markerWithLayer.record.y, canvas.width, canvas.height)

            if (dx.isFinite() && dy.isFinite() && dx >= 0f && dx <= canvas.width && dy >= 0f && dy <= canvas.height) {
                if (markerWithLayer.isSelectedLayer) {
                    canvas.drawCircle(dx.toFloat(), dy.toFloat(), markerRadiusPx, selectedPaint)
                    canvas.drawCircle(dx.toFloat(), dy.toFloat(), markerRadiusPx, selectedOutline)
                } else {
                    canvas.drawCircle(dx.toFloat(), dy.toFloat(), markerRadiusPx, otherPaint)
                    canvas.drawCircle(dx.toFloat(), dy.toFloat(), markerRadiusPx, otherOutline)
                }
            }
        }
    }

    private fun phaseColor(phase: String) = when (phase) {
        "tracking" -> android.graphics.Color.rgb(0, 230, 70)
        "moving" -> android.graphics.Color.rgb(255, 170, 0)
        else -> android.graphics.Color.rgb(255, 60, 60)
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float) {
        canvas.drawText(text, x, y, textOutlinePaint)
        canvas.drawText(text, x, y, gridTextPaint)
    }

    private fun drawTest(canvas: Canvas) {
        val info = testInfo ?: return
        val geom = info.geometry
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        var step = Double.NaN
        if (geom != null && geom.fitsDisplay(canvas.width, canvas.height)) {
            val s = canvas.height.toDouble() / geom.frameHeight
            val lines = BigMapTestGrid.lines(geom)
            step = BigMapTestGrid.stepOf(lines) ?: BigMapTestGrid.spacing(geom)
            val labelH = gridTextPaint.textSize
            for (line in lines) {
                canvas.drawLine((line.x0 * s).toFloat(), (line.y0 * s).toFloat(),
                    (line.x1 * s).toFloat(), (line.y1 * s).toFloat(), gridPaint)
                val text = BigMapTestGrid.label(step, line.unit)
                if (line.vertical) {
                    val x = (line.x0 * s).toFloat() + dp(3f)
                    if (x in 0f..w) drawLabel(canvas, "x $text", x, labelH + dp(2f))
                } else {
                    val y = (line.y0 * s).toFloat() - dp(3f)
                    if (y in 0f..h) drawLabel(canvas, "y $text", dp(4f), y)
                }
            }

            val color = phaseColor(info.phase)
            // След: где тест-метка рисовалась на последних кадрах. Ровная линия = геометрия едет вместе с картой.
            val trail = probe.trail()
            for (i in 1 until trail.size) {
                val a = trail[i - 1]
                val b = trail[i]
                trailPaint.color = (color and 0x00FFFFFF) or ((70 + 185 * i / trail.size) shl 24)
                canvas.drawLine((a.frameX * s).toFloat(), (a.frameY * s).toFloat(),
                    (b.frameX * s).toFloat(), (b.frameY * s).toFloat(), trailPaint)
            }
            for (point in trail) {
                trailPaint.style = Paint.Style.FILL
                trailPaint.color = (color and 0x00FFFFFF) or (160 shl 24)
                canvas.drawCircle((point.frameX * s).toFloat(), (point.frameY * s).toFloat(), dp(2.5f), trailPaint)
                trailPaint.style = Paint.Style.STROKE
            }

            testPinFrame?.let { (fx, fy) ->
                val px = (fx * s).toFloat()
                val py = (fy * s).toFloat()
                val r = dp(14f)
                if (px in 0f..w && py in 0f..h) {
                    pinPaint.color = color
                    canvas.drawCircle(px, py, r, pinOutlinePaint)
                    canvas.drawCircle(px, py, r, pinPaint)
                    for ((dx, dy) in listOf(r * 1.8f to 0f, 0f to r * 1.8f)) {
                        canvas.drawLine(px - dx, py - dy, px + dx, py + dy, pinOutlinePaint)
                        canvas.drawLine(px - dx, py - dy, px + dx, py + dy, pinPaint)
                    }
                    probe.pin?.let { drawLabel(canvas, "ТЕСТ", px + r + dp(4f), py - r) }
                } else {
                    // Метка вне экрана: стрелка на краю в её сторону.
                    val inset = dp(36f)
                    val cx = px.coerceIn(inset, w - inset)
                    val cy = py.coerceIn(inset, h - inset)
                    val angle = atan2((py - cy).toDouble(), (px - cx).toDouble())
                    val tipX = cx + (cos(angle) * dp(16f)).toFloat()
                    val tipY = cy + (sin(angle) * dp(16f)).toFloat()
                    val sideX = (-sin(angle) * dp(9f)).toFloat()
                    val sideY = (cos(angle) * dp(9f)).toFloat()
                    arrowPath.reset()
                    arrowPath.moveTo(tipX, tipY)
                    arrowPath.lineTo(cx + sideX, cy + sideY)
                    arrowPath.lineTo(cx - sideX, cy - sideY)
                    arrowPath.close()
                    pinPaint.color = color
                    canvas.drawPath(arrowPath, pinOutlinePaint)
                    pinPaint.style = Paint.Style.FILL
                    canvas.drawPath(arrowPath, pinPaint)
                    pinPaint.style = Paint.Style.STROKE
                }
            }
        }
        drawBadge(canvas, info, step)
    }

    private fun drawBadge(canvas: Canvas, info: BigMapTestInfo, step: Double) {
        val geom = info.geometry
        val pin = probe.pin
        val onScreen = testPinFrame?.let { (fx, fy) ->
            geom != null && fx >= 0.0 && fx < geom.frameWidth && fy >= 0.0 && fy < geom.frameHeight
        }
        val lines = buildList {
            add(if (geom != null) "Тест: геометрия есть (${info.phase})" else "Тест: ГЕОМЕТРИИ НЕТ (${info.phase})")
            if (geom != null) {
                add(String.format(java.util.Locale.US, "точек %d | ошибка %.2f px | масштаб %.3f px/ед",
                    geom.inliers, geom.errorPx, geom.frameScale))
                add(String.format(java.util.Locale.US, "возраст кадра %s | %s | шаг сетки %s ед",
                    info.frameAgeMs?.let { "$it мс" } ?: "?",
                    info.fps?.let { String.format(java.util.Locale.US, "%.1f кадр/с", it) } ?: "? кадр/с",
                    if (step.isNaN()) "?" else BigMapTestGrid.label(step, step)))
            }
            add(when {
                pin == null -> "метка: ждёт стабильной привязки (tracking)"
                onScreen == null -> String.format(java.util.Locale.US, "метка x %.0f y %.0f: нет геометрии", pin.x, pin.y)
                else -> String.format(java.util.Locale.US, "метка x %.0f y %.0f: %s", pin.x, pin.y,
                    if (onScreen) "на экране" else "вне экрана")
            })
        }
        val pad = dp(6f)
        val lineH = gridTextPaint.textSize + dp(3f)
        val width = lines.maxOf { gridTextPaint.measureText(it) } + 2 * pad
        val left = dp(12f)
        val top = dp(12f)
        canvas.drawRect(left, top, left + width, top + pad * 2 + lineH * lines.size - dp(3f), badgeBackground)
        val saved = gridTextPaint.color
        lines.forEachIndexed { i, text ->
            gridTextPaint.color = if (i == 0) phaseColor(if (geom != null) info.phase else "none") else saved
            canvas.drawText(text, left + pad, top + pad + gridTextPaint.textSize + lineH * i, gridTextPaint)
        }
        gridTextPaint.color = saved
    }
}
