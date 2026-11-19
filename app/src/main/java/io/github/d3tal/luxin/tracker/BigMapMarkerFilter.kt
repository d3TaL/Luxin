package io.github.d3tal.luxin.tracker

import io.github.d3tal.luxin.data.KongyingMarkerStore
import io.github.d3tal.luxin.data.LocalMarkerRecord

/**
 * Маркер grande карты с признаком принадлежности к выбранному этажу.
 *
 * `isSelectedLayer == true` — метка относится к этажу, который сейчас выбран пользователем
 * (или к поверхности, если выбран поверхностный этаж).
 * `isSelectedLayer == false` — метка относится к другому этажу; показывается приглушённо
 * с значком слоя, как это делает сама игра с телепортами.
 */
data class BigMapMarkerWithLayer(
    val record: LocalMarkerRecord,
    val isSelectedLayer: Boolean
)

/**
 * Фильтрация маркеров grande карты по пространству, этажу и видимости.
 *
 * Инвариант C1: Пространство ≠ Этаж ≠ Игрок.
 * - Только маркеры того же space, что вернул трекер grande карты.
 * - Все этажи показываются всегда; метки выбранного этажа — синим, других этажей — серым.
 * - Пустой уровень маркера = поверхность; показывается только если этаж grande карты = поверхность
 *   ИЛИ если этаж grande карты неизвестен (показываем все, без выделения).
 * - Неизвестный этаж grande карты ≠ поверхность: все метки показываются как «другой слой».
 * - Маркеры за кадром отбрасываются по проекции T_K_to_F.
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
     * @return все маркеры пространства с признаком принадлежности к выбранному этажу.
     */
    fun filterMarkersForBigMap(
        space: String?,
        layerDecision: LayerDecision,
        frameWidth: Int,
        frameHeight: Int,
        geometry: BigMapGeometry?,
    ): List<BigMapMarkerWithLayer> {
        if (space.isNullOrBlank() || geometry == null || frameWidth <= 0 || frameHeight <= 0) {
            return emptyList()
        }

        // allMarkers — консистентный снимок из KongyingMarkerStore
        val all = KongyingMarkerStore.allMarkers.value

        return all.filter { marker ->
            // 1. Пространство должно совпадать (регистронезависимо).
            if (!marker.space.equals(space, ignoreCase = true)) false
            // 2. Если этаж grande карты неизвестен, показываем все метки пространства.
            else if (layerDecision.mode == "unknown") true
            // 3. Иначе оставляем все метки пространства (не отсекаем по этажу),
            //    флаг isSelectedLayer рассчитывается ниже.
            else true
        }.map { marker ->
            // 4. Определяем, принадлежит ли маркер к выбранному этажу.
            val isSelected = when {
                layerDecision.mode == "unknown" -> false
                layerDecision.isSurface -> {
                    // Выбран этаж поверхности: поверхностные метки (пустой уровень, не underground)
                    // относятся к выбранному этажу, подземные — к другому.
                    marker.levels.isEmpty() && !marker.isUnderground
                }
                !layerDecision.isSurface && layerDecision.layerId != null -> {
                    // Выбран конкретный подземный/надземный этаж: совпадение по region_levels.
                    marker.levels.contains(layerDecision.layerId)
                }
                else -> false
            }
            BigMapMarkerWithLayer(marker, isSelected)
        }.filter { marker ->
            // 5. Маркер должен попадать в видимую область кадра (отбрасываем за кадром).
            val (px, py) = try {
                geometry.project(marker.record.x, marker.record.y)
            } catch (_: IllegalArgumentException) {
                return@filter false // An invalid imported point must not abort the entire HUD update.
            }
            px >= 0.0 && px < frameWidth && py >= 0.0 && py < frameHeight
        }
    }
}
