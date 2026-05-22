package com.aerion.amrosa.domain.model

data class Ingredient(
    val id: String,
    val sectionId: String?,
    val name: String,
    val quantityValue: Double?,     // numeric for scaling; null = non-scaleable
    val quantityUnit: String?,
    val quantityDisplay: String?,

    // F6: Unit conversions — null for seeded/manually-entered recipes
    val quantityValueMetric: Double? = null,
    val quantityUnitMetric: String? = null,
    val quantityDisplayMetric: String? = null,
    val quantityValueImperial: Double? = null,
    val quantityUnitImperial: String? = null,
    val quantityDisplayImperial: String? = null,

    val groupLabel: String?,
    val isOptional: Boolean,
    val substituteGroupId: String?,
    val substituteRatio: Float,
    val orderIndex: Int
)
