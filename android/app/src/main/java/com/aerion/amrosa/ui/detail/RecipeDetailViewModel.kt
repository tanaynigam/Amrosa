package com.aerion.amrosa.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.amrosa.data.auth.AuthRepository
import com.aerion.amrosa.data.local.entity.RecipeNoteEntity
import com.aerion.amrosa.data.remote.SharedRecipeService
import com.aerion.amrosa.data.remote.SocialRepository
import com.aerion.amrosa.data.repository.RecipeRepository
import com.aerion.amrosa.domain.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class RecipeDetailUiState(
    val recipe: Recipe? = null,
    val selectedServings: Int = 0,
    val scaleAnchorQty: Double? = null,
    val selectedSubstitutes: Map<String, String> = emptyMap(),
    val enabledOptionals: Set<String> = emptySet(),
    val checkedIngredients: Set<String> = emptySet(),
    val notes: List<RecipeNote> = emptyList(),
    val isLoading: Boolean = true,
    // Ownership + visibility
    val isOwner: Boolean = false,
    // Comments (populated when recipe is public)
    val comments: List<Comment> = emptyList(),
    val isVisibilityUpdating: Boolean = false,
    // Direct sharing to follower
    val following: List<UserProfile> = emptyList(),
    val isFollowingLoading: Boolean = false,
    /** Non-null for one compose frame after a successful direct share. */
    val shareSentToName: String? = null,
) {
    val isPublic: Boolean get() = recipe?.visibility == "public"

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

    val scaleFactor: Double get() {
        val recipe = recipe ?: return 1.0
        if (scaleAnchorQty != null && recipe.scaleIngredientId != null) {
            val baseQty = recipe.ingredients
                .find { it.id == recipe.scaleIngredientId }?.quantityValue ?: return 1.0
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
            val scaledMin = (min * scaleFactor).toInt()
            val scaledMax = (max * scaleFactor).toInt()
            return if (scaledMin == scaledMax) "$scaledMin" else "$scaledMin–$scaledMax"
        }
        return "${(recipe.baseServings * scaleFactor).toInt()}"
    }

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
    private val authRepository: AuthRepository,
    private val sharedRecipeService: SharedRecipeService,
    private val socialRepository: SocialRepository,
    private val recipeId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecipeDetailUiState())
    val uiState: StateFlow<RecipeDetailUiState> = _uiState.asStateFlow()

    private var commentsJob: Job? = null

    init {
        loadRecipe()
        observeNotes()
    }

    private fun loadRecipe() {
        viewModelScope.launch {
            val recipe = repository.getRecipeWithDetails(recipeId)
            val currentUid = authRepository.uid
            // A recipe is "owned" if its authorId matches the current user,
            // OR if authorId is null (recipes created before author attribution was added).
            val isOwner = currentUid != null &&
                (recipe?.authorId == null || recipe.authorId == currentUid)

            val defaultSubs = recipe?.ingredients
                ?.filter { it.substituteGroupId != null }
                ?.groupBy { it.substituteGroupId!! }
                ?.mapValues { (_, ings) -> ings.first().id }
                ?: emptyMap()

            val anchorQty = recipe?.scaleIngredientId?.let { anchorId ->
                recipe.ingredients.find { it.id == anchorId }?.quantityValue
            }

            _uiState.update {
                it.copy(
                    recipe = recipe,
                    selectedServings = recipe?.baseServings ?: 1,
                    scaleAnchorQty = anchorQty,
                    selectedSubstitutes = defaultSubs,
                    isLoading = false,
                    isOwner = isOwner
                )
            }

            // Start listening to comments if the recipe is already public
            if (recipe?.visibility == "public") startObservingComments()
        }
    }

    private fun observeNotes() {
        viewModelScope.launch {
            repository.getNotesForRecipe(recipeId).collect { notes ->
                _uiState.update { it.copy(notes = notes) }
            }
        }
    }

    private fun startObservingComments() {
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            sharedRecipeService.getCommentsFlow(recipeId).collect { comments ->
                _uiState.update { it.copy(comments = comments) }
            }
        }
    }

    private fun stopObservingComments() {
        commentsJob?.cancel()
        commentsJob = null
        _uiState.update { it.copy(comments = emptyList()) }
    }

    // ── Visibility toggle ─────────────────────────────────────────────────────

    fun setVisibility(visibility: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isVisibilityUpdating = true) }

            // 1. Persist to Room
            repository.setVisibility(recipeId, visibility)

            // 2. Update in-memory state immediately
            val updatedRecipe = _uiState.value.recipe?.copy(visibility = visibility)
            _uiState.update { it.copy(recipe = updatedRecipe, isVisibilityUpdating = false) }

            // 3. Mirror to / remove from shared_recipes
            val recipe = updatedRecipe ?: return@launch
            if (visibility == "public") {
                sharedRecipeService.publish(recipe)
                startObservingComments()
            } else {
                sharedRecipeService.unpublish(recipeId)
                stopObservingComments()
            }
        }
    }

    // ── Comments ──────────────────────────────────────────────────────────────

    fun addComment(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch { sharedRecipeService.addComment(recipeId, content) }
    }

    fun deleteComment(commentId: String) {
        viewModelScope.launch { sharedRecipeService.deleteComment(recipeId, commentId) }
    }

    // ── Scale ─────────────────────────────────────────────────────────────────

    fun adjustScale(delta: Int) {
        _uiState.update { state ->
            val recipe = state.recipe ?: return@update state
            if (recipe.scaleIngredientId != null && state.scaleAnchorQty != null) {
                val newQty = (state.scaleAnchorQty + delta * recipe.scaleStep)
                    .coerceAtLeast(recipe.scaleStep)
                state.copy(scaleAnchorQty = newQty)
            } else {
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
            state.copy(selectedServings = recipe.baseServings, scaleAnchorQty = baseAnchorQty)
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

    // ── Notes ─────────────────────────────────────────────────────────────────

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

    // ── Direct sharing ────────────────────────────────────────────────────────

    /** Load accepted following list for the follower picker sheet. */
    fun loadFollowing() {
        if (_uiState.value.following.isNotEmpty() || _uiState.value.isFollowingLoading) return
        _uiState.update { it.copy(isFollowingLoading = true) }
        viewModelScope.launch {
            socialRepository.getFollowingFlow().collect { following ->
                _uiState.update { it.copy(following = following, isFollowingLoading = false) }
            }
        }
    }

    /** Share this recipe directly to a follower. */
    fun shareToFollower(recipientUid: String, recipientName: String) {
        val recipe = _uiState.value.recipe ?: return
        viewModelScope.launch {
            socialRepository.shareRecipeTo(recipientUid, recipe)
            _uiState.update { it.copy(shareSentToName = recipientName) }
            // Clear the toast after one frame
            kotlinx.coroutines.delay(3000)
            _uiState.update { it.copy(shareSentToName = null) }
        }
    }

    companion object {
        fun factory(
            repository: RecipeRepository,
            authRepository: AuthRepository,
            sharedRecipeService: SharedRecipeService,
            socialRepository: SocialRepository,
            recipeId: String
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeDetailViewModel(repository, authRepository, sharedRecipeService, socialRepository, recipeId) as T
            }
    }
}
