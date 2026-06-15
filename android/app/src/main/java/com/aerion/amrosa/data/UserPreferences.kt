package com.aerion.amrosa.data

import android.content.Context

/**
 * Lightweight local user preferences (SharedPreferences). Currently holds the explicit
 * cuisine preferences that override Discover's implicit affinity. Local-only, never synced.
 */
class UserPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("amrosa_prefs", Context.MODE_PRIVATE)

    /** Preferred cuisine tags (lowercased). Empty = fall back to implicit affinity. */
    fun cuisinePreferences(): Set<String> =
        prefs.getStringSet(KEY_CUISINES, emptySet())?.toSet() ?: emptySet()

    fun setCuisinePreferences(cuisines: Set<String>) {
        prefs.edit().putStringSet(KEY_CUISINES, cuisines.map { it.lowercase() }.toSet()).apply()
    }

    /** Whether optional ingredients start included (their chip pre-selected) on a recipe. Default true. */
    fun includeOptionalsByDefault(): Boolean = prefs.getBoolean(KEY_OPT_DEFAULT, true)

    fun setIncludeOptionalsByDefault(value: Boolean) {
        prefs.edit().putBoolean(KEY_OPT_DEFAULT, value).apply()
    }

    private companion object {
        const val KEY_CUISINES = "cuisine_prefs"
        const val KEY_OPT_DEFAULT = "include_optionals_default"
    }
}
