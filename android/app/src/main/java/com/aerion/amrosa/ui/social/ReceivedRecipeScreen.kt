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

data class ReceivedRecipeUiState(
    val recipe: Recipe? = null,
    val fromDisplayName: String? = null,  // who shared this recipe with you
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
    private val shareId: String,
    private val socialRepository: SocialRepository,
    private val repository: RecipeRepository,
    private val authRepository: AuthRepository,
    private val gson: Gson
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceivedRecipeUiState())
    val uiState: StateFlow<ReceivedRecipeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val data = socialRepository.getReceivedRecipe(shareId)
            _uiState.update {
                it.copy(
                    recipe = data?.recipe,
                    fromDisplayName = data?.fromDisplayName,
                    selectedServings = data?.recipe?.baseServings ?: 1,
                    isLoading = false,
                    error = if (data == null) "Recipe not found" else null
                )
            }
        }
    }

    fun adjustServings(delta: Int) {
        _uiState.update { it.copy(selectedServings = (it.selectedServings + delta).coerceAtLeast(1)) }
    }

    /**
     * Save a copy of this received recipe to Room as the current user's personal recipe.
     * Returns the new recipeId via [savedRecipeId] in state.
     */
    fun saveToMyRecipes() {
        val recipe = _uiState.value.recipe ?: return
        val uid = authRepository.uid ?: return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val newRecipeId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            // Build Room entities
            val recipeEntity = RecipeEntity(
                id = newRecipeId,
                title = recipe.title,
                description = recipe.description,
                sourceUrls = gson.toJson(recipe.sourceUrls),
                baseServings = recipe.baseServings,
                baseServingsMin = recipe.baseServingsMin,
                baseServingsMax = recipe.baseServingsMax,
                scaleIngredientId = recipe.scaleIngredientId,
                scaleStep = recipe.scaleStep,
                prepTimeMinutes = recipe.prepTimeMinutes,
                cookTimeMinutes = recipe.cookTimeMinutes,
                imageUrl = recipe.imageUrl,
                tags = gson.toJson(recipe.tags),
                isCustomized = false,
                isImported = false,
                needsReview = false,
                version = 1,
                changeLog = "[]",
                createdAt = now,
                updatedAt = now,
                authorId = uid,
                authorDisplayName = authRepository.displayName ?: authRepository.email,
                visibility = "private"
            )

            val sectionEntities = recipe.sections.map { s ->
                RecipeSectionEntity(
                    id = UUID.randomUUID().toString(),
                    recipeId = newRecipeId,
                    name = s.name,
                    orderIndex = s.orderIndex
                )
            }
            val sectionIdMap = recipe.sections.zip(sectionEntities)
                .associate { (old, new) -> old.id to new.id }

            val ingredientEntities = recipe.ingredients.map { ing ->
                IngredientEntity(
                    id = UUID.randomUUID().toString(),
                    recipeId = newRecipeId,
                    sectionId = ing.sectionId?.let { sectionIdMap[it] },
                    name = ing.name,
                    quantityValue = ing.quantityValue,
                    quantityUnit = ing.quantityUnit,
                    quantityDisplay = ing.quantityDisplay,
                    groupLabel = ing.groupLabel,
                    isOptional = ing.isOptional,
                    substituteGroupId = ing.substituteGroupId,
                    substituteRatio = ing.substituteRatio,
                    orderIndex = ing.orderIndex,
                    quantityValueMetric = ing.quantityValueMetric,
                    quantityUnitMetric = ing.quantityUnitMetric,
                    quantityDisplayMetric = ing.quantityDisplayMetric,
                    quantityValueImperial = ing.quantityValueImperial,
                    quantityUnitImperial = ing.quantityUnitImperial,
                    quantityDisplayImperial = ing.quantityDisplayImperial
                )
            }

            val stepEntities = recipe.steps.map { step ->
                StepEntity(
                    id = UUID.randomUUID().toString(),
                    recipeId = newRecipeId,
                    sectionId = step.sectionId?.let { sectionIdMap[it] },
                    instruction = step.instruction,
                    orderIndex = step.orderIndex
                )
            }
            val stepIdMap = recipe.steps.zip(stepEntities)
                .associate { (old, new) -> old.id to new.id }

            val refEntities = recipe.steps.flatMap { step ->
                val newStepId = stepIdMap[step.id] ?: return@flatMap emptyList()
                step.ingredientRefs.mapNotNull { ref ->
                    // Find the new ingredient id from the original ingredient id
                    val oldIng = recipe.ingredients.find { it.id == ref.ingredientId }
                        ?: return@mapNotNull null
                    val newIngId = ingredientEntities.getOrNull(
                        recipe.ingredients.indexOf(oldIng)
                    )?.id ?: return@mapNotNull null
                    StepIngredientRefEntity(
                        stepId = newStepId,
                        ingredientId = newIngId,
                        quantityDisplay = ref.quantityDisplay
                    )
                }
            }

            try {
                repository.insertFullRecipe(
                    recipe = recipeEntity,
                    sections = sectionEntities,
                    ingredients = ingredientEntities,
                    steps = stepEntities,
                    refs = refEntities
                )
                _uiState.update { it.copy(isSaving = false, savedRecipeId = newRecipeId) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = "Couldn't save recipe: ${e.message}") }
            }
        }
    }

    companion object {
        fun factory(
            shareId: String,
            socialRepository: SocialRepository,
            repository: RecipeRepository,
            authRepository: AuthRepository,
            gson: Gson
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ReceivedRecipeViewModel(shareId, socialRepository, repository, authRepository, gson) as T
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
    shareId: String,
    onBack: () -> Unit,
    onSaved: (newRecipeId: String) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AmrosaApplication
    val viewModel: ReceivedRecipeViewModel = viewModel(
        key = shareId,
        factory = ReceivedRecipeViewModel.factory(
            shareId = shareId,
            socialRepository = app.container.socialRepository,
            repository = app.container.repository,
            authRepository = app.container.authRepository,
            gson = app.container.gson
        )
    )
    val state by viewModel.uiState.collectAsState()
    var selectedUnit by remember { mutableStateOf(UnitMode.ORIGINAL) }

    // Open the saved recipe's detail once the copy is written to Room
    LaunchedEffect(state.savedRecipeId) {
        state.savedRecipeId?.let { onSaved(it) }
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
                        Text("Save Recipe", fontWeight = FontWeight.SemiBold)
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
                    // ── From banner ─────────────────────────────────────────────
                    item(key = "from_banner") {
                        state.fromDisplayName?.let { sender ->
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
                                        "Shared by $sender",
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

