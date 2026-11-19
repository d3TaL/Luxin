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
 */
class BigMapMarkerOverlay(context: Context) : View(context) {

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(255, 0, 255) // magenta
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private var markers: List<LocalMarkerRecord> = emptyList()
    private var geometry: BigMapGeometry? = null

    /** Фиксированный радиус маркера в пикселях экрана (не меняется при зуме карты). */
    private val markerRadiusPx = 8f

    fun setMarkers(markers: List<LocalMarkerRecord>, geometry: BigMapGeometry?) {
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

        for (marker in markers) {
            val (dx, dy) = geom.toDisplay(marker.x, marker.y, canvas.width, canvas.height)

            if (dx.isFinite() && dy.isFinite() && dx >= 0f && dx <= canvas.width && dy >= 0f && dy <= canvas.height) {
                canvas.drawCircle(dx.toFloat(), dy.toFloat(), markerRadiusPx, markerPaint)
                canvas.drawCircle(dx.toFloat(), dy.toFloat(), markerRadiusPx, outlinePaint)
            }
        }
    }
}
