package com.aerion.amrosa.data.local.dao

import androidx.room.*
import com.aerion.amrosa.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {

    @Query("SELECT * FROM recipes ORDER BY title ASC")
    fun getAllRecipes(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE isCustomized = 1 ORDER BY createdAt DESC")
    fun getImportedRecipes(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun getRecipeById(id: String): RecipeEntity?

    @Query("SELECT * FROM recipe_sections WHERE recipeId = :recipeId ORDER BY orderIndex ASC")
    suspend fun getSectionsForRecipe(recipeId: String): List<RecipeSectionEntity>

    @Query("SELECT * FROM ingredients WHERE recipeId = :recipeId ORDER BY orderIndex ASC")
    suspend fun getIngredientsForRecipe(recipeId: String): List<IngredientEntity>

    @Query("SELECT * FROM steps WHERE recipeId = :recipeId ORDER BY orderIndex ASC")
    suspend fun getStepsForRecipe(recipeId: String): List<StepEntity>

    @Query("""
        SELECT * FROM step_ingredient_refs
        WHERE stepId IN (SELECT id FROM steps WHERE recipeId = :recipeId)
    """)
    suspend fun getStepRefsForRecipe(recipeId: String): List<StepIngredientRefEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipe(recipe: RecipeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSections(sections: List<RecipeSectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIngredients(ingredients: List<IngredientEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSteps(steps: List<StepEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStepRefs(refs: List<StepIngredientRefEntity>)

    @Update
    suspend fun updateRecipe(recipe: RecipeEntity)

    @Query("DELETE FROM recipes WHERE id = :id")
    suspend fun deleteRecipe(id: String)

    @Query("DELETE FROM recipe_sections WHERE recipeId = :recipeId")
    suspend fun deleteSectionsForRecipe(recipeId: String)

    @Query("DELETE FROM ingredients WHERE recipeId = :recipeId")
    suspend fun deleteIngredientsForRecipe(recipeId: String)

    @Query("DELETE FROM steps WHERE recipeId = :recipeId")
    suspend fun deleteStepsForRecipe(recipeId: String)

    @Query("""
        DELETE FROM step_ingredient_refs
        WHERE stepId IN (SELECT id FROM steps WHERE recipeId = :recipeId)
    """)
    suspend fun deleteStepRefsForRecipe(recipeId: String)

    @Query("DELETE FROM recipe_notes WHERE recipeId = :recipeId")
    suspend fun deleteNotesForRecipe(recipeId: String)

    @Query("SELECT COUNT(*) FROM recipes")
    suspend fun count(): Int
}
