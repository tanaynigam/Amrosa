package com.aerion.tablefeed.di

import android.content.Context
import androidx.room.Room
import com.aerion.tablefeed.data.auth.AuthRepository
import com.aerion.tablefeed.data.local.TablefeedDatabase
import com.aerion.tablefeed.data.local.MIGRATION_9_10
import com.aerion.tablefeed.data.local.MIGRATION_10_11
import com.aerion.tablefeed.data.local.MIGRATION_11_12
import com.aerion.tablefeed.data.local.MIGRATION_12_13
import com.aerion.tablefeed.data.local.MIGRATION_13_14
import com.aerion.tablefeed.data.local.MIGRATION_14_15
import com.aerion.tablefeed.data.remote.RecipeSyncService
import com.aerion.tablefeed.data.remote.SharedRecipeService
import com.aerion.tablefeed.data.remote.SocialRepository
import com.aerion.tablefeed.data.repository.RecipeRepository
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppContainer(context: Context) {
    val gson = Gson()

    private val database = Room.databaseBuilder(
        context.applicationContext,
        TablefeedDatabase::class.java,
        "tablefeed.db"
    )
        .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)  // preserve local data across schema bumps
        .fallbackToDestructiveMigration()         // safety net for any unhandled version jump
        .build()

    /**
     * Wipes all local Room data and resets sync state.
     * Called on sign-out so the next user starts from a clean slate.
     */
    suspend fun clearAllLocalData(context: Context) {
        withContext(Dispatchers.IO) {
            database.clearAllTables()
            context.getSharedPreferences("tablefeed_sync", Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }

    val repository = RecipeRepository(
        recipeDao = database.recipeDao(),
        noteDao = database.recipeNoteDao(),
        gson = gson
    )

    val authRepository = AuthRepository()
    val syncService = RecipeSyncService(context, repository, authRepository, gson)
    val sharedRecipeService = SharedRecipeService(authRepository, gson)
    val socialRepository = SocialRepository(authRepository)
    val userPreferences = com.aerion.tablefeed.data.UserPreferences(context)
}
