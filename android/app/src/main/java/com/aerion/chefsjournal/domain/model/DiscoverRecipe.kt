package com.aerion.chefsjournal.domain.model

/** Where a Discover recommendation came from — drives the source boost + card label + open behaviour. */
enum class RecipeSource { OWN, FRIEND, PUBLIC }

/**
 * A single recommendation candidate on the Discover tab. Lightweight (card-level).
 * [isLocal] = the recipe exists in Room (open the normal editable detail); otherwise it's a
 * remote mirror opened read-only via the review screen.
 */
data class DiscoverRecipe(
    val recipeId: String,
    val title: String,
    val tags: List<String>,
    val prepTimeMinutes: Int?,
    val cookTimeMinutes: Int?,
    val source: RecipeSource,
    val authorUid: String?,
    val authorName: String?,
    val isLocal: Boolean,
    // F13 Phase 2 — popularity (public recipes only; 0 otherwise).
    val saveCount: Int = 0,
    val likeCount: Int = 0,
)
