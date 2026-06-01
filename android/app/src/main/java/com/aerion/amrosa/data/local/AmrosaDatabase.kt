package com.aerion.amrosa.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.aerion.amrosa.data.local.dao.RecipeDao
import com.aerion.amrosa.data.local.dao.RecipeNoteDao
import com.aerion.amrosa.data.local.entity.*

@Database(
    entities = [
        RecipeEntity::class,
        RecipeSectionEntity::class,
        IngredientEntity::class,
        StepEntity::class,
        StepIngredientRefEntity::class,
        RecipeNoteEntity::class,
    ],
    version = AmrosaDatabase.DB_VERSION,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AmrosaDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
    abstract fun recipeNoteDao(): RecipeNoteDao

    companion object {
        const val DB_VERSION = 10
    }
}
