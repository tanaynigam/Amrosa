package com.aerion.amrosa.ui.account

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(onSignInClick: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as AmrosaApplication
    val viewModel: AccountViewModel = viewModel(
        factory = AccountViewModel.factory(
            authRepository = app.container.authRepository,
            repository = app.container.repository,
            context = context
        )
    )
    val state by viewModel.uiState.collectAsState()
    var showSignOutDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {

            // ── Profile card ──────────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
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
