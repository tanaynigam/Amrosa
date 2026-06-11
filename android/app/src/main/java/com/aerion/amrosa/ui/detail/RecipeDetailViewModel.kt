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

/** One member of a recipe's variation family (the base or a variation). */
data class VariantRef(
    val id: String,
    val label: String,
    val isCurrent: Boolean,
    val isBase: Boolean,
)

data class RecipeDetailUiState(
    val recipe: Recipe? = null,
    val selectedServings: Int = 0,
    val scaleAnchorQty: Double? = null,
    val selectedSubstitutes: Map<String, String> = emptyMap(),
    val enabledOptionals: Set<String> = emptySet(),
    val notes: List<RecipeNote> = emptyList(),
    val isLoading: Boolean = true,
    // Ownership + visibility
    val isOwner: Boolean = false,
    // Comments (populated when recipe is public)
    val comments: List<Comment> = emptyList(),
    // F13 Phase 2 — popularity (when published)
    val saveCount: Int = 0,
    val likeCount: Int = 0,
    val isVisibilityUpdating: Boolean = false,
    // Direct sharing to follower
    val following: List<UserProfile> = emptyList(),
    val isFollowingLoading: Boolean = false,
    /** Non-null for one compose frame after a successful direct share. */
    val shareSentToName: String? = null,
    /** Set true after a received recipe is removed → screen navigates back. */
    val removed: Boolean = false,
    // ── Variations ──
    /** Base + variations in this recipe's family. Empty for recipes with no family context. */
    val variants: List<VariantRef> = emptyList(),
    /** True when the owner can still add a variation (under the cap of 4). */
    val canAddVariant: Boolean = false,
    /** Non-null for one frame after a variation is created → screen navigates to its editor. */
    val createdVariantId: String? = null,
) {
    val isPublic: Boolean get() = recipe?.visibility == "public"

    /** True when the recipe is mirrored to the cloud (Co-Chefs or Public) — gates comments + sharing. */
    val isPublished: Boolean get() = recipe?.visibility == "friends" || recipe?.visibility == "public"

    /** Current visibility tier; defaults to "private". */
    val visibility: String get() = recipe?.visibility ?: "private"

    /** True when this is a received recipe (Tab 2) — read-only, "Remove" instead of edit/delete. */
    val isReceived: Boolean get() = recipe?.isReceived == true

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
    private var countsJob: Job? = null

    init {
        loadRecipe()
        observeNotes()
        observeRecipeChanges()
    }

    /**
     * Auto-refresh the screen whenever the recipe row changes in Room (e.g. after an editor
     * save bumps version/updatedAt). Room is the source of truth, so edits reflect immediately
     * without depending on lifecycle/resume events. The first emission is the already-loaded
     * state, so it's dropped.
     */
    private fun observeRecipeChanges() {
        viewModelScope.launch {
            repository.observeRecipe(recipeId)
                .map { it?.updatedAt to it?.version }
                .distinctUntilChanged()
                .drop(1)
                .collect { reload() }
        }
    }

    /**
     * Re-read the recipe from Room. Called when the screen resumes (e.g. returning
     * from the editor) so edits show immediately without a manual reload. The user's
     * current scaling / substitute / optional selections are preserved.
     */
    fun reload() = loadRecipe(preserveSelections = true)

    private fun loadRecipe(preserveSelections: Boolean = false) {
        viewModelScope.launch {
            val recipe = repository.getRecipeWithDetails(recipeId)
            val currentUid = authRepository.uid
            // Owned = authored by me AND not a received reference. Received recipes
            // (Tab 2) are read-only references to another user's canonical instance.
            val isOwner = currentUid != null &&
                recipe?.authorId == currentUid &&
                recipe.isReceived == false

            val defaultSubs = recipe?.ingredients
                ?.filter { it.substituteGroupId != null }
                ?.groupBy { it.substituteGroupId!! }
                ?.mapValues { (_, ings) -> ings.first().id }
                ?: emptyMap()

            val anchorQty = recipe?.scaleIngredientId?.let { anchorId ->
                recipe.ingredients.find { it.id == anchorId }?.quantityValue
            }

            // Build the variation family (base + its variations) for the selector chips.
            var variantRefs = emptyList<VariantRef>()
            var canAdd = false
            if (recipe != null) {
                val baseId = recipe.parentRecipeId ?: recipe.id
                val baseRecipe = if (recipe.parentRecipeId == null) recipe
                                 else repository.getRecipeWithDetails(baseId)
                val variants = repository.getVariants(baseId)
                variantRefs = buildList {
                    baseRecipe?.let {
                        // Base chip uses a fixed short label (recipe titles can be long).
                        add(VariantRef(it.id, "Original", it.id == recipe.id, isBase = true))
                    }
                    variants.forEach { v ->
                        add(VariantRef(v.id, v.variantName?.ifBlank { "Variation" } ?: "Variation",
                            v.id == recipe.id, isBase = false))
                    }
                }
                canAdd = isOwner && variants.size < MAX_VARIANTS
            }

            _uiState.update { prev ->
                prev.copy(
                    recipe = recipe,
                    selectedServings = if (preserveSelections && prev.selectedServings > 0)
                        prev.selectedServings else recipe?.baseServings ?: 1,
                    scaleAnchorQty = if (preserveSelections)
                        (prev.scaleAnchorQty ?: anchorQty) else anchorQty,
                    selectedSubstitutes = if (preserveSelections && prev.selectedSubstitutes.isNotEmpty())
                        prev.selectedSubstitutes else defaultSubs,
                    isLoading = false,
                    isOwner = isOwner,
                    variants = variantRefs,
                    canAddVariant = canAdd
                )
            }

            // Start listening to comments + popularity counts if already shared (Co-Chefs or Public)
            if (recipe?.visibility == "friends" || recipe?.visibility == "public") {
                startObservingComments()
                startObservingCounts()
            }
        }
    }

    private fun startObservingCounts() {
        countsJob?.cancel()
        countsJob = viewModelScope.launch {
            sharedRecipeService.likeStateFlow(recipeId).collect { ls ->
                _uiState.update { it.copy(saveCount = ls.saveCount, likeCount = ls.likeCount) }
            }
        }
    }

    private fun stopObservingCounts() {
        countsJob?.cancel()
        countsJob = null
        _uiState.update { it.copy(saveCount = 0, likeCount = 0) }
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

            // 3. Mirror to / remove from shared_recipes.
            //    "friends" + "public" are both published (the mirror records the tier, which
            //    the Firestore read rule enforces); "private" removes the mirror.
            val recipe = updatedRecipe ?: return@launch
            if (visibility == "friends" || visibility == "public") {
                sharedRecipeService.publish(recipe)
                startObservingComments()
                startObservingCounts()
            } else {
                sharedRecipeService.unpublish(recipeId)
                stopObservingComments()
                stopObservingCounts()
            }
        }
    }

    // ── Received recipes (Tab 2) ────────────────────────────────────────────────

    /**
     * Remove a received recipe from my list: deletes the cloud reference and the
     * local cached copy. The author's canonical instance is untouched.
     */
    fun removeReceivedRecipe() {
        viewModelScope.launch {
            socialRepository.removeReceivedReference(recipeId)
            repository.removeReceivedRecipe(recipeId)
            _uiState.update { it.copy(removed = true) }
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
            socialRepository.getFriendsFlow().collect { following ->
                _uiState.update { it.copy(following = following, isFollowingLoading = false) }
            }
        }
    }

    /** Share this (already-public) recipe directly to a co-chef. */
    fun shareToFollower(recipientUid: String, recipientName: String) {
        viewModelScope.launch { shareToFollowerInternal(recipientUid, recipientName) }
    }

    /**
     * Make the recipe at least Co-Chefs-visible (publishes the mirror) and then share it directly.
     * Used when the user confirms the "make visible to your co-chefs to share" prompt for a
     * private recipe. An already-Public recipe is left Public (not downgraded).
     */
    fun makeSharableAndShareToFollower(recipientUid: String, recipientName: String) {
        viewModelScope.launch {
            ensureSharableVisibility()
            shareToFollowerInternal(recipientUid, recipientName)
        }
    }

    /** Ensure the recipe is mirrored at Co-Chefs tier so a co-chef can read it. Keeps Public as-is. */
    private suspend fun ensureSharableVisibility() {
        val current = _uiState.value.recipe?.visibility ?: "private"
        if (current == "public") return  // already world-visible — don't downgrade
        repository.setVisibility(recipeId, "friends")
        val updated = _uiState.value.recipe?.copy(visibility = "friends") ?: return
        _uiState.update { it.copy(recipe = updated) }
        sharedRecipeService.publish(updated)
        startObservingComments()
    }

    private suspend fun shareToFollowerInternal(recipientUid: String, recipientName: String) {
        val recipe = _uiState.value.recipe ?: return
        socialRepository.shareRecipeTo(recipientUid, recipe)
        _uiState.update { it.copy(shareSentToName = recipientName) }
        kotlinx.coroutines.delay(3000)
        _uiState.update { it.copy(shareSentToName = null) }
    }

    // ── Variations ──────────────────────────────────────────────────────────────

    /**
     * Create a new variation of the current recipe (a fresh editable copy), then emit
     * its id so the screen can open the editor on it.
     */
    fun createVariant(name: String) {
        viewModelScope.launch {
            val newId = repository.duplicateAsVariant(
                sourceId = recipeId,
                variantName = name.trim().ifBlank { "Variation" },
                currentUid = authRepository.uid,
                displayName = authRepository.displayName ?: authRepository.email
            )
            if (newId != null) _uiState.update { it.copy(createdVariantId = newId) }
        }
    }

    fun clearCreatedVariant() = _uiState.update { it.copy(createdVariantId = null) }

    /** Record that this recipe was just cooked (Cooking Mode "Done") — feeds Discover recency. */
    fun markCooked() {
        viewModelScope.launch { repository.markCooked(recipeId) }
    }

    companion object {
        const val MAX_VARIANTS = 4
        const val MAX_VARIANT_NAME_LEN = 20

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
