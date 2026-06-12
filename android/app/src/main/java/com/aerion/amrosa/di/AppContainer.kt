package com.aerion.amrosa.di

import android.content.Context
import androidx.room.Room
import com.aerion.amrosa.data.DatabaseSeeder
import com.aerion.amrosa.data.auth.AuthRepository
import com.aerion.amrosa.data.local.AmrosaDatabase
import com.aerion.amrosa.data.local.MIGRATION_9_10
import com.aerion.amrosa.data.local.MIGRATION_10_11
import com.aerion.amrosa.data.local.MIGRATION_11_12
import com.aerion.amrosa.data.local.MIGRATION_12_13
import com.aerion.amrosa.data.remote.RecipeSyncService
import com.aerion.amrosa.data.remote.SharedRecipeService
import com.aerion.amrosa.data.remote.SocialRepository
import com.aerion.amrosa.data.repository.RecipeRepository
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppContainer(context: Context) {
    val gson = Gson()

    private val database = Room.databaseBuilder(
        context.applicationContext,
        AmrosaDatabase::class.java,
        "amrosa.db"
    )
        .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)  // preserve local data across schema bumps
        .fallbackToDestructiveMigration()         // safety net for any unhandled version jump
        .build()

    /**
     * Wipes all local Room data and resets sync state.
     * Called on sign-out so the next user starts from a clean slate.
     */
    suspend fun clearAllLocalData(context: Context) {
        withContext(Dispatchers.IO) {
            database.clearAllTables()
            context.getSharedPreferences("amrosa_sync", Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }

    val repository = RecipeRepository(
        recipeDao = database.recipeDao(),
        noteDao = database.recipeNoteDao(),
        gson = gson
    )

    val authRepository = AuthRepository()
    val seeder = DatabaseSeeder(context, repository, gson)
    val syncService = RecipeSyncService(context, repository, authRepository, gson)
    val sharedRecipeService = SharedRecipeService(authRepository, gson)
    val socialRepository = SocialRepository(authRepository)
    val userPreferences = com.aerion.amrosa.data.UserPreferences(context)
}
