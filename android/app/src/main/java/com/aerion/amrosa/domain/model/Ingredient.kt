package com.aerion.amrosa.domain.model

data class Ingredient(
    val id: String,
    val sectionId: String?,
    val name: String,
    val quantityValue: Double?,     // numeric for scaling; null = non-scaleable
    val quantityUnit: String?,
    val quantityDisplay: String?,
    val groupLabel: String?,
    val isOptional: Boolean,
    val substituteGroupId: String?,
    val substituteRatio: Float,
    val orderIndex: Int
)
