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

    fun getImportedRecipes(): Flow<List<Recipe>> =
        recipeDao.getImportedRecipes().map { list -> list.map { it.toBasicDomain() } }

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

    suspend fun insertFullRecipe(
        recipe: RecipeEntity,
        sections: List<RecipeSectionEntity>,
        ingredients: List<IngredientEntity>,
        steps: List<StepEntity>,
        refs: List<StepIngredientRefEntity>
    ) = recipeDao.insertFullRecipe(recipe, sections, ingredients, steps, refs)

    suspend fun deleteFullRecipe(recipeId: String) =
        recipeDao.deleteFullRecipe(recipeId)

    suspend fun addNote(note: RecipeNoteEntity) = noteDao.insertNote(note)
    suspend fun updateNote(note: RecipeNoteEntity) = noteDao.updateNote(note)
    suspend fun deleteNote(id: String) = noteDao.deleteNote(id)
    suspend fun count() = recipeDao.count()

    private fun stringList(json: String): List<String> =
        gson.fromJson(json, object : TypeToken<List<String>>() {}.type)

    private fun RecipeEntity.toBasicDomain() = Recipe(
        id = id, title = title, description = description,
        sourceUrls = stringList(sourceUrls), baseServings = baseServings,
        baseServingsMin = baseServingsMin, baseServingsMax = baseServingsMax,
        scaleIngredientId = scaleIngredientId, scaleStep = scaleStep,
        prepTimeMinutes = prepTimeMinutes, cookTimeMinutes = cookTimeMinutes,
        imageUrl = imageUrl, tags = stringList(tags),
        sections = emptyList(), ingredients = emptyList(), steps = emptyList(),
        isCustomized = isCustomized, createdAt = createdAt, updatedAt = updatedAt
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
        isCustomized = isCustomized, createdAt = createdAt, updatedAt = updatedAt
    )

    private fun RecipeSectionEntity.toDomain() = RecipeSection(id, name, orderIndex)

    private fun IngredientEntity.toDomain() = Ingredient(
        id, sectionId, name, quantityValue, quantityUnit, quantityDisplay,
        groupLabel, isOptional, substituteGroupId, substituteRatio, orderIndex
    )

    private fun StepEntity.toDomain(refs: List<StepIngredientRefEntity>) = Step(
        id = id, sectionId = sectionId, instruction = instruction, orderIndex = orderIndex,
        ingredientRefs = refs.map { StepIngredientRef(it.ingredientId, it.quantityDisplay) }
    )

    private fun RecipeNoteEntity.toDomain() = RecipeNote(id, recipeId, content, createdAt, updatedAt)
}
