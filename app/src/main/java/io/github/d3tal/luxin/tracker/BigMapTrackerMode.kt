package io.github.d3tal.luxin.tracker

enum class BigMapTrackerMode {
    LEGACY, MOTION;
    fun next() = if (this == LEGACY) MOTION else LEGACY
    companion object {
        fun fromStored(value: String?): BigMapTrackerMode = entries.firstOrNull { it.name == value } ?: LEGACY
    }
}

/** Main changes the mode; the serialized tracking worker reads it before resetting its revision. */
class BigMapModeSelection(initial: BigMapTrackerMode = BigMapTrackerMode.LEGACY) {
    @Volatile var mode: BigMapTrackerMode = initial
        private set

    fun change(next: BigMapTrackerMode, invalidate: () -> Unit): Boolean {
        if (mode == next) return false
        // Write the mode BEFORE advancing the service revision: observing the new revision
        // must imply observing the new mode. Publication is serialized with invalidate on Main.
        mode = next
        invalidate()
        return true
    }
}

/** Worker-owned revision barrier. The supplied reset clears both native sessions before use. */
class BigMapWorkerRevision {
    var revision: Long = -1
        private set
    var mode: BigMapTrackerMode = BigMapTrackerMode.LEGACY
        private set

    fun observe(nextRevision: Long, selection: BigMapModeSelection, reset: () -> Unit): Boolean {
        if (revision == nextRevision) return false
        reset()
        mode = selection.mode
        revision = nextRevision
        return true
    }
}
