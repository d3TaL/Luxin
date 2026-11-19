package io.github.d3tal.luxin.tracker

import io.github.d3tal.luxin.data.KongyingMarkerStore
import io.github.d3tal.luxin.data.LocalMarkerRecord

/**
 * Фильтрация маркеров grande карты по пространству, этажу и видимости.
 *
 * Инвариант C1: Пространство ≠ Этаж ≠ Игрок.
 * - Только маркеры того же space, что вернул трекер grande карты.
 * - Этаж маркера совпадает с подтверждённым этажем grande карты.
 * - Пустой уровень маркера = поверхность; показывается только если этаж grande карты = поверхность.
 * - Неизвестный этаж grande карты ≠ поверхность: маркеры этажа не показываются.
 */
object BigMapMarkerFilter {

    /**
     * Фильтрует маркеры grande карты для отрисовки на оверлее.
     *
     * @param space пространство grande карты (из registerBigMap).
     * @param layerDecision решение об этаже grande карты (из BigMapLayerDetector).
     * @param frameWidth ширина кадра захвата в пикселях.
     * @param frameHeight высота кадра захвата в пикселях.
     * @param geometry матрица T_K_to_F (Kongying units -> пиксели кадра).
     * @return маркеры, подходящие для отрисовки на grande карте.
     */
    fun filterMarkersForBigMap(
        space: String?,
        layerDecision: LayerDecision,
        frameWidth: Int,
        frameHeight: Int,
        geometry: BigMapGeometry?,
    ): List<LocalMarkerRecord> {
        if (space.isNullOrBlank() || geometry == null || frameWidth <= 0 || frameHeight <= 0) {
            return emptyList()
        }

        // allMarkers — консистентный снимок из KongyingMarkerStore
        val all = KongyingMarkerStore.allMarkers.value

        return all.filter { marker ->
            // 1. Пространство должно совпадать (регистронезависимо).
            if (!marker.space.equals(space, ignoreCase = true)) false
            // 2. Этаж grande карты unknown => маркеры этажа не показываются.
            else if (layerDecision.mode == "unknown") false
            // 3. Этаж маркера должен совпадать с этажом grande карты.
            // Пустой уровень маркера = поверхность.
            else {
                val markerIsSurface = marker.levels.isEmpty() && !marker.isUnderground
                when {
                    layerDecision.isSurface && markerIsSurface -> true // поверхность == поверхность
                    layerDecision.isSurface && !markerIsSurface -> false // маркер подземелья не показывается на поверхности
                    !layerDecision.isSurface &&
                        layerDecision.layerId != null &&
                        marker.levels.contains(layerDecision.layerId) -> true // уровень совпадает
                    else -> false
                }
            }
        }.filter { marker ->
            // 4. Маркер должен попадать в видимую область кадра (отбрасываем за кадром).
            val (px, py) = geometry.project(marker.x, marker.y)
            px >= 0.0 && px < frameWidth && py >= 0.0 && py < frameHeight
        }
    }
}
