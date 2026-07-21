package com.aerion.tablefeed.ui.util

/**
 * Compact a count for display on cards: 999 → "999", 1_200 → "1.2k", 15_000 → "15k",
 * 1_500_000 → "1.5M". Keeps one decimal only below 10 of each unit so chips stay short.
 */
fun compactCount(n: Int): String {
    if (n < 1000) return n.toString()
    fun trim(v: Double): String =
        (if (v < 10.0) "%.1f".format(v).trimEnd('0').trimEnd('.') else v.toInt().toString())
    return when {
        n < 1_000_000 -> trim(n / 1000.0) + "k"
        else          -> trim(n / 1_000_000.0) + "M"
    }
}
