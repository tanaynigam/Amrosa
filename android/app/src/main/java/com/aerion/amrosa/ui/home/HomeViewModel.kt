package com.aerion.amrosa.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.amrosa.data.repository.RecipeRepository
import com.aerion.amrosa.domain.model.Recipe
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class RecipeFilter { ALL, PERSONAL, YOURS }

data class HomeUiState(
    val allRecipes: List<Recipe> = emptyList(),
    val searchQuery: String = "",
    val selectedCategory: String? = null,
    val isLoading: Boolean = true
) {
    val categories: List<String> get() =
        allRecipes.flatMap { it.tags }.distinct().sorted()

    val filteredRecipes: List<Recipe> get() {
        var list = allRecipes
        if (searchQuery.isNotBlank()) {
            list = list.filter { r ->
                r.title.contains(searchQuery, ignoreCase = true) ||
                r.tags.any { it.contains(searchQuery, ignoreCase = true) }
            }
        }
        selectedCategory?.let { cat ->
            list = list.filter { r -> r.tags.any { it.equals(cat, ignoreCase = true) } }
        }
        return list
    }
}

class HomeViewModel(
    private val repository: RecipeRepository,
    private val filter: RecipeFilter = RecipeFilter.ALL
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val flow = when (filter) {
                RecipeFilter.ALL      -> repository.getAllRecipes()
                RecipeFilter.PERSONAL -> repository.getPersonalRecipes()
                RecipeFilter.YOURS    -> repository.getYoursRecipes()
            }
            flow.collect { recipes ->
                _uiState.update { it.copy(allRecipes = recipes, isLoading = false) }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onCategorySelect(category: String?) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    companion object {
        fun factory(
            repository: RecipeRepository,
            filter: RecipeFilter = RecipeFilter.ALL
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(repository, filter) as T
            }
    }
}
