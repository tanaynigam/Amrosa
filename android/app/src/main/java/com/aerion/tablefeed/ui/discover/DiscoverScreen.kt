package com.aerion.tablefeed.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.tablefeed.TablefeedApplication
import com.aerion.tablefeed.domain.model.DiscoverRecipe
import com.aerion.tablefeed.domain.model.RecipeSource
import com.aerion.tablefeed.ui.components.CompactSearchField
import com.aerion.tablefeed.ui.util.compactCount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    onRecipeClick: (DiscoverRecipe) -> Unit = {},
    onProfileClick: (uid: String, name: String) -> Unit = { _, _ -> },
) {
    val app = LocalContext.current.applicationContext as TablefeedApplication
    val vm: DiscoverViewModel = viewModel(
        factory = DiscoverViewModel.factory(
            app.container.repository,
            app.container.socialRepository,
            app.container.sharedRecipeService,
            app.container.authRepository,
            app.container.userPreferences,
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
                placeholder = "Search recipes or people",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            )

            if (state.searchQuery.isNotBlank()) {
                // ── Search results (people + recipes) ───────────────────────
                val hasUsers = state.userResults.isNotEmpty()
                val hasRecipes = state.searchResults.isNotEmpty()
                when {
                    state.isSearching && !hasUsers && !hasRecipes ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    !hasUsers && !hasRecipes ->
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No matches for \"${state.searchQuery.trim()}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        if (hasUsers) {
                            item(key = "people-hdr") { SearchSectionLabel("People") }
                            items(state.userResults, key = { "u-${it.uid}" }) { u ->
                                UserResultRow(
                                    user = u,
                                    status = state.followStatuses[u.uid] ?: "none",
                                    isLoading = state.pendingFollow == u.uid,
                                    onFollow = { vm.sendFollowRequest(u) },
                                    onClick = { onProfileClick(u.uid, u.displayName) },
                                )
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            }
                        }
                        if (hasRecipes) {
                            item(key = "recipes-hdr") { SearchSectionLabel("Recipes") }
                            items(state.searchResults, key = { it.recipeId }) { r ->
                                SearchResultRow(r, onClick = { onRecipeClick(r) })
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            }
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
                    else -> PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = vm::refresh,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
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
}

@Composable
private fun SearchSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

/** A person from the People search: tap → their profile; button → send co-chef request. */
@Composable
private fun UserResultRow(
    user: com.aerion.tablefeed.domain.model.UserProfile,
    status: String,
    isLoading: Boolean,
    onFollow: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(36.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(user.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(user.displayName, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium, maxLines = 1)
            user.email?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            isLoading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            status == "accepted" -> AssistChip(onClick = onClick,
                leadingIcon = { Icon(Icons.Default.People, null, Modifier.size(16.dp)) },
                label = { Text("Co-Chef") })
            status == "pending" -> AssistChip(onClick = {}, enabled = false,
                label = { Text("Requested") })
            else -> FilledTonalButton(onClick = onFollow, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Icon(Icons.Default.PersonAdd, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Add")
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
                    Text(" ${compactCount(recipe.saveCount)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" ${compactCount(recipe.likeCount)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(" ${compactCount(recipe.saveCount)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                run {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" ${compactCount(recipe.likeCount)}", style = MaterialTheme.typography.labelSmall,
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
