package com.aerion.tablefeed.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records when a recipe was last cooked (Cooking Mode "Done"). One row per recipe —
 * latest cook wins (REPLACE upsert). Drives the Discover recency penalty + "Recently cooked"
 * shelf. Local only, never synced.
 */
@Entity(tableName = "cooked_log")
data class CookedLogEntity(
    @PrimaryKey val recipeId: String,
    val cookedAt: Long,
)
