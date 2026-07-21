package com.aerion.chefsjournal.data.local.entity

import androidx.room.Entity

/**
 * A checked-off item on a recipe's Shopping List. Presence of a row = checked.
 * [itemKey] is the normalized ingredient name (the combined shopping-list line key).
 * Personal + local only — never synced to Firestore.
 */
@Entity(tableName = "shopping_checks", primaryKeys = ["recipeId", "itemKey"])
data class ShoppingCheckEntity(
    val recipeId: String,
    val itemKey: String,
)
