package com.aerion.amrosa.ui.edit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.amrosa.AmrosaApplication

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditorScreen(
    recipeId: String,
    onBack: () -> Unit
) {
    val app = LocalContext.current.applicationContext as AmrosaApplication
    val vm: RecipeEditorViewModel = viewModel(
        key = "editor-$recipeId",
        factory = RecipeEditorViewModel.factory(
            app.container.repository,
            app.container.syncService,
            app.container.authRepository,
            recipeId,
            app.container.gson
        )
    )
    val state by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Navigate back once save completes
    LaunchedEffect(state.saveComplete) {
        if (state.saveComplete) onBack()
    }

    // Show errors as snackbar
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearError()
        }
    }

    // Fork confirmation dialog
    if (state.showForkDialog) {
        ForkConfirmationDialog(
            onConfirm = vm::onForkConfirmed,
            onDismiss = vm::onForkDismissed   // triggers saveComplete → navigates back
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Edit Recipe") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(36.dp)
                                .padding(end = 12.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(
                            onClick = vm::save,
                            enabled = !state.isLoading
                        ) {
                            Text("Save", style = MaterialTheme.typography.labelLarge)
                        }
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

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // ── Metadata ──────────────────────────────────────────────────────
            item(key = "metadata") {
                MetadataCard(state = state, vm = vm)
            }

            // ── Sections ──────────────────────────────────────────────────────
            items(state.sections, key = { it.id }) { section ->
                SectionCard(
                    section = section,
                    canDelete = state.sections.size > 1,
                    onNameChange = { vm.updateSectionName(section.id, it) },
                    onDelete = { vm.deleteSection(section.id) },
                    onMoveUp = { vm.moveSectionUp(section.id) },
                    onMoveDown = { vm.moveSectionDown(section.id) },
                    onAddIngredient = { vm.addIngredient(section.id) },
                    onUpdateIngredient = { vm.updateIngredient(section.id, it) },
                    onDeleteIngredient = { vm.deleteIngredient(section.id, it) },
                    onMoveIngredientUp = { vm.moveIngredientUp(section.id, it) },
                    onMoveIngredientDown = { vm.moveIngredientDown(section.id, it) },
                    onAddStep = { vm.addStep(section.id) },
                    onUpdateStep = { vm.updateStep(section.id, it) },
                    onDeleteStep = { vm.deleteStep(section.id, it) },
                    onMoveStepUp = { vm.moveStepUp(section.id, it) },
                    onMoveStepDown = { vm.moveStepDown(section.id, it) }
                )
            }

            // ── Add Section ───────────────────────────────────────────────────
            item(key = "add_section") {
                OutlinedButton(
                    onClick = vm::addSection,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Section")
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(80.dp)) }
        }
    }
}

// ─── Metadata Card ────────────────────────────────────────────────────────────

