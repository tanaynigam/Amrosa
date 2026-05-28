package com.aerion.amrosa.domain.model

/** Lightweight summary of a recipe shared directly to the current user. Used for list display. */
data class ReceivedRecipeSummary(
    val shareId: String,
    val title: String,
    val fromDisplayName: String,
    val sharedAt: Long
)
