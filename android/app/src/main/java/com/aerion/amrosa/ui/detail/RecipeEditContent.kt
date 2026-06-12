package com.aerion.amrosa.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aerion.amrosa.ui.edit.EditorIngredient
import com.aerion.amrosa.ui.edit.EditorSection
import com.aerion.amrosa.ui.edit.EditorStep

/**
 * Renders the recipe in INLINE EDIT MODE as items in the detail screen's LazyColumn.
 * Step/ingredient item keys match the view-mode keys (ids) so toggling edit preserves scroll.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun LazyListScope.recipeEditItems(
    draft: EditDraft,
    vm: RecipeDetailViewModel,
    onDeleteRecipe: () -> Unit,
) {
    // ── Metadata (key "header" matches view mode) ──
    item(key = "header") { EditMetadataCard(draft, vm) }

    // ── Ingredients per section ──
    item(key = "ingredients_header") {
        Text("Ingredients", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
    draft.sections.forEach { section ->
        item(key = "edit-sec-ing-${section.id}") {
            EditSectionHeader(section, vm, canDelete = draft.sections.size > 1)
        }
        items(section.ingredients, key = { it.id }) { ing ->
            EditIngredientRow(
                ingredient = ing,
                onUpdate = { vm.updateIngredient(section.id, it) },
                onDelete = { vm.deleteIngredient(section.id, ing.id) },
                onMoveUp = { vm.moveIngredientUp(section.id, ing.id) },
                onMoveDown = { vm.moveIngredientDown(section.id, ing.id) },
            )
        }
        item(key = "add-ing-${section.id}") {
            TextButton(onClick = { vm.addIngredient(section.id) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text("Add ingredient")
            }
        }
    }

    item(key = "update_conversions") {
        OutlinedButton(onClick = vm::updateConversions,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text("Update unit conversions")
        }
    }

    // ── Steps per section (keys = step ids → scroll preserved) ──
    item(key = "instructions_header") {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Text("Instructions", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
    val allIngredients = draft.sections.flatMap { it.ingredients }
    draft.sections.forEach { section ->
        if (draft.sections.size > 1 && section.name.isNotBlank()) {
            item(key = "edit-sec-step-${section.id}") {
                Text(section.name, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
        items(section.steps, key = { it.id }) { step ->
            EditStepRow(
                step = step,
                allIngredients = allIngredients,
                onUpdate = { vm.updateStep(section.id, it) },
                onDelete = { vm.deleteStep(section.id, step.id) },
                onMoveUp = { vm.moveStepUp(section.id, step.id) },
                onMoveDown = { vm.moveStepDown(section.id, step.id) },
                onToggleIngredient = { ingId -> vm.toggleStepIngredient(section.id, step.id, ingId) },
            )
        }
        item(key = "add-step-${section.id}") {
            TextButton(onClick = { vm.addStep(section.id) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text("Add step")
            }
        }
    }

    item(key = "add_section") {
        OutlinedButton(onClick = vm::addSection,
            modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add section")
        }
    }
    item(key = "delete_recipe") {
        OutlinedButton(
            onClick = onDeleteRecipe,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        ) {
            Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text("Delete recipe")
        }
    }
    item(key = "edit_bottom_spacer") { Spacer(Modifier.height(80.dp)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditMetadataCard(draft: EditDraft, vm: RecipeDetailViewModel) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(draft.title, vm::editTitle, label = { Text("Title *") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(draft.description, vm::editDescription, label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)

            // Author dropdown
            var authorExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = authorExpanded, onExpandedChange = { authorExpanded = it }) {
                OutlinedTextField(
                    value = if (draft.isPersonalAuthor) "Personal — ${vm.personalAuthorName}" else "Imported",
                    onValueChange = {}, readOnly = true, label = { Text("Author") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(authorExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(authorExpanded, { authorExpanded = false }) {
                    DropdownMenuItem(text = { Text("Imported") },
                        onClick = { vm.editIsPersonalAuthor(false); authorExpanded = false })
                    DropdownMenuItem(text = { Text("Personal — ${vm.personalAuthorName}") },
                        onClick = { vm.editIsPersonalAuthor(true); authorExpanded = false })
                }
            }

            if (draft.isVariant) {
                OutlinedTextField(draft.variantName, vm::editVariantName,
                    label = { Text("Variation name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.prepTimeMinutes, vm::editPrepTime, label = { Text("Prep (min)") },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(draft.cookTimeMinutes, vm::editCookTime, label = { Text("Cook (min)") },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Range yield (e.g. 15–20)", style = MaterialTheme.typography.bodyMedium)
                Switch(draft.isRangeYield, vm::editIsRangeYield)
            }
            if (draft.isRangeYield) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft.baseServingsMin, vm::editBaseServingsMin, label = { Text("Min") },
                        modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(draft.baseServingsMax, vm::editBaseServingsMax, label = { Text("Max") },
                        modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            } else {
                OutlinedTextField(draft.baseServings, vm::editBaseServings, label = { Text("Servings") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }

            OutlinedTextField(draft.tagsText, vm::editTags, label = { Text("Tags") },
                placeholder = { Text("e.g. Indian, weeknight") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(draft.sourceUrlsText, vm::editSourceUrls, label = { Text("Source URLs (one per line)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
        }
    }
}

@Composable
private fun EditSectionHeader(section: EditorSection, vm: RecipeDetailViewModel, canDelete: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(section.name, { vm.updateSectionName(section.id, it) },
            label = { Text("Section name") }, modifier = Modifier.weight(1f), singleLine = true)
        Column {
            IconButton(onClick = { vm.moveSectionUp(section.id) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, "Up", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { vm.moveSectionDown(section.id) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, "Down", modifier = Modifier.size(18.dp))
            }
        }
        if (canDelete) {
            IconButton(onClick = { vm.deleteSection(section.id) }) {
                Icon(Icons.Default.Delete, "Delete section", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EditIngredientRow(
    ingredient: EditorIngredient,
    onUpdate: (EditorIngredient) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var showNote by remember(ingredient.id) { mutableStateOf(ingredient.shoppingNote.isNotBlank()) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ingredient.name, { onUpdate(ingredient.copy(name = it)) },
                    label = { Text("Ingredient") }, modifier = Modifier.weight(1f), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium)
                Column {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, "Up", modifier = Modifier.size(16.dp)) }
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, "Down", modifier = Modifier.size(16.dp)) }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(ingredient.quantityDisplay, { onUpdate(ingredient.copy(quantityDisplay = it)) },
                    label = { Text("Qty") }, modifier = Modifier.weight(2f), singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall)
                OutlinedTextField(ingredient.quantityUnit, { onUpdate(ingredient.copy(quantityUnit = it)) },
                    label = { Text("Unit") }, modifier = Modifier.weight(1.5f), singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall)
                OutlinedTextField(ingredient.groupLabel, { onUpdate(ingredient.copy(groupLabel = it)) },
                    label = { Text("Group") }, modifier = Modifier.weight(2f), singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ingredient.isOptional, { onUpdate(ingredient.copy(isOptional = it)) })
                Text("Optional", style = MaterialTheme.typography.bodySmall)
            }
            if (showNote) {
                OutlinedTextField(ingredient.shoppingNote, { onUpdate(ingredient.copy(shoppingNote = it)) },
                    label = { Text("Shopping note") }, placeholder = { Text("e.g. Amul butter") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 2, textStyle = MaterialTheme.typography.bodySmall)
            } else {
                TextButton(onClick = { showNote = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text("Shopping note", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditStepRow(
    step: EditorStep,
    allIngredients: List<EditorIngredient>,
    onUpdate: (EditorStep) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleIngredient: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                OutlinedTextField(step.instruction, { onUpdate(step.copy(instruction = it)) },
                    label = { Text("Instruction") }, modifier = Modifier.weight(1f), minLines = 2, maxLines = 8,
                    textStyle = MaterialTheme.typography.bodyMedium)
                Column {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, "Up", modifier = Modifier.size(16.dp)) }
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, "Down", modifier = Modifier.size(16.dp)) }
                    IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Close, "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)) }
                }
            }
            // #4 — which ingredients this step uses
            if (allIngredients.isNotEmpty()) {
                Text("Uses ingredients:", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    allIngredients.forEach { ing ->
                        if (ing.name.isNotBlank()) {
                            FilterChip(
                                selected = ing.id in step.ingredientIds,
                                onClick = { onToggleIngredient(ing.id) },
                                label = { Text(ing.name, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }
        }
    }
}
