package io.github.d3tal.luxin.tracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.DisplayMetrics
import android.view.View
import io.github.d3tal.luxin.data.LocalMarkerRecord

/**
 * Полноэкранный оверлей маркеров grande карты.
 *
 * Рисует метки Kongying, спроецированные через [BigMapGeometry.toDisplay] из координат
 * Kongying в пиксели экрана. Оверлей не касаемый, прозрачный, координаты совпадают с
 * захватом MediaProjection.
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
    private var displayWidth = 0
    private var displayHeight = 0
    private var density = 1f

    fun setDisplayMetrics(displayMetrics: DisplayMetrics) {
        density = displayMetrics.density
        displayWidth = displayMetrics.widthPixels
        displayHeight = displayMetrics.heightPixels
    }

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
        val scale = density

        for (marker in markers) {
            val (dx, dy) = geom.toDisplay(marker.x, marker.y, displayWidth, displayHeight)
            val cx = (dx / scale).toFloat()
            val cy = (dy / scale).toFloat()

            if (cx >= 0f && cx <= width && cy >= 0f && cy <= height) {
                val radius = 6f / scale
                canvas.drawCircle(cx, cy, radius, markerPaint)
                canvas.drawCircle(cx, cy, radius, outlinePaint)
            }
        }
    }
}
