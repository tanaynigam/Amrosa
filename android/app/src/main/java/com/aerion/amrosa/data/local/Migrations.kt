package com.aerion.amrosa.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v9 → v10: add `isReceived` to recipes (Recipe Ownership Model v2).
 * Existing local recipes are preserved and default to isReceived = 0 (i.e. "mine" / Tab 1).
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE recipes ADD COLUMN isReceived INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v10 → v11: add recipe-variation support (`parentRecipeId`, `variantName`) to recipes.
 * Existing recipes are preserved and default to NULL (i.e. they are base recipes, not variations).
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE recipes ADD COLUMN parentRecipeId TEXT")
        db.execSQL("ALTER TABLE recipes ADD COLUMN variantName TEXT")
    }
}
