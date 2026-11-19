package io.github.d3tal.luxin.tracker

import android.graphics.Bitmap

/**
 * Решение об этаже grande карты.
 *
 * `layerId == null && isSurface == true` — выбрана поверхность.
 * `layerId != null && isSurface == false` — выбран подземный/надземный уровень.
 * `mode == "unknown"` — этаж не определён. Неизвестный этаж ≠ поверхность.
 */
data class LayerDecision(
    val layerId: String?,
    val isSurface: Boolean,
    val mode: String, // "surface" | "layer" | "unknown"
    val reason: String?,
    // Brightness clusters alone do not identify a Kongying floor (including surface).
    val confirmed: Boolean = false
)

private const val LAYER_COLUMN_X_FRACTION_MIN = 0.88
private const val LAYER_COLUMN_X_FRACTION_MAX = 0.96
private const val BRIGHTNESS_THRESHOLD = 180

private data class ButtonRect(val x: Int, val y: Int, val brightness: Int)

private data class ButtonCluster(
    val minX: Int, val maxX: Int,
    val minY: Int, val maxY: Int,
    val avgBrightness: Double,
    val centerY: Int
)

private fun clusterVertically(candidates: List<ButtonRect>, maxGap: Int): List<ButtonCluster> {
    val sorted = candidates.sortedBy { it.y }
    val clusters = mutableListOf<MutableList<ButtonRect>>()
    var current = mutableListOf<ButtonRect>()

    for (c in sorted) {
        if (current.isEmpty() || c.y - current.last().y <= maxGap) {
            current.add(c)
        } else {
            clusters.add(current)
            current = mutableListOf(c)
        }
    }
    if (current.isNotEmpty()) clusters.add(current)

    return clusters.map { group ->
        ButtonCluster(
            minX = group.minOf { it.x },
            maxX = group.maxOf { it.x },
            minY = group.minOf { it.y },
            maxY = group.maxOf { it.y },
            avgBrightness = if (group.isEmpty()) 0.0 else group.sumOf { it.brightness.toDouble() } / group.size,
            centerY = (group.minOf { it.y } + group.maxOf { it.y }) / 2
        )
    }
}

/**
 * Точка входа: определяет этаж grande карты по колонке кнопок уровней.
 * Вызывается из CaptureService и тестов через `BigMapLayerDetector.detectLayer(...)`.
 */
object BigMapLayerDetector {

    /**
     * Извлекает компоненты цвета из ARGB int без зависимости от android.graphics.Color.
     */
    private fun brightnessOf(argb: Int): Int {
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        return (red * 0.299 + green * 0.587 + blue * 0.114).toInt()
    }

    /**
     * Определяет этаж grande карты на кадре.
     *
     * @param width ширина кадра в пикселях.
     * @param height высота кадра в пикселях.
     * @param getPixel функция, возвращающая ARGB-цвет пикселя (x, y).
     * @return `LayerDecision`. При неудаче — `mode = "unknown"`.
     */
    fun detectLayer(
        width: Int,
        height: Int,
        getPixel: (Int, Int) -> Int
    ): LayerDecision {
        if (width <= 0 || height <= 0) {
            return LayerDecision(null, false, "unknown", "empty bitmap")
        }

        // Колонка кнопок уровней — крайняя правая часть экрана.
        val x0 = (width * LAYER_COLUMN_X_FRACTION_MIN).toInt().coerceAtLeast(0)
        val x1 = (width * LAYER_COLUMN_X_FRACTION_MAX).toInt().coerceAtMost(width)
        if (x1 <= x0) {
            return LayerDecision(null, false, "unknown", "layer column out of bounds")
        }

        // Сканируем колонку: ищем яркие квадраты/ромбы кнопок.
        val buttonCandidates = mutableListOf<ButtonRect>()
        val stepY = maxOf(1, height / 60)
        val stepX = maxOf(1, width / 40)

        for (y in 0 until height step stepY) {
            for (x in x0 until x1 step stepX) {
                val c = getPixel(x, y)
                val brightness = brightnessOf(c)
                // Подсвеченная кнопка — существенно ярче окружающего темного UI.
                if (brightness > BRIGHTNESS_THRESHOLD) {
                    buttonCandidates.add(ButtonRect(x, y, brightness))
                }
            }
        }

        if (buttonCandidates.isEmpty()) {
            return LayerDecision(null, false, "unknown", "no bright buttons in layer column")
        }

        // Кластеризуем Kandidaten по вертикали — каждая кластера = одна кнопка.
        val clusters = clusterVertically(buttonCandidates, height / 12)
        if (clusters.isEmpty()) {
            return LayerDecision(null, false, "unknown", "no button clusters")
        }

        // Находим самую яркую кластеру — подсвеченная кнопка.
        val brightest = clusters.maxByOrNull { it.avgBrightness } ?: return LayerDecision(
            null, false, "unknown", "no brightest cluster"
        )

        // Кнопка поверхности — по DESIGN.md niveles над землёй стоят выше кнопки
        // поверхности, подземные — ниже. Без точной калибровки иконок используем
        // консервативную эвристику: подсвеченная кнопка в нижней части колонки
        // скорее всего поверхность, иначе — уровень.
        val columnCenterY = height / 2
        val isLikelySurface = brightest.centerY > columnCenterY

        return when {
            isLikelySurface -> LayerDecision(
                null, true, "surface",
                "brightest button in lower half: surface (heuristic)"
            )
            else -> LayerDecision(
                "layer_${brightest.centerY}", false, "layer",
                "brightest button in upper half: non-surface layer (heuristic)"
            )
        }
    }

    /**
     * android.graphics.Bitmap-адаптер для [detectLayer].
     * Вызывается из CaptureService, где уже есть Bitmap из MediaProjection.
     */
    fun detectLayer(bitmap: android.graphics.Bitmap): LayerDecision =
        detectLayer(bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) }
}
