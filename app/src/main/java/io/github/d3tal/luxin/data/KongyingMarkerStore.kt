package io.github.d3tal.luxin.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Потокобезопасное хранилище маркеров Kongying с индексом по пространству (space).
 *
 * Используется для фильтрации маркеров grande карты:
 * только маркеры того же space, что вернул трекер grande карты, могут отображаться.
 * Координаты разных пространств пересекаются — без индекса по space метки смешивать нельзя.
 */
object KongyingMarkerStore {
    private val mutex = Mutex()
    // Все загруженные маркеры, ключ — id маркера.
    private val markersById = mutableMapOf<Long, LocalMarkerRecord>()
    // Индекс: space -> список маркеров этого пространства.
    private val markersBySpace = mutableMapOf<String, MutableList<LocalMarkerRecord>>()

    /** Все загруженные маркеры (копия). */
    val allMarkers: StateFlow<List<LocalMarkerRecord>>
        get() = _allMarkers.asStateFlow()
    private val _allMarkers = MutableStateFlow(emptyList<LocalMarkerRecord>())

    /** Количество загруженных маркеров. */
    var size: Int
        get() = mutex.withLock { markersById.size }

    /**
     * Заменяет всё хранилище новым набором записей (вызывается после полной синхронизации).
     * Индекс по'espace перестраивается.
     */
    fun replaceAll(records: List<LocalMarkerRecord>) {
        mutex.withLock {
            markersById.clear()
            markersBySpace.clear()
            for (r in records) {
                markersById[r.id] = r
                markersBySpace.getOrPut(r.space) { mutableListOf() }.add(r)
            }
        }
        _allMarkers.value = records.toList()
    }

    /**
     * Добавляет или обновляет одну запись (инкрементальное обновление по MD5 чанка).
     */
    fun upsert(record: LocalMarkerRecord) {
        mutex.withLock {
            markersById[record.id] = record
            markersBySpace.getOrPut(record.space) { mutableListOf() }.addIfNotPresent(record)
        }
        _allMarkers.value = mutex.withLock { markersById.values.toList() }
    }

    /** Удаляет маркер по id. */
    fun remove(id: Long) {
        mutex.withLock {
            val removed = markersById.remove(id)
            if (removed != null) {
                markersBySpace[removed.space]?.removeAll { it.id == id }
            }
        }
        _allMarkers.value = mutex.withLock { markersById.values.toList() }
    }

    /**
     * Возвращает маркеры заданного пространства.
     * Пустой список, если пространство неизвестно или не загружено.
     */
    fun markersForSpace(space: String): List<LocalMarkerRecord> {
        return mutex.withLock {
            markersBySpace[space]?.toList() ?: emptyList()
        }
    }

    /** Возвращает маркер по id. */
    fun markerById(id: Long): LocalMarkerRecord? {
        return mutex.withLock { markersById[id] }
    }

    /** Поток маркеров заданного пространства (перевыдается при любом изменении хранилища). */
    fun markersForSpaceFlow(space: String): Flow<List<LocalMarkerRecord>> = flow {
        val initial = mutex.withLock { markersBySpace[space]?.toList() ?: emptyList() }
        emit(initial)
    }

    private fun <T> MutableList<T>.addIfNotPresent(element: T) {
        if (!any { it == element }) add(element)
    }
}
