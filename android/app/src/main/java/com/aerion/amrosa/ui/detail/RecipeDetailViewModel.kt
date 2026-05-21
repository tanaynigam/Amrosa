package com.aerion.amrosa.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.amrosa.data.local.entity.RecipeNoteEntity
import com.aerion.amrosa.data.repository.RecipeRepository
import com.aerion.amrosa.domain.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class RecipeDetailUiState(
    val recipe: Recipe? = null,
    val selectedServings: Int = 0,
    /** Current quantity of the scale-anchor ingredient (e.g. flour cups). */
    val scaleAnchorQty: Double? = null,
    val selectedSubstitutes: Map<String, String> = emptyMap(),
    val enabledOptionals: Set<String> = emptySet(),
    val checkedIngredients: Set<String> = emptySet(),
    val notes: List<RecipeNote> = emptyList(),
    val isLoading: Boolean = true
) {
    val visibleIngredients: List<Ingredient> get() {
        val recipe = recipe ?: return emptyList()
        return recipe.ingredients.filter { ing ->
            if (ing.isOptional && ing.id !in enabledOptionals) return@filter false
            if (ing.substituteGroupId != null) {
                val selectedId = selectedSubstitutes[ing.substituteGroupId]
                    ?: recipe.ingredients.firstOrNull { it.substituteGroupId == ing.substituteGroupId }?.id
                return@filter ing.id == selectedId
            }
            true
        }
    }

    /** Scale factor derived from anchor ingredient qty when available, else from servings. */
    val scaleFactor: Double get() {
        val recipe = recipe ?: return 1.0
        // Anchor-based scaling: ratio of current anchor qty to base anchor qty
        if (scaleAnchorQty != null && recipe.scaleIngredientId != null) {
            val baseQty = recipe.ingredients
                .find { it.id == recipe.scaleIngredientId }?.quantityValue ?: return 1.0
            if (baseQty == 0.0) return 1.0
            return scaleAnchorQty / baseQty
        }
        // Fallback: servings-based
        if (recipe.baseServings == 0) return 1.0
        return selectedServings.toDouble() / recipe.baseServings
    }

    /** Whether this recipe uses ingredient-based scaling. */
    val usesAnchorScaling: Boolean get() =
        recipe?.scaleIngredientId != null

    /** Yield display string: range or single number, scaled. */
    val yieldDisplay: String get() {
        val recipe = recipe ?: return "$selectedServings"
        val min = recipe.baseServingsMin
        val max = recipe.baseServingsMax
        if (min != null && max != null) {
            val scaledMin = (min * scaleFactor).toInt()
            val scaledMax = (max * scaleFactor).toInt()
            return if (scaledMin == scaledMax) "$scaledMin" else "$scaledMin–$scaledMax"
        }
        return "${(recipe.baseServings * scaleFactor).toInt()}"
    }

    /** Whether the current scaling is at the default (1×). */
    val isDefaultScale: Boolean get() = scaleFactor == 1.0

    fun resolvedIngredientName(ingredientId: String): String {
        val recipe = recipe ?: return ""
        val ing = recipe.ingredients.find { it.id == ingredientId } ?: return ""
        if (ing.substituteGroupId != null) {
            val selectedId = selectedSubstitutes[ing.substituteGroupId]
                ?: recipe.ingredients.firstOrNull { it.substituteGroupId == ing.substituteGroupId }?.id
            if (selectedId != ing.id) {
                return recipe.ingredients.find { it.id == selectedId }?.name ?: ing.name
            }
        }
        return ing.name
    }
}

class RecipeDetailViewModel(
    private val repository: RecipeRepository,
    private val recipeId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecipeDetailUiState())
    val uiState: StateFlow<RecipeDetailUiState> = _uiState.asStateFlow()

    init {
        loadRecipe()
        observeNotes()
    }

    private fun loadRecipe() {
        viewModelScope.launch {
            val recipe = repository.getRecipeWithDetails(recipeId)
            val defaultSubs = recipe?.ingredients
                ?.filter { it.substituteGroupId != null }
                ?.groupBy { it.substituteGroupId!! }
                ?.mapValues { (_, ings) -> ings.first().id }
                ?: emptyMap()

            // If recipe has an anchor ingredient, initialise anchor qty
            val anchorQty = recipe?.scaleIngredientId?.let { anchorId ->
                recipe.ingredients.find { it.id == anchorId }?.quantityValue
            }

            _uiState.update {
                it.copy(
                    recipe = recipe,
                    selectedServings = recipe?.baseServings ?: 1,
                    scaleAnchorQty = anchorQty,
                    selectedSubstitutes = defaultSubs,
                    isLoading = false
                )
            }
        }
    }

    private fun observeNotes() {
        viewModelScope.launch {
            repository.getNotesForRecipe(recipeId).collect { notes ->
                _uiState.update { it.copy(notes = notes) }
            }
        }
    }

    fun adjustScale(delta: Int) {
        _uiState.update { state ->
            val recipe = state.recipe ?: return@update state
            if (recipe.scaleIngredientId != null && state.scaleAnchorQty != null) {
                // Anchor-based: adjust the anchor ingredient qty by scaleStep
                val newQty = (state.scaleAnchorQty + delta * recipe.scaleStep)
                    .coerceAtLeast(recipe.scaleStep) // minimum one step
                state.copy(scaleAnchorQty = newQty)
            } else {
                // Fallback: adjust servings count by 1
                state.copy(selectedServings = (state.selectedServings + delta).coerceAtLeast(1))
            }
        }
    }

    fun resetScale() {
        _uiState.update { state ->
            val recipe = state.recipe ?: return@update state
            val baseAnchorQty = recipe.scaleIngredientId?.let { anchorId ->
                recipe.ingredients.find { it.id == anchorId }?.quantityValue
            }
            state.copy(
                selectedServings = recipe.baseServings,
                scaleAnchorQty = baseAnchorQty
            )
        }
    }

    fun selectSubstitute(groupId: String, ingredientId: String) {
        _uiState.update { it.copy(selectedSubstitutes = it.selectedSubstitutes + (groupId to ingredientId)) }
    }

    fun toggleOptional(ingredientId: String) {
        _uiState.update { state ->
            val updated = if (ingredientId in state.enabledOptionals)
                state.enabledOptionals - ingredientId else state.enabledOptionals + ingredientId
            state.copy(enabledOptionals = updated)
        }
    }

    fun toggleIngredientCheck(ingredientId: String) {
        _uiState.update { state ->
            val updated = if (ingredientId in state.checkedIngredients)
                state.checkedIngredients - ingredientId else state.checkedIngredients + ingredientId
            state.copy(checkedIngredients = updated)
        }
    }

    fun addNote(content: String) {
        if (content.isBlank()) return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            repository.addNote(
                RecipeNoteEntity(
                    id = UUID.randomUUID().toString(),
                    recipeId = recipeId,
                    content = content.trim(),
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch { repository.deleteNote(noteId) }
    }

    companion object {
        fun factory(repository: RecipeRepository, recipeId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeDetailViewModel(repository, recipeId) as T
            }
    }
}
