package com.aerion.amrosa.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.domain.model.DiscoverRecipe
import com.aerion.amrosa.domain.model.RecipeSource
import com.aerion.amrosa.ui.components.CompactSearchField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    onRecipeClick: (DiscoverRecipe) -> Unit = {}
) {
    val app = LocalContext.current.applicationContext as AmrosaApplication
    val vm: DiscoverViewModel = viewModel(
        factory = DiscoverViewModel.factory(
            app.container.repository,
            app.container.socialRepository,
            app.container.sharedRecipeService,
            app.container.authRepository,
        )
    )
    val state by vm.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discover") },
                actions = {
                    if (state.surprisePool.isNotEmpty()) {
                        TextButton(onClick = { state.surprisePool.random().let(onRecipeClick) }) {
                            Icon(Icons.Default.Casino, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Surprise me")
                        }
                    }
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── Pinned search field ─────────────────────────────────────────
            CompactSearchField(
                value = state.searchQuery,
                onValueChange = vm::onSearchChange,
                placeholder = "Search recipes — yours, co-chefs, public",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            )

            if (state.searchQuery.isNotBlank()) {
                // ── Search results ──────────────────────────────────────────
                when {
                    state.isSearching && state.searchResults.isEmpty() ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    state.searchResults.isEmpty() ->
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No recipes match \"${state.searchQuery.trim()}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.searchResults, key = { it.recipeId }) { r ->
                            SearchResultRow(r, onClick = { onRecipeClick(r) })
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            } else {
                // ── Recommendation shelves ──────────────────────────────────
                when {
                    state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.shelves.isEmpty() -> EmptyState()
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        items(state.shelves, key = { it.title }) { shelf ->
                            Column {
                                Text(
                                    shelf.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(shelf.recipes, key = { it.recipeId }) { r ->
                                        DiscoverCard(r, onClick = { onRecipeClick(r) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(recipe: DiscoverRecipe, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(recipe.title, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium, maxLines = 1)
            val (icon, label) = when (recipe.source) {
                RecipeSource.OWN -> Icons.Default.Bookmarks to "Yours"
                RecipeSource.FRIEND -> Icons.Default.People to (recipe.authorName ?: "Co-chef")
                RecipeSource.PUBLIC -> Icons.Default.Public to (recipe.authorName ?: "Community")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (recipe.saveCount > 0) {
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" ${recipe.saveCount}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DiscoverCard(recipe: DiscoverRecipe, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(200.dp).clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(recipe.title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, maxLines = 2)
            val time = listOfNotNull(
                recipe.prepTimeMinutes?.let { "${it}m prep" },
                recipe.cookTimeMinutes?.let { "${it}m cook" },
            ).joinToString(" · ")
            if (time.isNotBlank()) {
                Text(time, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (recipe.tags.isNotEmpty()) {
                Text(recipe.tags.take(3).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            // Source label
            val (icon, label) = when (recipe.source) {
                RecipeSource.OWN -> Icons.Default.Bookmarks to "Yours"
                RecipeSource.FRIEND -> Icons.Default.People to (recipe.authorName ?: "Co-chef")
                RecipeSource.PUBLIC -> Icons.Default.Public to (recipe.authorName ?: "Community")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                // Popularity badges (public recipes)
                if (recipe.saveCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" ${recipe.saveCount}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (recipe.likeCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" ${recipe.likeCount}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null,
                modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Nothing to discover yet", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text("Add your own recipes, follow some co-chefs, or check back when there are public recipes to explore.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
