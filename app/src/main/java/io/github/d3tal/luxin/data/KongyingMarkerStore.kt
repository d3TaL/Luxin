package io.github.d3tal.luxin.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.locks.ReentrantLock

/**
 * Потокобезопасное хранилище маркеров Kongying с индексом по пространству (space).
 *
 * Используется для фильтрации маркеров grande карты:
 * только маркеры того же space, что вернул трекер grande карты, могут отображаться.
 * Координаты разных пространств пересекаются — без индекса по space метки смешивать нельзя.
 */
object KongyingMarkerStore {
    private val lock = ReentrantLock()
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
        get() = lock.withLock { markersById.size }

    /**
     * Заменяет всё хранилище новым набором записей (вызывается после полной синхронизации).
     * Индекс по'espace перестраивается.
     */
    fun replaceAll(records: List<LocalMarkerRecord>) {
        lock.withLock {
            markersById.clear()
            markersBySpace.clear()
            for (r in records) {
                markersById[r.id] = r
                markersBySpace.getOrPut(r.space) { mutableListOf() }.add(r)
            }
        }
        _allMarkers.value = toSnapshot()
    }

    /**
     * Добавляет или обновляет одну запись (инкрементальное обновление по MD5 чанка).
     * Если маркер с тем же id уже существует в другом space, старый индекс очищается.
     */
    fun upsert(record: LocalMarkerRecord) {
        val snapshot: List<LocalMarkerRecord>
        lock.withLock {
            val existing = markersById[record.id]
            if (existing != null && existing.space != record.space) {
                markersBySpace.getOrNull(existing.space)?.removeAll { it.id == record.id }
            }
            markersById[record.id] = record
            markersBySpace.getOrPut(record.space) { mutableListOf() }.addIfNotPresent(record)
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
                markersBySpace[removed.space]?.removeAll { it.id == id }
                // Удаляем пустые индексы пространств, чтобы не держать мёртвые ключи.
                markersBySpace.removeAll { it.value.isEmpty() }
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
     * При любой мутации хранилища公開ывается новый снимок всем подписчикам.
     */
    fun markersForSpaceFlow(space: String): Flow<List<LocalMarkerRecord>> = flow {
        while (true) {
            emit(markersForSpace(space))
            awaitNextChange()
        }
    }

    private suspend fun awaitNextChange() = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.Default
    ) {
        // Простой polling-ожидание смены снимка; достаточно для UI-слоя.
        val lastSnapshot = toSnapshot()
        while (toSnapshot() == lastSnapshot) {
            kotlinx.coroutines.delay(50)
        }
    }

    /** Текущий снимок всех маркеров (вне локета, но вызывается только из локета в mutating методах). */
    private fun toSnapshotUnlocked(): List<LocalMarkerRecord> {
        return markersById.values.toList()
    }

    /** Текущий снимок всех маркеров (безопасно из любого контекста). */
    private fun toSnapshot(): List<LocalMarkerRecord> {
        return lock.withLock { toSnapshotUnlocked() }
    }

    private fun <T> MutableList<T>.addIfNotPresent(element: T) {
        if (!any { it == element }) add(element)
    }
}
