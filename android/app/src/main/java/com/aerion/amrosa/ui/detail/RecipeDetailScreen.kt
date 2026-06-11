package com.aerion.amrosa.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.domain.model.*
import com.aerion.amrosa.ui.util.QuantityScaler
import com.aerion.amrosa.ui.util.UnitMode
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    recipeId: String,
    onBack: () -> Unit,
    onEditClick: () -> Unit = {},
    onOpenRecipe: (String) -> Unit = {},
    onEditRecipe: (String) -> Unit = {},
    onShoppingClick: (servings: Int, anchorQty: Double?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val app = context.applicationContext as AmrosaApplication
    val viewModel: RecipeDetailViewModel = viewModel(
        key = recipeId,
        factory = RecipeDetailViewModel.factory(
            repository = app.container.repository,
            authRepository = app.container.authRepository,
            sharedRecipeService = app.container.sharedRecipeService,
            socialRepository = app.container.socialRepository,
            recipeId = recipeId
        )
    )
    val state by viewModel.uiState.collectAsState()
    var showNoteInput by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    var showCookingMode by remember { mutableStateOf(false) }
    // Which section cooking mode should open at (null = from the first step).
    var cookingStartSectionId by remember { mutableStateOf<String?>(null) }
    var selectedUnit by remember { mutableStateOf(UnitMode.ORIGINAL) }
    // Variation-name dialog state
    var showVariantDialog by remember { mutableStateOf(false) }
    var variantNameInput by remember { mutableStateOf("") }
    var showVisibilityDialog by remember { mutableStateOf(false) }
    // Dedicated prompt for the Share-link flow when the recipe isn't Public yet
    var showMakePublicForLink by remember { mutableStateOf(false) }
    var commentText by remember { mutableStateOf("") }
    // Set to true after "make public" is confirmed — opens share sheet once publish completes
    var pendingShareAfterPublish by remember { mutableStateOf(false) }
    // Share options sheet (merged: send to follower + share link)
    var showShareOptions by remember { mutableStateOf(false) }
    // Follower picker sheet (opened after selecting "Send to follower")
    var showFollowerPicker by remember { mutableStateOf(false) }
    // When sharing a PRIVATE recipe, remember who we're sharing to while the "make public" prompt shows
    var pendingShareRecipient by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Confirm dialog for removing a received recipe from Tab 2
    var showRemoveDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Cooking-mode step checklist: ticked ingredient ids, scoped to this recipe screen so it
    // clears automatically when you leave the recipe (session-only, never persisted).
    val cookingChecked = remember { mutableStateListOf<String>() }

    // Navigate back once a received recipe has been removed
    LaunchedEffect(state.removed) {
        if (state.removed) onBack()
    }

    // After a variation is created, open the editor on it
    LaunchedEffect(state.createdVariantId) {
        state.createdVariantId?.let { newId ->
            viewModel.clearCreatedVariant()
            onEditRecipe(newId)
        }
    }

    // Variation-name dialog
    if (showVariantDialog) {
        AlertDialog(
            onDismissRequest = { showVariantDialog = false },
            icon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
            title = { Text("New variation") },
            text = {
                Column {
                    Text(
                        "Creates an editable copy of this recipe. Give the variation a name:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = variantNameInput,
                        onValueChange = {
                            variantNameInput = it.take(RecipeDetailViewModel.MAX_VARIANT_NAME_LEN)
                        },
                        label = { Text("e.g. Spicy, Vegan") },
                        singleLine = true,
                        supportingText = {
                            Text("${variantNameInput.length}/${RecipeDetailViewModel.MAX_VARIANT_NAME_LEN}")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createVariant(variantNameInput)
                        showVariantDialog = false
                        variantNameInput = ""
                    },
                    enabled = variantNameInput.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showVariantDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Show snackbar when a recipe is sent successfully
    LaunchedEffect(state.shareSentToName) {
        state.shareSentToName?.let { name ->
            snackbarHostState.showSnackbar("Recipe sent to $name")
        }
    }

    // Helper: fire the Android share sheet for this recipe's deep link
    val openShareSheet: () -> Unit = {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://amrosa-2ec82.web.app/shared/$recipeId")
            putExtra(Intent.EXTRA_SUBJECT, state.recipe?.title ?: "Recipe")
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share recipe"))
    }

    // Once the recipe becomes public and we have a pending share, fire the share sheet
    LaunchedEffect(state.isPublic, pendingShareAfterPublish) {
        if (pendingShareAfterPublish && state.isPublic) {
            pendingShareAfterPublish = false
            openShareSheet()
        }
    }

    if (showCookingMode && state.recipe != null) {
        CookingModeScreen(
            recipe = state.recipe!!,
            state = state,
            selectedUnit = selectedUnit,
            onUnitChange = { selectedUnit = it },
            startSectionId = cookingStartSectionId,
            checkedIngredients = cookingChecked,
            onToggleIngredient = { id ->
                if (id in cookingChecked) cookingChecked.remove(id) else cookingChecked.add(id)
            },
            onExit = { showCookingMode = false },
            onDone = { viewModel.markCooked(); showCookingMode = false }
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(state.recipe?.title ?: "", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isReceived) {
                        // Received recipe (Tab 2) — read-only: only Remove + Cooking Mode
                        IconButton(onClick = { showRemoveDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove from my recipes")
                        }
                    } else {
                        // My recipe — share (owners) + edit
                        if (state.isOwner) {
                            IconButton(
                                onClick = {
                                    viewModel.loadFollowing()
                                    showShareOptions = true
                                },
                                enabled = !state.isVisibilityUpdating
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share recipe")
                            }
                        }
                        IconButton(onClick = onEditClick) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Recipe")
                        }
                    }
                    IconButton(onClick = {
                        onShoppingClick(
                            state.selectedServings,
                            if (state.usesAnchorScaling) state.scaleAnchorQty else null
                        )
                    }) {
                        Icon(Icons.Default.ShoppingCart, contentDescription = "Shopping List")
                    }
                    IconButton(onClick = { cookingStartSectionId = null; showCookingMode = true }) {
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

        // Ingredient checklist ordered by SECTION (in step order) then by GROUP within.
        // Each block = (sectionName? , [ (groupLabel, [ingredients]) ]). Section names are
        // null for single-section recipes (no redundant sub-header). Ingredients with no/unknown
        // section fall into a trailing "Other" block.
        val ingredientBlocks: List<Pair<String?, List<Pair<String, List<Ingredient>>>>> =
            remember(recipe, state.visibleIngredients) {
                val multiSection = recipe.sections.size > 1
                fun groupsOf(ings: List<Ingredient>) =
                    ings.groupBy { it.groupLabel ?: "" }.toList()
                        .map { (label, list) -> label to list.sortedBy { it.orderIndex } }

                val blocks = mutableListOf<Pair<String?, List<Pair<String, List<Ingredient>>>>>()
                val bySection = state.visibleIngredients.groupBy { it.sectionId }
                recipe.sections.sortedBy { it.orderIndex }.forEach { section ->
                    val ings = bySection[section.id].orEmpty()
                    if (ings.isNotEmpty()) {
                        blocks += (if (multiSection) section.name else null) to groupsOf(ings)
                    }
                }
                val knownSectionIds = recipe.sections.map { it.id }.toSet()
                val orphan = state.visibleIngredients.filter {
                    it.sectionId == null || it.sectionId !in knownSectionIds
                }
                if (orphan.isNotEmpty()) {
                    blocks += (if (blocks.isNotEmpty()) "Other" else null) to groupsOf(orphan)
                }
                blocks
            }

        // Build an ordered list of lazy-item keys so we can find section indices
        // Structure: header, time-row, sources?, options?, "Ingredients" header,
        //   ingredient blocks..., divider, "Instructions" header,
        //   then for each section: section-header key = "section-{sectionId}", steps...
        //   unsectioned steps, divider, notes...

        // Pre-compute section header indices for jump chips
        val sectionIndices = remember(recipe, ingredientBlocks) {
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
            ingredientBlocks.forEach { (sectionName, groups) ->
                if (sectionName != null) idx++ // section sub-header
                groups.forEach { (label, ings) ->
                    if (label.isNotBlank()) idx++ // group label
                    idx += ings.size // ingredient items
                }
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
                    // Visibility chip — only shown to the recipe owner (Private / Co-Chefs / Public)
                    if (state.isOwner) {
                        Spacer(Modifier.height(10.dp))
                        val (visIcon, visLabel) = when (state.visibility) {
                            "public"  -> Icons.Default.Public to "Public"
                            "friends" -> Icons.Default.People to "Co-Chefs"
                            else       -> Icons.Default.Lock to "Private"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = state.isPublished,
                                onClick = { showVisibilityDialog = true },
                                enabled = !state.isVisibilityUpdating,
                                leadingIcon = {
                                    Icon(visIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = {
                                    Text(visLabel, style = MaterialTheme.typography.labelMedium)
                                }
                            )
                            // Read-only popularity counts (published recipes)
                            if (state.isPublished && (state.saveCount > 0 || state.likeCount > 0)) {
                                Spacer(Modifier.width(12.dp))
                                Icon(Icons.Default.BookmarkAdd, contentDescription = "Saves",
                                    modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(" ${state.saveCount}", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Default.Favorite, contentDescription = "Likes",
                                    modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(" ${state.likeCount}", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // ── Variation selector ──────────────────────────────
                    // Shows the base + its variations as switchable chips, plus an
                    // "Add variation" chip for the owner (up to MAX_VARIANTS).
                    if (state.variants.size > 1 || state.canAddVariant) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            state.variants.forEach { v ->
                                FilterChip(
                                    selected = v.isCurrent,
                                    onClick = { if (!v.isCurrent) onOpenRecipe(v.id) },
                                    leadingIcon = if (v.isBase) {
                                        { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    label = { Text(v.label, style = MaterialTheme.typography.labelMedium) }
                                )
                            }
                            if (state.canAddVariant) {
                                AssistChip(
                                    onClick = { variantNameInput = ""; showVariantDialog = true },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    label = { Text("Variation", style = MaterialTheme.typography.labelMedium) }
                                )
                            }
                        }
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
                    val context = LocalContext.current
                    SectionHeader("Sources")
                    recipe.sourceUrls.forEach { url ->
                        Text(
                            text = "• $url",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 2.dp)
                                .clickable {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url)
                                    )
                                    context.startActivity(intent)
                                }
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
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ingredients", style = MaterialTheme.typography.headlineMedium)

                    // Unit toggle — only shown when at least one ingredient has conversions
                    val hasConversions = recipe.ingredients.any {
                        it.quantityValueMetric != null || it.quantityValueImperial != null
                    }
                    if (hasConversions) {
                        SingleChoiceSegmentedButtonRow {
                            UnitMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = selectedUnit == mode,
                                    onClick = { selectedUnit = mode },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index, count = UnitMode.entries.size
                                    ),
                                    label = {
                                        Text(
                                            when (mode) {
                                                UnitMode.ORIGINAL -> "Orig"
                                                UnitMode.METRIC   -> "Metric"
                                                UnitMode.IMPERIAL -> "Imp"
                                            },
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            ingredientBlocks.forEach { (sectionName, groups) ->
                if (sectionName != null) {
                    item {
                        Text(
                            sectionName,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
                groups.forEach { (label, ings) ->
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
                            scaledQty = QuantityScaler.scale(ing, state.scaleFactor, selectedUnit),
                            isOptional = ing.isOptional,
                            isOptionalEnabled = ing.id in state.enabledOptionals,
                            onToggleOptional = { viewModel.toggleOptional(ing.id) }
                        )
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }

            // ── Steps by section ────────────────────────────────────
            item { SectionHeader("Instructions") }

            recipe.sections.forEach { section ->
                item(key = "section-${section.id}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            section.name,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        TextButton(onClick = {
                            cookingStartSectionId = section.id
                            showCookingMode = true
                        }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Cook", style = MaterialTheme.typography.labelMedium)
                        }
                    }
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

            // ── Comments (shown when recipe is shared — Co-Chefs or Public) ──────────────
            if (state.isPublished) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Forum, contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Comments", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${state.comments.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Comment input
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = commentText,
                            onValueChange = { commentText = it },
                            placeholder = { Text("Add a comment…") },
                            modifier = Modifier.weight(1f),
                            maxLines = 3,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                viewModel.addComment(commentText)
                                commentText = ""
                            },
                            enabled = commentText.isNotBlank()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post comment",
                                tint = if (commentText.isNotBlank())
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (state.comments.isEmpty()) {
                    item {
                        Text(
                            "No comments yet. Be the first!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                items(state.comments, key = { "comment-${it.id}" }) { comment ->
                    val currentUid = (LocalContext.current.applicationContext as AmrosaApplication)
                        .container.authRepository.uid
                    CommentRow(
                        comment = comment,
                        canDelete = currentUid == comment.authorId || state.isOwner,
                        onDelete = { viewModel.deleteComment(comment.id) }
                    )
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    // ── Visibility chooser dialog (Private / Co-Chefs / Public) ───────────────
    if (showVisibilityDialog) {
        AlertDialog(
            onDismissRequest = { showVisibilityDialog = false },
            icon = { Icon(Icons.Default.Visibility, contentDescription = null) },
            title = { Text("Who can see this recipe?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    VisibilityOption(
                        selected = state.visibility == "private",
                        icon = Icons.Default.Lock, title = "Private", subtitle = "Only you",
                        onClick = { viewModel.setVisibility("private"); showVisibilityDialog = false }
                    )
                    VisibilityOption(
                        selected = state.visibility == "friends",
                        icon = Icons.Default.People, title = "Co-Chefs only",
                        subtitle = "Your co-chefs can find it on your profile",
                        onClick = { viewModel.setVisibility("friends"); showVisibilityDialog = false }
                    )
                    VisibilityOption(
                        selected = state.visibility == "public",
                        icon = Icons.Default.Public, title = "Public",
                        subtitle = "Anyone with the link can view",
                        onClick = { viewModel.setVisibility("public"); showVisibilityDialog = false }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showVisibilityDialog = false }) { Text("Done") }
            }
        )
    }

    // ── "Make Public to share a link" prompt (Share-link flow only) ───────────
    if (showMakePublicForLink) {
        AlertDialog(
            onDismissRequest = { showMakePublicForLink = false },
            icon = { Icon(Icons.Default.Public, contentDescription = null) },
            title = { Text("Make Public?") },
            text = { Text("A share link is accessible to anyone. This makes the recipe Public; you can change it back anytime.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.setVisibility("public")
                    pendingShareAfterPublish = true
                    showMakePublicForLink = false
                }) { Text("Make Public & Share") }
            },
            dismissButton = {
                TextButton(onClick = { showMakePublicForLink = false }) { Text("Cancel") }
            }
        )
    }

    // ── Share options sheet ───────────────────────────────────────────────────
    if (showShareOptions) {
        ShareOptionsSheet(
            onDismiss = { showShareOptions = false },
            onSendToFollower = {
                showShareOptions = false
                showFollowerPicker = true
            },
            onShareLink = {
                showShareOptions = false
                if (state.isPublic) {
                    openShareSheet()
                } else {
                    showMakePublicForLink = true
                }
            }
        )
    }

    // ── Follower picker bottom sheet ──────────────────────────────────────────
    if (showFollowerPicker) {
        FollowerPickerSheet(
            following = state.following,
            isLoading = state.isFollowingLoading,
            onDismiss = { showFollowerPicker = false },
            onSend = { uid, name ->
                showFollowerPicker = false
                if (state.isPublished) {
                    // Already shared (Co-Chefs or Public) — share immediately
                    viewModel.shareToFollower(uid, name)
                } else {
                    // Private — confirm making it Co-Chefs-visible before sharing
                    pendingShareRecipient = uid to name
                }
            }
        )
    }

    // ── "Make visible to co-chefs to share" confirm prompt ────────────────────
    pendingShareRecipient?.let { (uid, name) ->
        AlertDialog(
            onDismissRequest = { pendingShareRecipient = null },
            icon = { Icon(Icons.Default.People, contentDescription = null) },
            title = { Text("Share with $name?") },
            text = {
                Text(
                    "Sharing makes this recipe visible to your co-chefs so $name can view it. " +
                    "You can make it private again anytime — that removes it from everyone you shared with."
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.makeSharableAndShareToFollower(uid, name)
                    pendingShareRecipient = null
                }) { Text("Share") }
            },
            dismissButton = {
                TextButton(onClick = { pendingShareRecipient = null }) { Text("Cancel") }
            }
        )
    }

    // ── Remove received recipe confirm ────────────────────────────────────────
    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Remove from my recipes?") },
            text = {
                Text(
                    "This removes the recipe from your Shared list. It stays available to its " +
                    "author, and you can save it again if they share it with you."
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.removeReceivedRecipe(); showRemoveDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ── Share options sheet ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareOptionsSheet(
    onDismiss: () -> Unit,
    onSendToFollower: () -> Unit,
    onShareLink: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Text(
                "Share Recipe",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            HorizontalDivider()

            // Option A: Send to follower (default — shown first)
            ListItem(
                modifier = Modifier.clickable(onClick = onSendToFollower),
                headlineContent = {
                    Text(
                        "Send to a follower",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                },
                supportingContent = {
                    Text(
                        "Share directly — only that person will see it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                )
            )

            // Option B: Share link
            ListItem(
                modifier = Modifier.clickable(onClick = onShareLink),
                headlineContent = {
                    Text(
                        "Share link",
                        style = MaterialTheme.typography.bodyLarge
                    )
                },
                supportingContent = {
                    Text(
                        "Anyone with the link can view this recipe",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Default.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Follower picker sheet ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowerPickerSheet(
    following: List<com.aerion.amrosa.domain.model.UserProfile>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSend: (uid: String, name: String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Text(
                "Send to a Follower",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            HorizontalDivider()

            if (isLoading) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            } else if (following.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "You're not following anyone yet.\nFind people from the Account tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                following.forEach { user ->
                    ListItem(
                        headlineContent = { Text(user.displayName) },
                        leadingContent = {
                            Surface(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        user.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { onSend(user.uid, user.displayName) }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send to ${user.displayName}",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        modifier = Modifier.clickable { onSend(user.uid, user.displayName) }
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ── Cooking Mode ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CookingModeScreen(
    recipe: Recipe,
    state: RecipeDetailUiState,
    selectedUnit: UnitMode,
    onUnitChange: (UnitMode) -> Unit,
    startSectionId: String?,
    checkedIngredients: List<String>,
    onToggleIngredient: (String) -> Unit,
    onExit: () -> Unit,
    onDone: () -> Unit = onExit
) {
    val steps = recipe.sections
        .flatMap { section -> recipe.steps.filter { it.sectionId == section.id }.sortedBy { it.orderIndex } }
        .plus(recipe.steps.filter { it.sectionId == null }.sortedBy { it.orderIndex })

    // First step of each section, for the jump menu + "Cook from here" entry point.
    val sectionStarts = remember(recipe, steps) {
        recipe.sections.mapNotNull { sec ->
            val i = steps.indexOfFirst { it.sectionId == sec.id }
            if (i >= 0) sec.name to i else null
        }
    }
    val initialIndex = remember(startSectionId) {
        if (startSectionId == null) 0
        else steps.indexOfFirst { it.sectionId == startSectionId }.let { if (it < 0) 0 else it }
    }

    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    val step = steps.getOrNull(currentIndex)
    var showSectionMenu by remember { mutableStateOf(false) }

    val hasConversions = recipe.ingredients.any {
        it.quantityValueMetric != null || it.quantityValueImperial != null
    }

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
                actions = {
                    if (sectionStarts.size > 1) {
                        Box {
                            IconButton(onClick = { showSectionMenu = true }) {
                                Icon(Icons.Default.Menu, contentDescription = "Jump to section")
                            }
                            DropdownMenu(
                                expanded = showSectionMenu,
                                onDismissRequest = { showSectionMenu = false }
                            ) {
                                sectionStarts.forEach { (name, index) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = { currentIndex = index; showSectionMenu = false }
                                    )
                                }
                            }
                        }
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // Unit toggle — only when conversions exist; shared with the detail screen
                if (hasConversions) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        SingleChoiceSegmentedButtonRow {
                            UnitMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = selectedUnit == mode,
                                    onClick = { onUnitChange(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = UnitMode.entries.size),
                                    label = {
                                        Text(
                                            when (mode) {
                                                UnitMode.ORIGINAL -> "Orig"
                                                UnitMode.METRIC   -> "Metric"
                                                UnitMode.IMPERIAL -> "Imp"
                                            },
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

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
                            Column(modifier = Modifier.padding(8.dp)) {
                                visible.forEach { ref ->
                                    val ing = recipe.ingredients.find { it.id == ref.ingredientId }
                                    val name = state.resolvedIngredientName(ref.ingredientId)
                                    // Honour the unit toggle: scale the full ingredient amount in the
                                    // chosen unit; fall back to the step's ref display when unknown.
                                    val qty = if (ing != null)
                                        QuantityScaler.scale(ing, state.scaleFactor, selectedUnit)
                                    else
                                        QuantityScaler.scale(null, null, ref.quantityDisplay, state.scaleFactor)
                                    // Tick off ingredients as they're added (session-only; clears on recipe exit).
                                    val checked = ref.ingredientId in checkedIngredients
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { onToggleIngredient(ref.ingredientId) }
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "$name — $qty",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                textDecoration = if (checked) TextDecoration.LineThrough else null
                                            ),
                                            color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
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
                    Button(onClick = onDone) { Text("Done ✓") }
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

/** A selectable row in the visibility chooser dialog (Private / Co-Chefs / Public). */
@Composable
private fun VisibilityOption(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
    isOptional: Boolean,
    isOptionalEnabled: Boolean,
    onToggleOptional: () -> Unit
) {
    // Display-only row. The checkable list lives on the Shopping List screen now;
    // optional ingredients keep their enable/disable switch (it affects scaling).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isOptional) {
            Switch(
                checked = isOptionalEnabled,
                onCheckedChange = { onToggleOptional() },
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.width(8.dp))
        } else {
            Text(
                "•",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(start = 4.dp, end = 12.dp)
            )
        }
        Text(
            text = "${ingredient.name}  —  $scaledQty",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isOptional && !isOptionalEnabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface
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
private fun CommentRow(
    comment: Comment,
    canDelete: Boolean,
    onDelete: () -> Unit
) {
    val dateStr = remember(comment.createdAt) {
        SimpleDateFormat("MMM d  h:mm a", Locale.getDefault()).format(Date(comment.createdAt))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Avatar circle
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                comment.authorDisplayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.authorDisplayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(comment.content, style = MaterialTheme.typography.bodyMedium)
        }
        if (canDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete comment",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
