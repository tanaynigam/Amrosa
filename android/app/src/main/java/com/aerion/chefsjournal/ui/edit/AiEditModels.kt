package com.aerion.chefsjournal.ui.edit

/**
 * F22 Surface 1 — natural-language recipe editing.
 *
 * `editRecipeWithPrompt` returns a short list of EDIT OPERATIONS keyed to the ids we sent,
 * never a rewritten recipe. Each op is resolved against the live draft into an [AiChange]
 * (before → after) that the user reviews and accepts before anything is applied.
 */

/** One operation as returned by the Cloud Function. */
sealed interface AiEditOp {
    val reason: String

    data class UpdateIngredient(
        val id: String,
        override val reason: String,
        val fields: AiIngredientFields,
    ) : AiEditOp

    data class AddIngredient(
        val sectionId: String,
        override val reason: String,
        val fields: AiIngredientFields,
    ) : AiEditOp

    data class DeleteIngredient(val id: String, override val reason: String) : AiEditOp

    data class UpdateStep(
        val id: String,
        override val reason: String,
        val instruction: String,
    ) : AiEditOp

    data class AddStep(
        val sectionId: String,
        override val reason: String,
        val instruction: String,
    ) : AiEditOp

    data class DeleteStep(val id: String, override val reason: String) : AiEditOp

    data class UpdateMeta(
        override val reason: String,
        val fields: AiMetaFields,
    ) : AiEditOp
}

/**
 * Ingredient fields an op may change. A null means "not mentioned — leave it alone".
 * [present] records which keys the model actually sent, so an explicit null (clear the
 * value) stays distinguishable from an absent key.
 */
data class AiIngredientFields(
    val name: String? = null,
    val quantityDisplay: String? = null,
    val quantityUnit: String? = null,
    val quantityValue: Double? = null,
    val isOptional: Boolean? = null,
    val shoppingNote: String? = null,
    val present: Set<String> = emptySet(),
) {
    fun has(key: String) = key in present

    /** True when the amount changed — the cue to drop now-stale unit conversions. */
    val touchesQuantity: Boolean
        get() = has("quantityDisplay") || has("quantityValue") || has("quantityUnit")
}

data class AiMetaFields(
    val title: String? = null,
    val description: String? = null,
    val baseServings: Int? = null,
    val prepTimeMinutes: Int? = null,
    val cookTimeMinutes: Int? = null,
    val tags: List<String>? = null,
    val present: Set<String> = emptySet(),
) {
    fun has(key: String) = key in present
}

/** What kind of change a preview row represents — drives its icon and colour. */
enum class AiChangeKind { UPDATE, ADD, DELETE }

/**
 * One reviewable change: the op plus the before/after text the user actually reads.
 * [accepted] starts true; unticking a row drops that op on apply.
 */
data class AiChange(
    val op: AiEditOp,
    val kind: AiChangeKind,
    /** Which line this touches, e.g. "Garlic" or "Step 4" — so the user recognises it. */
    val target: String,
    val before: String,
    val after: String,
    val accepted: Boolean = true,
) {
    val reason: String get() = op.reason
}
