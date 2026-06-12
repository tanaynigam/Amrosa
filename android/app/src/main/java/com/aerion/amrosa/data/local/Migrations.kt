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

/**
 * v11 → v12: Shopping List support.
 * - `ingredients.shoppingNote`: optional author-entered brand/comment per ingredient.
 * - `shopping_checks`: persisted checked-off items per recipe (local only).
 * Existing data is preserved.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ingredients ADD COLUMN shoppingNote TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS shopping_checks (" +
                "recipeId TEXT NOT NULL, itemKey TEXT NOT NULL, " +
                "PRIMARY KEY(recipeId, itemKey))"
        )
    }
}

/**
 * v12 → v13: Discover tab — `cooked_log` records when a recipe was last cooked
 * (recency penalty + "Recently cooked" shelf). Local only. Existing data preserved.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS cooked_log (" +
                "recipeId TEXT NOT NULL PRIMARY KEY, cookedAt INTEGER NOT NULL)"
        )
    }
}

/**
 * v13 → v14: ingredient quantity ranges (e.g. "4–6 cloves").
 * Adds upper-bound columns for the original + metric + imperial values. null = single
 * quantity, so existing ingredients are unaffected. Existing data preserved.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ingredients ADD COLUMN quantityValueMax REAL")
        db.execSQL("ALTER TABLE ingredients ADD COLUMN quantityValueMaxMetric REAL")
        db.execSQL("ALTER TABLE ingredients ADD COLUMN quantityValueMaxImperial REAL")
    }
}
