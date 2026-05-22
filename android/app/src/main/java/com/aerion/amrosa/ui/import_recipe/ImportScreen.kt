package com.aerion.amrosa.ui.import_recipe

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.domain.model.Recipe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onRecipeClick: (String) -> Unit,
    onEditClick: (String) -> Unit = {}
) {
    val app = LocalContext.current.applicationContext as AmrosaApplication
    val viewModel: ImportViewModel = viewModel(
        factory = ImportViewModel.factory(app.container.repository, app.container.gson, app)
    )

    val mimeTypes = arrayOf(
        "text/plain", "text/csv", "text/comma-separated-values",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel", "application/octet-stream"
    )

    // File picker — for initial file import
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFromFile(it) } }

    // File picker — for reimport (replaces existing pending record)
    val reimportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.reimportFromFile(it) } }

    val state by viewModel.uiState.collectAsState()
    var recipeToDelete by remember { mutableStateOf<Recipe?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Recipe", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── URL input ─────────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Import from URL", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            CircularProgressIndicator(modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Importing…")
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Import Recipe")
                        }
                    }
                    AnimatedVisibility(visible = state.errorMessage != null) {
                        state.errorMessage?.let { error ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                            ) {
                                Row(modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(error, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                }
            }

            // ── File import ───────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Import from File", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Excel (.xlsx), CSV, or plain text (.txt)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { fileLauncher.launch(mimeTypes) },
                        enabled = !state.isImportingFile && !state.isImporting,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (state.isImportingFile) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Parsing file…")
                        } else {
                            Icon(Icons.Default.UploadFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Choose File")
                        }
                    }
                }
            }

            // ── Google Sheets / Docs hint ─────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp).padding(top = 2.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("Google Sheets & Docs",
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Paste a Sheets or Docs URL above — it works just like a regular URL. " +
                            "Make sure sharing is set to 'Anyone with the link can view'.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Imported recipes list ─────────────────────────────────────────
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.importedRecipes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No imported recipes yet", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Paste a URL above to get started", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
                else -> {
                    val pendingCount = state.importedRecipes.count { it.needsReview }
                    Text(
                        buildString {
                            append("Imported Recipes (${state.importedRecipes.size})")
                            if (pendingCount > 0) append("  ·  $pendingCount pending review")
                        },
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
                                onClick = {
                                    if (recipe.needsReview) viewModel.openReviewForRecipe(recipe.id)
                                    else onRecipeClick(recipe.id)
                                },
                                onDelete = { recipeToDelete = recipe }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Review sheet ──────────────────────────────────────────────────────────
    state.parsedRecipe?.let { parsed ->
        RecipeReviewSheet(
            parsed = parsed,
            primaryLabel = "Confirm",
            secondaryLabel = "Reimport",
            isSecondaryLoading = state.isReimporting,
            onPrimary = viewModel::confirmRecipe,
            onSecondary = {
                if (parsed.sourceUrls.isNotEmpty()) viewModel.reimportUrl()
                else reimportFileLauncher.launch(mimeTypes)
            },
            onDismiss = viewModel::dismissReview,
            onEdit = {
                val id = state.reviewingRecipeId ?: return@RecipeReviewSheet
                viewModel.dismissReview()
                onEditClick(id)
            }
        )
    }

    // ── Delete confirmation ───────────────────────────────────────────────────
    recipeToDelete?.let { recipe ->
        AlertDialog(
            onDismissRequest = { recipeToDelete = null },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text("Delete Recipe") },
            text = { Text("Remove \"${recipe.title}\" from your imported recipes? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteImportedRecipe(recipe.id); recipeToDelete = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { recipeToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

// ── Recipe card ───────────────────────────────────────────────────────────────

@Composable
private fun ImportedRecipeCard(
    recipe: Recipe,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            if (recipe.needsReview) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFFFF3CD),
                    shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null,
                            tint = Color(0xFF856404), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Needs Review — tap to confirm",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF856404), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(recipe.title, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val timeLabel = buildString {
                        recipe.prepTimeMinutes?.let { append("Prep ${it}min") }
                        if (recipe.prepTimeMinutes != null && recipe.cookTimeMinutes != null) append("  ·  ")
                        recipe.cookTimeMinutes?.let { append("Cook ${it}min") }
                    }
                    if (timeLabel.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(timeLabel, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (recipe.tags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(recipe.tags.take(3)) { tag ->
                                SuggestionChip(onClick = {},
                                    label = { Text(tag, style = MaterialTheme.typography.labelSmall) })
                            }
                            if (recipe.tags.size > 3) {
                                item {
                                    Text("+${recipe.tags.size - 3}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                                }
                            }
                        }
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
