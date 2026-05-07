package com.aerion.amrosa.ui.util

import kotlin.math.abs

object QuantityScaler {
    private val fractions = listOf(
        0.25 to "¼", 0.333 to "⅓", 0.5 to "½", 0.667 to "⅔", 0.75 to "¾"
    )

    fun scale(quantityValue: Double?, quantityUnit: String?, quantityDisplay: String?, scale: Double): String {
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
}
