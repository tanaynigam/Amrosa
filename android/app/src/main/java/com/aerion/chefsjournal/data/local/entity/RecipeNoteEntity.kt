package com.aerion.chefsjournal.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recipe_notes")
data class RecipeNoteEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long
)
