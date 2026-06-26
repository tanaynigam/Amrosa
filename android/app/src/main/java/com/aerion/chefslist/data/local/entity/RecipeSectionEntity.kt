package com.aerion.chefslist.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recipe_sections")
data class RecipeSectionEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val name: String,
    val orderIndex: Int
)
