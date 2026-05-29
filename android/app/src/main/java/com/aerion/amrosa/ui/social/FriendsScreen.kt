package com.aerion.amrosa.ui.social

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.People
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
import com.aerion.amrosa.domain.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─── ViewModel ────────────────────────────────────────────────────────────────

data class FriendsUiState(
    val friends: List<UserProfile> = emptyList(),
    val isLoading: Boolean = true,
    val removingUid: String? = null
)

class FriendsViewModel(
    private val socialRepository: SocialRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FriendsUiState())
    val uiState: StateFlow<FriendsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            socialRepository.getFriendsFlow().collect { friends ->
                _uiState.update { it.copy(friends = friends, isLoading = false) }
            }
        }
    }

    fun removeFriend(uid: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(removingUid = uid) }
            socialRepository.unfriend(uid)
            _uiState.update { it.copy(removingUid = null) }
        }
    }

    companion object {
        fun factory(socialRepository: SocialRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    FriendsViewModel(socialRepository) as T
            }
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AmrosaApplication
    val viewModel: FriendsViewModel = viewModel(
        factory = FriendsViewModel.factory(app.container.socialRepository)
    )
    val state by viewModel.uiState.collectAsState()
    var confirmRemoveUid by remember { mutableStateOf<String?>(null) }

    // Confirm unfriend dialog
    confirmRemoveUid?.let { uid ->
        val friend = state.friends.find { it.uid == uid }
        AlertDialog(
            onDismissRequest = { confirmRemoveUid = null },
            title = { Text("Remove Co-Chef?") },
            text = { Text("Remove ${friend?.displayName ?: "this person"} from your co-chefs?") },
            confirmButton = {
                Button(
                    onClick = { viewModel.removeFriend(uid); confirmRemoveUid = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveUid = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Co-Chefs") },
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
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (state.friends.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Default.People,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "No co-chefs yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Add co-chefs from the Account page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(state.friends, key = { it.uid }) { friend ->
                FriendRow(
                    friend = friend,
                    isRemoving = state.removingUid == friend.uid,
                    onRemove = { confirmRemoveUid = friend.uid }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun FriendRow(
    friend: UserProfile,
    isRemoving: Boolean,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with initial
        Surface(
            modifier = Modifier.size(44.dp).clip(CircleShape),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    friend.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = friend.displayName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )

        if (isRemoving) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            OutlinedButton(
                onClick = onRemove,
                shape = RoundedCornerShape(8.dp)
            ) { Text("Remove") }
        }
    }
}
