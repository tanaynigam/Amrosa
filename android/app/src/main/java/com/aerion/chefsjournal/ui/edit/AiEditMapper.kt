package com.aerion.chefsjournal.ui.edit

import com.aerion.chefsjournal.ui.detail.EditDraft

/**
 * F22 Surface 1 — turning `editRecipeWithPrompt` output into reviewable, applicable changes.
 *
 * Three stages, deliberately kept out of the ViewModel so they're pure and testable:
 *   1. [parseAiOps]       callable result  → typed ops
 *   2. [resolveChanges]   ops + draft      → before/after rows the user reviews
 *   3. [applyAiChanges]   accepted rows    → a new draft, applied in ONE transform
 *
 * Stage 3 is a single pure transform rather than a sequence of ViewModel mutator calls:
 * same semantics, but the whole batch lands atomically instead of emitting one state
 * update (and one recomposition) per operation.
 */

// ─── 1. Parse ─────────────────────────────────────────────────────────────────

data class AiEditResult(
    val ops: List<AiEditOp> = emptyList(),
    val notes: String? = null,
    val dropped: Int = 0,
)

@Suppress("UNCHECKED_CAST")
fun parseAiOps(data: Map<String, Any?>?): AiEditResult {
    if (data == null) return AiEditResult()
    val rawOps = (data["operations"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
    val ops = rawOps.mapNotNull { raw ->
        val reason = (raw["reason"] as? String)?.trim().orEmpty().ifBlank { "Updated by assistant" }
        val id = raw["id"] as? String
        val sectionId = raw["sectionId"] as? String
        val fields = raw["fields"] as? Map<String, Any?>
        when (raw["op"] as? String) {
            "updateIngredient" -> id?.let { AiEditOp.UpdateIngredient(it, reason, ingFields(fields)) }
            "addIngredient" -> sectionId?.let { AiEditOp.AddIngredient(it, reason, ingFields(fields)) }
            "deleteIngredient" -> id?.let { AiEditOp.DeleteIngredient(it, reason) }
            "updateStep" -> {
                val text = (raw["instruction"] as? String)?.trim()
                if (id != null && !text.isNullOrBlank()) AiEditOp.UpdateStep(id, reason, text) else null
            }
            "addStep" -> {
                val text = (raw["instruction"] as? String)?.trim()
                if (sectionId != null && !text.isNullOrBlank()) AiEditOp.AddStep(sectionId, reason, text) else null
            }
            "deleteStep" -> id?.let { AiEditOp.DeleteStep(it, reason) }
            "updateMeta" -> AiEditOp.UpdateMeta(reason, metaFields(fields))
            else -> null
        }
    }
    return AiEditResult(
        ops = ops,
        notes = (data["notes"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
        dropped = (data["dropped"] as? List<*>)?.size ?: 0,
    )
}

private fun ingFields(f: Map<String, Any?>?): AiIngredientFields {
    if (f == null) return AiIngredientFields()
    return AiIngredientFields(
        name = (f["name"] as? String)?.trim(),
        quantityDisplay = (f["quantityDisplay"] as? String)?.trim(),
        quantityUnit = (f["quantityUnit"] as? String)?.trim(),
        quantityValue = (f["quantityValue"] as? Number)?.toDouble(),
        isOptional = f["isOptional"] as? Boolean,
        shoppingNote = (f["shoppingNote"] as? String)?.trim(),
        present = f.keys.toSet(),
    )
}

private fun metaFields(f: Map<String, Any?>?): AiMetaFields {
    if (f == null) return AiMetaFields()
    return AiMetaFields(
        title = (f["title"] as? String)?.trim(),
        description = (f["description"] as? String)?.trim(),
        baseServings = (f["baseServings"] as? Number)?.toInt(),
        prepTimeMinutes = (f["prepTimeMinutes"] as? Number)?.toInt(),
        cookTimeMinutes = (f["cookTimeMinutes"] as? Number)?.toInt(),
        tags = (f["tags"] as? List<*>)?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) },
        present = f.keys.toSet(),
    )
}

// ─── 2. Resolve into before → after rows ──────────────────────────────────────

/** "2 cloves garlic" style one-liner used on both sides of the diff. */
private fun EditorIngredient.line(): String =
    listOf(quantityDisplay.trim(), quantityUnit.trim(), name.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .ifBlank { "(empty)" }

private fun AiIngredientFields.appliedTo(i: EditorIngredient): EditorIngredient = i.copy(
    name = if (has("name")) name.orEmpty().ifBlank { i.name } else i.name,
    quantityDisplay = if (has("quantityDisplay")) quantityDisplay.orEmpty() else i.quantityDisplay,
    quantityUnit = if (has("quantityUnit")) quantityUnit.orEmpty() else i.quantityUnit,
    quantityValue = if (has("quantityValue")) quantityValue else i.quantityValue,
    isOptional = if (has("isOptional")) isOptional ?: i.isOptional else i.isOptional,
    shoppingNote = if (has("shoppingNote")) shoppingNote.orEmpty() else i.shoppingNote,
    // A changed amount invalidates the stored metric/imperial values. Clearing them is the
    // honest move: a stale "227 g" next to "3 cloves" is worse than no conversion at all.
    // "Update conversions" repopulates them.
    quantityValueMetric = if (touchesQuantity) null else i.quantityValueMetric,
    quantityUnitMetric = if (touchesQuantity) null else i.quantityUnitMetric,
    quantityDisplayMetric = if (touchesQuantity) null else i.quantityDisplayMetric,
    quantityValueImperial = if (touchesQuantity) null else i.quantityValueImperial,
    quantityUnitImperial = if (touchesQuantity) null else i.quantityUnitImperial,
    quantityDisplayImperial = if (touchesQuantity) null else i.quantityDisplayImperial,
    quantityValueMax = if (touchesQuantity) null else i.quantityValueMax,
    quantityValueMaxMetric = if (touchesQuantity) null else i.quantityValueMaxMetric,
    quantityValueMaxImperial = if (touchesQuantity) null else i.quantityValueMaxImperial,
)

/**
 * Resolve each op against [draft] into a reviewable row. Ops whose target has since
 * vanished from the draft are skipped — the user may have deleted it while the call
 * was in flight.
 */
fun resolveChanges(ops: List<AiEditOp>, draft: EditDraft): List<AiChange> {
    val ingredients = draft.sections.flatMap { it.ingredients }.associateBy { it.id }
    val steps = draft.sections.flatMap { it.steps }.associateBy { it.id }
    // Steps are numbered across the whole recipe, matching how the detail screen reads.
    val stepNumber = draft.sections.flatMap { it.steps }.withIndex()
        .associate { (idx, s) -> s.id to idx + 1 }
    val sectionName = draft.sections.associate { it.id to it.name.trim() }

    return ops.mapNotNull { op ->
        when (op) {
            is AiEditOp.UpdateIngredient -> {
                val current = ingredients[op.id] ?: return@mapNotNull null
                val updated = op.fields.appliedTo(current)
                val before = current.line()
                val after = updated.line()
                // Optional-only flips produce identical lines; say what actually changed.
                val optionalFlip = op.fields.has("isOptional") &&
                    op.fields.isOptional != null && op.fields.isOptional != current.isOptional
                if (before == after && !optionalFlip) return@mapNotNull null
                AiChange(
                    op = op,
                    kind = AiChangeKind.UPDATE,
                    target = current.name.trim().ifBlank { "Ingredient" },
                    before = if (before == after) {
                        if (current.isOptional) "$before (optional)" else before
                    } else before,
                    after = if (before == after) {
                        if (updated.isOptional) "$after (optional)" else after
                    } else after,
                )
            }
            is AiEditOp.AddIngredient -> {
                val shown = op.fields.appliedTo(EditorIngredient()).line()
                AiChange(
                    op = op,
                    kind = AiChangeKind.ADD,
                    target = sectionName[op.sectionId]?.takeIf { it.isNotEmpty() }
                        ?.let { "New ingredient in $it" } ?: "New ingredient",
                    before = "",
                    after = shown,
                )
            }
            is AiEditOp.DeleteIngredient -> {
                val current = ingredients[op.id] ?: return@mapNotNull null
                AiChange(
                    op = op,
                    kind = AiChangeKind.DELETE,
                    target = current.name.trim().ifBlank { "Ingredient" },
                    before = current.line(),
                    after = "",
                )
            }
            is AiEditOp.UpdateStep -> {
                val current = steps[op.id] ?: return@mapNotNull null
                if (current.instruction.trim() == op.instruction.trim()) return@mapNotNull null
                AiChange(
                    op = op,
                    kind = AiChangeKind.UPDATE,
                    target = "Step ${stepNumber[op.id] ?: "?"}",
                    before = current.instruction,
                    after = op.instruction,
                )
            }
            is AiEditOp.AddStep -> AiChange(
                op = op,
                kind = AiChangeKind.ADD,
                target = sectionName[op.sectionId]?.takeIf { it.isNotEmpty() }
                    ?.let { "New step in $it" } ?: "New step",
                before = "",
                after = op.instruction,
            )
            is AiEditOp.DeleteStep -> {
                val current = steps[op.id] ?: return@mapNotNull null
                AiChange(
                    op = op,
                    kind = AiChangeKind.DELETE,
                    target = "Step ${stepNumber[op.id] ?: "?"}",
                    before = current.instruction,
                    after = "",
                )
            }
            is AiEditOp.UpdateMeta -> {
                val f = op.fields
                val rows = buildList {
                    if (f.has("title") && !f.title.isNullOrBlank() && f.title != draft.title)
                        add(Triple("Title", draft.title, f.title))
                    if (f.has("description") && f.description != draft.description)
                        add(Triple("Description", draft.description, f.description.orEmpty()))
                    if (f.has("baseServings") && f.baseServings != null &&
                        f.baseServings.toString() != draft.baseServings
                    ) add(Triple("Servings", draft.baseServings, f.baseServings.toString()))
                    if (f.has("prepTimeMinutes") && f.prepTimeMinutes.toString() != draft.prepTimeMinutes)
                        add(Triple("Prep time", minLabel(draft.prepTimeMinutes), minLabel(f.prepTimeMinutes?.toString())))
                    if (f.has("cookTimeMinutes") && f.cookTimeMinutes.toString() != draft.cookTimeMinutes)
                        add(Triple("Cook time", minLabel(draft.cookTimeMinutes), minLabel(f.cookTimeMinutes?.toString())))
                    if (f.has("tags") && f.tags != null && f.tags.joinToString(", ") != draft.tagsText)
                        add(Triple("Tags", draft.tagsText, f.tags.joinToString(", ")))
                }
                if (rows.isEmpty()) return@mapNotNull null
                AiChange(
                    op = op,
                    kind = AiChangeKind.UPDATE,
                    target = rows.joinToString(", ") { it.first },
                    before = rows.joinToString("\n") { it.second.ifBlank { "—" } },
                    after = rows.joinToString("\n") { it.third.ifBlank { "—" } },
                )
            }
        }
    }
}

private fun minLabel(v: String?): String =
    v?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { "$it min" } ?: "—"

// ─── 3. Apply ─────────────────────────────────────────────────────────────────

/**
 * Apply the accepted [changes] to [draft] in one transform. Deletions go through the
 * draft's `deleted*Ids` lists so the save path removes the rows from Room, exactly as a
 * manual delete does. Added rows get `*-new-` ids, which the save path already treats
 * as inserts.
 */
fun applyAiChanges(draft: EditDraft, changes: List<AiChange>): EditDraft {
    var d = draft
    for (change in changes.filter { it.accepted }) {
        d = when (val op = change.op) {
            is AiEditOp.UpdateIngredient -> d.mapSections { sec ->
                sec.copy(ingredients = sec.ingredients.map { ing ->
                    if (ing.id == op.id) op.fields.appliedTo(ing) else ing
                })
            }
            is AiEditOp.AddIngredient -> {
                val new = op.fields.appliedTo(EditorIngredient())
                val target = if (d.sections.any { it.id == op.sectionId }) op.sectionId
                             else d.sections.firstOrNull()?.id
                if (target == null) d else d.mapSections { sec ->
                    if (sec.id == target) sec.copy(ingredients = sec.ingredients + new) else sec
                }
            }
            is AiEditOp.DeleteIngredient -> d
                .mapSections { sec -> sec.copy(ingredients = sec.ingredients.filterNot { it.id == op.id }) }
                .let { it.copy(deletedIngredientIds = it.deletedIngredientIds + op.id) }
            is AiEditOp.UpdateStep -> d.mapSections { sec ->
                sec.copy(steps = sec.steps.map { st ->
                    if (st.id == op.id) st.copy(instruction = op.instruction) else st
                })
            }
            is AiEditOp.AddStep -> {
                val target = if (d.sections.any { it.id == op.sectionId }) op.sectionId
                             else d.sections.firstOrNull()?.id
                if (target == null) d else d.mapSections { sec ->
                    if (sec.id == target) sec.copy(steps = sec.steps + EditorStep(instruction = op.instruction)) else sec
                }
            }
            is AiEditOp.DeleteStep -> d
                .mapSections { sec -> sec.copy(steps = sec.steps.filterNot { it.id == op.id }) }
                .let { it.copy(deletedStepIds = it.deletedStepIds + op.id) }
            is AiEditOp.UpdateMeta -> {
                val f = op.fields
                d.copy(
                    title = if (f.has("title") && !f.title.isNullOrBlank()) f.title else d.title,
                    description = if (f.has("description")) f.description.orEmpty() else d.description,
                    baseServings = if (f.has("baseServings") && f.baseServings != null && f.baseServings > 0)
                        f.baseServings.toString() else d.baseServings,
                    prepTimeMinutes = if (f.has("prepTimeMinutes"))
                        f.prepTimeMinutes?.toString().orEmpty() else d.prepTimeMinutes,
                    cookTimeMinutes = if (f.has("cookTimeMinutes"))
                        f.cookTimeMinutes?.toString().orEmpty() else d.cookTimeMinutes,
                    tagsText = if (f.has("tags") && f.tags != null) f.tags.joinToString(", ") else d.tagsText,
                )
            }
        }
    }
    return d
}

private inline fun EditDraft.mapSections(transform: (EditorSection) -> EditorSection): EditDraft =
    copy(sections = sections.map(transform))

// ─── Slim payload sent to the Cloud Function ──────────────────────────────────

/**
 * The draft reduced to what the model needs to reason about: ids, names, quantity text,
 * step text. Conversions, changelog, notes and imageUrl are stripped — irrelevant to
 * editing and they inflate every call.
 */
fun EditDraft.toSlimPayload(): HashMap<String, Any?> = hashMapOf(
    "title" to title,
    "description" to description,
    "baseServings" to baseServings,
    "prepTimeMinutes" to prepTimeMinutes,
    "cookTimeMinutes" to cookTimeMinutes,
    "tags" to tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() },
    "sections" to sections.map { sec ->
        hashMapOf(
            "id" to sec.id,
            "name" to sec.name,
            "ingredients" to sec.ingredients.map { i ->
                hashMapOf(
                    "id" to i.id,
                    "name" to i.name,
                    "quantityDisplay" to i.quantityDisplay,
                    "quantityUnit" to i.quantityUnit,
                    "quantityValue" to i.quantityValue,
                    "isOptional" to i.isOptional,
                )
            },
            "steps" to sec.steps.map { s -> hashMapOf("id" to s.id, "instruction" to s.instruction) },
        )
    },
)
