package com.aerion.chefsjournal.ui.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.chefsjournal.ChefsJournalApplication
import com.aerion.chefsjournal.data.remote.SocialRepository
import com.aerion.chefsjournal.domain.model.ReceivedRecipeSummary
import com.aerion.chefsjournal.ui.components.ChefsJournalTopBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ─── ViewModel ────────────────────────────────────────────────────────────────

data class SharedInboxUiState(
    /** Pending shares not yet saved — shown at the top as "In review". */
    val pending: List<ReceivedRecipeSummary> = emptyList(),
    /** Saved received recipes (cached references) — normal cards. */
    val saved: List<com.aerion.chefsjournal.domain.model.Recipe> = emptyList(),
    val isLoading: Boolean = true
) {
    val isEmpty: Boolean get() = pending.isEmpty() && saved.isEmpty()
}

class SharedInboxViewModel(
    private val socialRepository: SocialRepository,
    private val repository: com.aerion.chefsjournal.data.repository.RecipeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharedInboxUiState())
    val uiState: StateFlow<SharedInboxUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            socialRepository.getReceivedRecipesSummaryFlow().collect { items ->
                _uiState.update { it.copy(pending = items, isLoading = false) }
            }
        }
        viewModelScope.launch {
            repository.getReceivedRecipes().collect { recipes ->
                _uiState.update { it.copy(saved = recipes, isLoading = false) }
            }
        }
    }

    /** Dismiss a pending share (delete the pointer) — clears stale/unwanted shares. */
    fun dismissPending(shareId: String) {
        viewModelScope.launch { socialRepository.deleteReceivedPointer(shareId) }
    }

    companion object {
        fun factory(
            socialRepository: SocialRepository,
            repository: com.aerion.chefsjournal.data.repository.RecipeRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SharedInboxViewModel(socialRepository, repository) as T
            }
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedInboxScreen(
    onItemClick: (shareId: String) -> Unit,
    onSavedClick: (recipeId: String) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as ChefsJournalApplication
    val viewModel: SharedInboxViewModel = viewModel(
        factory = SharedInboxViewModel.factory(app.container.socialRepository, app.container.repository)
    )
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ChefsJournalTopBar(title = "Shared Recipes")

            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.isEmpty -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Inbox,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "No recipes shared with you yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "When a co-chef sends you a recipe, it will appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Pending shares — "In review", tap to review & save
                    items(state.pending, key = { "pending-${it.shareId}" }) { item ->
                        SharedRecipeCard(
                            item = item,
                            inReview = true,
                            onClick = { onItemClick(item.shareId) },
                            onDismiss = { viewModel.dismissPending(item.shareId) }
                        )
                    }
                    // Saved received recipes — open the normal (read-only) detail
                    items(state.saved, key = { "saved-${it.id}" }) { recipe ->
                        SavedReceivedCard(
                            recipe = recipe,
                            onClick = { onSavedClick(recipe.id) }
                        )
                    }
                }
            }
        }
    }
}

// ─── Saved received recipe card (read-only) ──────────────────────────────────

@Composable
private fun SavedReceivedCard(
    recipe: com.aerion.chefsjournal.domain.model.Recipe,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(recipe.title, style = MaterialTheme.typography.titleLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            val timeLabel = buildString {
                recipe.prepTimeMinutes?.let { append("Prep ${it}min") }
                if (recipe.prepTimeMinutes != null && recipe.cookTimeMinutes != null) append("  ·  ")
                recipe.cookTimeMinutes?.let { append("Cook ${it}min") }
            }
            if (timeLabel.isNotBlank()) {
                Text(timeLabel, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            if (recipe.tags.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(recipe.tags) { tag ->
                        SuggestionChip(onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            // Original author (v2 label rule: "Imported by X" handled by isImported)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.Person, contentDescription = null,
                    modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (recipe.isImported) "Imported by ${recipe.authorDisplayName ?: "Unknown"}"
                    else (recipe.authorDisplayName ?: "Unknown"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── Card — same visual language as My Recipes cards ─────────────────────────

@Composable
private fun SharedRecipeCard(
    item: ReceivedRecipeSummary,
    inReview: Boolean = false,
    onClick: () -> Unit,
    onDismiss: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (inReview) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            "In review — tap to save",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (onDismiss != null) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onDismiss)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                item.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))

            // Times
            val timeLabel = buildString {
                item.prepTimeMinutes?.let { append("Prep ${it}min") }
                if (item.prepTimeMinutes != null && item.cookTimeMinutes != null) append("  ·  ")
                item.cookTimeMinutes?.let { append("Cook ${it}min") }
            }
            if (timeLabel.isNotBlank()) {
                Text(
                    timeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            // Tags
            if (item.tags.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(item.tags) { tag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // Author + "from" + timestamp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    item.authorDisplayName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Only show "sent by X" if sender differs from original author
                if (item.fromDisplayName != item.authorDisplayName) {
                    Text(
                        "· from ${item.fromDisplayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    formatRelativeTime(item.sharedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

private fun formatRelativeTime(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> "${diff / 86_400_000}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
    }
}
