package com.aerion.tablefeed.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import com.aerion.tablefeed.TablefeedApplication
import com.aerion.tablefeed.data.auth.AuthRepository
import com.aerion.tablefeed.data.local.entity.*
import com.aerion.tablefeed.data.remote.SharedRecipeService
import com.aerion.tablefeed.data.repository.RecipeRepository
import com.aerion.tablefeed.domain.model.*
import com.aerion.tablefeed.ui.util.QuantityScaler
import com.aerion.tablefeed.ui.util.UnitMode
import com.aerion.tablefeed.ui.util.compactCount
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ─── ViewModel ────────────────────────────────────────────────────────────────

data class SharedDetailUiState(
    val recipe: Recipe? = null,
    val isLoading: Boolean = true,
    val selectedServings: Int = 0,
    val comments: List<Comment> = emptyList(),
    val isCopied: Boolean = false,
    val currentUid: String? = null,
    val isLiked: Boolean = false,
    val likeCount: Int = 0,
) {
    /** Anyone signed in who isn't the author can heart a shared recipe. */
    val canLike: Boolean get() = currentUid != null && recipe != null && currentUid != recipe.authorId

    val scaleFactor: Double get() {
        val recipe = recipe ?: return 1.0
        if (recipe.baseServings == 0) return 1.0
        return selectedServings.toDouble() / recipe.baseServings
    }
    val yieldDisplay: String get() {
        val recipe = recipe ?: return "$selectedServings"
        return "${(recipe.baseServings * scaleFactor).toInt()}"
    }
}

