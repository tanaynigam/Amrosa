package com.aerion.amrosa.ui.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.data.remote.SocialRepository
import com.aerion.amrosa.domain.model.ProfileRecipeSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─── ViewModel ────────────────────────────────────────────────────────────────

data class ProfileUiState(
    val recipes: List<ProfileRecipeSummary> = emptyList(),
    val isLoading: Boolean = true,
    /** True when the viewer is an accepted co-chef of this profile (sees friends + public). */
    val isCoChef: Boolean = false,
)

class ProfileViewModel(
    private val socialRepository: SocialRepository,
    private val authorUid: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Co-chefs see the author's friends + public recipes; everyone else sees public only.
            // (The Firestore read rule enforces this — query the matching tier set.)
            val accepted = socialRepository.getFollowStatus(authorUid) == "accepted"
            val recipes = socialRepository.getAuthorRecipes(authorUid, includeFriendsOnly = accepted)
            _uiState.update { it.copy(recipes = recipes, isLoading = false, isCoChef = accepted) }
        }
    }

    companion object {
        fun factory(socialRepository: SocialRepository, authorUid: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ProfileViewModel(socialRepository, authorUid) as T
            }
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

/**
 * A co-chef's profile: their avatar/name and the recipes they've shared with co-chefs (+ public).
 * Tap a recipe to open it in review mode → "Add to Shared tab".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    uid: String,
    displayName: String,
    onBack: () -> Unit,
    onRecipeClick: (recipeId: String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as AmrosaApplication
    val viewModel: ProfileViewModel = viewModel(
        key = "profile-$uid",
        factory = ProfileViewModel.factory(app.container.socialRepository, uid)
    )
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── Profile header ──────────────────────────────────────────
            item(key = "header") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(72.dp).clip(CircleShape),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Text(displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(if (state.isCoChef) "Co-Chef" else "Chef",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            if (state.isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.recipes.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.isCoChef) "$displayName hasn't shared any recipes with co-chefs yet."
                            else "$displayName has no public recipes yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                item(key = "recipes_header") {
                    Text(
                        "Recipes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(state.recipes, key = { it.recipeId }) { r ->
                    ProfileRecipeCard(summary = r, onClick = { onRecipeClick(r.recipeId) })
                }
            }
        }
    }
}

@Composable
private fun ProfileRecipeCard(summary: ProfileRecipeSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(summary.title, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                // Tier badge
                val (badgeIcon, badgeText) = if (summary.visibility == "public")
                    Icons.Default.Public to "Public" else Icons.Default.People to "Co-Chefs"
                Icon(badgeIcon, contentDescription = null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(badgeText, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (summary.prepTimeMinutes != null || summary.cookTimeMinutes != null || summary.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    summary.prepTimeMinutes?.let {
                        Text("${it}m prep", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    summary.cookTimeMinutes?.let {
                        Text("${it}m cook", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (summary.tags.isNotEmpty()) {
                        Text(summary.tags.take(3).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1)
                    }
                }
            }
        }
    }
}
