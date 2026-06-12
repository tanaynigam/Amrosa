package com.aerion.amrosa.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aerion.amrosa.ui.edit.EditorIngredient
import com.aerion.amrosa.ui.edit.EditorSection
import com.aerion.amrosa.ui.edit.EditorStep

/**
 * Renders the recipe in INLINE EDIT MODE as items in the detail screen's LazyColumn.
 *
 * The goal is that edit mode looks like the *same page* as the read-only view — same
 * typography, headers, bullets and step badges — with the text simply becoming editable
 * in place (an underline signals editability). No cards, no boxed form fields. Item keys
 * ("header", "section-{id}", step ids, ingredient ids) match the view so toggling edit
 * preserves scroll position.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun LazyListScope.recipeEditItems(
    draft: EditDraft,
    vm: RecipeDetailViewModel,
    onDeleteRecipe: () -> Unit,
) {
    val multiSection = draft.sections.size > 1

    // ── Header (key "header" matches view mode) ──
    item(key = "header") { EditHeader(draft, vm) }

    // ── Time + yield row ──
    item(key = "edit_meta") { EditMetaRow(draft, vm) }

    // ── Sources ──
    item(key = "edit_sources") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Sources", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(vertical = 8.dp))
            InlineField(
                value = draft.sourceUrlsText, onValueChange = vm::editSourceUrls,
                textStyle = MaterialTheme.typography.labelMedium,
                placeholder = "One URL per line", singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }

    // ── Ingredients ──
    item(key = "ingredients_header") {
        Text("Ingredients", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
    draft.sections.forEach { section ->
        if (multiSection) {
            item(key = "edit-sec-ing-${section.id}") {
                EditSectionTitle(section, vm, style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary,
                    canDelete = true)
            }
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
            AddInline("Add ingredient") { vm.addIngredient(section.id) }
        }
    }

    item(key = "update_conversions") {
        TextButton(onClick = vm::updateConversions,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
            Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
            Text("Update unit conversions", style = MaterialTheme.typography.labelMedium)
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }

    // ── Instructions ──
    item(key = "instructions_header") {
        Text("Instructions", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
    val allIngredients = draft.sections.flatMap { it.ingredients }
    var stepNumber = 0
    draft.sections.forEach { section ->
        item(key = "section-${section.id}") {
            EditSectionTitle(section, vm, style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface, canDelete = multiSection)
        }
        section.steps.forEach { step ->
            stepNumber++
            val number = stepNumber
            item(key = step.id) {
                EditStepRow(
                    step = step, stepNumber = number, allIngredients = allIngredients,
                    onUpdate = { vm.updateStep(section.id, it) },
                    onDelete = { vm.deleteStep(section.id, step.id) },
                    onMoveUp = { vm.moveStepUp(section.id, step.id) },
                    onMoveDown = { vm.moveStepDown(section.id, step.id) },
                    onToggleIngredient = { ingId -> vm.toggleStepIngredient(section.id, step.id, ingId) },
                )
            }
        }
        item(key = "add-step-${section.id}") {
            AddInline("Add step") { vm.addStep(section.id) }
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

// ─────────────────────────────────────────────────────────────────────────────
//  Inline building blocks — mirror the read-only page, text becomes editable.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EditHeader(draft: EditDraft, vm: RecipeDetailViewModel) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        InlineField(
            value = draft.title, onValueChange = vm::editTitle,
            textStyle = MaterialTheme.typography.headlineLarge,
            placeholder = "Recipe title", modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        InlineField(
            value = draft.description, onValueChange = vm::editDescription,
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = "Description", singleLine = false, modifier = Modifier.fillMaxWidth(),
        )

        // Author + (optional) variation name — compact, inline.
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !draft.isPersonalAuthor,
                onClick = { vm.editIsPersonalAuthor(false) },
                label = { Text("Imported", style = MaterialTheme.typography.labelMedium) },
            )
            FilterChip(
                selected = draft.isPersonalAuthor,
                onClick = { vm.editIsPersonalAuthor(true) },
                label = { Text(vm.personalAuthorName, style = MaterialTheme.typography.labelMedium) },
            )
        }
        if (draft.isVariant) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Variation: ", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                InlineField(
                    value = draft.variantName, onValueChange = vm::editVariantName,
                    textStyle = MaterialTheme.typography.labelMedium,
                    placeholder = "name", modifier = Modifier.widthIn(min = 80.dp),
                )
            }
        }
    }
}

@Composable
private fun EditMetaRow(draft: EditDraft, vm: RecipeDetailViewModel) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Prep / cook
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LabeledInline("Prep", draft.prepTimeMinutes, vm::editPrepTime, "min")
                LabeledInline("Cook", draft.cookTimeMinutes, vm::editCookTime, "min")
            }
            // Yield
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Range", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Switch(
                        checked = draft.isRangeYield, onCheckedChange = vm::editIsRangeYield,
                        modifier = Modifier.size(36.dp)
                    )
                }
                if (draft.isRangeYield) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Yield ", style = MaterialTheme.typography.labelMedium)
                        InlineNumber(draft.baseServingsMin, vm::editBaseServingsMin, "min")
                        Text(" – ", style = MaterialTheme.typography.titleMedium)
                        InlineNumber(draft.baseServingsMax, vm::editBaseServingsMax, "max")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Yield ", style = MaterialTheme.typography.labelMedium)
                        InlineNumber(draft.baseServings, vm::editBaseServings, "0")
                    }
                }
            }
        }
        // Tags
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Label, null, modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            InlineField(
                value = draft.tagsText, onValueChange = vm::editTags,
                textStyle = MaterialTheme.typography.labelMedium,
                placeholder = "Tags (comma separated)", modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** Editable section title + reorder/delete, styled to match the given header style. */
