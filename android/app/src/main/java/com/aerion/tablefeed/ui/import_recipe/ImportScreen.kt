package com.aerion.tablefeed.ui.import_recipe

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.aerion.tablefeed.TablefeedApplication
import com.aerion.tablefeed.domain.model.Recipe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onBack: () -> Unit = {},
    onRecipeClick: (String) -> Unit = {},
    onEditClick: (String) -> Unit = {},
    /** When non-null, auto-opens the review sheet for this recipeId on entry. */
    reviewRecipeId: String? = null
) {
    val app = LocalContext.current.applicationContext as TablefeedApplication
    val viewModel: ImportViewModel = viewModel(
        factory = ImportViewModel.factory(app.container.repository, app.container.gson, app, app.container.authRepository)
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

    // Auto-open review sheet when arriving from a "needs review" card tap
    LaunchedEffect(reviewRecipeId) {
        if (reviewRecipeId != null) viewModel.openReviewForRecipe(reviewRecipeId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Recipe", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
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

            Spacer(modifier = Modifier.height(8.dp))
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
            },
            isOwnRecipe = state.isOwnRecipe,
            onIsOwnRecipeChange = viewModel::setIsOwnRecipe
        )
    }

}
