package com.aerion.amrosa.data.repository

import com.aerion.amrosa.data.local.dao.RecipeDao
import com.aerion.amrosa.data.local.dao.RecipeNoteDao
import com.aerion.amrosa.data.local.entity.*
import com.aerion.amrosa.domain.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RecipeRepository(
    private val recipeDao: RecipeDao,
    private val noteDao: RecipeNoteDao,
    private val gson: Gson
) {
    fun getAllRecipes(): Flow<List<Recipe>> =
        recipeDao.getAllRecipes().map { list -> list.map { it.toBasicDomain() } }

    fun getPersonalRecipes(): Flow<List<Recipe>> =
        recipeDao.getPersonalRecipes().map { list -> list.map { it.toBasicDomain() } }

    fun getImportedRecipes(): Flow<List<Recipe>> =
        recipeDao.getImportedRecipes().map { list -> list.map { it.toBasicDomain() } }

    /** All user recipes (personal + imported), pending review first. */
    fun getYoursRecipes(): Flow<List<Recipe>> =
        recipeDao.getYoursRecipes().map { list -> list.map { it.toBasicDomain() } }

    suspend fun getRecipeWithDetails(id: String): Recipe? {
        val entity = recipeDao.getRecipeById(id) ?: return null
        val sections = recipeDao.getSectionsForRecipe(id)
        val ingredients = recipeDao.getIngredientsForRecipe(id)
        val steps = recipeDao.getStepsForRecipe(id)
        val refs = recipeDao.getStepRefsForRecipe(id)
        return entity.toDomain(
            sections = sections.map { it.toDomain() },
            ingredients = ingredients.map { it.toDomain() },
            steps = steps.map { step ->
                step.toDomain(refs.filter { it.stepId == step.id })
            }
        )
    }

    fun getNotesForRecipe(recipeId: String): Flow<List<RecipeNote>> =
        noteDao.getNotesForRecipe(recipeId).map { list -> list.map { it.toDomain() } }

    suspend fun updateFullRecipe(
        recipe: RecipeEntity,
        sections: List<RecipeSectionEntity>,
        ingredients: List<IngredientEntity>,
        steps: List<StepEntity>,
        deletedSectionIds: List<String>,
        deletedIngredientIds: List<String>,
        deletedStepIds: List<String>
    ) = recipeDao.replaceFullRecipe(
        recipe, sections, ingredients, steps,
        deletedSectionIds, deletedIngredientIds, deletedStepIds
    )

    suspend fun insertFullRecipe(
        recipe: RecipeEntity,
        sections: List<RecipeSectionEntity>,
        ingredients: List<IngredientEntity>,
        steps: List<StepEntity>,
        refs: List<StepIngredientRefEntity>
    ) = recipeDao.insertFullRecipe(recipe, sections, ingredients, steps, refs)

    suspend fun deleteFullRecipe(recipeId: String) =
        recipeDao.deleteFullRecipe(recipeId)

    /** Clears the needsReview flag — called when the user taps Confirm on the review sheet. */
    suspend fun confirmImportedRecipe(recipeId: String) =
        recipeDao.updateNeedsReview(recipeId, false)

    /** Updates the isImported flag — called when the user changes author choice on the review sheet. */
    suspend fun updateIsImported(recipeId: String, isImported: Boolean) =
        recipeDao.updateIsImported(recipeId, isImported)

    suspend fun addNote(note: RecipeNoteEntity) = noteDao.insertNote(note)
    suspend fun updateNote(note: RecipeNoteEntity) = noteDao.updateNote(note)
    suspend fun deleteNote(id: String) = noteDao.deleteNote(id)
    suspend fun count() = recipeDao.count()

    /** Returns every personal recipe with full sections/ingredients/steps — for bulk cloud push. */
    suspend fun setVisibility(recipeId: String, visibility: String) =
        recipeDao.updateVisibility(recipeId, visibility)

    suspend fun getAllPersonalRecipesWithDetails(): List<Recipe> =
        recipeDao.getPersonalRecipesOnce().mapNotNull { entity ->
            getRecipeWithDetails(entity.id)
        }

    /** IDs of all locally-cached received recipes (Tab 2). Used by the received-reference refresh. */
    suspend fun getReceivedRecipeIds(): List<String> = recipeDao.getReceivedRecipeIdsOnce()

    // ─── Domain mapping ───────────────────────────────────────────────────────

    private fun changeLogList(json: String): List<RecipeChange> =
        try {
            gson.fromJson(json, object : TypeToken<List<RecipeChange>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    private fun stringList(json: String): List<String> =
        gson.fromJson(json, object : TypeToken<List<String>>() {}.type) ?: emptyList()

    private fun RecipeEntity.toBasicDomain() = Recipe(
        id = id, title = title, description = description,
        sourceUrls = stringList(sourceUrls), baseServings = baseServings,
        baseServingsMin = baseServingsMin, baseServingsMax = baseServingsMax,
        scaleIngredientId = scaleIngredientId, scaleStep = scaleStep,
        prepTimeMinutes = prepTimeMinutes, cookTimeMinutes = cookTimeMinutes,
        imageUrl = imageUrl, tags = stringList(tags),
        sections = emptyList(), ingredients = emptyList(), steps = emptyList(),
        isCustomized = isCustomized, isImported = isImported,
        isReceived = isReceived,
        needsReview = needsReview,
        version = version, changeLog = changeLogList(changeLog),
        createdAt = createdAt, updatedAt = updatedAt,
        authorId = authorId, authorDisplayName = authorDisplayName,
        visibility = visibility
    )

    private fun RecipeEntity.toDomain(
        sections: List<RecipeSection>,
        ingredients: List<Ingredient>,
        steps: List<Step>
    ) = Recipe(
        id = id, title = title, description = description,
        sourceUrls = stringList(sourceUrls), baseServings = baseServings,
        baseServingsMin = baseServingsMin, baseServingsMax = baseServingsMax,
        scaleIngredientId = scaleIngredientId, scaleStep = scaleStep,
        prepTimeMinutes = prepTimeMinutes, cookTimeMinutes = cookTimeMinutes,
        imageUrl = imageUrl, tags = stringList(tags),
        sections = sections, ingredients = ingredients, steps = steps,
        isCustomized = isCustomized, isImported = isImported,
        isReceived = isReceived,
        needsReview = needsReview,
        version = version, changeLog = changeLogList(changeLog),
        createdAt = createdAt, updatedAt = updatedAt,
        authorId = authorId, authorDisplayName = authorDisplayName,
        visibility = visibility
    )

    private fun RecipeSectionEntity.toDomain() = RecipeSection(id, name, orderIndex)

    private fun IngredientEntity.toDomain() = Ingredient(
        id = id, sectionId = sectionId, name = name,
        quantityValue = quantityValue, quantityUnit = quantityUnit, quantityDisplay = quantityDisplay,
        quantityValueMetric = quantityValueMetric, quantityUnitMetric = quantityUnitMetric,
        quantityDisplayMetric = quantityDisplayMetric,
        quantityValueImperial = quantityValueImperial, quantityUnitImperial = quantityUnitImperial,
        quantityDisplayImperial = quantityDisplayImperial,
        groupLabel = groupLabel, isOptional = isOptional,
        substituteGroupId = substituteGroupId, substituteRatio = substituteRatio,
        orderIndex = orderIndex
    )

    private fun StepEntity.toDomain(refs: List<StepIngredientRefEntity>) = Step(
        id = id, sectionId = sectionId, instruction = instruction, orderIndex = orderIndex,
        ingredientRefs = refs.map { StepIngredientRef(it.ingredientId, it.quantityDisplay) }
    )

    private fun RecipeNoteEntity.toDomain() = RecipeNote(id, recipeId, content, createdAt, updatedAt)
}
