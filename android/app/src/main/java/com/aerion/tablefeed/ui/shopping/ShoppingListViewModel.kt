package com.aerion.tablefeed.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.tablefeed.data.repository.RecipeRepository
import com.aerion.tablefeed.domain.model.Recipe
import com.aerion.tablefeed.ui.util.UnitMode
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ShoppingUiState(
    val recipe: Recipe? = null,
    val isLoading: Boolean = true,
    val selectedServings: Int = 1,
    val scaleAnchorQty: Double? = null,
    val selectedUnit: UnitMode = UnitMode.ORIGINAL,
    val checkedKeys: Set<String> = emptySet(),
    val lines: List<ShoppingLine> = emptyList(),
) {
    val scaleFactor: Double get() {
        val recipe = recipe ?: return 1.0
        if (scaleAnchorQty != null && recipe.scaleIngredientId != null) {
            val baseQty = recipe.ingredients.find { it.id == recipe.scaleIngredientId }?.quantityValue ?: return 1.0
            if (baseQty == 0.0) return 1.0
            return scaleAnchorQty / baseQty
        }
        if (recipe.baseServings == 0) return 1.0
        return selectedServings.toDouble() / recipe.baseServings
    }

    val usesAnchorScaling: Boolean get() = recipe?.scaleIngredientId != null

    val yieldDisplay: String get() {
        val recipe = recipe ?: return "$selectedServings"
        val min = recipe.baseServingsMin
        val max = recipe.baseServingsMax
        if (min != null && max != null) {
            val lo = (min * scaleFactor).toInt()
            val hi = (max * scaleFactor).toInt()
            return if (lo == hi) "$lo" else "$lo–$hi"
        }
        return "${(recipe.baseServings * scaleFactor).toInt()}"
    }

    val isDefaultScale: Boolean get() = scaleFactor == 1.0

    /** Whether any ingredient carries conversions (controls the unit toggle visibility). */
    val hasConversions: Boolean get() =
        recipe?.ingredients?.any { it.quantityValueMetric != null || it.quantityValueImperial != null } == true
}

class ShoppingListViewModel(
    private val repository: RecipeRepository,
    private val recipeId: String,
    initialServings: Int?,
    initialAnchorQty: Double?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ShoppingUiState(selectedServings = initialServings ?: 1, scaleAnchorQty = initialAnchorQty)
    )
    val uiState: StateFlow<ShoppingUiState> = _uiState.asStateFlow()

    init {
        load()
        observeChecks()
    }

    private fun load() {
        viewModelScope.launch {
            val recipe = repository.getRecipeWithDetails(recipeId)
            val anchorQty = _uiState.value.scaleAnchorQty
                ?: recipe?.scaleIngredientId?.let { id ->
                    recipe.ingredients.find { it.id == id }?.quantityValue
                }
            val servings = _uiState.value.selectedServings.takeIf { it > 0 }
                ?: recipe?.baseServings ?: 1
            _uiState.update {
                it.copy(
                    recipe = recipe,
                    selectedServings = servings,
                    scaleAnchorQty = anchorQty,
                    isLoading = false,
                )
            }
            rebuildLines()
        }
    }

    private fun observeChecks() {
        viewModelScope.launch {
            repository.checkedShoppingItems(recipeId).collect { keys ->
                _uiState.update { it.copy(checkedKeys = keys.toSet()) }
            }
        }
    }

    /** Ingredients that belong on the shopping list: all, but only one member per substitute group. */
    private fun shoppingIngredients(recipe: Recipe) = recipe.ingredients.filter { ing ->
        if (ing.substituteGroupId != null) {
            recipe.ingredients.firstOrNull { it.substituteGroupId == ing.substituteGroupId }?.id == ing.id
        } else true
    }

    private fun rebuildLines() {
        val state = _uiState.value
        val recipe = state.recipe ?: return
        val lines = ShoppingAggregator.build(
            ingredients = shoppingIngredients(recipe),
            scaleFactor = state.scaleFactor,
            unitMode = state.selectedUnit,
        )
        _uiState.update { it.copy(lines = lines) }
    }

    fun setUnit(unit: UnitMode) {
        _uiState.update { it.copy(selectedUnit = unit) }
        rebuildLines()
    }

    fun adjustScale(delta: Int) {
        _uiState.update { state ->
            val recipe = state.recipe ?: return@update state
            if (recipe.scaleIngredientId != null && state.scaleAnchorQty != null) {
                val newQty = (state.scaleAnchorQty + delta * recipe.scaleStep).coerceAtLeast(recipe.scaleStep)
                state.copy(scaleAnchorQty = newQty)
            } else {
                state.copy(selectedServings = (state.selectedServings + delta).coerceAtLeast(1))
            }
        }
        rebuildLines()
    }

    fun toggle(itemKey: String) {
        val checked = itemKey in _uiState.value.checkedKeys
        viewModelScope.launch { repository.setShoppingChecked(recipeId, itemKey, !checked) }
    }

    fun resetChecks() {
        viewModelScope.launch { repository.clearShoppingChecks(recipeId) }
    }

    companion object {
        fun factory(
            repository: RecipeRepository,
            recipeId: String,
            initialServings: Int?,
            initialAnchorQty: Double?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ShoppingListViewModel(repository, recipeId, initialServings, initialAnchorQty) as T
        }
    }
}
