package com.aerion.chefslist.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.aerion.chefslist.data.local.dao.RecipeDao
import com.aerion.chefslist.data.local.dao.RecipeNoteDao
import com.aerion.chefslist.data.local.entity.*

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
    version = ChefsListDatabase.DB_VERSION,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ChefsListDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
    abstract fun recipeNoteDao(): RecipeNoteDao

    companion object {
        const val DB_VERSION = 15
    }
}
