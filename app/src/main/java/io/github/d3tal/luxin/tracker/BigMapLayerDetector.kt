package io.github.d3tal.luxin.tracker

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Решение об этаже grande карты.
 *
 * `layerId == null && isSurface == true` — выбрана поверхность.
 * `layerId != null && isSurface == false` — выбран подземный/надземный уровень.
 * `mode == "unknown"` — этаж не определён. Неизвестный этаж ≠ поверхность.
 *
 * По DESIGN.md: поверхность должна быть явным ID, не значением по умолчанию.
 * Этот детектор использует консервативную эвристику: подсвеченную кнопку в
 * нижней части колонки traktует как поверхность, иначе — как уровень.
 * Точное определение требует сверки с изображением уровня Kongying (этап 4).
 */
data class LayerDecision(
    val layerId: String?,
    val isSurface: Boolean,
    val mode: String, // "surface" | "layer" | "unknown"
    val reason: String?
)

/**
 * Определяет выбранный этаж grande карты по колонке кнопков уровней справа.
 *
 * Колонка кнопок: поверхность + 1–3 уровня. Порядок в колонке отражает высоту:
 * уровни над землёй — выше кнопки поверхности, подземные — ниже.
 * Выбранная кнопка подсвечена.
 *
 * При смене этажа геометрия grande карты (сетка координат) не меняется —
 * меняется только набор отображаемых маркеров.
 */
object BigMapLayerDetector {

    private const val LAYER_COLUMN_X_FRACTION_MIN = 0.88
    private const val LAYER_COLUMN_X_FRACTION_MAX = 0.96
    private const val BRIGHTNESS_THRESHOLD = 180

    /**
     * Определяет этаж grande карты на кадре.
     *
     * @param bitmap кадр grande карты (оригинальные пиксели захвата).
     * @return `LayerDecision`. При неудаче — `mode = "unknown"`.
     */
    fun detectLayer(bitmap: Bitmap): LayerDecision {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return LayerDecision(null, false, "unknown", "empty bitmap")
        }

        val w = bitmap.width
        val h = bitmap.height

        // Колонка кнопок уровней — крайняя правая часть экрана.
        val x0 = (w * LAYER_COLUMN_X_FRACTION_MIN).toInt().coerceAtLeast(0)
        val x1 = (w * LAYER_COLUMN_X_FRACTION_MAX).toInt().coerceAtMost(w)
        if (x1 <= x0) {
            return LayerDecision(null, false, "unknown", "layer column out of bounds")
        }

        // Сканируем колонку: ищем яркие квадраты/ромбы кнопок.
        val buttonCandidates = mutableListOf<ButtonRect>()
        val stepY = maxOf(1, h / 60)
        val stepX = maxOf(1, w / 40)

        for (y in 0 until h step stepY) {
            for (x in x0 until x1 step stepX) {
                val c = bitmap.getPixel(x, y)
                val brightness = (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114).toInt()
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
        val clusters = clusterVertically(buttonCandidates, h / 12)
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
        val columnCenterY = h / 2
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
                avgBrightness = group.averageOf { it.brightness.toDouble() },
                centerY = (group.minOf { it.y } + group.maxOf { it.y }) / 2
            )
        }
    }
}
