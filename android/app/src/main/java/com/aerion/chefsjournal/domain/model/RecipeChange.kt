package com.aerion.chefsjournal.domain.model

data class RecipeChange(
    val version: Int,
    val timestamp: Long,
    val summary: String
)
