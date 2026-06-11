package com.aerion.amrosa.ui.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.data.auth.AuthRepository
import com.aerion.amrosa.data.local.entity.*
import com.aerion.amrosa.data.remote.SharedRecipeService
import com.aerion.amrosa.data.remote.SocialRepository
import com.aerion.amrosa.data.repository.RecipeRepository
import com.aerion.amrosa.domain.model.*
import com.aerion.amrosa.ui.util.QuantityScaler
import com.aerion.amrosa.ui.util.UnitMode
import com.google.gson.Gson
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

// ─── ViewModel ────────────────────────────────────────────────────────────────

/**
 * How the review screen was entered:
 *  - [Pointer]: from the Shared inbox — a pending `shared_to` pointer (consumed on save).
 *  - [Direct]: from a co-chef's profile — the recipe id + author are already known (no pointer).
 */
sealed class ReviewSource {
    data class Pointer(val shareId: String) : ReviewSource()
    data class Direct(val recipeId: String, val authorUid: String, val authorName: String) : ReviewSource()
}

data class ReceivedRecipeUiState(
    val recipe: Recipe? = null,
    val bannerLabel: String? = null,      // "Shared by X" (inbox) or "By X" (profile)
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val savedRecipeId: String? = null,   // non-null once saved to Room
    val error: String? = null,
    val selectedServings: Int = 1
) {
    val scaleFactor: Double get() {
        val recipe = recipe ?: return 1.0
        if (recipe.baseServings == 0) return 1.0
        return selectedServings.toDouble() / recipe.baseServings
    }
}

