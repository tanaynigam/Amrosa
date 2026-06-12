package com.aerion.amrosa.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.amrosa.data.auth.AuthRepository
import com.aerion.amrosa.data.local.entity.*
import com.aerion.amrosa.data.remote.RecipeSyncService
import com.aerion.amrosa.data.remote.SharedRecipeService
import com.aerion.amrosa.data.remote.SocialRepository
import com.aerion.amrosa.data.repository.RecipeRepository
import com.aerion.amrosa.domain.model.*
import com.aerion.amrosa.ui.edit.EditorIngredient
import com.aerion.amrosa.ui.edit.EditorSection
import com.aerion.amrosa.ui.edit.EditorStep
import com.google.firebase.functions.FirebaseFunctions
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

/** One member of a recipe's variation family (the base or a variation). */
data class VariantRef(
    val id: String,
    val label: String,
    val isCurrent: Boolean,
    val isBase: Boolean,
)

/** Editable draft used by the inline edit mode on the detail screen. */
data class EditDraft(
    val title: String = "",
    val description: String = "",
    val prepTimeMinutes: String = "",
    val cookTimeMinutes: String = "",
    val baseServings: String = "1",
    val isRangeYield: Boolean = false,
    val baseServingsMin: String = "",
    val baseServingsMax: String = "",
    val tagsText: String = "",          // comma-separated
    val sourceUrlsText: String = "",    // newline-separated
    val isPersonalAuthor: Boolean = false,
    val isVariant: Boolean = false,
    val variantName: String = "",
    val sections: List<EditorSection> = emptyList(),
    val deletedSectionIds: List<String> = emptyList(),
    val deletedIngredientIds: List<String> = emptyList(),
    val deletedStepIds: List<String> = emptyList(),
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
    // ── Inline edit mode ──
    val isEditMode: Boolean = false,
    val draft: EditDraft? = null,
    val isSavingEdit: Boolean = false,
    val editError: String? = null,
    val isConverting: Boolean = false,
    val conversionMessage: String? = null,
    val isDeleting: Boolean = false,
    val deleteComplete: Boolean = false,
    val variantCount: Int = 0,
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
    private val syncService: RecipeSyncService,
    private val recipeId: String,
    private val gson: Gson,
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

    // ══ Inline edit mode ════════════════════════════════════════════════════════
    // Ported from the old RecipeEditorViewModel; the detail screen renders this draft
    // inline so editing keeps scroll context. The draft is built from the current recipe.

    val personalAuthorName: String
        get() = authRepository.displayName ?: authRepository.email ?: "Me"

    // Originals preserved across an edit (not user-editable)
    private var editScaleIngredientId: String? = null
    private var editScaleStep: Double = 1.0
    private var editImageUrl: String? = null
    private var editCreatedAt: Long = 0L
    private var editIsImported: Boolean = false
    private var editVersion: Int = 1
    private var editChangeLog: List<RecipeChange> = emptyList()
    private var editAuthorId: String? = null
    private var editVisibility: String = "private"
    private var editParentRecipeId: String? = null

    fun enterEdit() {
        val recipe = _uiState.value.recipe ?: return
        if (!_uiState.value.isOwner) return
        editScaleIngredientId = recipe.scaleIngredientId
        editScaleStep = recipe.scaleStep
        editImageUrl = recipe.imageUrl
        editCreatedAt = recipe.createdAt
        editIsImported = recipe.isImported
        editVersion = recipe.version
        editChangeLog = recipe.changeLog
        editAuthorId = recipe.authorId ?: authRepository.uid
        editVisibility = recipe.visibility
        editParentRecipeId = recipe.parentRecipeId

        val sections = if (recipe.sections.isEmpty()) {
            listOf(EditorSection(
                id = "sec-default-${UUID.randomUUID()}", name = "",
                ingredients = recipe.ingredients.sortedBy { it.orderIndex }.map { it.toEditor() },
                steps = recipe.steps.sortedBy { it.orderIndex }.map { it.toEditor() }
            ))
        } else {
            recipe.sections.sortedBy { it.orderIndex }.map { section ->
                EditorSection(
                    id = section.id, name = section.name,
                    ingredients = recipe.ingredients.filter { it.sectionId == section.id }
                        .sortedBy { it.orderIndex }.map { it.toEditor() },
                    steps = recipe.steps.filter { it.sectionId == section.id }
                        .sortedBy { it.orderIndex }.map { it.toEditor() }
                )
            }
        }
        val draft = EditDraft(
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
            isPersonalAuthor = !recipe.isImported,
            isVariant = recipe.parentRecipeId != null,
            variantName = recipe.variantName ?: "",
            sections = sections,
        )
        _uiState.update { it.copy(isEditMode = true, draft = draft, editError = null) }
        viewModelScope.launch {
            val vc = if (recipe.parentRecipeId == null) repository.getVariants(recipeId).size else 0
            _uiState.update { it.copy(variantCount = vc) }
        }
    }

    fun cancelEdit() = _uiState.update { it.copy(isEditMode = false, draft = null, editError = null) }
    fun clearEditError() = _uiState.update { it.copy(editError = null) }
    fun clearConversionMessage() = _uiState.update { it.copy(conversionMessage = null) }

    private fun updateDraft(transform: (EditDraft) -> EditDraft) =
        _uiState.update { st -> st.draft?.let { st.copy(draft = transform(it)) } ?: st }

    private fun transformSection(sectionId: String, transform: (EditorSection) -> EditorSection) =
        updateDraft { d -> d.copy(sections = d.sections.map { if (it.id == sectionId) transform(it) else it }) }

    // Metadata
    fun editTitle(v: String) = updateDraft { it.copy(title = v) }
    fun editDescription(v: String) = updateDraft { it.copy(description = v) }
    fun editPrepTime(v: String) = updateDraft { it.copy(prepTimeMinutes = v.filter(Char::isDigit)) }
    fun editCookTime(v: String) = updateDraft { it.copy(cookTimeMinutes = v.filter(Char::isDigit)) }
    fun editBaseServings(v: String) = updateDraft { it.copy(baseServings = v.filter(Char::isDigit)) }
    fun editIsRangeYield(v: Boolean) = updateDraft { it.copy(isRangeYield = v) }
    fun editBaseServingsMin(v: String) = updateDraft { it.copy(baseServingsMin = v.filter(Char::isDigit)) }
    fun editBaseServingsMax(v: String) = updateDraft { it.copy(baseServingsMax = v.filter(Char::isDigit)) }
    fun editTags(v: String) = updateDraft { it.copy(tagsText = v) }
    fun editSourceUrls(v: String) = updateDraft { it.copy(sourceUrlsText = v) }
    fun editIsPersonalAuthor(v: Boolean) = updateDraft { it.copy(isPersonalAuthor = v) }
    fun editVariantName(v: String) = updateDraft { it.copy(variantName = v.take(MAX_VARIANT_NAME_LEN)) }

    // Sections
    fun addSection() = updateDraft { it.copy(sections = it.sections + EditorSection(name = "New Section")) }
    fun updateSectionName(sectionId: String, name: String) = transformSection(sectionId) { it.copy(name = name) }
    fun deleteSection(sectionId: String) = updateDraft { d ->
        val section = d.sections.find { it.id == sectionId } ?: return@updateDraft d
        d.copy(
            sections = d.sections.filter { it.id != sectionId },
            deletedSectionIds = d.deletedSectionIds + sectionId,
            deletedIngredientIds = d.deletedIngredientIds + section.ingredients.map { it.id },
            deletedStepIds = d.deletedStepIds + section.steps.map { it.id },
        )
    }
    fun moveSectionUp(sectionId: String) = updateDraft { it.copy(sections = it.sections.moved(sectionId, { s -> s.id }, -1)) }
    fun moveSectionDown(sectionId: String) = updateDraft { it.copy(sections = it.sections.moved(sectionId, { s -> s.id }, +1)) }

    // Ingredients
    fun addIngredient(sectionId: String) = transformSection(sectionId) { it.copy(ingredients = it.ingredients + EditorIngredient()) }
    fun updateIngredient(sectionId: String, updated: EditorIngredient) =
        transformSection(sectionId) { it.copy(ingredients = it.ingredients.map { i -> if (i.id == updated.id) updated else i }) }
    fun deleteIngredient(sectionId: String, ingredientId: String) = updateDraft { d ->
        d.copy(
            sections = d.sections.map { s ->
                s.copy(
                    ingredients = if (s.id == sectionId) s.ingredients.filter { it.id != ingredientId } else s.ingredients,
                    // also unlink from any step that used it
                    steps = s.steps.map { st -> st.copy(ingredientIds = st.ingredientIds - ingredientId) },
                )
            },
            deletedIngredientIds = d.deletedIngredientIds + ingredientId,
        )
    }
    fun moveIngredientUp(sectionId: String, id: String) = transformSection(sectionId) { it.copy(ingredients = it.ingredients.moved(id, { i -> i.id }, -1)) }
    fun moveIngredientDown(sectionId: String, id: String) = transformSection(sectionId) { it.copy(ingredients = it.ingredients.moved(id, { i -> i.id }, +1)) }

    // Steps
    fun addStep(sectionId: String) = transformSection(sectionId) { it.copy(steps = it.steps + EditorStep()) }
    fun updateStep(sectionId: String, updated: EditorStep) =
        transformSection(sectionId) { it.copy(steps = it.steps.map { s -> if (s.id == updated.id) updated else s }) }
    fun deleteStep(sectionId: String, stepId: String) = updateDraft { d ->
        d.copy(
            sections = d.sections.map { s -> if (s.id == sectionId) s.copy(steps = s.steps.filter { it.id != stepId }) else s },
            deletedStepIds = d.deletedStepIds + stepId,
        )
    }
    fun moveStepUp(sectionId: String, id: String) = transformSection(sectionId) { it.copy(steps = it.steps.moved(id, { s -> s.id }, -1)) }
    fun moveStepDown(sectionId: String, id: String) = transformSection(sectionId) { it.copy(steps = it.steps.moved(id, { s -> s.id }, +1)) }

    /** #4 — set which ingredients a step uses (drives the cooking-mode card). */
    fun toggleStepIngredient(sectionId: String, stepId: String, ingredientId: String) =
        transformSection(sectionId) { s ->
            s.copy(steps = s.steps.map { st ->
                if (st.id != stepId) st
                else st.copy(ingredientIds = if (ingredientId in st.ingredientIds) st.ingredientIds - ingredientId else st.ingredientIds + ingredientId)
            })
        }

    fun saveEdit() {
        val draft = _uiState.value.draft ?: return
        val title = draft.title.trim()
        if (title.isBlank()) { _uiState.update { it.copy(editError = "Title cannot be empty") }; return }
        _uiState.update { it.copy(isSavingEdit = true, editError = null) }
        val now = System.currentTimeMillis()
        val newVersion = editVersion + 1
        val newChangeLog = editChangeLog + RecipeChange(newVersion, now, "Edited recipe")
        viewModelScope.launch {
            try {
                val recipeEntity = RecipeEntity(
                    id = recipeId, title = title,
                    description = draft.description.trim().ifBlank { null },
                    sourceUrls = gson.toJson(draft.sourceUrlsText.lines().map { it.trim() }.filter { it.isNotBlank() }),
                    baseServings = draft.baseServings.toIntOrNull() ?: 1,
                    baseServingsMin = if (draft.isRangeYield) draft.baseServingsMin.toIntOrNull() else null,
                    baseServingsMax = if (draft.isRangeYield) draft.baseServingsMax.toIntOrNull() else null,
                    scaleIngredientId = editScaleIngredientId,
                    scaleStep = editScaleStep,
                    prepTimeMinutes = draft.prepTimeMinutes.toIntOrNull(),
                    cookTimeMinutes = draft.cookTimeMinutes.toIntOrNull(),
                    imageUrl = editImageUrl,
                    tags = gson.toJson(draft.tagsText.split(",").map { it.trim() }.filter { it.isNotBlank() }),
                    isCustomized = true,
                    isImported = !draft.isPersonalAuthor,
                    version = newVersion,
                    changeLog = gson.toJson(newChangeLog),
                    createdAt = editCreatedAt,
                    updatedAt = now,
                    syncedAt = null,
                    authorId = editAuthorId,
                    authorDisplayName = if (draft.isPersonalAuthor) (authRepository.displayName ?: authRepository.email) else "Imported",
                    visibility = editVisibility,
                    parentRecipeId = editParentRecipeId,
                    variantName = if (editParentRecipeId != null) draft.variantName.trim().ifBlank { "Variation" } else null,
                )
                val sectionEntities = draft.sections.mapIndexed { idx, s ->
                    RecipeSectionEntity(id = s.id, recipeId = recipeId, name = s.name.trim(), orderIndex = idx)
                }
                val ingredientEntities = draft.sections.flatMap { s ->
                    s.ingredients.mapIndexed { idx, ing ->
                        IngredientEntity(
                            id = ing.id, recipeId = recipeId, sectionId = s.id, name = ing.name.trim(),
                            quantityValue = ing.quantityValue,
                            quantityUnit = ing.quantityUnit.trim().ifBlank { null },
                            quantityDisplay = ing.quantityDisplay.trim().ifBlank { null },
                            groupLabel = ing.groupLabel.trim().ifBlank { null },
                            isOptional = ing.isOptional,
                            substituteGroupId = ing.substituteGroupId,
                            substituteRatio = ing.substituteRatio,
                            orderIndex = idx,
                            quantityValueMetric = ing.quantityValueMetric, quantityUnitMetric = ing.quantityUnitMetric,
                            quantityDisplayMetric = ing.quantityDisplayMetric,
                            quantityValueImperial = ing.quantityValueImperial, quantityUnitImperial = ing.quantityUnitImperial,
                            quantityDisplayImperial = ing.quantityDisplayImperial,
                            shoppingNote = ing.shoppingNote.trim().ifBlank { null },
                        )
                    }
                }
                val stepEntities = draft.sections.flatMap { s ->
                    s.steps.mapIndexed { idx, step ->
                        StepEntity(id = step.id, recipeId = recipeId, sectionId = s.id, instruction = step.instruction.trim(), orderIndex = idx)
                    }
                }
                val ingredientById = draft.sections.flatMap { it.ingredients }.associateBy { it.id }
                val refs = draft.sections.flatMap { s ->
                    s.steps.flatMap { step ->
                        step.ingredientIds.mapNotNull { ingId ->
                            val ing = ingredientById[ingId] ?: return@mapNotNull null
                            StepIngredientRefEntity(stepId = step.id, ingredientId = ingId,
                                quantityDisplay = ing.quantityDisplay.trim().ifBlank { null })
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    repository.updateFullRecipe(
                        recipe = recipeEntity, sections = sectionEntities, ingredients = ingredientEntities,
                        steps = stepEntities, refs = refs,
                        deletedSectionIds = draft.deletedSectionIds,
                        deletedIngredientIds = draft.deletedIngredientIds,
                        deletedStepIds = draft.deletedStepIds,
                    )
                }
                if (!editIsImported) {
                    viewModelScope.launch(Dispatchers.IO) { syncService.pushPersonalRecipe(recipeId) }
                }
                if (editVisibility == "friends" || editVisibility == "public") {
                    viewModelScope.launch(Dispatchers.IO) {
                        repository.getRecipeWithDetails(recipeId)?.let { sharedRecipeService.publish(it) }
                    }
                }
                // Exit edit mode; the Room-Flow observer reloads the fresh recipe into view.
                _uiState.update { it.copy(isSavingEdit = false, isEditMode = false, draft = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSavingEdit = false, editError = "Save failed: ${e.message}") }
            }
        }
    }

    // ── Update conversions (Gemini) ─────────────────────────────────────────────
    private val functions by lazy { FirebaseFunctions.getInstance("us-central1") }

    fun updateConversions() {
        val draft = _uiState.value.draft ?: return
        val ingredients = draft.sections.flatMap { it.ingredients }
        if (ingredients.isEmpty()) return
        _uiState.update { it.copy(isConverting = true) }
        viewModelScope.launch {
            try {
                val payload = ingredients.map { hashMapOf("id" to it.id, "name" to it.name, "quantityDisplay" to it.quantityDisplay) }
                @Suppress("UNCHECKED_CAST")
                val data = withContext(Dispatchers.IO) {
                    functions.getHttpsCallable("convertIngredients").call(hashMapOf("ingredients" to payload)).await().getData()
                } as? Map<String, Any?>
                val byId = ((data?.get("ingredients") as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList())
                    .associateBy { it["id"] as? String }
                updateDraft { d ->
                    d.copy(sections = d.sections.map { sec ->
                        sec.copy(ingredients = sec.ingredients.map { ing ->
                            val r = byId[ing.id] ?: return@map ing
                            ing.copy(
                                quantityValueMetric = (r["quantityValueMetric"] as? Number)?.toDouble(),
                                quantityUnitMetric = r["quantityUnitMetric"] as? String,
                                quantityDisplayMetric = r["quantityDisplayMetric"] as? String,
                                quantityValueImperial = (r["quantityValueImperial"] as? Number)?.toDouble(),
                                quantityUnitImperial = r["quantityUnitImperial"] as? String,
                                quantityDisplayImperial = r["quantityDisplayImperial"] as? String,
                            )
                        })
                    })
                }
                _uiState.update { it.copy(isConverting = false, conversionMessage = "Conversions updated") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isConverting = false, conversionMessage = "Conversion failed: ${e.message}") }
            }
        }
    }

    // ── Delete recipe (cascade variations) ──────────────────────────────────────
    fun deleteRecipe() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            withContext(Dispatchers.IO) {
                if (editParentRecipeId == null) {
                    repository.getVariants(recipeId).forEach { variant ->
                        repository.deleteFullRecipe(variant.id)
                        syncService.deletePersonalRecipe(variant.id)
                        if (variant.visibility == "public") sharedRecipeService.unpublish(variant.id)
                    }
                }
                repository.deleteFullRecipe(recipeId)
                syncService.deletePersonalRecipe(recipeId)
                if (editVisibility == "public") sharedRecipeService.unpublish(recipeId)
            }
            _uiState.update { it.copy(isDeleting = false, deleteComplete = true) }
        }
    }

    // ── Editor mappings ─────────────────────────────────────────────────────────
    private fun Ingredient.toEditor() = EditorIngredient(
        id = id, name = name,
        quantityDisplay = quantityDisplay ?: "", quantityUnit = quantityUnit ?: "",
        groupLabel = groupLabel ?: "", isOptional = isOptional,
        quantityValue = quantityValue, substituteGroupId = substituteGroupId, substituteRatio = substituteRatio,
        quantityValueMetric = quantityValueMetric, quantityUnitMetric = quantityUnitMetric, quantityDisplayMetric = quantityDisplayMetric,
        quantityValueImperial = quantityValueImperial, quantityUnitImperial = quantityUnitImperial, quantityDisplayImperial = quantityDisplayImperial,
        shoppingNote = shoppingNote ?: "",
    )

    private fun Step.toEditor() = EditorStep(
        id = id, instruction = instruction,
        ingredientIds = ingredientRefs.map { it.ingredientId },
    )

    private fun <T> List<T>.moved(id: String, idOf: (T) -> String, direction: Int): List<T> {
        val idx = indexOfFirst { idOf(it) == id }
        val newIdx = idx + direction
        if (idx < 0 || newIdx !in indices) return this
        return toMutableList().also { val item = it.removeAt(idx); it.add(newIdx, item) }
    }

    companion object {
        const val MAX_VARIANTS = 4
        const val MAX_VARIANT_NAME_LEN = 20

        fun factory(
            repository: RecipeRepository,
            authRepository: AuthRepository,
            sharedRecipeService: SharedRecipeService,
            socialRepository: SocialRepository,
            syncService: RecipeSyncService,
            recipeId: String,
            gson: Gson,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeDetailViewModel(repository, authRepository, sharedRecipeService, socialRepository, syncService, recipeId, gson) as T
            }
    }
}
