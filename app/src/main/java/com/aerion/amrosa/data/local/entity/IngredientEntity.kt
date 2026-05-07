package com.aerion.amrosa.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ingredients")
data class IngredientEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val sectionId: String?,
    val name: String,
    val quantityValue: Double?,     // numeric for scaling; null = non-scaleable
    val quantityUnit: String?,
    val quantityDisplay: String?,   // full human-readable string
    val groupLabel: String?,        // e.g. "Wet Ingredients", "Dry Ingredients"
    val isOptional: Boolean = false,
    val substituteGroupId: String?, // shared ID = mutually exclusive alternatives
    val substituteRatio: Float = 1.0f, // qty multiplier vs base ingredient in group
    val orderIndex: Int
)