class ReceivedRecipeViewModel(
    private val source: ReviewSource,
    private val socialRepository: SocialRepository,
    private val sharedRecipeService: SharedRecipeService,
    private val repository: RecipeRepository,
    private val authRepository: AuthRepository,
    private val gson: Gson
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceivedRecipeUiState())
    val uiState: StateFlow<ReceivedRecipeUiState> = _uiState.asStateFlow()

    // Resolved details kept for the save step
    private var resolvedRecipeId: String? = null
    private var authorUid: String = ""
    private var authorName: String = "Someone"
    private var pointerShareId: String? = null   // pointer mode only — consumed on save

    init {
        viewModelScope.launch {
            when (source) {
                is ReviewSource.Pointer -> {
                    pointerShareId = source.shareId
                    val p = socialRepository.getReceivedPointer(source.shareId)
                    p?.let { resolvedRecipeId = it.recipeId; authorUid = it.authorUid; authorName = it.authorName }
                    val recipe = resolvedRecipeId?.let { sharedRecipeService.getSharedRecipeDetail(it) }
                    _uiState.update {
                        it.copy(
                            recipe = recipe,
                            bannerLabel = p?.let { pp -> "Shared by ${pp.fromDisplayName}" },
                            selectedServings = recipe?.baseServings ?: 1,
                            isLoading = false,
                            error = when {
                                p == null -> "This share is no longer available."
                                recipe == null -> "This recipe is no longer shared by its author."
                                else -> null
                            }
                        )
                    }
                }
                is ReviewSource.Direct -> {
                    resolvedRecipeId = source.recipeId
                    authorUid = source.authorUid
                    authorName = source.authorName
                    val recipe = sharedRecipeService.getSharedRecipeDetail(source.recipeId)
                    _uiState.update {
                        it.copy(
                            recipe = recipe,
                            bannerLabel = "By ${source.authorName}",
                            selectedServings = recipe?.baseServings ?: 1,
                            isLoading = false,
                            error = if (recipe == null) "This recipe is no longer available." else null
                        )
                    }
                }
            }
        }
    }

    fun adjustServings(delta: Int) {
        _uiState.update { it.copy(selectedServings = (it.selectedServings + delta).coerceAtLeast(1)) }
    }

    /** Record a cook (Cooking Mode "Done") on this recipe — feeds Discover recency. */
    fun markCooked() {
        val rid = resolvedRecipeId ?: _uiState.value.recipe?.id ?: return
        viewModelScope.launch { repository.markCooked(rid) }
    }

    /**
     * Save this recipe to the Shared tab (Tab 2, Recipe Ownership Model v2):
     *   1. write a reference at received_recipes/{uid}/items/{recipeId}
     *   2. cache the recipe locally with isReceived = true (original author preserved)
     *   3. consume the pending share pointer (pointer mode only)
     * The local copy keeps the canonical recipeId so future refreshes overwrite it in place.
     */
    fun saveToMyRecipes() {
        val recipe = _uiState.value.recipe ?: return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                // Real author name: the mirror's name unless it's blank/"Imported" (legacy/iOS),
                // in which case fall back to the name resolved from the pointer/profile.
                val mirrorName = recipe.authorDisplayName
                val realAuthorName = if (mirrorName.isNullOrBlank() || mirrorName.equals("Imported", true))
                    authorName else mirrorName
                socialRepository.saveReceivedReference(
                    recipeId = recipe.id,
                    authorUid = authorUid,
                    authorName = realAuthorName
                )
                repository.cacheReceivedRecipe(recipe.copy(authorDisplayName = realAuthorName))
                pointerShareId?.let { socialRepository.deleteReceivedPointer(it) }
                _uiState.update { it.copy(isSaving = false, savedRecipeId = recipe.id) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = "Couldn't save recipe: ${e.message}") }
            }
        }
    }

    companion object {
        fun factory(
            source: ReviewSource,
            socialRepository: SocialRepository,
            sharedRecipeService: SharedRecipeService,
            repository: RecipeRepository,
            authRepository: AuthRepository,
            gson: Gson
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ReceivedRecipeViewModel(source, socialRepository, sharedRecipeService, repository, authRepository, gson) as T
            }
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

/**
 * Shows a recipe shared directly to the current user.
 * Provides a "Save to My Recipes" button.
 *
 * @param onBack back nav
 * @param onSaved called with the new local recipeId once the copy is saved to My Recipes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivedRecipeScreen(
    source: ReviewSource,
    onBack: () -> Unit,
    onSaved: (newRecipeId: String) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AmrosaApplication
    val vmKey = when (source) {
        is ReviewSource.Pointer -> "ptr-${source.shareId}"
        is ReviewSource.Direct -> "dir-${source.recipeId}"
    }
    val viewModel: ReceivedRecipeViewModel = viewModel(
        key = vmKey,
        factory = ReceivedRecipeViewModel.factory(
            source = source,
            socialRepository = app.container.socialRepository,
            sharedRecipeService = app.container.sharedRecipeService,
            repository = app.container.repository,
            authRepository = app.container.authRepository,
            gson = app.container.gson
        )
    )
    val state by viewModel.uiState.collectAsState()
    var selectedUnit by remember { mutableStateOf(UnitMode.ORIGINAL) }
    var showCookingMode by remember { mutableStateOf(false) }
    val cookingChecked = remember { mutableStateListOf<String>() }

    // Open the saved recipe's detail once the copy is written to Room
    LaunchedEffect(state.savedRecipeId) {
        state.savedRecipeId?.let { onSaved(it) }
    }

    // Cook without saving — reuses the shared CookingModeScreen with a transient detail state.
    if (showCookingMode && state.recipe != null) {
        val recipe = state.recipe!!
        com.aerion.amrosa.ui.detail.CookingModeScreen(
            recipe = recipe,
            state = com.aerion.amrosa.ui.detail.RecipeDetailUiState(
                recipe = recipe, selectedServings = state.selectedServings
            ),
            selectedUnit = selectedUnit,
            onUnitChange = { selectedUnit = it },
            startSectionId = null,
            checkedIngredients = cookingChecked,
            onToggleIngredient = { id ->
                if (id in cookingChecked) cookingChecked.remove(id) else cookingChecked.add(id)
            },
            onExit = { showCookingMode = false },
            onDone = { viewModel.markCooked(); showCookingMode = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.recipe?.title ?: "Shared Recipe", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (state.recipe != null) {
                        IconButton(onClick = { showCookingMode = true }) {
                            Icon(Icons.Default.MenuBook, contentDescription = "Cooking Mode")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (state.recipe != null && state.savedRecipeId == null) {
                Surface(
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Button(
                        onClick = viewModel::saveToMyRecipes,
                        enabled = !state.isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Add to Shared tab", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    ) { padding ->
        when {
            state.isLoading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.error != null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    state.error!!,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }

            else -> {
                val recipe = state.recipe!!
                val hasConversionData = recipe.ingredients.any {
                    it.quantityValueMetric != null || it.quantityValueImperial != null
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    // ── Byline banner ───────────────────────────────────────────
                    item(key = "from_banner") {
                        state.bannerLabel?.let { banner ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Dining,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Text(
                                        banner,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // ── Title & description ─────────────────────────────────────
                    item(key = "header") {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(recipe.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            recipe.description?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // ── Meta ────────────────────────────────────────────────────
                    item(key = "meta") {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            recipe.prepTimeMinutes?.let {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.Timer, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${it}m prep", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            recipe.cookTimeMinutes?.let {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.LocalFireDepartment, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${it}m cook", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Group, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${(recipe.baseServings * state.scaleFactor).toInt()} servings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // ── Yield adjuster ──────────────────────────────────────────
                    item(key = "yield") {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = { viewModel.adjustServings(-1) },
                                enabled = state.selectedServings > 1
                            ) { Icon(Icons.Default.Remove, null) }
                            Text(state.selectedServings.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { viewModel.adjustServings(1) }) {
                                Icon(Icons.Default.Add, null)
                            }
                            Text("servings", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // ── Unit toggle ─────────────────────────────────────────────
                    if (hasConversionData) {
                        item(key = "unit_toggle") {
                            SegmentedButtonRow(
                                selectedUnit = selectedUnit,
                                onSelect = { selectedUnit = it }
                            )
                        }
                    }

                    item(key = "ingredients_header") {
                        Text(
                            "Ingredients",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    // ── Ingredients ─────────────────────────────────────────────
                    val ingredientsBySection = if (recipe.sections.isEmpty()) {
                        mapOf(null to recipe.ingredients.sortedBy { it.orderIndex })
                    } else {
                        recipe.sections.sortedBy { it.orderIndex }.associate { section ->
                            section to recipe.ingredients.filter { it.sectionId == section.id }.sortedBy { it.orderIndex }
                        }
                    }

                    ingredientsBySection.forEach { (section, ings) ->
                        if (section != null) {
                            item(key = "sec_ing_${section.id}") {
                                Text(
                                    section.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                        }
                        items(ings, key = { "ing_${it.id}" }) { ing ->
                            val qty = QuantityScaler.scale(ing, state.scaleFactor, selectedUnit)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (qty.isNotBlank()) {
                                    Text(qty, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 56.dp))
                                }
                                Text(ing.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                if (ing.isOptional) {
                                    Text("optional", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    item(key = "divider") { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }

                    item(key = "steps_header") {
                        Text(
                            "Instructions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    // ── Steps ───────────────────────────────────────────────────
                    val stepsBySection = if (recipe.sections.isEmpty()) {
                        mapOf(null to recipe.steps.sortedBy { it.orderIndex })
                    } else {
                        recipe.sections.sortedBy { it.orderIndex }.associate { section ->
                            section to recipe.steps.filter { it.sectionId == section.id }.sortedBy { it.orderIndex }
                        }
                    }
                    var globalStepIndex = 0
                    stepsBySection.forEach { (section, steps) ->
                        if (section != null) {
                            item(key = "sec_step_${section.id}") {
                                Text(
                                    section.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                        }
                        steps.forEach { step ->
                            val displayIndex = ++globalStepIndex
                            item(key = "step_${step.id}") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                "$displayIndex",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    Text(step.instruction, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    // ── Sources ─────────────────────────────────────────────────
                    if (recipe.sourceUrls.isNotEmpty()) {
                        item(key = "sources") {
                            val context = LocalContext.current
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            Text(
                                "Sources",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            recipe.sourceUrls.forEach { url ->
                                Text(
                                    text = "• $url",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp, vertical = 2.dp)
                                        .clickable {
                                            val intent = android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(url)
                                            )
                                            context.startActivity(intent)
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedButtonRow(
    selectedUnit: UnitMode,
    onSelect: (UnitMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        UnitMode.values().forEach { mode ->
            val isSelected = mode == selectedUnit
            OutlinedButton(
                onClick = { onSelect(mode) },
                modifier = Modifier.weight(1f),
                shape = when (mode) {
                    UnitMode.ORIGINAL -> RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 0.dp, bottomEnd = 0.dp)
                    UnitMode.IMPERIAL -> RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp, topStart = 0.dp, bottomStart = 0.dp)
                    else -> RoundedCornerShape(0.dp)
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                )
            ) {
                Text(
                    mode.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val UnitMode.label: String get() = when (this) {
    UnitMode.ORIGINAL -> "Original"
    UnitMode.METRIC -> "Metric"
    UnitMode.IMPERIAL -> "Imperial"
}

