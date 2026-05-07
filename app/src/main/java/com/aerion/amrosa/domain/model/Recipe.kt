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
    val createdAt: Long,
    val updatedAt: Long
)
