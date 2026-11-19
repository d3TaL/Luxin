package io.github.d3tal.luxin.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock

/**
 * Потокобезопасное хранилище маркеров Kongying с индексом по пространству (space).
 *
 * Используется для фильтрации маркеров grande карты:
 * только маркеры того же space, что вернул трекер grande карты, могут отображаться.
 * Координаты разных пространств пересекаются — без индекса по space метки смешивать нельзя.
 *
 * Инварианты:
 * - Каждый id маркера встречается не более одного раза во всём хранилище и не более
 *   одного раза в списке каждого пространства.
 * - Мутации pubblicруют один консистентный снимок в Mutation Order (сначала лок, потом
 *   StateFlow), поэтому подписчики не могут получить устаревший снимок после нового.
 */
object KongyingMarkerStore {
    private val lock = ReentrantLock()
    // Все загруженные маркеры, ключ — id маркера.
    private val markersById = mutableMapOf<Long, LocalMarkerRecord>()
    // Индекс: space -> список маркеров этого пространства (без дублей id).
    private val markersBySpace = mutableMapOf<String, MutableList<LocalMarkerRecord>>()

    /** Все загруженные маркеры (копия, консистентный снимок). */
    val allMarkers: StateFlow<List<LocalMarkerRecord>>
        get() = _allMarkers.asStateFlow()
    private val _allMarkers = MutableStateFlow(emptyList<LocalMarkerRecord>())

    /** Количество загруженных маркеров. */
    val size: Int
        get() = lock.withLock { markersById.size }

    /**
     * Заменяет всё хранилище новым набором записей (вызывается после полной синхронизации).
     * Индекс по'espace перестраивается.
     */
    fun replaceAll(records: List<LocalMarkerRecord>) {
        val snapshot: List<LocalMarkerRecord>
        lock.withLock {
            markersById.clear()
            markersBySpace.clear()
            for (r in records) {
                markersById[r.id] = r
                markersBySpace.getOrPut(r.space) { mutableListOf() }.add(r)
            }
            snapshot = toSnapshotUnlocked()
        }
        _allMarkers.value = snapshot
    }

    /**
     * Добавляет или обновляет одну запись (инкрементальное обновление по MD5 чанка).
     * Если маркер с тем же id уже существует в другом space, старый индекс очищается.
     * Повторный upsert с тем же id заменяет старую запись, а не дублирует её.
     */
    fun upsert(record: LocalMarkerRecord) {
        val snapshot: List<LocalMarkerRecord>
        lock.withLock {
            // Удаляем старую запись с тем же id из всех пространств (обрабатывает и смену
            // пространства, и обновление координат без дублирования).
            markersBySpace.values.forEach { it.removeAll { it.id == record.id } }
            markersById[record.id] = record
            markersBySpace.getOrPut(record.space) { mutableListOf() }.add(record)
            snapshot = toSnapshotUnlocked()
        }
        _allMarkers.value = snapshot
    }

    /** Удаляет маркер по id. */
    fun remove(id: Long) {
        val snapshot: List<LocalMarkerRecord>
        lock.withLock {
            val removed = markersById.remove(id)
            if (removed != null) {
                markersBySpace.values.forEach { it.removeAll { it.id == id } }
                val emptyKeys = markersBySpace.keys.filter { markersBySpace[key]!!.isEmpty() }
                emptyKeys.forEach { markersBySpace.remove(it) }
            }
            snapshot = toSnapshotUnlocked()
        }
        _allMarkers.value = snapshot
    }

    /**
     * Возвращает маркеры заданного пространства.
     * Пустой список, если пространство неизвестно или не загружено.
     */
    fun markersForSpace(space: String): List<LocalMarkerRecord> {
        return lock.withLock {
            markersBySpace[space]?.toList() ?: emptyList()
        }
    }

    /** Возвращает маркер по id. */
    fun markerById(id: Long): LocalMarkerRecord? {
        return lock.withLock { markersById[id] }
    }

    /**
     * Поток маркеров заданного пространства.
     * При любой мутации хранилища公開ывается новый снимок всем подписчикам через
     * [_allMarkers]; фильтрация по space применяется к каждому снимку.
     */
    fun markersForSpaceFlow(space: String): Flow<List<LocalMarkerRecord>> =
        _allMarkers.map { it.filter { it.space == space } }

    /** Текущий снимок всех маркеров (вызывается только изнутри локета). */
    private fun toSnapshotUnlocked(): List<LocalMarkerRecord> {
        return markersById.values.toList()
    }

    private fun <T> MutableList<T>.addIfNotPresent(element: T) {
        if (!any { it == element }) add(element)
    }
}
