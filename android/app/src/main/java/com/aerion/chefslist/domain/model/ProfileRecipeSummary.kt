package com.aerion.chefslist.domain.model

/**
 * Card-level data for a recipe shown on another chef's profile.
 * Sourced from the `shared_recipes` mirror (a co-chef sees the author's friends + public recipes).
 */
data class ProfileRecipeSummary(
    val recipeId: String,
    val title: String,
    val prepTimeMinutes: Int?,
    val cookTimeMinutes: Int?,
    val tags: List<String>,
    /** "friends" or "public" — drives the small tier badge on the card. */
    val visibility: String,
)
