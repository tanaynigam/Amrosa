package com.aerion.chefsjournal.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.aerion.chefsjournal.data.local.dao.RecipeDao
import com.aerion.chefsjournal.data.local.dao.RecipeNoteDao
import com.aerion.chefsjournal.data.local.entity.*

@Database(
    entities = [
        RecipeEntity::class,
        RecipeSectionEntity::class,
        IngredientEntity::class,
        StepEntity::class,
        StepIngredientRefEntity::class,
        RecipeNoteEntity::class,
        ShoppingCheckEntity::class,
        CookedLogEntity::class,
    ],
    version = ChefsJournalDatabase.DB_VERSION,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ChefsJournalDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
    abstract fun recipeNoteDao(): RecipeNoteDao

    companion object {
        const val DB_VERSION = 15
    }
}
