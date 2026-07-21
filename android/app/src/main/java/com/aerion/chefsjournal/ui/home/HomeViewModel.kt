package com.aerion.chefsjournal.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.chefsjournal.data.auth.AuthRepository
import com.aerion.chefsjournal.data.remote.SharedRecipeService
import com.aerion.chefsjournal.data.repository.RecipeRepository
import com.aerion.chefsjournal.domain.model.Recipe
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class RecipeFilter { ALL, PERSONAL, YOURS }

/** Secondary filter shown as chips on the My Recipes tab. */
enum class YourRecipesFilter { ALL, PERSONAL, IMPORTED }

data class HomeUiState(
    val allRecipes: List<Recipe> = emptyList(),
    val searchQuery: String = "",
    val selectedCategory: String? = null,
    val yourRecipesFilter: YourRecipesFilter = YourRecipesFilter.ALL,
    val isLoading: Boolean = true,
    /** Like counts for the user's own published recipes (id → count), shown on cards. */
    val likeCounts: Map<String, Int> = emptyMap(),
) {
    val categories: List<String> get() =
        allRecipes.flatMap { it.tags }.distinct().sorted()

    val filteredRecipes: List<Recipe> get() {
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
}

class HomeViewModel(
    private val repository: RecipeRepository,
    private val sharedRecipeService: SharedRecipeService,
    private val authRepository: AuthRepository,
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
        // Like counts for the user's own published recipes, so cards can show a heart + count.
        viewModelScope.launch {
            authRepository.uid?.let { uid ->
                val counts = sharedRecipeService.getAuthorLikeCounts(uid)
                if (counts.isNotEmpty()) _uiState.update { it.copy(likeCounts = counts) }
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
        _uiState.update { it.copy(yourRecipesFilter = filter, selectedCategory = null) }
    }

    companion object {
        fun factory(
            repository: RecipeRepository,
            sharedRecipeService: SharedRecipeService,
            authRepository: AuthRepository,
            filter: RecipeFilter = RecipeFilter.ALL
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(repository, sharedRecipeService, authRepository, filter) as T
            }
    }
}
