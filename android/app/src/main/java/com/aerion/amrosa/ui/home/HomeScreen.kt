package com.aerion.amrosa.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.domain.model.Recipe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onRecipeClick: (String) -> Unit, onSettingsClick: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as AmrosaApplication
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(app.container.repository))
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Amrita & Ambrosia", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                placeholder = { Text("Search recipes...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (state.categories.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    item {
                        FilterChip(
                            selected = state.selectedCategory == null,
                            onClick = { viewModel.onCategorySelect(null) },
                            label = { Text("All") }
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

            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.filteredRecipes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No recipes found", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.filteredRecipes, key = { it.id }) { recipe ->
                        RecipeCard(recipe = recipe, onClick = { onRecipeClick(recipe.id) })
                    }
                }
            }
        }
    }
}

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
                        SuggestionChip(onClick = {}, label = {
                            Text(tag, style = MaterialTheme.typography.labelSmall)
                        })
                    }
                }
            }
        }
    }
}
