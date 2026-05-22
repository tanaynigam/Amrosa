package com.aerion.amrosa.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val sourceUrls: String,         // JSON List<String>
    val baseServings: Int,
    val baseServingsMin: Int? = null,   // display range low end (e.g. 15)
    val baseServingsMax: Int? = null,   // display range high end (e.g. 20)
    val scaleIngredientId: String? = null, // ingredient to anchor scaling (e.g. flour)
    val scaleStep: Double = 1.0,        // increment per +/- tap on the anchor ingredient
    val prepTimeMinutes: Int?,
    val cookTimeMinutes: Int?,
    val imageUrl: String?,
    val tags: String,               // JSON List<String>
    val isCustomized: Boolean = false,
    val isImported: Boolean = false,    // true = URL/file-imported; false = seeded/personal/edited
    val needsReview: Boolean = false,   // true = imported but not yet confirmed by user
    val version: Int = 1,               // increments on each user save
    val changeLog: String = "[]",       // JSON array of RecipeChange entries
    val createdAt: Long,
    val updatedAt: Long,
    val syncedAt: Long? = null,
    val authorId: String? = null,           // Firebase UID of creator; null for seeded
    val authorDisplayName: String? = null,  // e.g. "Tanay", "Imported", "Amrosa"
    val visibility: String = "private",     // "private" or "public"
)
