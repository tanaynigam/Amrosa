package com.aerion.amrosa.ui.import_recipe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.domain.model.Recipe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onRecipeClick: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as AmrosaApplication
    val viewModel: ImportViewModel = viewModel(
        factory = ImportViewModel.factory(app.container.repository, app.container.gson)
    )
    val state by viewModel.uiState.collectAsState()

    // Delete confirmation dialog
    var recipeToDelete by remember { mutableStateOf<Recipe?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Recipe", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // URL Input Section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Import from URL",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.url,
                        onValueChange = viewModel::onUrlChange,
                        placeholder = { Text("Paste recipe URL...") },
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        enabled = !state.isImporting,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::importRecipe,
                        enabled = !state.isImporting && state.url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (state.isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Importing...")
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Import Recipe")
                        }
                    }

                    // Error message
                    AnimatedVisibility(visible = state.errorMessage != null) {
                        state.errorMessage?.let { error ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Imported recipes list
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.importedRecipes.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "No imported recipes yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Paste a URL above to get started",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                else -> {
                    Text(
                        "Imported Recipes (${state.importedRecipes.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.importedRecipes, key = { it.id }) { recipe ->
                            ImportedRecipeCard(
                                recipe = recipe,
                                onClick = { onRecipeClick(recipe.id) },
                                onDelete = { recipeToDelete = recipe }
                            )
                        }
                    }
                }
            }
        }
    }

    // Review bottom sheet for parsed recipe
    state.parsedRecipe?.let { parsed ->
        RecipeReviewSheet(
            parsed = parsed,
            onSave = viewModel::saveImportedRecipe,
            onDismiss = viewModel::dismissReview
        )
    }

    // Delete confirmation dialog
    recipeToDelete?.let { recipe ->
        AlertDialog(
            onDismissRequest = { recipeToDelete = null },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text("Delete Recipe") },
            text = { Text("Remove \"${recipe.title}\" from your imported recipes? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteImportedRecipe(recipe.id)
                        recipeToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { recipeToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ImportedRecipeCard(
    recipe: Recipe,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    recipe.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                val timeLabel = buildString {
                    recipe.prepTimeMinutes?.let { append("Prep ${it}min") }
                    if (recipe.prepTimeMinutes != null && recipe.cookTimeMinutes != null) append("  ·  ")
                    recipe.cookTimeMinutes?.let { append("Cook ${it}min") }
                }
                if (timeLabel.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        timeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (recipe.tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(recipe.tags.take(3)) { tag ->
                            SuggestionChip(
                                onClick = {},
                                label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        if (recipe.tags.size > 3) {
                            item {
                                Text(
                                    "+${recipe.tags.size - 3}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeReviewSheet(
    parsed: ParsedRecipeData,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Text(
                "Review Imported Recipe",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
                parsed.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Description
            parsed.description?.let { desc ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Meta info row
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                parsed.prepTimeMinutes?.let { prep ->
                    InfoChip("Prep: ${prep}min")
                }
                parsed.cookTimeMinutes?.let { cook ->
                    InfoChip("Cook: ${cook}min")
                }
                InfoChip("Serves: ${parsed.baseServings}")
            }

            // Tags
            if (parsed.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(parsed.tags) { tag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // Ingredients
            Text(
                "Ingredients (${parsed.ingredients.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Group by section
            val ingredientsBySection = parsed.ingredients.groupBy { it.sectionId }
            val sectionMap = parsed.sections.associateBy { it.id }

            ingredientsBySection.forEach { (sectionId, ingredients) ->
                if (sectionId != null) {
                    sectionMap[sectionId]?.let { section ->
                        Text(
                            section.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )
                    }
                }
                ingredients.forEach { ing ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(
                            "•",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            buildString {
                                ing.quantityDisplay?.let { append("$it ") }
                                append(ing.name)
                                if (ing.isOptional) append(" (optional)")
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // Steps
            Text(
                "Steps (${parsed.steps.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            val stepsBySection = parsed.steps.groupBy { it.sectionId }

            stepsBySection.forEach { (sectionId, steps) ->
                if (sectionId != null) {
                    sectionMap[sectionId]?.let { section ->
                        Text(
                            section.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )
                    }
                }
                steps.forEachIndexed { index, step ->
                    Row(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .width(28.dp)
                                .padding(end = 4.dp)
                        )
                        Text(
                            step.instruction,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Discard")
                }
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Recipe")
                }
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
