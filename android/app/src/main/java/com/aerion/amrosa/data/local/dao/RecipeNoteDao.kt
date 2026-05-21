package com.aerion.amrosa.data.local.dao

import androidx.room.*
import com.aerion.amrosa.data.local.entity.RecipeNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeNoteDao {

    @Query("SELECT * FROM recipe_notes WHERE recipeId = :recipeId ORDER BY createdAt DESC")
    fun getNotesForRecipe(recipeId: String): Flow<List<RecipeNoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: RecipeNoteEntity)

    @Update
    suspend fun updateNote(note: RecipeNoteEntity)

    @Query("DELETE FROM recipe_notes WHERE id = :id")
    suspend fun deleteNote(id: String)
}
