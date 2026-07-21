package com.aerion.tablefeed.domain.model

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

/** Full recipe + sender name, used by the received-recipe review screen. */
data class ReceivedRecipeData(
    val recipe: Recipe,
    val fromDisplayName: String
)

/**
 * A pending share pointer (shared_to/{uid}/recipes/{shareId}).
 * The actual recipe is resolved from shared_recipes/{recipeId}.
 */
data class ReceivedPointer(
    val shareId: String,
    val recipeId: String,
    val authorUid: String,
    val authorName: String,
    val fromDisplayName: String
)
