package com.aerion.tablefeed.ui.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.tablefeed.data.auth.AuthRepository
import com.aerion.tablefeed.data.remote.SharedRecipeService
import com.aerion.tablefeed.domain.model.Recipe
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SharedUiState(
    val recipes: List<Recipe> = emptyList(),
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val currentUid: String? = null
) {
    val filtered: List<Recipe> get() {
        if (searchQuery.isBlank()) return recipes
        val q = searchQuery.lowercase()
        return recipes.filter { r ->
            r.title.lowercase().contains(q) ||
            r.tags.any { it.lowercase().contains(q) } ||
            r.authorDisplayName?.lowercase()?.contains(q) == true
        }
    }
}

class SharedViewModel(
    private val sharedRecipeService: SharedRecipeService,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharedUiState())
    val uiState: StateFlow<SharedUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(currentUid = authRepository.uid) }
        viewModelScope.launch {
            sharedRecipeService.getSharedRecipesFlow().collect { recipes ->
                _uiState.update { it.copy(recipes = recipes, isLoading = false) }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    companion object {
        fun factory(
            sharedRecipeService: SharedRecipeService,
            authRepository: AuthRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SharedViewModel(sharedRecipeService, authRepository) as T
            }
    }
}
