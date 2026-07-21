package com.aerion.chefsjournal.data.local.entity

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
    val orderIndex: Int,

    // F6: Unit conversions — populated by Gemini on import; null for seeded/manual recipes.
    // Placed last so existing positional constructor calls in DatabaseSeeder remain valid.
    val quantityValueMetric: Double? = null,
    val quantityUnitMetric: String? = null,
    val quantityDisplayMetric: String? = null,
    val quantityValueImperial: Double? = null,
    val quantityUnitImperial: String? = null,
    val quantityDisplayImperial: String? = null,

    // Author-entered shopping note (brand/substitute/comment, e.g. "Amul butter").
    // Optional; travels with the recipe. Shown on the Shopping List, edited in the editor.
    val shoppingNote: String? = null,

    // Range upper bounds (e.g. "4–6 cloves"). null = single quantity (the common case).
    // The unit + display strings are shared with the min value; the range is rendered at
    // display time by scaling both ends. Placed last to keep positional construction valid.
    val quantityValueMax: Double? = null,
    val quantityValueMaxMetric: Double? = null,
    val quantityValueMaxImperial: Double? = null,
)
