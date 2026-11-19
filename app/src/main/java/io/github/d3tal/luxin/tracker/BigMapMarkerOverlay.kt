package io.github.d3tal.luxin.tracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import io.github.d3tal.luxin.data.LocalMarkerRecord

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

    /** Фиксированный радиус маркера в пикселях экрана (не меняется при зуме карты). */
    private val markerRadiusPx = 8f

    fun setMarkers(markers: List<BigMapMarkerWithLayer>, geometry: BigMapGeometry?) {
        this.markers = markers
        this.geometry = geometry
        invalidate()
    }

    fun clear() {
        markers = emptyList()
        geometry = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val geom = geometry ?: return

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
}
