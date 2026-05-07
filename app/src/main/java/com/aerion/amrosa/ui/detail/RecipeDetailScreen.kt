package com.aerion.amrosa.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.domain.model.*
import com.aerion.amrosa.ui.util.QuantityScaler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    recipeId: String,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as AmrosaApplication
    val viewModel: RecipeDetailViewModel = viewModel(
        key = recipeId,
        factory = RecipeDetailViewModel.factory(app.container.repository, recipeId)
    )
    val state by viewModel.uiState.collectAsState()
    var showNoteInput by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    var showCookingMode by remember { mutableStateOf(false) }

    if (showCookingMode && state.recipe != null) {
        CookingModeScreen(
            recipe = state.recipe!!,
            state = state,
            onExit = { showCookingMode = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.recipe?.title ?: "", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCookingMode = true }) {
                        Icon(Icons.Default.MenuBook, contentDescription = "Cooking Mode")
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

        val recipe = state.recipe ?: return@Scaffold
        val listState = rememberLazyListState()
        val coroutineScope = rememberCoroutineScope()

        // Build an ordered list of lazy-item keys so we can find section indices
        // Structure: header, time-row, sources?, options?, "Ingredients" header,
        //   ingredient groups..., divider, "Instructions" header,
        //   then for each section: section-header key = "section-{sectionId}", steps...
        //   unsectioned steps, divider, notes...

        // Pre-compute section header indices for jump chips
        val sectionIndices = remember(recipe, state.visibleIngredients) {
            var idx = 0
            idx++ // header
            idx++ // time-row + divider

            if (recipe.sections.size > 1) idx++ // section-jumps row

            if (recipe.sourceUrls.isNotEmpty()) idx++ // sources + divider

            val subGroups = recipe.ingredients
                .filter { it.substituteGroupId != null }
                .groupBy { it.substituteGroupId!! }
            if (subGroups.isNotEmpty()) {
                idx++ // "Options" header
                idx += subGroups.size // one item per substitute group
                idx++ // divider
            }

            idx++ // "Ingredients" header
            val grouped = state.visibleIngredients.groupBy { it.groupLabel ?: "" }
            grouped.forEach { (label, ings) ->
                if (label.isNotBlank()) idx++ // group label
                idx += ings.size // ingredient items
            }
            idx++ // divider after ingredients

            idx++ // "Instructions" header

            val map = mutableMapOf<String, Int>()
            recipe.sections.forEach { section ->
                map[section.id] = idx
                idx++ // section header
                idx += recipe.steps.count { it.sectionId == section.id }
            }
            map
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── Header ─────────────────────────────────────────────
            item(key = "header") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(recipe.title, style = MaterialTheme.typography.headlineLarge)
                    recipe.description?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── Time + Servings row ─────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        recipe.prepTimeMinutes?.let {
                            Text("Prep  ${it}min", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        recipe.cookTimeMinutes?.let {
                            Text("Cook  ${it}min", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // Servings adjuster
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Yield ", style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { viewModel.adjustScale(-1) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Remove, contentDescription = "Less", modifier = Modifier.size(18.dp))
                        }
                        Text(
                            state.yieldDisplay,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.widthIn(min = 32.dp),
                        )
                        IconButton(onClick = { viewModel.adjustScale(1) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Add, contentDescription = "More", modifier = Modifier.size(18.dp))
                        }
                        if (!state.isDefaultScale) {
                            IconButton(onClick = { viewModel.resetScale() }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }

            // ── Section jump chips ─────────────────────────────────
            if (recipe.sections.size > 1) {
                item(key = "section-jumps") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        recipe.sections.forEach { section ->
                            val targetIndex = sectionIndices[section.id] ?: 0
                            AssistChip(
                                onClick = {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(targetIndex)
                                    }
                                },
                                label = { Text(section.name, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }
                }
            }

            // ── Source links ────────────────────────────────────────
            if (recipe.sourceUrls.isNotEmpty()) {
                item {
                    SectionHeader("Sources")
                    recipe.sourceUrls.forEach { url ->
                        Text(
                            text = "• $url",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }

            // ── Substitute selectors ────────────────────────────────
            val substituteGroups = recipe.ingredients
                .filter { it.substituteGroupId != null }
                .groupBy { it.substituteGroupId!! }

            if (substituteGroups.isNotEmpty()) {
                item { SectionHeader("Options") }
                substituteGroups.forEach { (groupId, options) ->
                    item {
                        SubstituteSelector(
                            options = options,
                            selectedId = state.selectedSubstitutes[groupId] ?: options.first().id,
                            onSelect = { viewModel.selectSubstitute(groupId, it) }
                        )
                    }
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }

            // ── Ingredient checklist ────────────────────────────────
            item { SectionHeader("Ingredients") }

            val grouped = state.visibleIngredients.groupBy { it.groupLabel ?: "" }
            grouped.forEach { (label, ings) ->
                if (label.isNotBlank()) {
                    item {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
                items(ings, key = { it.id }) { ing ->
                    IngredientRow(
                        ingredient = ing,
                        scaledQty = QuantityScaler.scale(
                            ing.quantityValue, ing.quantityUnit, ing.quantityDisplay, state.scaleFactor
                        ),
                        isChecked = ing.id in state.checkedIngredients,
                        isOptional = ing.isOptional,
                        isOptionalEnabled = ing.id in state.enabledOptionals,
                        onCheck = { viewModel.toggleIngredientCheck(ing.id) },
                        onToggleOptional = { viewModel.toggleOptional(ing.id) }
                    )
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }

            // ── Steps by section ────────────────────────────────────
            item { SectionHeader("Instructions") }

            recipe.sections.forEach { section ->
                item(key = "section-${section.id}") {
                    Text(
                        section.name,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                val sectionSteps = recipe.steps
                    .filter { it.sectionId == section.id }
                    .sortedBy { it.orderIndex }

                items(sectionSteps, key = { it.id }) { step ->
                    StepRow(
                        step = step,
                        stepNumber = step.orderIndex + 1,
                        recipe = recipe,
                        state = state
                    )
                }
            }

            // Steps not in any section
            val unsectionedSteps = recipe.steps
                .filter { it.sectionId == null }
                .sortedBy { it.orderIndex }
            if (unsectionedSteps.isNotEmpty()) {
                items(unsectionedSteps, key = { it.id }) { step ->
                    StepRow(step = step, stepNumber = step.orderIndex + 1, recipe = recipe, state = state)
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }

            // ── Notes ───────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Notes", style = MaterialTheme.typography.headlineMedium)
                    IconButton(onClick = { showNoteInput = !showNoteInput }) {
                        Icon(Icons.Default.AddComment, contentDescription = "Add note")
                    }
                }
            }

            if (showNoteInput) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            placeholder = { Text("Add a note, tweak, or observation…") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = {
                                viewModel.addNote(noteText)
                                noteText = ""
                                showNoteInput = false
                            },
                            enabled = noteText.isNotBlank()
                        ) { Text("Save Note") }
                    }
                }
            }

            items(state.notes, key = { it.id }) { note ->
                NoteRow(note = note, onDelete = { viewModel.deleteNote(note.id) })
            }

            if (state.notes.isEmpty() && !showNoteInput) {
                item {
                    Text(
                        "No notes yet. Tap + to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

// ── Cooking Mode ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CookingModeScreen(
    recipe: Recipe,
    state: RecipeDetailUiState,
    onExit: () -> Unit
) {
    val steps = recipe.sections
        .flatMap { section -> recipe.steps.filter { it.sectionId == section.id }.sortedBy { it.orderIndex } }
        .plus(recipe.steps.filter { it.sectionId == null }.sortedBy { it.orderIndex })

    var currentIndex by remember { mutableIntStateOf(0) }
    val step = steps.getOrNull(currentIndex)

    // Keep screen on
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Step ${currentIndex + 1} of ${steps.size}") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Default.Close, contentDescription = "Exit cooking mode")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Section label
                val sectionName = recipe.sections.find { it.id == step?.sectionId }?.name
                sectionName?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 12.dp))
                }

                // Instruction — large text for cooking
                Text(
                    text = step?.instruction ?: "",
                    style = MaterialTheme.typography.bodyLarge
                )

                // Inline ingredient refs for this step
                step?.ingredientRefs?.let { refs ->
                    val visible = refs.filter { ref ->
                        val ing = recipe.ingredients.find { it.id == ref.ingredientId }
                        ing != null && (
                            ing.substituteGroupId == null ||
                            state.selectedSubstitutes[ing.substituteGroupId] == ing.id
                        ) && (!ing.isOptional || ing.id in state.enabledOptionals)
                    }
                    if (visible.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                visible.forEach { ref ->
                                    val ing = recipe.ingredients.find { it.id == ref.ingredientId }
                                    val name = state.resolvedIngredientName(ref.ingredientId)
                                    val qty = QuantityScaler.scale(
                                        ing?.quantityValue, ing?.quantityUnit,
                                        ref.quantityDisplay, state.scaleFactor
                                    )
                                    Text("• $name — $qty",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(vertical = 2.dp))
                                }
                            }
                        }
                    }
                }
            }

            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = { if (currentIndex > 0) currentIndex-- },
                    enabled = currentIndex > 0
                ) { Text("← Previous") }

                if (currentIndex < steps.size - 1) {
                    Button(onClick = { currentIndex++ }) { Text("Next →") }
                } else {
                    Button(onClick = onExit) { Text("Done ✓") }
                }
            }
        }
    }
}

// ── Shared sub-composables ───────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SubstituteSelector(
    options: List<Ingredient>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "Choose: ${options.joinToString(" or ") { it.name }}",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { ing ->
                FilterChip(
                    selected = ing.id == selectedId,
                    onClick = { onSelect(ing.id) },
                    label = { Text(ing.name, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
    }
}

@Composable
private fun IngredientRow(
    ingredient: Ingredient,
    scaledQty: String,
    isChecked: Boolean,
    isOptional: Boolean,
    isOptionalEnabled: Boolean,
    onCheck: () -> Unit,
    onToggleOptional: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isOptional) {
            Switch(
                checked = isOptionalEnabled,
                onCheckedChange = { onToggleOptional() },
                modifier = Modifier.size(36.dp)
            )
        } else {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onCheck() }
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${ingredient.name}  —  $scaledQty",
            style = MaterialTheme.typography.bodyMedium.copy(
                textDecoration = if (isChecked && !isOptional) TextDecoration.LineThrough else null,
                color = if (isOptional && !isOptionalEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
        )
    }
}

@Composable
private fun StepRow(
    step: Step,
    stepNumber: Int,
    recipe: Recipe,
    state: RecipeDetailUiState
) {
    val visibleRefs = step.ingredientRefs.filter { ref ->
        val ing = recipe.ingredients.find { it.id == ref.ingredientId } ?: return@filter false
        if (ing.substituteGroupId != null && state.selectedSubstitutes[ing.substituteGroupId] != ing.id)
            return@filter false
        if (ing.isOptional && ing.id !in state.enabledOptionals) return@filter false
        true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // Step number badge
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$stepNumber",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(step.instruction, style = MaterialTheme.typography.bodyLarge)
        }

        // Inline ingredient refs for this step
        if (visibleRefs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .padding(start = 40.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                visibleRefs.forEach { ref ->
                    val ing = recipe.ingredients.find { it.id == ref.ingredientId }
                    val name = state.resolvedIngredientName(ref.ingredientId)
                    val qty = QuantityScaler.scale(
                        ing?.quantityValue, ing?.quantityUnit,
                        ref.quantityDisplay, state.scaleFactor
                    )
                    Text(
                        "· $name — $qty",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteRow(note: RecipeNote, onDelete: () -> Unit) {
    val dateStr = remember(note.createdAt) {
        SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault()).format(Date(note.createdAt))
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(note.content, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(dateStr, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete note",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
