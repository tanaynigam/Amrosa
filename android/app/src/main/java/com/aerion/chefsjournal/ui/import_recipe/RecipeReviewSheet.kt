package com.aerion.chefsjournal.ui.import_recipe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Generic recipe review bottom sheet.
 *
 * Used by both the import flow (Confirm / Reimport) and the freeform flow (Save / Reformat).
 *
 * @param primaryLabel      Label for the primary (confirming) action button
 * @param secondaryLabel    Label for the secondary (re-run) action button
 * @param isSecondaryLoading  True when the secondary action is in flight — shows a spinner
 * @param onPrimary         Called when the user taps the primary button
 * @param onSecondary       Called when the user taps the secondary button
 * @param onDismiss         Called when the sheet is dismissed (back gesture or background tap)
 * @param onEdit            Optional — when non-null, an Edit icon appears in the header; called
 *                          when tapped. For imports the recipe is already in Room; for freeform
 *                          the caller saves it first then navigates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipeReviewSheet(
    parsed: ParsedRecipeData,
    primaryLabel: String,
    secondaryLabel: String,
    isSecondaryLoading: Boolean = false,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null,
    // Author attribution toggle — only shown for imports (null = hide)
    isOwnRecipe: Boolean = false,
    onIsOwnRecipeChange: ((Boolean) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var parseNotesDismissed by remember { mutableStateOf(false) }

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
            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Review Recipe",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (onEdit != null) {
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit recipe",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // ── Parse notes banner (Gemini uncertainty) ───────────────────────
            AnimatedVisibility(visible = parsed.parseNotes != null && !parseNotesDismissed) {
                parsed.parseNotes?.let { notes ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(18.dp).padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Parser note",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            IconButton(
                                onClick = { parseNotesDismissed = true },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── Title + description ───────────────────────────────────────────
            Text(parsed.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            parsed.description?.let { desc ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(desc, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Meta row ──────────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                parsed.prepTimeMinutes?.let { InfoChip("Prep: ${it}min") }
                parsed.cookTimeMinutes?.let { InfoChip("Cook: ${it}min") }
                InfoChip("Serves: ${parsed.baseServings}")
            }

            if (parsed.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(parsed.tags) { tag ->
                        SuggestionChip(onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // ── Ingredients ───────────────────────────────────────────────────
            Text("Ingredients (${parsed.ingredients.size})",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))

            val sectionMap = parsed.sections.associateBy { it.id }
            val ingredientsBySection = parsed.ingredients.groupBy { it.sectionId }

            ingredientsBySection.forEach { (sectionId, ingredients) ->
                if (sectionId != null) {
                    sectionMap[sectionId]?.let { section ->
                        Text(section.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                    }
                }
                ingredients.forEach { ing ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 8.dp))
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

            // ── Steps ─────────────────────────────────────────────────────────
            Text("Steps (${parsed.steps.size})",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))

            val stepsBySection = parsed.steps.groupBy { it.sectionId }
            stepsBySection.forEach { (sectionId, steps) ->
                if (sectionId != null) {
                    sectionMap[sectionId]?.let { section ->
                        Text(section.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                    }
                }
                steps.forEachIndexed { index, step ->
                    Row(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text("${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(28.dp).padding(end = 4.dp))
                        Text(step.instruction, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // ── Author attribution toggle (imports only) ──────────────────────
            if (onIsOwnRecipeChange != null) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "Who wrote this recipe?",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !isOwnRecipe,
                        onClick = { onIsOwnRecipeChange(false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = { SegmentedButtonDefaults.Icon(active = !isOwnRecipe) }
                    ) { Text("Imported") }
                    SegmentedButton(
                        selected = isOwnRecipe,
                        onClick = { onIsOwnRecipeChange(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = { SegmentedButtonDefaults.Icon(active = isOwnRecipe) }
                    ) { Text("My Recipe") }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (isOwnRecipe) "Your name will appear as the author if you share this."
                    else "\"Imported\" will appear as the author if you share this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Action buttons ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Secondary (Reimport / Reformat)
                OutlinedButton(
                    onClick = onSecondary,
                    enabled = !isSecondaryLoading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isSecondaryLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Working…")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(secondaryLabel)
                    }
                }

                // Primary (Confirm / Save)
                Button(
                    onClick = onPrimary,
                    enabled = !isSecondaryLoading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(primaryLabel)
                }
            }
        }
    }
}

// ── Info chip (shared) ────────────────────────────────────────────────────────

@Composable
internal fun InfoChip(text: String) {
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
