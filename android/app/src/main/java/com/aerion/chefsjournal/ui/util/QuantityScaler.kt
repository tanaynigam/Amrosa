package com.aerion.chefsjournal.ui.util

import com.aerion.chefsjournal.domain.model.Ingredient
import kotlin.math.abs

/** Which unit system to display in the recipe detail screen. Session-level — not persisted. */
enum class UnitMode { ORIGINAL, METRIC, IMPERIAL }

object QuantityScaler {
    private val fractions = listOf(
        0.25 to "¼", 0.333 to "⅓", 0.5 to "½", 0.667 to "⅔", 0.75 to "¾"
    )

    /** Format a single scaled number with nice fractions (e.g. 2.25 → "2¼"). */
    private fun formatNumber(value: Double): String {
        val whole = value.toLong()
        val fraction = value - whole
        val fractionStr = fractions.minByOrNull { abs(it.first - fraction) }
            ?.takeIf { abs(it.first - fraction) < 0.08 }?.second
        return when {
            fractionStr != null && whole == 0L -> fractionStr
            fractionStr != null -> "$whole$fractionStr"
            value == value.toLong().toDouble() -> value.toLong().toString()
            else -> String.format("%.1f", value)
        }
    }

    /**
     * Scale a raw quantity value and format it for display.
     * Falls back to [quantityDisplay] when [quantityValue] is null (e.g. "to taste").
     */
    fun scale(
        quantityValue: Double?,
        quantityUnit: String?,
        quantityDisplay: String?,
        scale: Double
    ): String = scale(quantityValue, null, quantityUnit, quantityDisplay, scale)

    /**
     * Range-aware scaling. When [quantityValueMax] is non-null and differs from
     * [quantityValue], renders a scaled range like "4–6 cloves" (both ends scaled).
     * Falls back to [quantityDisplay] when [quantityValue] is null.
     */
    fun scale(
        quantityValue: Double?,
        quantityValueMax: Double?,
        quantityUnit: String?,
        quantityDisplay: String?,
        scale: Double
    ): String {
        if (quantityValue == null) return quantityDisplay ?: ""
        val minStr = formatNumber(quantityValue * scale)
        val number =
            if (quantityValueMax != null && quantityValueMax > quantityValue)
                "$minStr–${formatNumber(quantityValueMax * scale)}"
            else
                minStr
        return if (quantityUnit != null) "$number $quantityUnit" else number
    }

    /**
     * Unit-aware scaling for F6 (with F-range support).
     * Picks metric or imperial conversion fields when available;
     * falls back to original if the conversion fields are null.
     */
    fun scale(ingredient: Ingredient, scaleFactor: Double, unitMode: UnitMode): String =
        when (unitMode) {
            UnitMode.METRIC ->
                if (ingredient.quantityValueMetric != null)
                    scale(ingredient.quantityValueMetric, ingredient.quantityValueMaxMetric,
                        ingredient.quantityUnitMetric, ingredient.quantityDisplayMetric, scaleFactor)
                else
                    scale(ingredient.quantityValue, ingredient.quantityValueMax,
                        ingredient.quantityUnit, ingredient.quantityDisplay, scaleFactor)

            UnitMode.IMPERIAL ->
                if (ingredient.quantityValueImperial != null)
                    scale(ingredient.quantityValueImperial, ingredient.quantityValueMaxImperial,
                        ingredient.quantityUnitImperial, ingredient.quantityDisplayImperial, scaleFactor)
                else
                    scale(ingredient.quantityValue, ingredient.quantityValueMax,
                        ingredient.quantityUnit, ingredient.quantityDisplay, scaleFactor)

            UnitMode.ORIGINAL ->
                scale(ingredient.quantityValue, ingredient.quantityValueMax,
                    ingredient.quantityUnit, ingredient.quantityDisplay, scaleFactor)
        }
}
