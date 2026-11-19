package io.github.d3tal.luxin.tracker

/** Two consecutive fresh UI observations confirm the map; one rejection clears it immediately.
 * The first candidate must already suppress minimap output (not a permission to keep old fixes).
 */
class BigMapConfirmation {
    private var hits = 0

    fun observe(detected: Boolean): String {
        hits = if (detected) (hits + 1).coerceAtMost(2) else 0
        return when (hits) {
            2 -> "big_map"
            1 -> "big_map_pending"
            else -> "unknown"
        }
    }

    /** Finish withdrawing the map HUD before the caller can enter blocking minimap JNI. */
    internal suspend fun observeLive(detected: Boolean, onMapClosed: suspend () -> Unit): String {
        val hadMap = hits == 2
        val mode = observe(detected)
        if (hadMap && !detected) onMapClosed()
        return mode
    }

    fun reset() { hits = 0 }
}
