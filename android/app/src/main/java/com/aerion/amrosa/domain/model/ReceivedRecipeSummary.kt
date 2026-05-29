package com.aerion.amrosa.domain.model

/** Card-level data for a recipe shared directly to the current user. */
data class ReceivedRecipeSummary(
    val shareId: String,
    val title: String,
    /** Original recipe author (e.g. "Tanay" or "Imported"). */
    val authorDisplayName: String,
    /** Who sent this recipe to you. */
    val fromDisplayName: String,
    val sharedAt: Long,
    val prepTimeMinutes: Int?,
    val cookTimeMinutes: Int?,
    val tags: List<String>
)

/** Full recipe + sender name, returned by getReceivedRecipe(). */
data class ReceivedRecipeData(
    val recipe: Recipe,
    val fromDisplayName: String
)
