package com.aerion.amrosa.data.local.dao

import androidx.room.*
import com.aerion.amrosa.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RecipeDao {

    @Query("SELECT * FROM recipes ORDER BY title ASC")
    abstract fun getAllRecipes(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE isCustomized = 1 ORDER BY createdAt DESC")
    abstract fun getImportedRecipes(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    abstract suspend fun getRecipeById(id: String): RecipeEntity?

    @Query("SELECT * FROM recipe_sections WHERE recipeId = :recipeId ORDER BY orderIndex ASC")
    abstract suspend fun getSectionsForRecipe(recipeId: String): List<RecipeSectionEntity>

    @Query("SELECT * FROM ingredients WHERE recipeId = :recipeId ORDER BY orderIndex ASC")
    abstract suspend fun getIngredientsForRecipe(recipeId: String): List<IngredientEntity>

    @Query("SELECT * FROM steps WHERE recipeId = :recipeId ORDER BY orderIndex ASC")
    abstract suspend fun getStepsForRecipe(recipeId: String): List<StepEntity>

    @Query("""
        SELECT * FROM step_ingredient_refs
        WHERE stepId IN (SELECT id FROM steps WHERE recipeId = :recipeId)
    """)
    abstract suspend fun getStepRefsForRecipe(recipeId: String): List<StepIngredientRefEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertRecipe(recipe: RecipeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSections(sections: List<RecipeSectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertIngredients(ingredients: List<IngredientEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSteps(steps: List<StepEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertStepRefs(refs: List<StepIngredientRefEntity>)

    @Update
    abstract suspend fun updateRecipe(recipe: RecipeEntity)

    @Query("DELETE FROM recipes WHERE id = :id")
    abstract suspend fun deleteRecipe(id: String)

    @Query("DELETE FROM recipe_sections WHERE recipeId = :recipeId")
    abstract suspend fun deleteSectionsForRecipe(recipeId: String)

    @Query("DELETE FROM ingredients WHERE recipeId = :recipeId")
    abstract suspend fun deleteIngredientsForRecipe(recipeId: String)

    @Query("DELETE FROM steps WHERE recipeId = :recipeId")
    abstract suspend fun deleteStepsForRecipe(recipeId: String)

    @Query("""
        DELETE FROM step_ingredient_refs
        WHERE stepId IN (SELECT id FROM steps WHERE recipeId = :recipeId)
    """)
    abstract suspend fun deleteStepRefsForRecipe(recipeId: String)

    @Query("DELETE FROM recipe_notes WHERE recipeId = :recipeId")
    abstract suspend fun deleteNotesForRecipe(recipeId: String)

    @Query("SELECT COUNT(*) FROM recipes")
    abstract suspend fun count(): Int

    // ─── Single-item deletes (used by Recipe Editor) ──────────────────────────

    @Query("DELETE FROM steps WHERE id = :stepId")
    abstract suspend fun deleteStep(stepId: String)

    @Query("DELETE FROM ingredients WHERE id = :ingredientId")
    abstract suspend fun deleteIngredient(ingredientId: String)

    @Query("DELETE FROM recipe_sections WHERE id = :sectionId")
    abstract suspend fun deleteSection(sectionId: String)

    @Query("DELETE FROM step_ingredient_refs WHERE stepId = :stepId")
    abstract suspend fun deleteStepRefsForStep(stepId: String)

    @Query("DELETE FROM step_ingredient_refs WHERE ingredientId = :ingredientId")
    abstract suspend fun deleteStepRefsByIngredient(ingredientId: String)

    /**
     * Atomically applies recipe editor changes:
     * - Removes deleted steps/ingredients/sections (and orphaned refs)
     * - Upserts the recipe + all surviving/new entities
     * - Does NOT touch recipe_notes (notes survive edits)
     * - Does NOT touch refs for unchanged steps (they stay linked)
     */
    @Transaction
    open suspend fun replaceFullRecipe(
        recipe: RecipeEntity,
        sections: List<RecipeSectionEntity>,
        ingredients: List<IngredientEntity>,
        steps: List<StepEntity>,
        deletedSectionIds: List<String>,
        deletedIngredientIds: List<String>,
        deletedStepIds: List<String>
    ) {
        // Clean up refs first so foreign-key-like integrity is maintained
        deletedStepIds.forEach { deleteStepRefsForStep(it) }
        deletedIngredientIds.forEach { deleteStepRefsByIngredient(it) }
        // Delete removed items (order: steps → ingredients → sections)
        deletedStepIds.forEach { deleteStep(it) }
        deletedIngredientIds.forEach { deleteIngredient(it) }
        deletedSectionIds.forEach { deleteSection(it) }
        // Upsert everything that remains or is new (REPLACE handles both)
        insertRecipe(recipe)
        insertSections(sections)
        insertIngredients(ingredients)
        insertSteps(steps)
    }

    /**
     * Inserts a full recipe (all 5 tables) atomically.
     * If any insert fails the entire operation is rolled back — no partial state.
     */
    @Transaction
    open suspend fun insertFullRecipe(
        recipe: RecipeEntity,
        sections: List<RecipeSectionEntity>,
        ingredients: List<IngredientEntity>,
        steps: List<StepEntity>,
        refs: List<StepIngredientRefEntity>
    ) {
        insertRecipe(recipe)
        insertSections(sections)
        insertIngredients(ingredients)
        insertSteps(steps)
        insertStepRefs(refs)
    }

    /**
     * Deletes a full recipe (all related rows across 6 tables) atomically.
     * Order matters: child rows first, then the recipe row itself.
     */
    @Transaction
    open suspend fun deleteFullRecipe(recipeId: String) {
        deleteStepRefsForRecipe(recipeId)
        deleteNotesForRecipe(recipeId)
        deleteStepsForRecipe(recipeId)
        deleteIngredientsForRecipe(recipeId)
        deleteSectionsForRecipe(recipeId)
        deleteRecipe(recipeId)
    }
}
