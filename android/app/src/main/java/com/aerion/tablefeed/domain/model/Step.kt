package com.aerion.tablefeed.domain.model

data class Step(
    val id: String,
    val sectionId: String?,
    val instruction: String,
    val orderIndex: Int,
    val ingredientRefs: List<StepIngredientRef>
)

data class StepIngredientRef(
    val ingredientId: String,
    val quantityDisplay: String?
)
