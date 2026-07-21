package com.aerion.chefsjournal.data

import android.content.Context

/**
 * Lightweight local user preferences (SharedPreferences). Currently holds the explicit
 * cuisine preferences that override Discover's implicit affinity. Local-only, never synced.
 */
class UserPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("chefsjournal_prefs", Context.MODE_PRIVATE)

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

    // ── Per-recipe last selections (remembered across visits) ──────────────────

    /** Last substitute choices for a recipe: groupId → selected ingredientId. */
    fun recipeSubstitutes(recipeId: String): Map<String, String> =
        prefs.getStringSet(KEY_SUBS + recipeId, emptySet()).orEmpty().mapNotNull { entry ->
            val i = entry.indexOf('=')
            if (i <= 0) null else entry.substring(0, i) to entry.substring(i + 1)
        }.toMap()

    fun setRecipeSubstitutes(recipeId: String, map: Map<String, String>) {
        prefs.edit().putStringSet(KEY_SUBS + recipeId, map.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    /** Last included optional-ingredient ids for a recipe; null = no saved choice yet (use default). */
    fun recipeEnabledOptionals(recipeId: String): Set<String>? =
        if (prefs.contains(KEY_OPTS + recipeId)) prefs.getStringSet(KEY_OPTS + recipeId, emptySet()).orEmpty()
        else null

    fun setRecipeEnabledOptionals(recipeId: String, ids: Set<String>) {
        prefs.edit().putStringSet(KEY_OPTS + recipeId, ids).apply()
    }

    private companion object {
        const val KEY_CUISINES = "cuisine_prefs"
        const val KEY_OPT_DEFAULT = "include_optionals_default"
        const val KEY_SUBS = "recipe_subs_"
        const val KEY_OPTS = "recipe_opts_"
    }
}
