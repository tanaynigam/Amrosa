package com.aerion.chefsjournal.ui.shopping

import com.aerion.chefsjournal.domain.model.Ingredient
import com.aerion.chefsjournal.ui.util.QuantityScaler
import com.aerion.chefsjournal.ui.util.UnitMode

/** One combined line on the shopping list. [key] is the persisted checked-state key. */
data class ShoppingLine(
    val key: String,
    val name: String,
    val quantity: String,
    val note: String?,
)

/**
 * Combines a recipe's ingredients into a flat shopping list: ingredients with the same
 * (normalized) name are merged into one line with their quantities summed. Summing happens
 * per unit within the active [UnitMode] — Metric mode reduces most items to clean g/ml; when
 * units in a group don't match they're joined with " + ". Non-numeric amounts ("to taste")
 * are carried through as-is. Author shopping notes are aggregated onto the line.
 */
object ShoppingAggregator {

    /** Mirror of the backend `normalizeKey` so learned keys line up conceptually. */
    fun normalizeKey(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun valueUnitFor(ing: Ingredient, mode: UnitMode): Pair<Double?, String?> =
        when (mode) {
            UnitMode.METRIC ->
                if (ing.quantityValueMetric != null) ing.quantityValueMetric to ing.quantityUnitMetric
                else ing.quantityValue to ing.quantityUnit
            UnitMode.IMPERIAL ->
                if (ing.quantityValueImperial != null) ing.quantityValueImperial to ing.quantityUnitImperial
                else ing.quantityValue to ing.quantityUnit
            UnitMode.ORIGINAL -> ing.quantityValue to ing.quantityUnit
        }

    fun build(
        ingredients: List<Ingredient>,
        scaleFactor: Double,
        unitMode: UnitMode,
    ): List<ShoppingLine> {
        // Preserve first-seen order of ingredient names.
        val groups = LinkedHashMap<String, MutableList<Ingredient>>()
        for (ing in ingredients) {
            val key = normalizeKey(ing.name)
            if (key.isEmpty()) continue
            groups.getOrPut(key) { mutableListOf() }.add(ing)
        }

        return groups.map { (key, members) ->
            // Sum numeric amounts bucketed by unit; carry non-numeric amounts as display text.
            val buckets = LinkedHashMap<String, Pair<Double, String?>>() // unitKey -> (sum, displayUnit)
            val nonNumeric = LinkedHashSet<String>()

            for (ing in members) {
                val (value, unit) = valueUnitFor(ing, unitMode)
                if (value != null) {
                    val unitKey = (unit ?: "").lowercase().trim()
                    val existing = buckets[unitKey]
                    buckets[unitKey] = ((existing?.first ?: 0.0) + value) to (existing?.second ?: unit)
                } else {
                    ing.quantityDisplay?.takeIf { it.isNotBlank() }?.let { nonNumeric.add(it) }
                }
            }

            val parts = buckets.map { (_, sumUnit) ->
                QuantityScaler.scale(sumUnit.first, sumUnit.second, null, scaleFactor)
            } + nonNumeric.toList()

            val note = members.mapNotNull { it.shoppingNote?.trim()?.ifBlank { null } }
                .distinct()
                .joinToString("; ")
                .ifBlank { null }

            ShoppingLine(
                key = key,
                name = members.first().name,
                quantity = parts.joinToString(" + "),
                note = note,
            )
        }
    }
}
