package com.aerion.amrosa.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.amrosa.data.UserPreferences
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
    // Notes — the community thread (populated when the recipe is shared). `notesLocked` = author
    // froze new notes. (Backed by the comments subcollection on the mirror.)
    val comments: List<Comment> = emptyList(),
    val notesLocked: Boolean = false,
    // F13 Phase 2 — popularity (when published)
    val saveCount: Int = 0,
    val likeCount: Int = 0,
    val isVisibilityUpdating: Boolean = false,
    // "Shared with specific people" — resolved profiles for the recipe's sharedWith UIDs.
    val sharedRecipients: List<UserProfile> = emptyList(),
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

    /** True when the recipe is mirrored to the cloud (Co-Chefs or Public) — gates sharing/popularity. */
    val isPublished: Boolean get() = recipe?.visibility == "friends" || recipe?.visibility == "public"

    /** True when the recipe is shared in any tier → the community Notes thread is available. */
    val notesVisible: Boolean get() = recipe != null && recipe.visibility != "private"

    /** Current visibility tier; defaults to "private". */
    val visibility: String get() = recipe?.visibility ?: "private"

    /** True when this is a received recipe (Tab 2) — read-only, "Remove" instead of edit/delete. */
    val isReceived: Boolean get() = recipe?.isReceived == true

    val visibleIngredients: List<Ingredient> get() {
        val recipe = recipe ?: return emptyList()
        return recipe.ingredients.filter { ing ->
            // Optional ingredients show only when their section-top chip is selected.
            if (ing.isOptional && ing.id !in enabledOptionals) return@filter false
            // Substitute groups collapse to the selected option (alternatives are offered inline).
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

    /**
     * Step refs resolved for display. A ref to *any* member of a substitute group resolves to
     * the currently-selected member (so switching the substitute keeps the step's ingredient
     * visible), collapsed to one entry per group. Optional refs whose ingredient is excluded are
     * dropped. Returns (ingredientToShow, the ref's quantity override) pairs.
     */
    fun visibleStepRefs(refs: List<StepIngredientRef>): List<Pair<Ingredient, String?>> {
        val recipe = recipe ?: return emptyList()
        val seenGroups = HashSet<String>()
        val out = ArrayList<Pair<Ingredient, String?>>()
        for (ref in refs) {
            val ing = recipe.ingredients.find { it.id == ref.ingredientId } ?: continue
            val resolved = if (ing.substituteGroupId != null) {
                if (!seenGroups.add(ing.substituteGroupId)) continue   // one row per group
                val selectedId = selectedSubstitutes[ing.substituteGroupId]
                    ?: recipe.ingredients.firstOrNull { it.substituteGroupId == ing.substituteGroupId }?.id
                recipe.ingredients.find { it.id == selectedId } ?: ing
            } else ing
            if (resolved.isOptional && resolved.id !in enabledOptionals) continue
            out += resolved to ref.quantityDisplay
        }
        return out
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
    private val userPreferences: UserPreferences,
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

            // Optional ingredients: their section-top chip starts selected (included) when the
            // user's "include optionals by default" preference is on (default true).
            val defaultOptionals = if (userPreferences.includeOptionalsByDefault())
                recipe?.ingredients?.filter { it.isOptional }?.map { it.id }?.toSet() ?: emptySet()
            else emptySet()

            // Restore the user's last per-recipe selections, validated against the current recipe
            // (ingredients may have changed since they were saved).
            val ingById = recipe?.ingredients?.associateBy { it.id } ?: emptyMap()
            val savedSubs = userPreferences.recipeSubstitutes(recipeId).filter { (grp, id) ->
                ingById[id]?.substituteGroupId == grp
            }
            val initialSubs = defaultSubs + savedSubs   // saved choices win per group
            val optionalIds = recipe?.ingredients?.filter { it.isOptional }?.map { it.id }?.toSet() ?: emptySet()
            val initialOptionals = userPreferences.recipeEnabledOptionals(recipeId)
                ?.filter { it in optionalIds }?.toSet()
                ?: defaultOptionals

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
                        prev.selectedSubstitutes else initialSubs,
                    enabledOptionals = if (preserveSelections && prev.enabledOptionals.isNotEmpty())
                        prev.enabledOptionals else initialOptionals,
                    isLoading = false,
                    isOwner = isOwner,
                    variants = variantRefs,
                    canAddVariant = canAdd
                )
            }

            // Start listening to notes (comments) + popularity counts if shared; load the notes lock.
            if (recipe != null && recipe.visibility != "private") {
                startObservingComments()
                if (recipe.visibility == "friends" || recipe.visibility == "public") startObservingCounts()
                _uiState.update { it.copy(notesLocked = sharedRecipeService.getNotesLocked(recipeId)) }
            }

            // Resolve "shared with" recipient names for the owner's recipients sheet.
            if (isOwner && recipe != null && recipe.sharedWith.isNotEmpty()) {
                val profiles = socialRepository.getUsers(recipe.sharedWith)
                _uiState.update { it.copy(sharedRecipients = profiles) }
            } else {
                _uiState.update { it.copy(sharedRecipients = emptyList()) }
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

    /** Owner-only: freeze / unfreeze new notes on this recipe (existing notes are kept). */
    fun toggleNotesLock() {
        val locked = !_uiState.value.notesLocked
        _uiState.update { it.copy(notesLocked = locked) }   // optimistic
        viewModelScope.launch(Dispatchers.IO) {
            val ok = sharedRecipeService.setNotesLocked(recipeId, locked)
            if (!ok) _uiState.update { it.copy(notesLocked = !locked) }  // revert on failure
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

            // Going Private clears the share list too. Friends/Public keep the (now-irrelevant) list.
            if (visibility == "private") repository.setSharedWith(recipeId, emptyList())
            else repository.setVisibility(recipeId, visibility)

            val newShared = if (visibility == "private") emptyList() else _uiState.value.recipe?.sharedWith ?: emptyList()
            val updatedRecipe = _uiState.value.recipe?.copy(visibility = visibility, sharedWith = newShared)
            _uiState.update {
                it.copy(recipe = updatedRecipe, isVisibilityUpdating = false,
                    sharedRecipients = if (visibility == "private") emptyList() else it.sharedRecipients)
            }

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

    /** Set the recipe's "shared with specific people" list (visibility ⇒ "shared", or "private" if empty). */
    fun setSharedRecipients(profiles: List<UserProfile>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isVisibilityUpdating = true) }
            val uids = profiles.map { it.uid }.distinct()
            repository.setSharedWith(recipeId, uids)
            val newVis = if (uids.isEmpty()) "private" else "shared"
            val updatedRecipe = _uiState.value.recipe?.copy(visibility = newVis, sharedWith = uids)
            _uiState.update { it.copy(recipe = updatedRecipe, sharedRecipients = profiles, isVisibilityUpdating = false) }
            val recipe = updatedRecipe ?: return@launch
            if (uids.isEmpty()) sharedRecipeService.unpublish(recipeId)
            else sharedRecipeService.publish(recipe)   // mirror with the ACL so recipients can read
        }
    }

    fun addSharedRecipient(user: UserProfile) =
        setSharedRecipients((_uiState.value.sharedRecipients + user).distinctBy { it.uid })

    fun removeSharedRecipient(uid: String) =
        setSharedRecipients(_uiState.value.sharedRecipients.filterNot { it.uid == uid })

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
        // Remember this choice for next time the recipe is opened.
        userPreferences.setRecipeSubstitutes(recipeId, _uiState.value.selectedSubstitutes)
    }

    fun toggleOptional(ingredientId: String) {
        _uiState.update { state ->
            val updated = if (ingredientId in state.enabledOptionals)
                state.enabledOptionals - ingredientId else state.enabledOptionals + ingredientId
            state.copy(enabledOptionals = updated)
        }
        userPreferences.setRecipeEnabledOptionals(recipeId, _uiState.value.enabledOptionals)
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
     * Share the recipe directly to a specific person: add them to the recipe's per-recipient
     * share list (visibility "shared") so they — and only they (plus the author) — can read the
     * live recipe, then deliver the pointer + notification. A recipe that is already Friends/Public
     * stays as-is (already broadly readable). Edits propagate via the single mirror doc.
     */
    fun makeSharableAndShareToFollower(recipientUid: String, recipientName: String) {
        viewModelScope.launch {
            ensureSharedWith(recipientUid)
            shareToFollowerInternal(recipientUid, recipientName)
        }
    }

    /** Add [recipientUid] to the recipe's share list (visibility "shared") + republish the mirror. */
    private suspend fun ensureSharedWith(recipientUid: String) {
        val recipe = _uiState.value.recipe ?: return
        if (recipe.visibility == "public" || recipe.visibility == "friends") return  // already readable
        if (recipientUid in recipe.sharedWith) return
        val uids = (recipe.sharedWith + recipientUid).distinct()
        repository.setSharedWith(recipeId, uids)
        val updated = recipe.copy(visibility = "shared", sharedWith = uids)
        _uiState.update { it.copy(recipe = updated) }
        sharedRecipeService.publish(updated)
        // Refresh the recipients chips.
        val profiles = socialRepository.getUsers(uids)
        _uiState.update { it.copy(sharedRecipients = profiles) }
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
    private var editSharedWith: List<String> = emptyList()
    private var editParentRecipeId: String? = null
    // Snapshot of each ingredient's conversion-relevant fields at edit start, so on save we
    // recompute unit conversions ONLY for ingredients whose name/quantity actually changed.
    private var editIngredientSig: Map<String, String> = emptyMap()

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
        editSharedWith = recipe.sharedWith
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
        editIngredientSig = sections.flatMap { it.ingredients }.associate { it.id to ingredientSig(it) }
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
    /** Add a section with a given name (used by the section edit sheet's "Add"). */
    fun addSection(name: String) = updateDraft { it.copy(sections = it.sections + EditorSection(name = name.trim().ifBlank { "New Section" })) }
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
    /** Append a fully-specified ingredient (used by the ingredient edit sheet in "new" mode). */
    fun addIngredient(sectionId: String, ingredient: EditorIngredient) =
        transformSection(sectionId) { it.copy(ingredients = it.ingredients + ingredient) }
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
    /** Append a fully-specified step (used by the step edit sheet in "new" mode). */
    fun addStep(sectionId: String, step: EditorStep) =
        transformSection(sectionId) { it.copy(steps = it.steps + step) }
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

    /**
     * Link [ingredientId] as a substitute alternative of [targetId] (they share a
     * substituteGroupId, creating one if needed). [targetId] == null unlinks the ingredient
     * from its group. Operates across all sections by id.
     */
    fun linkSubstitute(ingredientId: String, targetId: String?) = updateDraft { d ->
        if (targetId == null) {
            d.copy(sections = d.sections.map { s ->
                s.copy(ingredients = s.ingredients.map {
                    if (it.id == ingredientId) it.copy(substituteGroupId = null) else it
                })
            })
        } else {
            val target = d.sections.flatMap { it.ingredients }.find { it.id == targetId }
            val groupId = target?.substituteGroupId ?: "subgrp-${UUID.randomUUID()}"
            d.copy(sections = d.sections.map { s ->
                s.copy(ingredients = s.ingredients.map { ing ->
                    when (ing.id) {
                        ingredientId -> ing.copy(substituteGroupId = groupId)
                        targetId -> if (ing.substituteGroupId == null) ing.copy(substituteGroupId = groupId) else ing
                        else -> ing
                    }
                })
            })
        }
    }

    /** #4 — set which ingredients a step uses (drives the cooking-mode card). */
    fun toggleStepIngredient(sectionId: String, stepId: String, ingredientId: String) =
        transformSection(sectionId) { s ->
            s.copy(steps = s.steps.map { st ->
                if (st.id != stepId) st
                else st.copy(ingredientIds = if (ingredientId in st.ingredientIds) st.ingredientIds - ingredientId else st.ingredientIds + ingredientId)
            })
        }

    /**
     * #9 — Auto-arrange ingredients from the step text (local, no Gemini). Per section: link any
     * ingredient that isn't referenced by any step to the **first step that mentions it** (by a
     * significant word of its name, e.g. "oil" in "olive oil"), then **reorder** the ingredient list
     * by first-mention order (unmentioned ones keep their relative order at the end). Explicit action
     * (edit-mode ＋ menu) so it never silently overrides a manual reorder.
     */
    fun autoArrangeFromSteps() = updateDraft { d ->
        val stop = setOf("the","and","for","with","to","of","an","into","until","then","your","you",
            "add","stir","cook","heat","mix","over","from","this","that","each","about","minutes","minute")
        fun tokens(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in stop }
        d.copy(sections = d.sections.map { sec ->
            val firstMention = HashMap<String, Int>()
            sec.steps.forEachIndexed { si, step ->
                val words = step.instruction.lowercase().split(Regex("[^a-z0-9]+")).toHashSet()
                sec.ingredients.forEach { ing ->
                    if (ing.id !in firstMention && ing.name.isNotBlank() && tokens(ing.name).any { it in words })
                        firstMention[ing.id] = si
                }
            }
            val linkedAnywhere = sec.steps.flatMap { it.ingredientIds }.toHashSet()
            val newSteps = sec.steps.mapIndexed { si, step ->
                val toAdd = sec.ingredients.filter { firstMention[it.id] == si && it.id !in linkedAnywhere }.map { it.id }
                if (toAdd.isEmpty()) step else step.copy(ingredientIds = (step.ingredientIds + toAdd).distinct())
            }
            val newIngredients = sec.ingredients.sortedWith(compareBy { firstMention[it.id] ?: Int.MAX_VALUE })
            sec.copy(ingredients = newIngredients, steps = newSteps)
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
                // #8 — auto-update conversions ONLY for ingredients whose name/quantity changed
                // (or were added) this session. Best-effort: a convert failure doesn't block save.
                val changed = draft.sections.flatMap { it.ingredients }.filter {
                    it.quantityDisplay.isNotBlank() && editIngredientSig[it.id] != ingredientSig(it)
                }
                val converted = if (changed.isNotEmpty())
                    runCatching { convertRemote(changed) }.getOrDefault(emptyMap()) else emptyMap()
                val effectiveSections = if (converted.isEmpty()) draft.sections else draft.sections.map { s ->
                    s.copy(ingredients = s.ingredients.map { ing -> converted[ing.id]?.let { ing.withConversions(it) } ?: ing })
                }

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
                    sharedWith = gson.toJson(editSharedWith),
                    parentRecipeId = editParentRecipeId,
                    variantName = if (editParentRecipeId != null) draft.variantName.trim().ifBlank { "Variation" } else null,
                )
                val sectionEntities = draft.sections.mapIndexed { idx, s ->
                    RecipeSectionEntity(id = s.id, recipeId = recipeId, name = s.name.trim(), orderIndex = idx)
                }
                val ingredientEntities = effectiveSections.flatMap { s ->
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
                            quantityValueMax = ing.quantityValueMax,
                            quantityValueMaxMetric = ing.quantityValueMaxMetric,
                            quantityValueMaxImperial = ing.quantityValueMaxImperial,
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
                // Reconcile the mirror with the recipe's visibility: publish for any shared tier
                // (shared/friends/public) so recipients get the edit; remove it for private.
                viewModelScope.launch(Dispatchers.IO) {
                    if (editVisibility == "shared" || editVisibility == "friends" || editVisibility == "public") {
                        repository.getRecipeWithDetails(recipeId)?.let { sharedRecipeService.publish(it) }
                    } else {
                        sharedRecipeService.unpublish(recipeId)
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

    /** Conversion-relevant fingerprint of an ingredient (name + quantity) — drives "what changed". */
    private fun ingredientSig(i: EditorIngredient): String =
        "${i.name.trim()}|${i.quantityDisplay.trim()}|${i.quantityUnit.trim()}|${i.quantityValue}|${i.quantityValueMax}"

    /** Call the `convertIngredients` Cloud Function for [ings]; returns id → raw conversion fields. */
    private suspend fun convertRemote(ings: List<EditorIngredient>): Map<String, Map<String, Any?>> {
        if (ings.isEmpty()) return emptyMap()
        val payload = ings.map { hashMapOf("id" to it.id, "name" to it.name, "quantityDisplay" to it.quantityDisplay) }
        @Suppress("UNCHECKED_CAST")
        val data = withContext(Dispatchers.IO) {
            functions.getHttpsCallable("convertIngredients").call(hashMapOf("ingredients" to payload)).await().getData()
        } as? Map<String, Any?>
        return ((data?.get("ingredients") as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList())
            .mapNotNull { r -> (r["id"] as? String)?.let { it to r } }.toMap()
    }

    private fun EditorIngredient.withConversions(r: Map<String, Any?>) = copy(
        quantityValueMetric = (r["quantityValueMetric"] as? Number)?.toDouble(),
        quantityUnitMetric = r["quantityUnitMetric"] as? String,
        quantityDisplayMetric = r["quantityDisplayMetric"] as? String,
        quantityValueImperial = (r["quantityValueImperial"] as? Number)?.toDouble(),
        quantityUnitImperial = r["quantityUnitImperial"] as? String,
        quantityDisplayImperial = r["quantityDisplayImperial"] as? String,
        quantityValueMaxMetric = (r["quantityValueMaxMetric"] as? Number)?.toDouble(),
        quantityValueMaxImperial = (r["quantityValueMaxImperial"] as? Number)?.toDouble(),
    )

    fun updateConversions() {
        val draft = _uiState.value.draft ?: return
        val ingredients = draft.sections.flatMap { it.ingredients }
        if (ingredients.isEmpty()) return
        _uiState.update { it.copy(isConverting = true) }
        viewModelScope.launch {
            try {
                val byId = convertRemote(ingredients)
                updateDraft { d ->
                    d.copy(sections = d.sections.map { sec ->
                        sec.copy(ingredients = sec.ingredients.map { ing ->
                            byId[ing.id]?.let { ing.withConversions(it) } ?: ing
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
                        // Remove the mirror for any shared tier (friends OR public), not just public.
                        if (variant.visibility != "private") sharedRecipeService.unpublish(variant.id)
                    }
                }
                repository.deleteFullRecipe(recipeId)
                syncService.deletePersonalRecipe(recipeId)
                if (editVisibility != "private") sharedRecipeService.unpublish(recipeId)
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
        quantityValueMax = quantityValueMax, quantityValueMaxMetric = quantityValueMaxMetric, quantityValueMaxImperial = quantityValueMaxImperial,
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
            userPreferences: UserPreferences,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeDetailViewModel(repository, authRepository, sharedRecipeService, socialRepository, syncService, recipeId, gson, userPreferences) as T
            }
    }
}

/**
 * Render the live edit [EditDraft] as a [Recipe] so the detail screen's normal view composables
 * can display it unchanged while editing (edits reflect immediately). [base] supplies the fields
 * the draft doesn't track (ids, image, timestamps, scaling anchor). Quantities are shown at 1×.
 */
internal fun EditDraft.toPreviewRecipe(base: Recipe): Recipe {
    val sections = this.sections.mapIndexed { idx, s -> RecipeSection(id = s.id, name = s.name, orderIndex = idx) }
    val ingredients = this.sections.flatMap { s ->
        s.ingredients.mapIndexed { idx, ing ->
            Ingredient(
                id = ing.id, sectionId = s.id, name = ing.name,
                quantityValue = ing.quantityValue,
                quantityUnit = ing.quantityUnit.ifBlank { null },
                quantityDisplay = ing.quantityDisplay.ifBlank { null },
                quantityValueMetric = ing.quantityValueMetric, quantityUnitMetric = ing.quantityUnitMetric,
                quantityDisplayMetric = ing.quantityDisplayMetric,
                quantityValueImperial = ing.quantityValueImperial, quantityUnitImperial = ing.quantityUnitImperial,
                quantityDisplayImperial = ing.quantityDisplayImperial,
                quantityValueMax = ing.quantityValueMax, quantityValueMaxMetric = ing.quantityValueMaxMetric,
                quantityValueMaxImperial = ing.quantityValueMaxImperial,
                groupLabel = ing.groupLabel.ifBlank { null }, isOptional = ing.isOptional,
                substituteGroupId = ing.substituteGroupId, substituteRatio = ing.substituteRatio,
                orderIndex = idx, shoppingNote = ing.shoppingNote.ifBlank { null },
            )
        }
    }
    var stepOrder = 0
    val steps = this.sections.flatMap { s ->
        s.steps.map { st ->
            Step(
                id = st.id, sectionId = s.id, instruction = st.instruction, orderIndex = stepOrder++,
                ingredientRefs = st.ingredientIds.map { StepIngredientRef(it, null) },
            )
        }
    }
    return base.copy(
        title = this.title,
        description = this.description.ifBlank { null },
        sourceUrls = this.sourceUrlsText.lines().map { it.trim() }.filter { it.isNotBlank() },
        baseServings = this.baseServings.toIntOrNull() ?: 1,
        baseServingsMin = if (this.isRangeYield) this.baseServingsMin.toIntOrNull() else null,
        baseServingsMax = if (this.isRangeYield) this.baseServingsMax.toIntOrNull() else null,
        prepTimeMinutes = this.prepTimeMinutes.toIntOrNull(),
        cookTimeMinutes = this.cookTimeMinutes.toIntOrNull(),
        tags = this.tagsText.split(",").map { it.trim() }.filter { it.isNotBlank() },
        sections = sections, ingredients = ingredients, steps = steps,
        variantName = if (this.isVariant) this.variantName else base.variantName,
    )
}