@Composable
private fun MetadataCard(state: EditorUiState, vm: RecipeEditorViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Recipe Details", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = state.title,
                onValueChange = vm::updateTitle,
                label = { Text("Title *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = state.description,
                onValueChange = vm::updateDescription,
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            OutlinedTextField(
                value = state.authorDisplayName,
                onValueChange = vm::updateAuthorDisplayName,
                label = { Text("Author") },
                placeholder = { Text("Your name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Person, contentDescription = null,
                        modifier = Modifier.size(20.dp))
                }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.prepTimeMinutes,
                    onValueChange = vm::updatePrepTime,
                    label = { Text("Prep (min)") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.cookTimeMinutes,
                    onValueChange = vm::updateCookTime,
                    label = { Text("Cook (min)") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            // Yield
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Range yield (e.g. 15–20 cookies)", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = state.isRangeYield, onCheckedChange = vm::updateIsRangeYield)
            }

            if (state.isRangeYield) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.baseServingsMin,
                        onValueChange = vm::updateBaseServingsMin,
                        label = { Text("Min yield") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.baseServingsMax,
                        onValueChange = vm::updateBaseServingsMax,
                        label = { Text("Max yield") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            } else {
                OutlinedTextField(
                    value = state.baseServings,
                    onValueChange = vm::updateBaseServings,
                    label = { Text("Servings") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = state.tagsText,
                onValueChange = vm::updateTags,
                label = { Text("Tags") },
                placeholder = { Text("e.g. Indian, weeknight, pasta") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = state.sourceUrlsText,
                onValueChange = vm::updateSourceUrls,
                label = { Text("Source URLs (one per line)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
        }
    }
}

// ─── Section Card ─────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(
    section: EditorSection,
    canDelete: Boolean,
    onNameChange: (String) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onAddIngredient: () -> Unit,
    onUpdateIngredient: (EditorIngredient) -> Unit,
    onDeleteIngredient: (String) -> Unit,
    onMoveIngredientUp: (String) -> Unit,
    onMoveIngredientDown: (String) -> Unit,
    onAddStep: () -> Unit,
    onUpdateStep: (EditorStep) -> Unit,
    onDeleteStep: (String) -> Unit,
    onMoveStepUp: (String) -> Unit,
    onMoveStepDown: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Section header row
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = section.name,
                    onValueChange = onNameChange,
                    label = { Text("Section name") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.width(4.dp))
                Column {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, "Move section up",
                            modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, "Move section down",
                            modifier = Modifier.size(20.dp))
                    }
                }
                if (canDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, "Delete section",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            HorizontalDivider()

            // ── Ingredients ───────────────────────────────────────────────────
            Text(
                "Ingredients",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            if (section.ingredients.isEmpty()) {
                Text(
                    "No ingredients yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            section.ingredients.forEachIndexed { idx, ingredient ->
                IngredientRow(
                    ingredient = ingredient,
                    canMoveUp = idx > 0,
                    canMoveDown = idx < section.ingredients.lastIndex,
                    onUpdate = onUpdateIngredient,
                    onDelete = { onDeleteIngredient(ingredient.id) },
                    onMoveUp = { onMoveIngredientUp(ingredient.id) },
                    onMoveDown = { onMoveIngredientDown(ingredient.id) }
                )
            }

            TextButton(
                onClick = onAddIngredient,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Ingredient")
            }

            HorizontalDivider()

            // ── Steps ─────────────────────────────────────────────────────────
            Text(
                "Steps",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            if (section.steps.isEmpty()) {
                Text(
                    "No steps yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            section.steps.forEachIndexed { idx, step ->
                StepRow(
                    step = step,
                    stepNumber = idx + 1,
                    canMoveUp = idx > 0,
                    canMoveDown = idx < section.steps.lastIndex,
                    onUpdate = onUpdateStep,
                    onDelete = { onDeleteStep(step.id) },
                    onMoveUp = { onMoveStepUp(step.id) },
                    onMoveDown = { onMoveStepDown(step.id) }
                )
            }

            TextButton(
                onClick = onAddStep,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Step")
            }
        }
    }
}

// ─── Ingredient Row ───────────────────────────────────────────────────────────

@Composable
private fun IngredientRow(
    ingredient: EditorIngredient,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUpdate: (EditorIngredient) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row 1: Name + reorder/delete controls
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ingredient.name,
                    onValueChange = { onUpdate(ingredient.copy(name = it)) },
                    label = { Text("Ingredient name") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, "Move up",
                            modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, "Move down",
                            modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Close, "Delete ingredient",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Row 2: Quantity · Unit · Group label
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = ingredient.quantityDisplay,
                    onValueChange = { onUpdate(ingredient.copy(quantityDisplay = it)) },
                    label = { Text("Qty") },
                    placeholder = { Text("e.g. 2¼") },
                    modifier = Modifier.weight(2f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = ingredient.quantityUnit,
                    onValueChange = { onUpdate(ingredient.copy(quantityUnit = it)) },
                    label = { Text("Unit") },
                    placeholder = { Text("cup") },
                    modifier = Modifier.weight(1.5f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = ingredient.groupLabel,
                    onValueChange = { onUpdate(ingredient.copy(groupLabel = it)) },
                    label = { Text("Group") },
                    placeholder = { Text("Dry") },
                    modifier = Modifier.weight(2f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }

            // Row 3: Optional toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = ingredient.isOptional,
                    onCheckedChange = { onUpdate(ingredient.copy(isOptional = it)) }
                )
                Text("Optional ingredient", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ─── Step Row ─────────────────────────────────────────────────────────────────

@Composable
private fun StepRow(
    step: EditorStep,
    stepNumber: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUpdate: (EditorStep) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "$stepNumber.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 18.dp, end = 8.dp, start = 4.dp)
            )
            OutlinedTextField(
                value = step.instruction,
                onValueChange = { onUpdate(step.copy(instruction = it)) },
                label = { Text("Instruction") },
                modifier = Modifier.weight(1f),
                minLines = 2,
                maxLines = 8,
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, "Move up",
                        modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, "Move down",
                        modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Close, "Delete step",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ─── Fork Confirmation Dialog ─────────────────────────────────────────────────

@Composable
private fun ForkConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Edit, contentDescription = null) },
        title = { Text("Edit Official Recipe?") },
        text = {
            Text(
                "This recipe is synced from the cloud. Editing it creates a personal copy — " +
                "your version won't be overwritten by future syncs. This can't be undone."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Edit Anyway") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
