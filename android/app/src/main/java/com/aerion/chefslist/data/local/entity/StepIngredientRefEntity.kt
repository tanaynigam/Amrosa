package com.aerion.chefslist.data.local.entity

import androidx.room.Entity

@Entity(tableName = "step_ingredient_refs", primaryKeys = ["stepId", "ingredientId"])
data class StepIngredientRefEntity(
    val stepId: String,
    val ingredientId: String,
    val quantityDisplay: String?    // quantity specifically used in this step (base yield)
)
