package com.aerion.chefslist.domain.model

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

    // Range upper bounds (e.g. "4–6 cloves"). null = single quantity. Unit/display shared
    // with the min value; the range is rendered by scaling both ends.
    val quantityValueMax: Double? = null,
    val quantityValueMaxMetric: Double? = null,
    val quantityValueMaxImperial: Double? = null,

    val groupLabel: String?,
    val isOptional: Boolean,
    val substituteGroupId: String?,
    val substituteRatio: Float,
    val orderIndex: Int,

    // Author-entered shopping note (brand/substitute/comment). Shown on the Shopping List.
    val shoppingNote: String? = null
)