class SharedDetailViewModel(
    private val recipeId: String,
    private val sharedRecipeService: SharedRecipeService,
    private val repository: RecipeRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharedDetailUiState(currentUid = authRepository.uid))
    val uiState: StateFlow<SharedDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val recipe = sharedRecipeService.getSharedRecipeDetail(recipeId)
            _uiState.update {
                it.copy(
                    recipe = recipe,
                    selectedServings = recipe?.baseServings ?: 1,
                    isLoading = false
                )
            }
        }
        viewModelScope.launch {
            sharedRecipeService.getCommentsFlow(recipeId).collect { comments ->
                _uiState.update { it.copy(comments = comments) }
            }
        }
        viewModelScope.launch {
            sharedRecipeService.likeStateFlow(recipeId).collect { ls ->
                _uiState.update { it.copy(isLiked = ls.isLiked, likeCount = ls.likeCount) }
            }
        }
    }

    /** Heart / un-heart this recipe. No-op for the author (it's their own recipe). */
    fun toggleLike() {
        val s = _uiState.value
        if (!s.canLike) return
        val liked = !s.isLiked
        _uiState.update { it.copy(isLiked = liked) }   // optimistic; the count flow corrects it
        viewModelScope.launch { sharedRecipeService.setLiked(recipeId, liked) }
    }

    fun adjustServings(delta: Int) {
        _uiState.update { it.copy(selectedServings = (it.selectedServings + delta).coerceAtLeast(1)) }
    }

    fun addComment(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch { sharedRecipeService.addComment(recipeId, content) }
    }

    fun deleteComment(commentId: String) {
        viewModelScope.launch { sharedRecipeService.deleteComment(recipeId, commentId) }
    }

    /** Save a copy of this shared recipe into the user's personal Room DB. */
    fun copyToMyRecipes() {
        val recipe = _uiState.value.recipe ?: return
        val uid = authRepository.uid ?: return
        viewModelScope.launch {
            val newId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val newRecipeId = newId

            val recipeEntity = RecipeEntity(
                id = newRecipeId,
                title = recipe.title,
                description = recipe.description,
                sourceUrls = com.google.gson.Gson().toJson(recipe.sourceUrls),
                baseServings = recipe.baseServings,
                baseServingsMin = recipe.baseServingsMin,
                baseServingsMax = recipe.baseServingsMax,
                scaleIngredientId = recipe.scaleIngredientId,
                scaleStep = recipe.scaleStep,
                prepTimeMinutes = recipe.prepTimeMinutes,
                cookTimeMinutes = recipe.cookTimeMinutes,
                imageUrl = recipe.imageUrl,
                tags = com.google.gson.Gson().toJson(recipe.tags),
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
            val sections = recipe.sections.map { s ->
                RecipeSectionEntity(id = UUID.randomUUID().toString(), recipeId = newRecipeId,
                    name = s.name, orderIndex = s.orderIndex)
            }
            // Build ID remapping so ingredients/steps can reference new section IDs
            val sectionIdMap = recipe.sections.zip(sections).associate { (old, new) -> old.id to new.id }
            val ingredients = recipe.ingredients.map { ing ->
                IngredientEntity(
                    id = UUID.randomUUID().toString(), recipeId = newRecipeId,
                    sectionId = ing.sectionId?.let { sectionIdMap[it] },
                    name = ing.name,
                    quantityValue = ing.quantityValue, quantityUnit = ing.quantityUnit,
                    quantityDisplay = ing.quantityDisplay,
                    quantityValueMetric = ing.quantityValueMetric,
                    quantityUnitMetric = ing.quantityUnitMetric,
                    quantityDisplayMetric = ing.quantityDisplayMetric,
                    quantityValueImperial = ing.quantityValueImperial,
                    quantityUnitImperial = ing.quantityUnitImperial,
                    quantityDisplayImperial = ing.quantityDisplayImperial,
                    quantityValueMax = ing.quantityValueMax,
                    quantityValueMaxMetric = ing.quantityValueMaxMetric,
                    quantityValueMaxImperial = ing.quantityValueMaxImperial,
                    groupLabel = ing.groupLabel,
                    isOptional = ing.isOptional,
                    substituteGroupId = ing.substituteGroupId,
                    substituteRatio = ing.substituteRatio,
                    orderIndex = ing.orderIndex
                )
            }
            val ingIdMap = recipe.ingredients.zip(ingredients).associate { (old, new) -> old.id to new.id }
            val steps = recipe.steps.map { step ->
                StepEntity(
                    id = UUID.randomUUID().toString(), recipeId = newRecipeId,
                    sectionId = step.sectionId?.let { sectionIdMap[it] },
                    instruction = step.instruction, orderIndex = step.orderIndex
                )
            }
            val stepIdMap = recipe.steps.zip(steps).associate { (old, new) -> old.id to new.id }
            val refs = recipe.steps.flatMap { step ->
                step.ingredientRefs.map { ref ->
                    StepIngredientRefEntity(
                        stepId = stepIdMap[step.id] ?: "",
                        ingredientId = ingIdMap[ref.ingredientId] ?: "",
                        quantityDisplay = ref.quantityDisplay
                    )
                }
            }
            repository.insertFullRecipe(recipeEntity, sections, ingredients, steps, refs)
            _uiState.update { it.copy(isCopied = true) }
        }
    }

    companion object {
        fun factory(
            recipeId: String,
            sharedRecipeService: SharedRecipeService,
            repository: RecipeRepository,
            authRepository: AuthRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SharedDetailViewModel(recipeId, sharedRecipeService, repository, authRepository) as T
            }
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedRecipeDetailScreen(
    recipeId: String,
    onBack: () -> Unit
) {
    val app = LocalContext.current.applicationContext as TablefeedApplication
    val viewModel: SharedDetailViewModel = viewModel(
        key = "shared-$recipeId",
        factory = SharedDetailViewModel.factory(
            recipeId = recipeId,
            sharedRecipeService = app.container.sharedRecipeService,
            repository = app.container.repository,
            authRepository = app.container.authRepository
        )
    )
    val state by viewModel.uiState.collectAsState()
    var commentText by remember { mutableStateOf("") }
    var selectedUnit by remember { mutableStateOf(UnitMode.ORIGINAL) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.recipe?.title ?: "", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val recipe = state.recipe ?: run {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Recipe not found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── Header ────────────────────────────────────────────────
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(recipe.title, style = MaterialTheme.typography.headlineLarge)
                    recipe.authorDisplayName?.let { author ->
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Text(author, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    recipe.description?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── Copy button ───────────────────────────────────────────
            item {
                Button(
                    onClick = viewModel::copyToMyRecipes,
                    enabled = !state.isCopied && state.currentUid != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        if (state.isCopied) Icons.Default.CheckCircle else Icons.Default.BookmarkAdd,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.isCopied) "Copied to Your Recipes" else "Copy to My Recipes")
                }
                if (state.currentUid == null) {
                    Text(
                        "Sign in to copy this recipe.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // ── Like (heart) — always shown for shared recipes (toggle if you can like it). ──
            run {
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.canLike) {
                            IconButton(onClick = viewModel::toggleLike) {
                                Icon(
                                    if (state.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (state.isLiked) "Unlike" else "Like",
                                    tint = if (state.isLiked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Spacer(Modifier.width(12.dp))
                            Icon(Icons.Default.Favorite, contentDescription = "Likes",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            if (state.likeCount > 0) "${compactCount(state.likeCount)} ${if (state.likeCount == 1) "like" else "likes"}"
                            else "Be the first to like",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Time + Yield ──────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        recipe.prepTimeMinutes?.let {
                            Text("Prep  ${it}min", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        recipe.cookTimeMinutes?.let {
                            Text("Cook  ${it}min", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Yield ", style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { viewModel.adjustServings(-1) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Remove, contentDescription = "Less", modifier = Modifier.size(16.dp))
                        }
                        Text(state.yieldDisplay, style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.widthIn(min = 28.dp))
                        IconButton(onClick = { viewModel.adjustServings(1) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Add, contentDescription = "More", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            // ── Ingredients ───────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ingredients", style = MaterialTheme.typography.headlineMedium)
                    val hasConversions = recipe.ingredients.any {
                        it.quantityValueMetric != null || it.quantityValueImperial != null
                    }
                    if (hasConversions) {
                        SingleChoiceSegmentedButtonRow {
                            UnitMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = selectedUnit == mode,
                                    onClick = { selectedUnit = mode },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = UnitMode.entries.size),
                                    label = {
                                        Text(when (mode) {
                                            UnitMode.ORIGINAL -> "Orig"
                                            UnitMode.METRIC   -> "Metric"
                                            UnitMode.IMPERIAL -> "Imp"
                                        }, style = MaterialTheme.typography.labelSmall)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            val grouped = recipe.ingredients.groupBy { it.groupLabel ?: "" }
            grouped.forEach { (label, ings) ->
                if (label.isNotBlank()) {
                    item {
                        Text(label,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
                items(ings, key = { "ing-${it.id}" }) { ing ->
                    val qty = QuantityScaler.scale(ing, state.scaleFactor, selectedUnit)
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 3.dp)) {
                        Text("• ", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text("${ing.name}  —  $qty", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }

            // ── Steps ─────────────────────────────────────────────────
            item { Text("Instructions", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }

            recipe.sections.forEach { section ->
                item {
                    Text(section.name, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                val sectionSteps = recipe.steps.filter { it.sectionId == section.id }
                    .sortedBy { it.orderIndex }
                items(sectionSteps, key = { "step-${it.id}" }) { step ->
                    SharedStepRow(step = step, stepNumber = step.orderIndex + 1)
                }
            }
            val unsectionedSteps = recipe.steps.filter { it.sectionId == null }.sortedBy { it.orderIndex }
            if (unsectionedSteps.isNotEmpty()) {
                items(unsectionedSteps, key = { "step-${it.id}" }) { step ->
                    SharedStepRow(step = step, stepNumber = step.orderIndex + 1)
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }

            // ── Comments ──────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Forum, contentDescription = null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Comments", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.weight(1f))
                    Text("${state.comments.size}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item {
                if (state.currentUid != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = commentText,
                            onValueChange = { commentText = it },
                            placeholder = { Text("Add a comment…") },
                            modifier = Modifier.weight(1f),
                            maxLines = 3,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { viewModel.addComment(commentText); commentText = "" },
                            enabled = commentText.isNotBlank()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post",
                                tint = if (commentText.isNotBlank()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Text("Sign in to leave a comment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }

            if (state.comments.isEmpty()) {
                item {
                    Text("No comments yet.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }

            items(state.comments, key = { "comment-${it.id}" }) { comment ->
                SharedCommentRow(
                    comment = comment,
                    canDelete = state.currentUid == comment.authorId ||
                        state.currentUid == recipe.authorId,
                    onDelete = { viewModel.deleteComment(comment.id) }
                )
            }
        }
    }
}

@Composable
private fun SharedStepRow(step: Step, stepNumber: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.size(28.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Text("$stepNumber",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Text(step.instruction, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SharedCommentRow(
    comment: Comment,
    canDelete: Boolean,
    onDelete: () -> Unit
) {
    val dateStr = remember(comment.createdAt) {
        SimpleDateFormat("MMM d  h:mm a", Locale.getDefault()).format(Date(comment.createdAt))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.size(32.dp).background(
                MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                comment.authorDisplayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.authorDisplayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text(dateStr, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            Text(comment.content, style = MaterialTheme.typography.bodyMedium)
        }
        if (canDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
