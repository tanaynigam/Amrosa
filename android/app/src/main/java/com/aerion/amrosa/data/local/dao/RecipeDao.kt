package com.aerion.amrosa.data.local.dao

import androidx.room.*
import com.aerion.amrosa.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RecipeDao {

    // List queries show BASE recipes only (parentRecipeId IS NULL); variations are reached
    // via the base recipe's detail screen, never as standalone list cards.
    @Query("SELECT * FROM recipes WHERE parentRecipeId IS NULL ORDER BY title ASC")
    abstract fun getAllRecipes(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE isImported = 0 AND parentRecipeId IS NULL ORDER BY title ASC")
    abstract fun getPersonalRecipes(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE isImported = 1 AND parentRecipeId IS NULL ORDER BY needsReview DESC, createdAt DESC")
    abstract fun getImportedRecipes(): Flow<List<RecipeEntity>>

    /** Tab 1 — my recipes only (personal + imported), pending review first. Excludes received + variations. */
    @Query("SELECT * FROM recipes WHERE isReceived = 0 AND parentRecipeId IS NULL ORDER BY needsReview DESC, updatedAt DESC")
    abstract fun getYoursRecipes(): Flow<List<RecipeEntity>>

    /** All variations of a base recipe (one-shot), oldest first. */
    @Query("SELECT * FROM recipes WHERE parentRecipeId = :parentId ORDER BY createdAt ASC")
    abstract suspend fun getVariantsOnce(parentId: String): List<RecipeEntity>

    /** Tab 2 — recipes received from other users (read-only references), newest first. */
    @Query("SELECT * FROM recipes WHERE isReceived = 1 ORDER BY updatedAt DESC")
    abstract fun getReceivedRecipes(): Flow<List<RecipeEntity>>

    @Query("UPDATE recipes SET needsReview = :needsReview WHERE id = :id")
    abstract suspend fun updateNeedsReview(id: String, needsReview: Boolean)

    @Query("UPDATE recipes SET isImported = :isImported WHERE id = :id")
    abstract suspend fun updateIsImported(id: String, isImported: Boolean)

    @Query("SELECT * FROM recipes WHERE id = :id")
    abstract suspend fun getRecipeById(id: String): RecipeEntity?

    /** Observe a single recipe row. Emits on every edit (version/updatedAt bump) → drives auto-refresh. */
    @Query("SELECT * FROM recipes WHERE id = :id")
    abstract fun observeRecipe(id: String): Flow<RecipeEntity?>

    /** Local last-edit timestamp for a recipe (null if not present) — used for pull conflict checks. */
    @Query("SELECT updatedAt FROM recipes WHERE id = :id")
    abstract suspend fun getUpdatedAt(id: String): Long?

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

    // ─── Shopping list checked items ──────────────────────────────────────────

    @Query("SELECT itemKey FROM shopping_checks WHERE recipeId = :recipeId")
    abstract fun getShoppingChecks(recipeId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertShoppingCheck(check: ShoppingCheckEntity)

    @Query("DELETE FROM shopping_checks WHERE recipeId = :recipeId AND itemKey = :itemKey")
    abstract suspend fun deleteShoppingCheck(recipeId: String, itemKey: String)

    @Query("DELETE FROM shopping_checks WHERE recipeId = :recipeId")
    abstract suspend fun deleteShoppingChecksForRecipe(recipeId: String)

    @Query("SELECT COUNT(*) FROM recipes")
    abstract suspend fun count(): Int

    /**
     * One-shot read of recipes eligible for cloud push to personal_recipes:
     * personal (isImported=0) AND owned by me (isReceived=0). Received references are never pushed.
     */
    @Query("SELECT * FROM recipes WHERE isImported = 0 AND isReceived = 0 ORDER BY title ASC")
    abstract suspend fun getPersonalRecipesOnce(): List<RecipeEntity>

    /** IDs of all locally-cached received recipes (Tab 2). Used by the received-reference refresh. */
    @Query("SELECT id FROM recipes WHERE isReceived = 1")
    abstract suspend fun getReceivedRecipeIdsOnce(): List<String>

    @Query("UPDATE recipes SET visibility = :visibility WHERE id = :id")
    abstract suspend fun updateVisibility(id: String, visibility: String)

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
     * Replace a recipe's SYNCED content from a cloud pull. Unlike [insertFullRecipe] (which only
     * REPLACE-inserts by id and so leaves orphan child rows for items the cloud no longer has),
     * this first clears the recipe's sections/ingredients/steps/refs, then re-inserts the fresh set.
     * Local-only data (recipe_notes, shopping_checks) is intentionally NOT touched.
     */
    @Transaction
    open suspend fun replacePulledRecipe(
        recipe: RecipeEntity,
        sections: List<RecipeSectionEntity>,
        ingredients: List<IngredientEntity>,
        steps: List<StepEntity>,
        refs: List<StepIngredientRefEntity>
    ) {
        deleteStepRefsForRecipe(recipe.id)
        deleteStepsForRecipe(recipe.id)
        deleteIngredientsForRecipe(recipe.id)
        deleteSectionsForRecipe(recipe.id)
        insertRecipe(recipe)
        insertSections(sections)
        insertIngredients(ingredients)
        insertSteps(steps)
        insertStepRefs(refs)
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
        deleteShoppingChecksForRecipe(recipeId)
        deleteStepsForRecipe(recipeId)
        deleteIngredientsForRecipe(recipeId)
        deleteSectionsForRecipe(recipeId)
        deleteRecipe(recipeId)
    }
}
