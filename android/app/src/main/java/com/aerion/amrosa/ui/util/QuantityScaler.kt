package com.aerion.amrosa.ui.util

import com.aerion.amrosa.domain.model.Ingredient
import kotlin.math.abs

/** Which unit system to display in the recipe detail screen. Session-level — not persisted. */
enum class UnitMode { ORIGINAL, METRIC, IMPERIAL }

object QuantityScaler {
    private val fractions = listOf(
        0.25 to "¼", 0.333 to "⅓", 0.5 to "½", 0.667 to "⅔", 0.75 to "¾"
    )

    /**
     * Scale a raw quantity value and format it for display.
     * Falls back to [quantityDisplay] when [quantityValue] is null (e.g. "to taste").
     */
    fun scale(
        quantityValue: Double?,
        quantityUnit: String?,
        quantityDisplay: String?,
        scale: Double
    ): String {
        if (quantityValue == null) return quantityDisplay ?: ""
        val scaled = quantityValue * scale
        val whole = scaled.toLong()
        val fraction = scaled - whole

        val fractionStr = fractions.minByOrNull { abs(it.first - fraction) }
            ?.takeIf { abs(it.first - fraction) < 0.08 }?.second

        val number = when {
            fractionStr != null && whole == 0L -> fractionStr
            fractionStr != null -> "$whole$fractionStr"
            scaled == scaled.toLong().toDouble() -> scaled.toLong().toString()
            else -> String.format("%.1f", scaled)
        }

        return if (quantityUnit != null) "$number $quantityUnit" else number
    }

    /**
     * Unit-aware scaling for F6.
     * Picks metric or imperial conversion fields when available;
     * falls back to original if the conversion fields are null.
     */
    fun scale(ingredient: Ingredient, scaleFactor: Double, unitMode: UnitMode): String =
        when (unitMode) {
            UnitMode.METRIC ->
                if (ingredient.quantityValueMetric != null)
                    scale(ingredient.quantityValueMetric, ingredient.quantityUnitMetric,
                        ingredient.quantityDisplayMetric, scaleFactor)
                else
                    scale(ingredient.quantityValue, ingredient.quantityUnit,
                        ingredient.quantityDisplay, scaleFactor)

            UnitMode.IMPERIAL ->
                if (ingredient.quantityValueImperial != null)
                    scale(ingredient.quantityValueImperial, ingredient.quantityUnitImperial,
                        ingredient.quantityDisplayImperial, scaleFactor)
                else
                    scale(ingredient.quantityValue, ingredient.quantityUnit,
                        ingredient.quantityDisplay, scaleFactor)

            UnitMode.ORIGINAL ->
                scale(ingredient.quantityValue, ingredient.quantityUnit,
                    ingredient.quantityDisplay, scaleFactor)
        }
}
