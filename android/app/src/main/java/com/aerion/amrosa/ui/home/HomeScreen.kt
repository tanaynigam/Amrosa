package com.aerion.amrosa.ui.home

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
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.domain.model.Recipe
import com.aerion.amrosa.domain.model.ReceivedRecipeSummary
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
    onSharedRecipeClick: (shareId: String) -> Unit = {},
    filter: RecipeFilter = RecipeFilter.ALL
) {
    val app = LocalContext.current.applicationContext as AmrosaApplication
    val viewModel: HomeViewModel = viewModel(
        key = "home-${filter.name}",
        factory = HomeViewModel.factory(
            repository = app.container.repository,
            socialRepository = app.container.socialRepository,
            filter = filter
        )
    )
    val state by viewModel.uiState.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }

    val isSharedMode = state.yourRecipesFilter == YourRecipesFilter.SHARED

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (filter) {
                            RecipeFilter.YOURS -> "My Recipes"
                            RecipeFilter.PERSONAL -> "My Recipes"
                            else -> "Amrita & Ambrosia"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    if (filter == RecipeFilter.ALL) {
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (!isSharedMode && (filter == RecipeFilter.YOURS || filter == RecipeFilter.PERSONAL)) {
                ExtendedFloatingActionButton(
                    text = { Text("Add Recipe") },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = { showAddSheet = true }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── Compact search bar (stays sticky) ─────────────────────────────
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                placeholder = { Text(if (isSharedMode) "Search shared recipes…" else "Search recipes…") },
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

            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

            // ── Content ───────────────────────────────────────────────────────
            when {
                state.isLoading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                isSharedMode && state.isSharedLoading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                else -> {
                    val showEmpty = if (isSharedMode)
                        state.filteredSharedRecipes.isEmpty()
                    else
                        state.filteredRecipes.isEmpty()

                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp,
                            top = 8.dp,
                            bottom = if (!isSharedMode && (filter == RecipeFilter.YOURS || filter == RecipeFilter.PERSONAL)) 88.dp else 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // ── Combined filter chips (scroll with content) ─────────
                        if (filter == RecipeFilter.YOURS) {
                            item(key = "chips") {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    // Author / source filter chips
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
                                    item {
                                        FilterChip(
                                            selected = state.yourRecipesFilter == YourRecipesFilter.SHARED,
                                            onClick = { viewModel.onYourRecipesFilterChange(YourRecipesFilter.SHARED) },
                                            label = { Text("Shared") }
                                        )
                                    }

                                    // Thin divider + category chips (only in non-Shared mode)
                                    if (!isSharedMode && state.categories.isNotEmpty()) {
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

                        // ── Empty state ─────────────────────────────────────────
                        if (showEmpty) {
                            item(key = "empty") {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (isSharedMode) "No shared recipes yet"
                                        else "No recipes found",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else if (isSharedMode) {
                            // ── Shared recipe cards ─────────────────────────────
                            items(state.filteredSharedRecipes, key = { it.shareId }) { summary ->
                                SharedInboxCard(
                                    summary = summary,
                                    onClick = { onSharedRecipeClick(summary.shareId) }
                                )
                            }
                        } else {
                            // ── My Recipes cards ────────────────────────────────
                            items(state.filteredRecipes, key = { it.id }) { recipe ->
                                RecipeCard(
                                    recipe = recipe,
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
private fun RecipeCard(recipe: Recipe, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(recipe.title, style = MaterialTheme.typography.titleLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
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

            val showAuthor = !recipe.authorDisplayName.isNullOrBlank()
            val isShared = recipe.visibility == "public"
            if (showAuthor || isShared) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showAuthor) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(recipe.authorDisplayName!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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

// ── Shared inbox card (same visual as SharedInboxScreen) ─────────────────────

@Composable
private fun SharedInboxCard(summary: ReceivedRecipeSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(summary.title, style = MaterialTheme.typography.titleLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(6.dp))

            val timeLabel = buildString {
                summary.prepTimeMinutes?.let { append("Prep ${it}min") }
                if (summary.prepTimeMinutes != null && summary.cookTimeMinutes != null) append("  ·  ")
                summary.cookTimeMinutes?.let { append("Cook ${it}min") }
            }
            if (timeLabel.isNotBlank()) {
                Text(timeLabel, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (summary.tags.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(summary.tags) { tag ->
                        SuggestionChip(onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.Person, contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(summary.authorDisplayName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (summary.fromDisplayName != summary.authorDisplayName) {
                    Text("· from ${summary.fromDisplayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
                Spacer(Modifier.weight(1f))
                Text(formatRelative(summary.sharedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}

private fun formatRelative(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000      -> "just now"
        diff < 3_600_000   -> "${diff / 60_000}m ago"
        diff < 86_400_000  -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> "${diff / 86_400_000}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
    }
}
