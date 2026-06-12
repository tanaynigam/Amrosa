package com.aerion.amrosa.data.repository

import com.aerion.amrosa.data.local.dao.RecipeDao
import com.aerion.amrosa.data.local.dao.RecipeNoteDao
import com.aerion.amrosa.data.local.entity.*
import com.aerion.amrosa.domain.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

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

    /** Tab 1 — my recipes only (personal + imported), pending review first. */
    fun getYoursRecipes(): Flow<List<Recipe>> =
        recipeDao.getYoursRecipes().map { list -> list.map { it.toBasicDomain() } }

    /** Tab 2 — recipes received from other users (read-only references). */
    fun getReceivedRecipes(): Flow<List<Recipe>> =
        recipeDao.getReceivedRecipes().map { list -> list.map { it.toBasicDomain() } }

    /** Emits whenever the recipe row changes (e.g. after an editor save) — drives detail auto-refresh. */
    fun observeRecipe(id: String): Flow<com.aerion.amrosa.data.local.entity.RecipeEntity?> =
        recipeDao.observeRecipe(id)

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
        refs: List<StepIngredientRefEntity>,
        deletedSectionIds: List<String>,
        deletedIngredientIds: List<String>,
        deletedStepIds: List<String>
    ) = recipeDao.replaceFullRecipe(
        recipe, sections, ingredients, steps, refs,
        deletedSectionIds, deletedIngredientIds, deletedStepIds
    )

    suspend fun insertFullRecipe(
        recipe: RecipeEntity,
        sections: List<RecipeSectionEntity>,
        ingredients: List<IngredientEntity>,
        steps: List<StepEntity>,
        refs: List<StepIngredientRefEntity>
    ) = recipeDao.insertFullRecipe(recipe, sections, ingredients, steps, refs)

    /** Clean-replace a recipe's synced content from a cloud pull (preserves local notes + shopping checks). */
    suspend fun replacePulledRecipe(
        recipe: RecipeEntity,
        sections: List<RecipeSectionEntity>,
        ingredients: List<IngredientEntity>,
        steps: List<StepEntity>,
        refs: List<StepIngredientRefEntity>
    ) = recipeDao.replacePulledRecipe(recipe, sections, ingredients, steps, refs)

    /** Local last-edit timestamp for a recipe (null if absent) — for pull conflict resolution. */
    suspend fun getLocalUpdatedAt(id: String): Long? = recipeDao.getUpdatedAt(id)

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

    /**
     * Cache a received recipe (from a shared_recipes mirror) into Room with isReceived = true.
     * Uses the canonical recipe id + child ids so later refreshes REPLACE the same rows.
     * Preserves the original author; the local copy is private + read-only.
     */
    suspend fun cacheReceivedRecipe(recipe: Recipe) {
        val now = System.currentTimeMillis()
        val recipeEntity = RecipeEntity(
            id = recipe.id,
            title = recipe.title,
            description = recipe.description,
            sourceUrls = gson.toJson(recipe.sourceUrls),
            baseServings = recipe.baseServings,
            baseServingsMin = recipe.baseServingsMin,
            baseServingsMax = recipe.baseServingsMax,
            scaleIngredientId = recipe.scaleIngredientId,
            scaleStep = recipe.scaleStep,
            prepTimeMinutes = recipe.prepTimeMinutes,
            cookTimeMinutes = recipe.cookTimeMinutes,
            imageUrl = recipe.imageUrl,
            tags = gson.toJson(recipe.tags),
            isCustomized = recipe.isCustomized,
            isImported = recipe.isImported,
            isReceived = true,
            needsReview = false,
            version = recipe.version,
            changeLog = "[]",
            createdAt = recipe.createdAt.takeIf { it > 0 } ?: now,
            updatedAt = now,
            authorId = recipe.authorId,
            authorDisplayName = recipe.authorDisplayName,
            visibility = "private"
        )
        val sections = recipe.sections.map {
            RecipeSectionEntity(id = it.id, recipeId = recipe.id, name = it.name, orderIndex = it.orderIndex)
        }
        val ingredients = recipe.ingredients.map {
            IngredientEntity(
                id = it.id, recipeId = recipe.id, sectionId = it.sectionId, name = it.name,
                quantityValue = it.quantityValue, quantityUnit = it.quantityUnit, quantityDisplay = it.quantityDisplay,
                groupLabel = it.groupLabel, isOptional = it.isOptional,
                substituteGroupId = it.substituteGroupId, substituteRatio = it.substituteRatio,
                orderIndex = it.orderIndex,
                quantityValueMetric = it.quantityValueMetric, quantityUnitMetric = it.quantityUnitMetric,
                quantityDisplayMetric = it.quantityDisplayMetric,
                quantityValueImperial = it.quantityValueImperial, quantityUnitImperial = it.quantityUnitImperial,
                quantityDisplayImperial = it.quantityDisplayImperial,
                quantityValueMax = it.quantityValueMax, quantityValueMaxMetric = it.quantityValueMaxMetric,
                quantityValueMaxImperial = it.quantityValueMaxImperial,
                shoppingNote = it.shoppingNote
            )
        }
        val steps = recipe.steps.map {
            StepEntity(id = it.id, recipeId = recipe.id, sectionId = it.sectionId,
                instruction = it.instruction, orderIndex = it.orderIndex)
        }
        val refs = recipe.steps.flatMap { step ->
            step.ingredientRefs.map { ref ->
                StepIngredientRefEntity(stepId = step.id, ingredientId = ref.ingredientId,
                    quantityDisplay = ref.quantityDisplay)
            }
        }
        recipeDao.insertFullRecipe(recipeEntity, sections, ingredients, steps, refs)
    }

    /** Remove a received recipe's local cache (the cloud reference is removed separately). */
    suspend fun removeReceivedRecipe(recipeId: String) = recipeDao.deleteFullRecipe(recipeId)

    // ─── Shopping list checked items ────────────────────────────────────────────

    fun checkedShoppingItems(recipeId: String): Flow<List<String>> =
        recipeDao.getShoppingChecks(recipeId)

    suspend fun setShoppingChecked(recipeId: String, itemKey: String, checked: Boolean) {
        if (checked) recipeDao.insertShoppingCheck(ShoppingCheckEntity(recipeId, itemKey))
        else recipeDao.deleteShoppingCheck(recipeId, itemKey)
    }

    suspend fun clearShoppingChecks(recipeId: String) =
        recipeDao.deleteShoppingChecksForRecipe(recipeId)

    // ─── Cooked log (Discover recency) ──────────────────────────────────────────

    /** Record that a recipe was just cooked (latest cook wins). */
    suspend fun markCooked(recipeId: String) =
        recipeDao.upsertCooked(CookedLogEntity(recipeId, System.currentTimeMillis()))

    /** recipeId → last cookedAt, for the recency penalty + "Recently cooked" shelf. */
    fun cookedLogFlow(): Flow<Map<String, Long>> =
        recipeDao.getCookedLog().map { list -> list.associate { it.recipeId to it.cookedAt } }

    // ─── Recipe variations ──────────────────────────────────────────────────────

    /** All variations of a base recipe (basic domain — no children loaded). */
    suspend fun getVariants(parentId: String): List<Recipe> =
        recipeDao.getVariantsOnce(parentId).map { it.toBasicDomain() }

    /**
     * Deep-copies a recipe into a new variation with fresh ids. Every internal reference
     * (section / ingredient / step ids, step→ingredient refs, the scale anchor, and
     * substitute-group ids) is remapped so the copy is fully self-contained.
     * The new variation points at the *original* base ([Recipe.parentRecipeId] of the source,
     * or the source itself), so variations-of-variations still group under one base.
     *
     * @return the new recipe id, or null if the source can't be loaded.
     */
    suspend fun duplicateAsVariant(
        sourceId: String,
        variantName: String,
        currentUid: String?,
        displayName: String?
    ): String? {
        val source = getRecipeWithDetails(sourceId) ?: return null
        val baseId = source.parentRecipeId ?: source.id
        val now = System.currentTimeMillis()
        val newRecipeId = "recipe-var-${UUID.randomUUID()}"

        val sectionIdMap = source.sections.associate { it.id to "sec-${UUID.randomUUID()}" }
        val ingredientIdMap = source.ingredients.associate { it.id to "ing-${UUID.randomUUID()}" }
        val stepIdMap = source.steps.associate { it.id to "step-${UUID.randomUUID()}" }
        val subGroupMap = source.ingredients.mapNotNull { it.substituteGroupId }.distinct()
            .associateWith { "subg-${UUID.randomUUID()}" }

        val recipeEntity = RecipeEntity(
            id = newRecipeId,
            title = source.title,
            description = source.description,
            sourceUrls = gson.toJson(source.sourceUrls),
            baseServings = source.baseServings,
            baseServingsMin = source.baseServingsMin,
            baseServingsMax = source.baseServingsMax,
            scaleIngredientId = source.scaleIngredientId?.let { ingredientIdMap[it] },
            scaleStep = source.scaleStep,
            prepTimeMinutes = source.prepTimeMinutes,
            cookTimeMinutes = source.cookTimeMinutes,
            imageUrl = source.imageUrl,
            tags = gson.toJson(source.tags),
            isCustomized = true,
            isImported = source.isImported,
            isReceived = false,
            needsReview = false,
            version = 1,
            changeLog = "[]",
            createdAt = now,
            updatedAt = now,
            syncedAt = null,
            authorId = currentUid ?: source.authorId,
            authorDisplayName = displayName ?: source.authorDisplayName,
            visibility = "private",
            parentRecipeId = baseId,
            variantName = variantName
        )

        val sections = source.sections.map { s ->
            RecipeSectionEntity(
                id = sectionIdMap.getValue(s.id),
                recipeId = newRecipeId,
                name = s.name,
                orderIndex = s.orderIndex
            )
        }
        val ingredients = source.ingredients.map { i ->
            IngredientEntity(
                id = ingredientIdMap.getValue(i.id),
                recipeId = newRecipeId,
                sectionId = i.sectionId?.let { sectionIdMap[it] },
                name = i.name,
                quantityValue = i.quantityValue, quantityUnit = i.quantityUnit, quantityDisplay = i.quantityDisplay,
                quantityValueMetric = i.quantityValueMetric, quantityUnitMetric = i.quantityUnitMetric,
                quantityDisplayMetric = i.quantityDisplayMetric,
                quantityValueImperial = i.quantityValueImperial, quantityUnitImperial = i.quantityUnitImperial,
                quantityDisplayImperial = i.quantityDisplayImperial,
                quantityValueMax = i.quantityValueMax, quantityValueMaxMetric = i.quantityValueMaxMetric,
                quantityValueMaxImperial = i.quantityValueMaxImperial,
                groupLabel = i.groupLabel, isOptional = i.isOptional,
                substituteGroupId = i.substituteGroupId?.let { subGroupMap[it] },
                substituteRatio = i.substituteRatio,
                orderIndex = i.orderIndex,
                shoppingNote = i.shoppingNote
            )
        }
        val steps = source.steps.map { st ->
            StepEntity(
                id = stepIdMap.getValue(st.id),
                recipeId = newRecipeId,
                sectionId = st.sectionId?.let { sectionIdMap[it] },
                instruction = st.instruction,
                orderIndex = st.orderIndex
            )
        }
        val refs = source.steps.flatMap { st ->
            val newStepId = stepIdMap.getValue(st.id)
            st.ingredientRefs.mapNotNull { ref ->
                val newIngId = ingredientIdMap[ref.ingredientId] ?: return@mapNotNull null
                StepIngredientRefEntity(stepId = newStepId, ingredientId = newIngId, quantityDisplay = ref.quantityDisplay)
            }
        }

        recipeDao.insertFullRecipe(recipeEntity, sections, ingredients, steps, refs)
        return newRecipeId
    }

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
        visibility = visibility,
        parentRecipeId = parentRecipeId, variantName = variantName
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
        visibility = visibility,
        parentRecipeId = parentRecipeId, variantName = variantName
    )

    private fun RecipeSectionEntity.toDomain() = RecipeSection(id, name, orderIndex)

    private fun IngredientEntity.toDomain() = Ingredient(
        id = id, sectionId = sectionId, name = name,
        quantityValue = quantityValue, quantityUnit = quantityUnit, quantityDisplay = quantityDisplay,
        quantityValueMetric = quantityValueMetric, quantityUnitMetric = quantityUnitMetric,
        quantityDisplayMetric = quantityDisplayMetric,
        quantityValueImperial = quantityValueImperial, quantityUnitImperial = quantityUnitImperial,
        quantityDisplayImperial = quantityDisplayImperial,
        quantityValueMax = quantityValueMax, quantityValueMaxMetric = quantityValueMaxMetric,
        quantityValueMaxImperial = quantityValueMaxImperial,
        groupLabel = groupLabel, isOptional = isOptional,
        substituteGroupId = substituteGroupId, substituteRatio = substituteRatio,
        orderIndex = orderIndex,
        shoppingNote = shoppingNote
    )

    private fun StepEntity.toDomain(refs: List<StepIngredientRefEntity>) = Step(
        id = id, sectionId = sectionId, instruction = instruction, orderIndex = orderIndex,
        ingredientRefs = refs.map { StepIngredientRef(it.ingredientId, it.quantityDisplay) }
    )

    private fun RecipeNoteEntity.toDomain() = RecipeNote(id, recipeId, content, createdAt, updatedAt)
}
