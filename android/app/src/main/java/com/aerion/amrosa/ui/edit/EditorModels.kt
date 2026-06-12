package com.aerion.amrosa.ui.edit

import java.util.UUID

// ─── Editable draft models (shared by the inline editor on the detail screen) ──

data class EditorIngredient(
    val id: String = "ing-new-${UUID.randomUUID()}",
    val name: String = "",
    val quantityDisplay: String = "",
    val quantityUnit: String = "",
    val groupLabel: String = "",
    val isOptional: Boolean = false,
    // Preserved from original — kept on save
    val quantityValue: Double? = null,
    val substituteGroupId: String? = null,
    val substituteRatio: Float = 1.0f,
    // Unit conversions — preserved across edits; (re)populated by "Update conversions"
    val quantityValueMetric: Double? = null,
    val quantityUnitMetric: String? = null,
    val quantityDisplayMetric: String? = null,
    val quantityValueImperial: Double? = null,
    val quantityUnitImperial: String? = null,
    val quantityDisplayImperial: String? = null,
    // Author-entered shopping note (brand/comment); shown on the Shopping List.
    val shoppingNote: String = "",
)

data class EditorStep(
    val id: String = "step-new-${UUID.randomUUID()}",
    val instruction: String = "",
    /** Ids of the ingredients this step uses (drives the inline refs + cooking-mode card). */
    val ingredientIds: List<String> = emptyList(),
)

data class EditorSection(
    val id: String = "sec-new-${UUID.randomUUID()}",
    val name: String = "",
    val ingredients: List<EditorIngredient> = emptyList(),
    val steps: List<EditorStep> = emptyList(),
)