@Composable
private fun EditSectionTitle(
    section: EditorSection,
    vm: RecipeDetailViewModel,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
    canDelete: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InlineField(
            value = section.name, onValueChange = { vm.updateSectionName(section.id, it) },
            textStyle = style, color = color, placeholder = "Section",
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { vm.moveSectionUp(section.id) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.KeyboardArrowUp, "Move up", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { vm.moveSectionDown(section.id) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, "Move down", modifier = Modifier.size(18.dp))
        }
        if (canDelete) {
            IconButton(onClick = { vm.deleteSection(section.id) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "Delete section",
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Ingredient as an editable bullet line — mirrors "• name — qty unit". */
@Composable
private fun EditIngredientRow(
    ingredient: EditorIngredient,
    onUpdate: (EditorIngredient) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var showExtra by remember(ingredient.id) {
        mutableStateOf(ingredient.groupLabel.isNotBlank() || ingredient.shoppingNote.isNotBlank())
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("•", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(end = 10.dp))
            InlineField(
                value = ingredient.name, onValueChange = { onUpdate(ingredient.copy(name = it)) },
                textStyle = MaterialTheme.typography.bodyMedium, placeholder = "ingredient",
                modifier = Modifier.weight(1f),
            )
            Text("  —  ", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            InlineField(
                value = ingredient.quantityDisplay, onValueChange = { onUpdate(ingredient.copy(quantityDisplay = it)) },
                textStyle = MaterialTheme.typography.bodyMedium, placeholder = "qty",
                modifier = Modifier.widthIn(min = 40.dp, max = 96.dp),
            )
            IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, "Move up", modifier = Modifier.size(15.dp)) }
            IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, "Move down", modifier = Modifier.size(15.dp)) }
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, "Delete", tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(15.dp)) }
        }
        // Secondary controls (unit, group, optional, shopping note) — tucked under, opt-in.
        Row(
            Modifier.padding(start = 20.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("unit ", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                InlineField(
                    value = ingredient.quantityUnit, onValueChange = { onUpdate(ingredient.copy(quantityUnit = it)) },
                    textStyle = MaterialTheme.typography.labelMedium, placeholder = "—",
                    modifier = Modifier.widthIn(min = 32.dp, max = 72.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ingredient.isOptional, { onUpdate(ingredient.copy(isOptional = it)) },
                    modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(4.dp))
                Text("optional", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!showExtra) {
                TextButton(onClick = { showExtra = true }, contentPadding = PaddingValues(0.dp)) {
                    Text("+ group / note", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (showExtra) {
            Row(
                Modifier.padding(start = 20.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("group ", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InlineField(
                        value = ingredient.groupLabel, onValueChange = { onUpdate(ingredient.copy(groupLabel = it)) },
                        textStyle = MaterialTheme.typography.labelMedium, placeholder = "—",
                        modifier = Modifier.widthIn(min = 48.dp, max = 120.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("note ", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InlineField(
                        value = ingredient.shoppingNote, onValueChange = { onUpdate(ingredient.copy(shoppingNote = it)) },
                        textStyle = MaterialTheme.typography.labelMedium, placeholder = "e.g. Amul",
                        modifier = Modifier.widthIn(min = 48.dp, max = 140.dp),
                    )
                }
            }
        }
    }
}

/** Step as an editable numbered line — mirrors the view's badge + instruction + ref chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditStepRow(
    step: EditorStep,
    stepNumber: Int,
    allIngredients: List<EditorIngredient>,
    onUpdate: (EditorStep) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleIngredient: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.size(28.dp).then(
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer,
                        androidx.compose.foundation.shape.RoundedCornerShape(50))
                ),
                contentAlignment = Alignment.Center
            ) {
                Text("$stepNumber",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            InlineField(
                value = step.instruction, onValueChange = { onUpdate(step.copy(instruction = it)) },
                textStyle = MaterialTheme.typography.bodyLarge, placeholder = "Step instruction",
                singleLine = false, modifier = Modifier.weight(1f),
            )
            Column {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, "Move up", modifier = Modifier.size(15.dp)) }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, "Move down", modifier = Modifier.size(15.dp)) }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "Delete", tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp)) }
            }
        }
        // #4 — which ingredients this step uses (shown indented under the instruction).
        val named = allIngredients.filter { it.name.isNotBlank() }
        if (named.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(Modifier.padding(start = 40.dp)) {
                Text("Uses:", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    named.forEach { ing ->
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

// ── Small reusable inline editors ────────────────────────────────────────────

/** A prep/cook style "Label [value] suffix" inline row. */
@Composable
private fun LabeledInline(label: String, value: String, onChange: (String) -> Unit, suffix: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label  ", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        InlineField(value, onChange, MaterialTheme.typography.labelMedium, placeholder = "—",
            modifier = Modifier.widthIn(min = 28.dp, max = 56.dp), keyboardType = KeyboardType.Number)
        Text(suffix, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InlineNumber(value: String, onChange: (String) -> Unit, placeholder: String) {
    InlineField(value, onChange, MaterialTheme.typography.titleMedium, placeholder = placeholder,
        modifier = Modifier.widthIn(min = 28.dp, max = 56.dp), keyboardType = KeyboardType.Number)
}

@Composable
private fun AddInline(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(start = 8.dp)) {
        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Text that edits in place: styled exactly like the surrounding page text, with a faint
 * underline as the only affordance that it's editable. The page layout is unchanged.
 */
@Composable
private fun InlineField(
    value: String,
    onValueChange: (String) -> Unit,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    placeholder: String = "",
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val lineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = textStyle.copy(color = color),
        singleLine = singleLine,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier
            .drawBehind {
                val y = size.height - 1.dp.toPx()
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            .padding(bottom = 3.dp),
        decorationBox = { inner ->
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, style = textStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
            inner()
        },
    )
}
