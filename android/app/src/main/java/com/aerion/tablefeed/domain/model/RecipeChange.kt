package com.aerion.tablefeed.domain.model

data class RecipeChange(
    val version: Int,
    val timestamp: Long,
    val summary: String
)
