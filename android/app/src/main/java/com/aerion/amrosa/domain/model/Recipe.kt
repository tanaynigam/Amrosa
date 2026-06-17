package com.aerion.amrosa.domain.model

data class Recipe(
    val id: String,
    val title: String,
    val description: String?,
    val sourceUrls: List<String>,
    val baseServings: Int,
    val baseServingsMin: Int? = null,
    val baseServingsMax: Int? = null,
    val scaleIngredientId: String? = null,
    val scaleStep: Double = 1.0,
    val prepTimeMinutes: Int?,
    val cookTimeMinutes: Int?,
    val imageUrl: String?,
    val tags: List<String>,
    val sections: List<RecipeSection>,
    val ingredients: List<Ingredient>,
    val steps: List<Step>,
    val isCustomized: Boolean,
    val isImported: Boolean = false,
    val isReceived: Boolean = false,        // true = received from another user → Tab 2, read-only
    val needsReview: Boolean = false,
    val version: Int = 1,
    val changeLog: List<RecipeChange> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val authorId: String? = null,
    val authorDisplayName: String? = null,
    val visibility: String = "private",     // private | shared | friends | public
    val parentRecipeId: String? = null,     // null = base; else id of the base this varies
    val variantName: String? = null,        // e.g. "Spicy" — only set on variations
    val sharedWith: List<String> = emptyList(), // recipient UIDs when visibility == "shared"
    val likeCount: Int = 0,                  // popularity (only populated for cloud-read shared recipes)
)
