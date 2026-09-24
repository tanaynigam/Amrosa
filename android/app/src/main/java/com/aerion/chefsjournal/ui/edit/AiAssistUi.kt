package com.aerion.chefsjournal.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * F22 Surface 1 — the AI prompt bar, pinned to the bottom of the detail screen in edit mode.
 *
 * Pinned rather than inline on purpose: a prompt box you have to scroll to find is the same
 * mistake as "Format with Gemini" sitting below a long text area.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPromptBar(
    isThinking: Boolean,
    suggestions: List<String>,
    message: String?,
    onSend: (String) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit() {
        val t = text.trim()
        if (t.length < 2 || isThinking) return
        keyboard?.hide()
        onSend(t)
        text = ""
    }

    Surface(tonalElevation = 3.dp, modifier = modifier) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {

            if (message != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismissMessage, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, "Dismiss", Modifier.size(16.dp))
                    }
                }
            }

            // Example chips — the cure for the blank-box freeze. Hidden once the user types.
            if (text.isBlank() && !isThinking && suggestions.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                ) {
                    suggestions.forEach { s ->
                        SuggestionChip(onClick = { text = s }, label = { Text(s, maxLines = 1) })
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(1000) },
                    modifier = Modifier.weight(1f),
                    enabled = !isThinking,
                    placeholder = { Text("Ask for a change…") },
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(20.dp)) },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                Spacer(Modifier.width(8.dp))
                if (isThinking) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                } else {
                    FilledIconButton(onClick = { submit() }, enabled = text.trim().length >= 2) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

/**
 * The review sheet — the trust mechanism of the whole feature. Every change is shown as
 * before → after with a plain-language reason, and nothing is applied until the user taps
 * Apply. Rows can be individually unticked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChangesSheet(
    changes: List<AiChange>,
    notes: String?,
    onToggle: (Int) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val acceptedCount = changes.count { it.accepted }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {

            Text(
                if (changes.isEmpty()) "No changes to make" else "Review changes",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(4.dp))
            if (changes.isNotEmpty()) {
                Text(
                    "Nothing is saved until you tap Apply, then Save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (notes != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (changes.isEmpty()) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                return@Column
            }

            Spacer(Modifier.height(12.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                changes.forEachIndexed { index, change ->
                    AiChangeCard(change) { onToggle(index) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = onApply,
                    enabled = acceptedCount > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (acceptedCount == changes.size) "Apply all" else "Apply $acceptedCount")
                }
            }
        }
    }
}

@Composable
private fun AiChangeCard(change: AiChange, onToggle: () -> Unit) {
    val dim = !change.accepted
    val accent = when (change.kind) {
        AiChangeKind.ADD -> MaterialTheme.colorScheme.primary
        AiChangeKind.DELETE -> MaterialTheme.colorScheme.error
        AiChangeKind.UPDATE -> MaterialTheme.colorScheme.secondary
    }
    val icon = when (change.kind) {
        AiChangeKind.ADD -> Icons.Default.Add
        AiChangeKind.DELETE -> Icons.Default.Delete
        AiChangeKind.UPDATE -> Icons.Default.Edit
    }

    OutlinedCard(
        onClick = onToggle,
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (dim) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                             else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (dim) MaterialTheme.colorScheme.outline else accent,
                modifier = Modifier.size(18.dp).padding(top = 2.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    change.target,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (dim) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))

                if (change.before.isNotBlank()) {
                    Text(
                        change.before,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = if (change.kind == AiChangeKind.DELETE) TextDecoration.LineThrough
                                         else TextDecoration.None,
                    )
                }
                if (change.after.isNotBlank()) {
                    if (change.before.isNotBlank()) {
                        Text(
                            "↓",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text(
                        change.after,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    change.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(checked = change.accepted, onCheckedChange = { onToggle() })
        }
    }
}
