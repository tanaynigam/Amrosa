package com.aerion.amrosa.ui.social

import androidx.compose.foundation.background
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
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.data.remote.SocialRepository
import com.aerion.amrosa.domain.model.SocialNotification
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ─── ViewModel ────────────────────────────────────────────────────────────────

data class NotificationsUiState(
    val notifications: List<SocialNotification> = emptyList(),
    val isLoading: Boolean = true
)

class NotificationsViewModel(
    private val socialRepository: SocialRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            socialRepository.getNotificationsFlow().collect { notifs ->
                _uiState.update { it.copy(notifications = notifs, isLoading = false) }
            }
        }
    }

    fun markRead(notifId: String) {
        viewModelScope.launch { socialRepository.markNotificationRead(notifId) }
    }

    fun markAllRead() {
        viewModelScope.launch { socialRepository.markAllNotificationsRead() }
    }

    companion object {
        fun factory(socialRepository: SocialRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NotificationsViewModel(socialRepository) as T
            }
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

/**
 * @param onBack back nav
 * @param onFollowNotification navigate to Account tab to handle follow request
 * @param onRecipeShared navigate to ReceivedRecipeScreen with the shareId
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onFollowNotification: () -> Unit,
    onRecipeShared: (shareId: String) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AmrosaApplication
    val viewModel: NotificationsViewModel = viewModel(
        factory = NotificationsViewModel.factory(app.container.socialRepository)
    )
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (state.notifications.any { !it.read }) {
                        TextButton(onClick = viewModel::markAllRead) {
                            Text("Mark all read")
                        }
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

        if (state.notifications.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "No notifications yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(state.notifications, key = { it.id }) { notif ->
                NotificationRow(
                    notif = notif,
                    onClick = {
                        if (!notif.read) viewModel.markRead(notif.id)
                        when (notif.type) {
                            "follow_request", "follow_accepted" -> onFollowNotification()
                            "recipe_shared" -> notif.shareId?.let { onRecipeShared(it) }
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun NotificationRow(
    notif: SocialNotification,
    onClick: () -> Unit
) {
    val (icon, label) = when (notif.type) {
        "follow_request" -> Icons.Default.PersonAdd to "sent you a follow request"
        "follow_accepted" -> Icons.Default.People to "accepted your follow request"
        "recipe_shared" -> Icons.Default.Dining to "shared a recipe with you"
        else -> Icons.Default.Notifications to "sent you a notification"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (!notif.read) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon circle
        Surface(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape),
            color = if (!notif.read)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (!notif.read)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            // "Name" in bold + label text
            Text(
                buildAnnotatedString {
                    val bold = androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)
                    pushStyle(bold)
                    append(notif.fromDisplayName)
                    pop()
                    append(" $label")
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (notif.type == "recipe_shared" && notif.recipeName != null) {
                Text(
                    "\"${notif.recipeName}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                formatRelative(notif.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!notif.read) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

private fun buildAnnotatedString(block: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit) =
    androidx.compose.ui.text.buildAnnotatedString(block)

private fun formatRelative(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> "${diff / 86_400_000}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
    }
}
