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
