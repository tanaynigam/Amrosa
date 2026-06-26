package com.aerion.chefslist.ui.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.aerion.chefslist.ChefsListApplication
import com.aerion.chefslist.data.auth.AuthRepository
import com.aerion.chefslist.data.remote.SocialRepository
import com.aerion.chefslist.domain.model.UserProfile
import com.aerion.chefslist.ui.components.CompactSearchField
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ─── ViewModel ────────────────────────────────────────────────────────────────

data class UserSearchUiState(
    val query: String = "",
    val results: List<UserProfile> = emptyList(),
    val isSearching: Boolean = false,
    /** uid → "none" | "pending" | "accepted". Updated as requests are sent/cancelled. */
    val followStatus: Map<String, String> = emptyMap(),
    /** uid of any in-flight action. */
    val pendingAction: String? = null
)

@OptIn(FlowPreview::class)
class UserSearchViewModel(
    private val socialRepository: SocialRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserSearchUiState())
    val uiState: StateFlow<UserSearchUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queryFlow
                .debounce(300)
                .distinctUntilChanged()
                .collect { q ->
                    if (q.isBlank()) {
                        _uiState.update { it.copy(results = emptyList(), isSearching = false) }
                        return@collect
                    }
                    _uiState.update { it.copy(isSearching = true) }
                    val results = socialRepository.searchUsers(q)
                    // Pre-populate follow statuses for the results
                    val statuses = results.associate { user ->
                        user.uid to socialRepository.getFollowStatus(user.uid)
                    }
                    _uiState.update { it.copy(results = results, followStatus = statuses, isSearching = false) }
                }
        }
    }

    fun onQueryChange(q: String) {
        _uiState.update { it.copy(query = q) }
        queryFlow.value = q
    }

    fun sendFollowRequest(target: UserProfile) {
        viewModelScope.launch {
            _uiState.update { it.copy(pendingAction = target.uid) }
            socialRepository.sendFollowRequest(target.uid, target.displayName)
            _uiState.update { state ->
                state.copy(
                    followStatus = state.followStatus + (target.uid to "pending"),
                    pendingAction = null
                )
            }
        }
    }

    fun unfriend(target: UserProfile) {
        viewModelScope.launch {
            _uiState.update { it.copy(pendingAction = target.uid) }
            socialRepository.unfriend(target.uid)
            _uiState.update { state ->
                state.copy(
                    followStatus = state.followStatus + (target.uid to "none"),
                    pendingAction = null
                )
            }
        }
    }

    companion object {
        fun factory(
            socialRepository: SocialRepository,
            authRepository: AuthRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    UserSearchViewModel(socialRepository, authRepository) as T
            }
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSearchScreen(
    onBack: () -> Unit,
    onProfileClick: (uid: String, name: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val app = context.applicationContext as ChefsListApplication
    val viewModel: UserSearchViewModel = viewModel(
        factory = UserSearchViewModel.factory(
            socialRepository = app.container.socialRepository,
            authRepository = app.container.authRepository
        )
    )
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find People") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Search field ──────────────────────────────────────────────────
            CompactSearchField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = "Find chefs by name or email…",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // ── Results ───────────────────────────────────────────────────────
            if (state.isSearching) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.results.isEmpty() && state.query.isNotBlank()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No users found for \"${state.query}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn {
                    items(state.results, key = { it.uid }) { user ->
                        UserSearchRow(
                            user = user,
                            followStatus = state.followStatus[user.uid] ?: "none",
                            isLoading = state.pendingAction == user.uid,
                            onFollow = { viewModel.sendFollowRequest(user) },
                            onUnfollow = { viewModel.unfriend(user) },
                            onClick = { onProfileClick(user.uid, user.displayName) }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun UserSearchRow(
    user: UserProfile,
    followStatus: String,
    isLoading: Boolean,
    onFollow: () -> Unit,
    onUnfollow: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Surface(
            modifier = Modifier.size(44.dp).clip(CircleShape),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    user.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.bodyLarge
            )
            if (!user.email.isNullOrBlank()) {
                Text(
                    text = user.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            when (followStatus) {
                "accepted" -> OutlinedButton(
                    onClick = onUnfollow,
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Co-Chef ✓") }

                "pending" -> OutlinedButton(
                    onClick = onUnfollow,
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Requested") }

                else -> Button(
                    onClick = onFollow,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Co-Chef")
                }
            }
        }
    }
}
