package io.github.d3tal.luxin.capture

import java.io.Closeable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class TrackingOwner {
    private val mutex = Mutex()
    suspend fun run(block: suspend () -> Unit) = mutex.withLock { block() }
}

/** Single capture producer, single serialized CV consumer. Ownership moves on take(). */
internal class MotionSlot<T : Closeable> : Closeable {
    private var pending: T? = null
    private var latest = -1L
    private var closed = false
    @Synchronized fun offer(ts: Long, item: T) {
        if (closed || ts <= latest) { item.close(); return }
        pending?.close()
        latest = ts
        pending = item
    }
    @Synchronized fun take(): T? = pending.also { pending = null }
    @Synchronized override fun close() { closed = true; pending?.close(); pending = null }
}

internal fun needsMotionGate(confirmed: Boolean, lastCheckedNs: Long, nowNs: Long): Boolean =
    !confirmed || lastCheckedNs < 0 || nowNs < lastCheckedNs || nowNs - lastCheckedNs >= 300_000_000L
