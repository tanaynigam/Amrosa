package com.aerion.amrosa.di

import android.content.Context
import androidx.room.Room
import com.aerion.amrosa.data.DatabaseSeeder
import com.aerion.amrosa.data.auth.AuthRepository
import com.aerion.amrosa.data.local.AmrosaDatabase
import com.aerion.amrosa.data.remote.RecipeSyncService
import com.aerion.amrosa.data.remote.SharedRecipeService
import com.aerion.amrosa.data.repository.RecipeRepository
import com.google.gson.Gson

class AppContainer(context: Context) {
    val gson = Gson()

    private val database = Room.databaseBuilder(
        context.applicationContext,
        AmrosaDatabase::class.java,
        "amrosa.db"
    ).fallbackToDestructiveMigration().build()

    val repository = RecipeRepository(
        recipeDao = database.recipeDao(),
        noteDao = database.recipeNoteDao(),
        gson = gson
    )

    val authRepository = AuthRepository()
    val seeder = DatabaseSeeder(context, repository, gson)
    val syncService = RecipeSyncService(context, repository, authRepository, gson)
    val sharedRecipeService = SharedRecipeService(authRepository, gson)
}
