package com.aerion.tablefeed.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.tablefeed.TablefeedApplication
import com.aerion.tablefeed.data.RecipeSaveTracker
import com.aerion.tablefeed.domain.model.Recipe
import com.aerion.tablefeed.ui.util.compactCount
import com.aerion.tablefeed.domain.model.ReceivedRecipeSummary
import com.aerion.tablefeed.ui.components.TablefeedTopBar
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRecipeClick: (String) -> Unit,
    onSettingsClick: () -> Unit = {},
    onFabFreeformClick: () -> Unit = {},
    onFabImportClick: () -> Unit = {},
    onNeedsReviewClick: (String) -> Unit = {},
    filter: RecipeFilter = RecipeFilter.ALL
) {
    val app = LocalContext.current.applicationContext as TablefeedApplication
    val viewModel: HomeViewModel = viewModel(
        key = "home-${filter.name}",
        factory = HomeViewModel.factory(
            repository = app.container.repository,
            sharedRecipeService = app.container.sharedRecipeService,
            authRepository = app.container.authRepository,
            filter = filter
        )
    )
    val state by viewModel.uiState.collectAsState()
    val savingIds by RecipeSaveTracker.saving.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (filter == RecipeFilter.YOURS || filter == RecipeFilter.PERSONAL) {
                ExtendedFloatingActionButton(
                    text = { Text("Add Recipe") },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = { showAddSheet = true }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            TablefeedTopBar(
                title = when (filter) {
                    RecipeFilter.YOURS -> "My Recipes"
                    RecipeFilter.PERSONAL -> "My Recipes"
                    else -> "Tablefeed"
                },
                actions = {
                    if (filter == RecipeFilter.ALL) {
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }
            )

            // ── Compact search bar ────────────────────────────────────────────
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                placeholder = { Text("Search recipes…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // ── Content ───────────────────────────────────────────────────────
            when {
                state.isLoading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp,
                            top = 8.dp,
                            bottom = if (filter == RecipeFilter.YOURS || filter == RecipeFilter.PERSONAL) 88.dp else 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // ── Filter chips (scroll with content) ──────────────────
                        if (filter == RecipeFilter.YOURS) {
                            item(key = "chips") {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    item {
                                        FilterChip(
                                            selected = state.yourRecipesFilter == YourRecipesFilter.ALL,
                                            onClick = { viewModel.onYourRecipesFilterChange(YourRecipesFilter.ALL) },
                                            label = { Text("All") }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = state.yourRecipesFilter == YourRecipesFilter.PERSONAL,
                                            onClick = { viewModel.onYourRecipesFilterChange(YourRecipesFilter.PERSONAL) },
                                            label = { Text("Personal") }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = state.yourRecipesFilter == YourRecipesFilter.IMPORTED,
                                            onClick = { viewModel.onYourRecipesFilterChange(YourRecipesFilter.IMPORTED) },
                                            label = { Text("Imported") }
                                        )
                                    }

                                    // Thin divider + category chips
                                    if (state.categories.isNotEmpty()) {
                                        item {
                                            Box(
                                                modifier = Modifier
                                                    .height(32.dp)
                                                    .padding(horizontal = 4.dp, vertical = 6.dp)
                                                    .width(1.dp)
                                                    .background(MaterialTheme.colorScheme.outlineVariant)
                                            )
                                        }
                                        item {
                                            FilterChip(
                                                selected = state.selectedCategory == null,
                                                onClick = { viewModel.onCategorySelect(null) },
                                                label = { Text("All categories") }
                                            )
                                        }
                                        items(state.categories) { category ->
                                            FilterChip(
                                                selected = state.selectedCategory == category,
                                                onClick = { viewModel.onCategorySelect(category) },
                                                label = { Text(category) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (state.filteredRecipes.isEmpty()) {
                            item(key = "empty") {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No recipes found",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            items(state.filteredRecipes, key = { it.id }) { recipe ->
                                RecipeCard(
                                    recipe = recipe,
                                    isUpdating = recipe.id in savingIds,
                                    likeCount = state.likeCounts[recipe.id] ?: 0,
                                    onClick = {
                                        if (recipe.needsReview) onNeedsReviewClick(recipe.id)
                                        else onRecipeClick(recipe.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Add Recipe bottom sheet ───────────────────────────────────────────────
    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    "Add New Recipe",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                HorizontalDivider()

                ListItem(
                    headlineContent = { Text("Type it out") },
                    supportingContent = {
                        Text("Free-text entry — Gemini will structure it",
                            style = MaterialTheme.typography.bodySmall)
                    },
                    leadingContent = {
                        Icon(Icons.Default.Edit, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable {
                        showAddSheet = false
                        onFabFreeformClick()
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                ListItem(
                    headlineContent = { Text("Import from URL or file") },
                    supportingContent = {
                        Text("Paste a URL, Google Sheet, or upload a file",
                            style = MaterialTheme.typography.bodySmall)
                    },
                    leadingContent = {
                        Icon(Icons.Default.CloudDownload, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable {
                        showAddSheet = false
                        onFabImportClick()
                    }
                )
            }
        }
    }
}

// ── My Recipes card ───────────────────────────────────────────────────────────

@Composable
private fun RecipeCard(recipe: Recipe, isUpdating: Boolean = false, likeCount: Int = 0, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(recipe.title, style = MaterialTheme.typography.titleLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // Background-save indicator — the recipe just got an edit and is still persisting.
                if (isUpdating) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            val timeLabel = buildString {
                recipe.prepTimeMinutes?.let { append("Prep ${it}min") }
                if (recipe.prepTimeMinutes != null && recipe.cookTimeMinutes != null) append("  ·  ")
                recipe.cookTimeMinutes?.let { append("Cook ${it}min") }
            }
            if (timeLabel.isNotBlank()) {
                Text(timeLabel, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (recipe.tags.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(recipe.tags) { tag ->
                        SuggestionChip(onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }

            // Tab 1 only shows my recipes → label is "me" / "Imported by me"
            val isShared = recipe.visibility == "public"
            run {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(if (recipe.isImported) "Imported by me" else "me",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Like count — shown on every card (0 until someone hearts it).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Favorite, contentDescription = "Likes",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(compactCount(likeCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isShared) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(Icons.Default.Public, contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text("Shared", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
            }

            if (recipe.needsReview) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.RateReview, contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("Needs review — tap to confirm",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }
    }
}

