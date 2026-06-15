package com.aerion.amrosa.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.data.local.AmrosaDatabase
import com.aerion.amrosa.domain.model.UserProfile
import com.aerion.amrosa.ui.components.AmrosaTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AccountScreen(
    onSignInClick: () -> Unit = {},
    onFindPeopleClick: () -> Unit = {},
    onFriendsClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as AmrosaApplication
    val viewModel: AccountViewModel = viewModel(
        factory = AccountViewModel.factory(
            authRepository = app.container.authRepository,
            repository = app.container.repository,
            socialRepository = app.container.socialRepository,
            context = context
        )
    )
    val state by viewModel.uiState.collectAsState()
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar when name update completes
    LaunchedEffect(state.nameUpdateMessage) {
        state.nameUpdateMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearNameUpdateMessage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AmrosaTopBar(title = "Account")

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {

            // ── Profile card ──────────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .then(
                        if (!state.isAnonymous) Modifier.clickable { showEditNameDialog = true }
                        else Modifier
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape),
                        color = if (state.isAnonymous)
                            MaterialTheme.colorScheme.surfaceVariant
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (state.isAnonymous) Icons.Default.PersonOutline
                                else Icons.Default.Person,
                                contentDescription = null,
                                tint = if (state.isAnonymous)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else
                                    MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        if (state.isAnonymous) {
                            Text(
                                "Not signed in",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Sign in to back up and sync your recipes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                state.user?.displayName ?: "Signed in",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            state.user?.email?.let { email ->
                                Text(
                                    email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            state.user?.phoneNumber?.let { phone ->
                                if (phone.isNotEmpty()) {
                                    Text(
                                        phone,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                "Tap to edit name",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // ── Sign-in / Sign-out ────────────────────────────────────────────
            if (state.isAnonymous) {
                Button(
                    onClick = onSignInClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign In / Create Account", fontWeight = FontWeight.SemiBold)
                }
            } else {
                OutlinedButton(
                    onClick = { showSignOutDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign Out")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // ── Co-Chefs ──────────────────────────────────────────────────────
            if (!state.isAnonymous) {
                AccountSectionHeader("Co-Chefs")

                // Pending co-chef requests
                if (state.pendingRequests.isNotEmpty()) {
                    state.pendingRequests.forEach { requester ->
                        PendingRequestCard(
                            profile = requester,
                            isActioning = state.pendingFollowAction == requester.uid,
                            onAccept = { viewModel.acceptFollowRequest(requester) },
                            onDecline = { viewModel.declineFollowRequest(requester) }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // Co-Chefs count (tappable → co-chefs list)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onFriendsClick)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Co-Chefs", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${state.friendCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Find Co-Chefs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PersonSearch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        onClick = onFindPeopleClick,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            "Find Co-Chefs",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            // ── Recipe preferences (Discover affinity) ────────────────────────
            if (state.availableCuisines.isNotEmpty()) {
                AccountSectionHeader("Recipe preferences")
                Text(
                    "Pick cuisines you love — Discover recommends more of them. Leave empty to learn from your recipes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.availableCuisines.forEach { cuisine ->
                        FilterChip(
                            selected = cuisine.lowercase() in state.selectedCuisines,
                            onClick = { viewModel.toggleCuisine(cuisine) },
                            label = { Text(cuisine) }
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            // ── Ingredient display preference ─────────────────────────────────
            AccountSectionHeader("Ingredients")
            var includeOptionals by remember { mutableStateOf(app.container.userPreferences.includeOptionalsByDefault()) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Include optional ingredients by default",
                        style = MaterialTheme.typography.bodyLarge)
                    Text("When on, a recipe's optional ingredients start included; turn off to opt in per recipe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = includeOptionals,
                    onCheckedChange = {
                        includeOptionals = it
                        app.container.userPreferences.setIncludeOptionalsByDefault(it)
                    }
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // ── Sync & Storage ────────────────────────────────────────────────
            AccountSectionHeader("Sync & Storage")
            AccountRow("Recipes on this device", "${state.recipeCount}")
            AccountRow(
                "Last synced",
                if (state.lastSyncTimestamp > 0L) formatTimestamp(state.lastSyncTimestamp)
                else "Never"
            )
            AccountRow(
                "Backup",
                if (state.isAnonymous) "Sign in to enable" else "Active"
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // ── Database ──────────────────────────────────────────────────────
            AccountSectionHeader("Database")
            AccountRow("Version", "${AmrosaDatabase.DB_VERSION}")
            AccountRow("Storage", "Room (SQLite) + Firestore")

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // ── About ─────────────────────────────────────────────────────────
            AccountSectionHeader("About")
            AccountRow("App", "Amrosa")
            AccountRow("Version", "1.0")
            AccountRow("Company", "Aerion")

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Amrita & Ambrosia — exquisite recipes, deeply personal.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // ── Edit name dialog ──────────────────────────────────────────────────────
    if (showEditNameDialog) {
        var nameInput by remember { mutableStateOf(state.user?.displayName ?: "") }
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
            title = { Text("Edit Name") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateDisplayName(nameInput)
                        showEditNameDialog = false
                    },
                    enabled = nameInput.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ── Sign-out dialog ───────────────────────────────────────────────────────
    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
            title = { Text("Sign Out") },
            text = {
                Text("All recipes will be removed from this device. They'll sync back automatically when you sign in again.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.signOut(); showSignOutDialog = false }) {
                    Text("Sign Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ── Pending follow request card ───────────────────────────────────────────────

@Composable
private fun PendingRequestCard(
    profile: UserProfile,
    isActioning: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar initial
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        profile.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    profile.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "wants to be co-chefs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isActioning) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onAccept, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Accept",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDecline, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Decline",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

// ── Shared composables ────────────────────────────────────────────────────────

@Composable
private fun AccountSectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun AccountRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault()).format(Date(millis))
