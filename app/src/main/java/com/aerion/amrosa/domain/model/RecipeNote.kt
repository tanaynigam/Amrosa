package com.aerion.amrosa.domain.model

data class RecipeNote(
    val id: String,
    val recipeId: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long
)
