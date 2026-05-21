package com.aerion.amrosa.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.amrosa.data.local.entity.*
import com.aerion.amrosa.data.repository.RecipeRepository
import com.aerion.amrosa.domain.model.Ingredient
import com.aerion.amrosa.domain.model.Step
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ─── Editor data models ───────────────────────────────────────────────────────

data class EditorIngredient(
    val id: String = "ing-new-${UUID.randomUUID()}",
    val name: String = "",
    val quantityDisplay: String = "",
    val quantityUnit: String = "",
    val groupLabel: String = "",
    val isOptional: Boolean = false,
    // Preserved from original — not shown in v1 UI but kept on save
    val quantityValue: Double? = null,
    val substituteGroupId: String? = null,
    val substituteRatio: Float = 1.0f
)

data class EditorStep(
    val id: String = "step-new-${UUID.randomUUID()}",
    val instruction: String = ""
)

data class EditorSection(
    val id: String = "sec-new-${UUID.randomUUID()}",
    val name: String = "",
    val ingredients: List<EditorIngredient> = emptyList(),
    val steps: List<EditorStep> = emptyList()
)

data class EditorUiState(
    val isLoading: Boolean = true,
    val showForkDialog: Boolean = false,
    val isSaving: Boolean = false,
    val saveComplete: Boolean = false,
    val error: String? = null,
    // Recipe metadata
    val title: String = "",
    val description: String = "",
    val prepTimeMinutes: String = "",
    val cookTimeMinutes: String = "",
    val baseServings: String = "1",
    val isRangeYield: Boolean = false,
    val baseServingsMin: String = "",
    val baseServingsMax: String = "",
    val tagsText: String = "",         // comma-separated
    val sourceUrlsText: String = "",   // newline-separated
    // Content
    val sections: List<EditorSection> = emptyList(),
    // Deleted IDs — used to clean up DB rows on save
    val deletedSectionIds: List<String> = emptyList(),
    val deletedIngredientIds: List<String> = emptyList(),
    val deletedStepIds: List<String> = emptyList()
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

class RecipeEditorViewModel(
    private val repository: RecipeRepository,
    private val recipeId: String,
    private val gson: Gson
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // Original fields not exposed in the editor UI — preserved on save
    private var originalScaleIngredientId: String? = null
    private var originalScaleStep: Double = 1.0
    private var originalImageUrl: String? = null
    private var originalCreatedAt: Long = 0L

    init { loadRecipe() }

    // ─── Load ─────────────────────────────────────────────────────────────────

    private fun loadRecipe() {
        viewModelScope.launch {
            val recipe = withContext(Dispatchers.IO) {
                repository.getRecipeWithDetails(recipeId)
            }
            if (recipe == null) {
                _uiState.update { it.copy(isLoading = false, error = "Recipe not found") }
                return@launch
            }

            originalScaleIngredientId = recipe.scaleIngredientId
            originalScaleStep = recipe.scaleStep
            originalImageUrl = recipe.imageUrl
            originalCreatedAt = recipe.createdAt

            val sections = if (recipe.sections.isEmpty()) {
                // No explicit sections — wrap everything in one implicit section
                listOf(
                    EditorSection(
                        id = "sec-default-${UUID.randomUUID()}",
                        name = "",
                        ingredients = recipe.ingredients
                            .sortedBy { it.orderIndex }
                            .map { it.toEditor() },
                        steps = recipe.steps
                            .sortedBy { it.orderIndex }
                            .map { it.toEditor() }
                    )
                )
            } else {
                recipe.sections.sortedBy { it.orderIndex }.map { section ->
                    EditorSection(
                        id = section.id,
                        name = section.name,
                        ingredients = recipe.ingredients
                            .filter { it.sectionId == section.id }
                            .sortedBy { it.orderIndex }
                            .map { it.toEditor() },
                        steps = recipe.steps
                            .filter { it.sectionId == section.id }
                            .sortedBy { it.orderIndex }
                            .map { it.toEditor() }
                    )
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    showForkDialog = !recipe.isCustomized,
                    title = recipe.title,
                    description = recipe.description ?: "",
                    prepTimeMinutes = recipe.prepTimeMinutes?.toString() ?: "",
                    cookTimeMinutes = recipe.cookTimeMinutes?.toString() ?: "",
                    baseServings = recipe.baseServings.toString(),
                    isRangeYield = recipe.baseServingsMin != null,
                    baseServingsMin = recipe.baseServingsMin?.toString() ?: "",
                    baseServingsMax = recipe.baseServingsMax?.toString() ?: "",
                    tagsText = recipe.tags.joinToString(", "),
                    sourceUrlsText = recipe.sourceUrls.joinToString("\n"),
                    sections = sections
                )
            }
        }
    }

    // ─── Fork dialog ──────────────────────────────────────────────────────────

    /** User confirmed they want to fork the official recipe and edit it. */
    fun onForkConfirmed() = _uiState.update { it.copy(showForkDialog = false) }

    /** User declined to edit — signal the screen to navigate back. */
    fun onForkDismissed() = _uiState.update { it.copy(showForkDialog = false, saveComplete = true) }

    // ─── Metadata updaters ────────────────────────────────────────────────────

    fun updateTitle(v: String) = _uiState.update { it.copy(title = v) }
    fun updateDescription(v: String) = _uiState.update { it.copy(description = v) }
    fun updatePrepTime(v: String) = _uiState.update { it.copy(prepTimeMinutes = v.filter(Char::isDigit)) }
    fun updateCookTime(v: String) = _uiState.update { it.copy(cookTimeMinutes = v.filter(Char::isDigit)) }
    fun updateBaseServings(v: String) = _uiState.update { it.copy(baseServings = v.filter(Char::isDigit)) }
    fun updateIsRangeYield(v: Boolean) = _uiState.update { it.copy(isRangeYield = v) }
    fun updateBaseServingsMin(v: String) = _uiState.update { it.copy(baseServingsMin = v.filter(Char::isDigit)) }
    fun updateBaseServingsMax(v: String) = _uiState.update { it.copy(baseServingsMax = v.filter(Char::isDigit)) }
    fun updateTags(v: String) = _uiState.update { it.copy(tagsText = v) }
    fun updateSourceUrls(v: String) = _uiState.update { it.copy(sourceUrlsText = v) }

    // ─── Section operations ───────────────────────────────────────────────────

    fun addSection() = _uiState.update {
        it.copy(sections = it.sections + EditorSection(name = "New Section"))
    }

    fun updateSectionName(sectionId: String, name: String) =
        transformSection(sectionId) { it.copy(name = name) }

    fun deleteSection(sectionId: String) {
        val section = _uiState.value.sections.find { it.id == sectionId } ?: return
        _uiState.update {
            it.copy(
                sections = it.sections.filter { s -> s.id != sectionId },
                deletedSectionIds = it.deletedSectionIds + sectionId,
                deletedIngredientIds = it.deletedIngredientIds + section.ingredients.map { i -> i.id },
                deletedStepIds = it.deletedStepIds + section.steps.map { s -> s.id }
            )
        }
    }

    fun moveSectionUp(sectionId: String) =
        _uiState.update { it.copy(sections = it.sections.moved(sectionId, { s -> s.id }, -1)) }

    fun moveSectionDown(sectionId: String) =
        _uiState.update { it.copy(sections = it.sections.moved(sectionId, { s -> s.id }, +1)) }

    // ─── Ingredient operations ────────────────────────────────────────────────

    fun addIngredient(sectionId: String) =
        transformSection(sectionId) { it.copy(ingredients = it.ingredients + EditorIngredient()) }

    fun updateIngredient(sectionId: String, updated: EditorIngredient) =
        transformSection(sectionId) {
            it.copy(ingredients = it.ingredients.map { i -> if (i.id == updated.id) updated else i })
        }

    fun deleteIngredient(sectionId: String, ingredientId: String) {
        transformSection(sectionId) { s ->
            s.copy(ingredients = s.ingredients.filter { it.id != ingredientId })
        }
        _uiState.update { it.copy(deletedIngredientIds = it.deletedIngredientIds + ingredientId) }
    }

    fun moveIngredientUp(sectionId: String, ingredientId: String) =
        transformSection(sectionId) {
            it.copy(ingredients = it.ingredients.moved(ingredientId, { i -> i.id }, -1))
        }

    fun moveIngredientDown(sectionId: String, ingredientId: String) =
        transformSection(sectionId) {
            it.copy(ingredients = it.ingredients.moved(ingredientId, { i -> i.id }, +1))
        }

    // ─── Step operations ──────────────────────────────────────────────────────

    fun addStep(sectionId: String) =
        transformSection(sectionId) { it.copy(steps = it.steps + EditorStep()) }

    fun updateStep(sectionId: String, updated: EditorStep) =
        transformSection(sectionId) {
            it.copy(steps = it.steps.map { s -> if (s.id == updated.id) updated else s })
        }

    fun deleteStep(sectionId: String, stepId: String) {
        transformSection(sectionId) { s ->
            s.copy(steps = s.steps.filter { it.id != stepId })
        }
        _uiState.update { it.copy(deletedStepIds = it.deletedStepIds + stepId) }
    }

    fun moveStepUp(sectionId: String, stepId: String) =
        transformSection(sectionId) {
            it.copy(steps = it.steps.moved(stepId, { s -> s.id }, -1))
        }

    fun moveStepDown(sectionId: String, stepId: String) =
        transformSection(sectionId) {
            it.copy(steps = it.steps.moved(stepId, { s -> s.id }, +1))
        }

    // ─── Save ─────────────────────────────────────────────────────────────────

    fun save() {
        val state = _uiState.value
        val title = state.title.trim()
        if (title.isBlank()) {
            _uiState.update { it.copy(error = "Title cannot be empty") }
            return
        }

        _uiState.update { it.copy(isSaving = true, error = null) }
        val now = System.currentTimeMillis()

        viewModelScope.launch {
            try {
                val recipeEntity = RecipeEntity(
                    id = recipeId,
                    title = title,
                    description = state.description.trim().ifBlank { null },
                    sourceUrls = gson.toJson(
                        state.sourceUrlsText.lines()
                            .map { it.trim() }.filter { it.isNotBlank() }
                    ),
                    baseServings = state.baseServings.toIntOrNull() ?: 1,
                    baseServingsMin = if (state.isRangeYield) state.baseServingsMin.toIntOrNull() else null,
                    baseServingsMax = if (state.isRangeYield) state.baseServingsMax.toIntOrNull() else null,
                    scaleIngredientId = originalScaleIngredientId,
                    scaleStep = originalScaleStep,
                    prepTimeMinutes = state.prepTimeMinutes.toIntOrNull(),
                    cookTimeMinutes = state.cookTimeMinutes.toIntOrNull(),
                    imageUrl = originalImageUrl,
                    tags = gson.toJson(
                        state.tagsText.split(",")
                            .map { it.trim() }.filter { it.isNotBlank() }
                    ),
                    isCustomized = true,  // always true after any edit
                    createdAt = originalCreatedAt,
                    updatedAt = now
                )

                val sectionEntities = state.sections.mapIndexed { idx, section ->
                    RecipeSectionEntity(
                        id = section.id,
                        recipeId = recipeId,
                        name = section.name.trim(),
                        orderIndex = idx
                    )
                }

                val ingredientEntities = state.sections.flatMap { section ->
                    section.ingredients.mapIndexed { idx, ing ->
                        IngredientEntity(
                            id = ing.id,
                            recipeId = recipeId,
                            sectionId = section.id,
                            name = ing.name.trim(),
                            quantityValue = ing.quantityValue,
                            quantityUnit = ing.quantityUnit.trim().ifBlank { null },
                            quantityDisplay = ing.quantityDisplay.trim().ifBlank { null },
                            groupLabel = ing.groupLabel.trim().ifBlank { null },
                            isOptional = ing.isOptional,
                            substituteGroupId = ing.substituteGroupId,
                            substituteRatio = ing.substituteRatio,
                            orderIndex = idx
                        )
                    }
                }

                val stepEntities = state.sections.flatMap { section ->
                    section.steps.mapIndexed { idx, step ->
                        StepEntity(
                            id = step.id,
                            recipeId = recipeId,
                            sectionId = section.id,
                            instruction = step.instruction.trim(),
                            orderIndex = idx
                        )
                    }
                }

                withContext(Dispatchers.IO) {
                    repository.updateFullRecipe(
                        recipe = recipeEntity,
                        sections = sectionEntities,
                        ingredients = ingredientEntities,
                        steps = stepEntities,
                        deletedSectionIds = state.deletedSectionIds,
                        deletedIngredientIds = state.deletedIngredientIds,
                        deletedStepIds = state.deletedStepIds
                    )
                }

                _uiState.update { it.copy(isSaving = false, saveComplete = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = "Save failed: ${e.message}") }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun transformSection(sectionId: String, transform: (EditorSection) -> EditorSection) =
        _uiState.update { state ->
            state.copy(sections = state.sections.map { s -> if (s.id == sectionId) transform(s) else s })
        }

    /** Moves the item with [id] by [direction] (+1 down, -1 up). No-op if at boundary. */
    private fun <T> List<T>.moved(id: String, idOf: (T) -> String, direction: Int): List<T> {
        val idx = indexOfFirst { idOf(it) == id }
        val newIdx = idx + direction
        if (idx < 0 || newIdx !in indices) return this
        return toMutableList().also {
            val item = it.removeAt(idx)
            it.add(newIdx, item)
        }
    }

    private fun Ingredient.toEditor() = EditorIngredient(
        id = id,
        name = name,
        quantityDisplay = quantityDisplay ?: "",
        quantityUnit = quantityUnit ?: "",
        groupLabel = groupLabel ?: "",
        isOptional = isOptional,
        quantityValue = quantityValue,
        substituteGroupId = substituteGroupId,
        substituteRatio = substituteRatio
    )

    private fun Step.toEditor() = EditorStep(id = id, instruction = instruction)

    companion object {
        fun factory(repository: RecipeRepository, recipeId: String, gson: Gson) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeEditorViewModel(repository, recipeId, gson) as T
            }
    }
}
