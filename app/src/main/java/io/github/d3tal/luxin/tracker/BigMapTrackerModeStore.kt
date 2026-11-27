package io.github.d3tal.luxin.tracker

import android.content.Context

object BigMapTrackerModeStore {
    private const val KEY = "bigmap_tracker_mode"
    private fun prefs(context: Context) = context.getSharedPreferences("bigmap_tracking", Context.MODE_PRIVATE)
    fun load(context: Context) = BigMapTrackerMode.fromStored(prefs(context).getString(KEY, null))
    fun save(context: Context, mode: BigMapTrackerMode) { prefs(context).edit().putString(KEY, mode.name).apply() }
}
