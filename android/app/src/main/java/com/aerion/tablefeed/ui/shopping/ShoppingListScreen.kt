package com.aerion.tablefeed.ui.shopping

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.tablefeed.TablefeedApplication
import com.aerion.tablefeed.ui.util.UnitMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(
    recipeId: String,
    initialServings: Int?,
    initialAnchorQty: Double?,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as TablefeedApplication
    val vm: ShoppingListViewModel = viewModel(
        key = "shopping-$recipeId",
        factory = ShoppingListViewModel.factory(
            app.container.repository, recipeId, initialServings, initialAnchorQty
        )
    )
    val state by vm.uiState.collectAsState()
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset checklist?") },
            text = { Text("Uncheck every item on this shopping list.") },
            confirmButton = {
                TextButton(onClick = { vm.resetChecks(); showResetDialog = false }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shopping List") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.checkedKeys.isNotEmpty()) {
                        TextButton(onClick = { showResetDialog = true }) { Text("Reset") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val recipe = state.recipe
        if (recipe == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Recipe not found")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── Recipe + yield + unit toggle ──────────────────────────
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(recipe.title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Yield adjuster
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (state.usesAnchorScaling) "Batch " else "Yield ",
                                style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = { vm.adjustScale(-1) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Remove, contentDescription = "Less", modifier = Modifier.size(18.dp))
                            }
                            Text(state.yieldDisplay, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.widthIn(min = 28.dp))
                            IconButton(onClick = { vm.adjustScale(1) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Add, contentDescription = "More", modifier = Modifier.size(18.dp))
                            }
                        }
                        // Unit toggle
                        if (state.hasConversions) {
                            SingleChoiceSegmentedButtonRow {
                                UnitMode.entries.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = state.selectedUnit == mode,
                                        onClick = { vm.setUnit(mode) },
                                        shape = SegmentedButtonDefaults.itemShape(index = index, count = UnitMode.entries.size),
                                        label = {
                                            Text(
                                                when (mode) {
                                                    UnitMode.ORIGINAL -> "Orig"
                                                    UnitMode.METRIC -> "Metric"
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
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            // ── Combined checklist ────────────────────────────────────
            items(state.lines, key = { it.key }) { line ->
                val checked = line.key in state.checkedKeys
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Checkbox(checked = checked, onCheckedChange = { vm.toggle(line.key) })
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f).padding(top = 12.dp)) {
                        val deco = if (checked) TextDecoration.LineThrough else null
                        val color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface
                        Text(
                            buildString {
                                append(line.name)
                                if (line.quantity.isNotBlank()) append("  —  ${line.quantity}")
                            },
                            style = MaterialTheme.typography.bodyLarge.copy(textDecoration = deco),
                            color = color
                        )
                        line.note?.let {
                            Text(
                                "💡 $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }

            if (state.lines.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No ingredients", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
