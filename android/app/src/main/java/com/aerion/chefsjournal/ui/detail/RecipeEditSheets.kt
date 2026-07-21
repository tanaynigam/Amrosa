package com.aerion.chefsjournal.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aerion.chefsjournal.ui.edit.EditorIngredient
import com.aerion.chefsjournal.ui.edit.EditorSection
import com.aerion.chefsjournal.ui.edit.EditorStep

/** What the open edit bottom sheet is editing. `id == null` means "add new". */
sealed interface EditTarget {
    data object Details : EditTarget
    data class Section(val sectionId: String?) : EditTarget
    data class Ingredient(val sectionId: String, val ingredientId: String?) : EditTarget
    data class Step(val sectionId: String, val stepId: String?) : EditTarget
}

private fun plain(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

/** Build the human-readable quantity display from the numeric amount + unit (+ optional range). */
private fun deriveDisplay(value: Double?, max: Double?, unit: String, fallback: String): String {
    if (value == null) return fallback.trim()
    val num = if (max != null && max > value) "${plain(value)}–${plain(max)}" else plain(value)
    return "$num ${unit.trim()}".trim()
}

/**
 * The single host for all edit bottom sheets. The detail screen renders this when [target] is
 * non-null. Existing items are updated live (the row behind the sheet reflects edits); "new"
 * items (id == null) are committed by the sheet's Add button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBottomSheet(
    target: EditTarget,
    draft: EditDraft,
    vm: RecipeDetailViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (target) {
                is EditTarget.Details -> DetailsSheet(draft, vm)
                is EditTarget.Section -> SectionSheet(target, draft, vm, onDismiss)
                is EditTarget.Ingredient -> IngredientSheet(target, draft, vm, onDismiss)
                is EditTarget.Step -> StepSheet(target, draft, vm, onDismiss)
            }
        }
    }
}

// ── Recipe details (title / description / author / times / yield / tags / sources) ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(draft: EditDraft, vm: RecipeDetailViewModel) {
    SheetTitle("Recipe details")
    OutlinedTextField(draft.title, vm::editTitle, label = { Text("Title *") },
        modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(draft.description, vm::editDescription, label = { Text("Description") },
        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)

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
        OutlinedTextField(draft.variantName, vm::editVariantName, label = { Text("Variation name") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
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
        modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4)
}

// ── Section ──
@Composable
private fun SectionSheet(target: EditTarget.Section, draft: EditDraft, vm: RecipeDetailViewModel, onDismiss: () -> Unit) {
    val existing = draft.sections.find { it.id == target.sectionId }
    var name by remember(target) { mutableStateOf(existing?.name ?: "") }
    SheetTitle(if (existing == null) "Add section" else "Edit section")
    OutlinedTextField(
        value = name,
        onValueChange = { name = it; if (existing != null) vm.updateSectionName(existing.id, it) },
        label = { Text("Section name") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
    )
    if (existing == null) {
        Button(onClick = { vm.addSection(name); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add section")
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.moveSectionUp(existing.id) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.KeyboardArrowUp, null); Text("Up")
            }
            OutlinedButton(onClick = { vm.moveSectionDown(existing.id) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.KeyboardArrowDown, null); Text("Down")
            }
        }
        if (draft.sections.size > 1) {
            DeleteButton("Delete section") { vm.deleteSection(existing.id); onDismiss() }
        }
        DoneButton(onDismiss)
    }
}

// ── Ingredient ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IngredientSheet(target: EditTarget.Ingredient, draft: EditDraft, vm: RecipeDetailViewModel, onDismiss: () -> Unit) {
    // Locate by id across all sections (the item's section can change while the sheet is open).
    val existing = draft.sections.flatMap { it.ingredients }.find { it.id == target.ingredientId }
    val base = existing ?: EditorIngredient()
    // Which section this item lives in / will be added to.
    var sectionId by remember(target) {
        mutableStateOf(existing?.let { vm.sectionIdOfIngredient(it.id) } ?: target.sectionId)
    }

    var name by remember(target) { mutableStateOf(base.name) }
    var qty by remember(target) { mutableStateOf(base.quantityValue?.let { plain(it) } ?: "") }
    var max by remember(target) { mutableStateOf(base.quantityValueMax?.let { plain(it) } ?: "") }
    var unit by remember(target) { mutableStateOf(base.quantityUnit) }
    // Free-text display only used when there's no numeric amount (e.g. "to taste").
    var text by remember(target) { mutableStateOf(if (base.quantityValue == null) base.quantityDisplay else "") }
    var group by remember(target) { mutableStateOf(base.groupLabel) }
    var optional by remember(target) { mutableStateOf(base.isOptional) }
    var note by remember(target) { mutableStateOf(base.shoppingNote) }

    // Substitute linking — other ingredients this one can stand in for.
    val others = draft.sections.flatMap { it.ingredients }
        .filter { it.id != base.id && it.name.isNotBlank() }
    val initialSubTarget = existing?.substituteGroupId?.let { grp ->
        others.firstOrNull { it.substituteGroupId == grp }?.id
    }
    var subTargetId by remember(target) { mutableStateOf(initialSubTarget) }

    fun build(): EditorIngredient {
        val v = qty.toDoubleOrNull()
        val m = max.toDoubleOrNull()
        return base.copy(
            name = name,
            quantityValue = v,
            quantityValueMax = m,
            quantityUnit = unit,
            quantityDisplay = deriveDisplay(v, m, unit, text),
            groupLabel = group,
            isOptional = optional,
            shoppingNote = note,
        )
    }
    // Live-update the row behind the sheet for existing ingredients (section-agnostic).
    fun push() { if (existing != null) vm.updateIngredientAnywhere(build()) }

    SheetTitle(if (existing == null) "Add ingredient" else "Edit ingredient")
    OutlinedTextField(name, { name = it; push() }, label = { Text("Ingredient") },
        modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(qty, { qty = it.filter { c -> c.isDigit() || c == '.' }; push() },
            label = { Text("Quantity") }, modifier = Modifier.weight(1f), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(max, { max = it.filter { c -> c.isDigit() || c == '.' }; push() },
            label = { Text("to (range)") }, placeholder = { Text("—") },
            modifier = Modifier.weight(1f), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    OutlinedTextField(unit, { unit = it; push() }, label = { Text("Unit") },
        placeholder = { Text("cup, g, clove…") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    // Only relevant when there's no numeric amount (e.g. "to taste", "a pinch").
    if (qty.isBlank()) {
        OutlinedTextField(text, { text = it; push() }, label = { Text("Or free text (e.g. to taste)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
    }
    // Group — an editable dropdown: suggests the recipe's existing groups, but typing a new value
    // creates a new group.
    val existingGroups = remember(draft) {
        draft.sections.flatMap { it.ingredients }.map { it.groupLabel }.filter { it.isNotBlank() }.distinct()
    }
    var groupExpanded by remember(target) { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = groupExpanded, onExpandedChange = { groupExpanded = it }) {
        OutlinedTextField(
            value = group, onValueChange = { group = it; groupExpanded = true; push() },
            label = { Text("Group (e.g. Spices)") },
            trailingIcon = { if (existingGroups.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(groupExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
            singleLine = true,
        )
        val suggestions = existingGroups.filter { group.isBlank() || (it.contains(group, true) && it != group) }
        if (suggestions.isNotEmpty()) {
            ExposedDropdownMenu(groupExpanded, { groupExpanded = false }) {
                suggestions.forEach { g ->
                    DropdownMenuItem(text = { Text(g) }, onClick = { group = g; groupExpanded = false; push() })
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Switch(optional, { optional = it; push() })
        Spacer(Modifier.width(8.dp)); Text("Optional", style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedTextField(note, { note = it; push() }, label = { Text("Shopping note (e.g. Amul)") },
        modifier = Modifier.fillMaxWidth(), singleLine = true)

    // Section — move this ingredient between sections (or create one). Reflected in steps too,
    // since sections are shared. For a new ingredient this picks where it lands.
    SectionSelector(
        sections = draft.sections,
        currentId = sectionId,
        onSelect = { id -> sectionId = id; if (existing != null) vm.moveIngredientToSection(existing.id, id) },
        onCreate = { name ->
            val id = "sec-new-${java.util.UUID.randomUUID()}"
            vm.addSectionWithId(id, name); sectionId = id
            if (existing != null) vm.moveIngredientToSection(existing.id, id)
        },
    )

    // ── Substitute linking — mark this as an alternative of another ingredient (they share a
    // group and offer a swap chip on the detail view). For a new ingredient the link is on Add. ──
    if (others.isNotEmpty()) {
        var subExpanded by remember(target) { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = subExpanded, onExpandedChange = { subExpanded = it }) {
            OutlinedTextField(
                value = subTargetId?.let { id -> others.firstOrNull { it.id == id }?.name } ?: "None",
                onValueChange = {}, readOnly = true, label = { Text("Substitute for") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(subExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(subExpanded, { subExpanded = false }) {
                DropdownMenuItem(text = { Text("None") }, onClick = {
                    subTargetId = null; subExpanded = false
                    if (existing != null) vm.linkSubstitute(existing.id, null)
                })
                others.forEach { other ->
                    DropdownMenuItem(text = { Text(other.name) }, onClick = {
                        subTargetId = other.id; subExpanded = false
                        if (existing != null) vm.linkSubstitute(existing.id, other.id)
                    })
                }
            }
        }
    }

    if (existing == null) {
        Button(onClick = {
            vm.addIngredient(sectionId, build())
            subTargetId?.let { vm.linkSubstitute(base.id, it) }
            onDismiss()
        },
            modifier = Modifier.fillMaxWidth(), enabled = name.isNotBlank()) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add ingredient")
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.moveIngredientUp(sectionId, existing.id) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.KeyboardArrowUp, "Move up"); Text("Up")
            }
            OutlinedButton(onClick = { vm.moveIngredientDown(sectionId, existing.id) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.KeyboardArrowDown, "Move down"); Text("Down")
            }
        }
        DeleteButton("Delete ingredient") { vm.deleteIngredientAnywhere(existing.id); onDismiss() }
        DoneButton(onDismiss)
    }
}

// ── Step ──
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun StepSheet(target: EditTarget.Step, draft: EditDraft, vm: RecipeDetailViewModel, onDismiss: () -> Unit) {
    val existing = draft.sections.flatMap { it.steps }.find { it.id == target.stepId }
    val base = existing ?: EditorStep()
    var sectionId by remember(target) {
        mutableStateOf(existing?.let { vm.sectionIdOfStep(it.id) } ?: target.sectionId)
    }

    var instruction by remember(target) { mutableStateOf(base.instruction) }
    // For a new step the chosen ingredient ids are held locally until "Add".
    var ids by remember(target) { mutableStateOf(base.ingredientIds.toSet()) }
    val allIngredients = draft.sections.flatMap { it.ingredients }.filter { it.name.isNotBlank() }

    SheetTitle(if (existing == null) "Add step" else "Edit step")
    OutlinedTextField(
        value = instruction,
        onValueChange = { instruction = it; if (existing != null) vm.updateStepAnywhere(existing.copy(instruction = it, ingredientIds = ids.toList())) },
        label = { Text("Instruction") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 8,
    )
    if (allIngredients.isNotEmpty()) {
        Text("Uses ingredients", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            allIngredients.forEach { ing ->
                val selected = ing.id in ids
                FilterChip(
                    selected = selected,
                    onClick = {
                        ids = if (selected) ids - ing.id else ids + ing.id
                        if (existing != null) vm.toggleStepIngredientAnywhere(existing.id, ing.id)
                    },
                    label = { Text(ing.name, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }

    // Section — move this step between sections (or create one). Shared with ingredients.
    SectionSelector(
        sections = draft.sections,
        currentId = sectionId,
        onSelect = { id -> sectionId = id; if (existing != null) vm.moveStepToSection(existing.id, id) },
        onCreate = { name ->
            val id = "sec-new-${java.util.UUID.randomUUID()}"
            vm.addSectionWithId(id, name); sectionId = id
            if (existing != null) vm.moveStepToSection(existing.id, id)
        },
    )

    if (existing == null) {
        Button(onClick = { vm.addStep(sectionId, base.copy(instruction = instruction, ingredientIds = ids.toList())); onDismiss() },
            modifier = Modifier.fillMaxWidth(), enabled = instruction.isNotBlank()) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add step")
        }
    } else {
        DeleteButton("Delete step") { vm.deleteStepAnywhere(existing.id); onDismiss() }
        DoneButton(onDismiss)
    }
}

// ── Section selector — pick an existing section or create a new one. Shared by the ingredient
//    and step sheets so sections stay consistent across both. A blank section name shows as "Main". ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionSelector(
    sections: List<EditorSection>,
    currentId: String?,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var newMode by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    fun label(s: EditorSection) = s.name.ifBlank { "Main" }
    val currentLabel = sections.firstOrNull { it.id == currentId }?.let { label(it) } ?: "Main"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = currentLabel, onValueChange = {}, readOnly = true, label = { Text("Section") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            sections.forEach { s ->
                DropdownMenuItem(text = { Text(label(s)) }, onClick = { expanded = false; onSelect(s.id) })
            }
            DropdownMenuItem(
                text = { Text("＋ New section…") },
                onClick = { expanded = false; newMode = true; newName = "" },
            )
        }
    }
    if (newMode) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newName, { newName = it }, label = { Text("New section name") },
                modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = { onCreate(newName); newMode = false }, enabled = newName.isNotBlank()) { Text("Add") }
        }
    }
}

// ── Small shared bits ──
@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun DoneButton(onDismiss: () -> Unit) {
    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun DeleteButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp)); Text(label)
    }
}
