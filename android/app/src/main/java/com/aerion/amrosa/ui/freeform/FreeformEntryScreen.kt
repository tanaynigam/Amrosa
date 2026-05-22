package com.aerion.amrosa.ui.freeform

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.ui.import_recipe.RecipeReviewSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeformEntryScreen(
    onBack: () -> Unit,
    onEditClick: (String) -> Unit = {}
) {
    val app = LocalContext.current.applicationContext as AmrosaApplication
    val viewModel: FreeformViewModel = viewModel(
        factory = FreeformViewModel.factory(app.container.repository, app.container.gson, app.container.authRepository)
    )
    val state by viewModel.uiState.collectAsState()

    // Navigate back when the recipe is saved
    LaunchedEffect(state.savedRecipeId) {
        if (state.savedRecipeId != null) onBack()
    }

    // Navigate to editor when save-and-edit is triggered
    LaunchedEffect(state.editRecipeId) {
        state.editRecipeId?.let { onEditClick(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Recipe", style = MaterialTheme.typography.titleLarge) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── Hint card ─────────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp).padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "Type or paste your recipe — anything goes. " +
                        "Prose, bullet points, partial notes, or a quick brain-dump. " +
                        "Gemini will structure it for you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Free-text input ───────────────────────────────────────────────
            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::onTextChange,
                placeholder = {
                    Text(
                        "e.g. Grandma's dal — 1 cup masoor dal, boil with turmeric and salt. " +
                        "Fry jeera and garlic in ghee, add tomato and cook down. Mix. Serves 4.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !state.isFormatting,
                minLines = 8
            )

            // Character count
            Text(
                "${state.text.length} characters",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Error ─────────────────────────────────────────────────────────
            AnimatedVisibility(visible = state.errorMessage != null) {
                state.errorMessage?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
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

            // ── Format button ─────────────────────────────────────────────────
            Button(
                onClick = viewModel::formatWithGemini,
                enabled = !state.isFormatting && state.text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (state.isFormatting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Formatting with Gemini…")
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Format with Gemini", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ── Review sheet ──────────────────────────────────────────────────────────
    state.parsedRecipe?.let { parsed ->
        RecipeReviewSheet(
            parsed = parsed,
            primaryLabel = "Save Recipe",
            secondaryLabel = "Reformat",
            onPrimary = viewModel::saveRecipe,
            onSecondary = viewModel::reformat,
            onDismiss = viewModel::dismissReview,
            onEdit = viewModel::saveAndEdit
        )
    }
}
