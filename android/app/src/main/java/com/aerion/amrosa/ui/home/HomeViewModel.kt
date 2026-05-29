package com.aerion.amrosa.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.amrosa.data.remote.SocialRepository
import com.aerion.amrosa.data.repository.RecipeRepository
import com.aerion.amrosa.domain.model.Recipe
import com.aerion.amrosa.domain.model.ReceivedRecipeSummary
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class RecipeFilter { ALL, PERSONAL, YOURS }

/** Secondary filter shown as chips on the My Recipes tab. */
enum class YourRecipesFilter { ALL, PERSONAL, IMPORTED, SHARED }

data class HomeUiState(
    val allRecipes: List<Recipe> = emptyList(),
    val sharedRecipes: List<ReceivedRecipeSummary> = emptyList(),
    val searchQuery: String = "",
    val selectedCategory: String? = null,
    val yourRecipesFilter: YourRecipesFilter = YourRecipesFilter.ALL,
    val isLoading: Boolean = true,
    val isSharedLoading: Boolean = true
) {
    val categories: List<String> get() =
        allRecipes.flatMap { it.tags }.distinct().sorted()

    val filteredRecipes: List<Recipe> get() {
        if (yourRecipesFilter == YourRecipesFilter.SHARED) return emptyList()
        var list = when (yourRecipesFilter) {
            YourRecipesFilter.PERSONAL -> allRecipes.filter { !it.isImported }
            YourRecipesFilter.IMPORTED -> allRecipes.filter { it.isImported }
            else                       -> allRecipes
        }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery
            list = list.filter { r ->
                r.title.contains(q, ignoreCase = true) ||
                r.tags.any { it.contains(q, ignoreCase = true) }
            }
        }
        selectedCategory?.let { cat ->
            list = list.filter { r -> r.tags.any { it.equals(cat, ignoreCase = true) } }
        }
        return list
    }

    val filteredSharedRecipes: List<ReceivedRecipeSummary> get() {
        if (yourRecipesFilter != YourRecipesFilter.SHARED) return emptyList()
        if (searchQuery.isBlank()) return sharedRecipes
        val q = searchQuery
        return sharedRecipes.filter { r ->
            r.title.contains(q, ignoreCase = true) ||
            r.tags.any { it.contains(q, ignoreCase = true) }
        }
    }
}

class HomeViewModel(
    private val repository: RecipeRepository,
    private val socialRepository: SocialRepository,
    private val filter: RecipeFilter = RecipeFilter.ALL
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // Load local recipes
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
        // Load shared recipes (only relevant on the YOURS tab where Shared chip lives)
        if (filter == RecipeFilter.YOURS) {
            viewModelScope.launch {
                socialRepository.getReceivedRecipesSummaryFlow().collect { shared ->
                    _uiState.update { it.copy(sharedRecipes = shared, isSharedLoading = false) }
                }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onCategorySelect(category: String?) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun onYourRecipesFilterChange(filter: YourRecipesFilter) {
        _uiState.update {
            it.copy(
                yourRecipesFilter = filter,
                // Reset category filter when switching modes
                selectedCategory = null
            )
        }
    }

    companion object {
        fun factory(
            repository: RecipeRepository,
            socialRepository: SocialRepository,
            filter: RecipeFilter = RecipeFilter.ALL
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(repository, socialRepository, filter) as T
            }
    }
}
