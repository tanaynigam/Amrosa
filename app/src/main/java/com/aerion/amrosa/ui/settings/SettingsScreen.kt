package com.aerion.amrosa.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.data.local.AmrosaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AmrosaApplication
    val syncService = app.container.syncService
    val repository = app.container.repository
    val scope = rememberCoroutineScope()

    val syncPrefs = context.getSharedPreferences("amrosa_sync", Context.MODE_PRIVATE)
    var lastSyncTimestamp by remember { mutableLongStateOf(syncPrefs.getLong("last_sync_timestamp", 0L)) }
    var recipeCount by remember { mutableIntStateOf(0) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncResult by remember { mutableStateOf<String?>(null) }

    // Load recipe count on first composition
    LaunchedEffect(Unit) {
        recipeCount = withContext(Dispatchers.IO) { repository.count() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState())
        ) {
            // ── Cloud Sync ──
            SettingsSectionHeader("Cloud Sync")

            // Last synced
            SettingsRow(
                label = "Last synced",
                value = if (lastSyncTimestamp > 0L) formatTimestamp(lastSyncTimestamp) else "Never"
            )

            // Recipes in cloud
            SettingsRow(
                label = "Local recipes",
                value = "$recipeCount"
            )

            // Force sync button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Force full sync",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Re-download all recipes from the cloud",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    FilledTonalButton(
                        onClick = {
                            isSyncing = true
                            syncResult = null
                            scope.launch {
                                val count = withContext(Dispatchers.IO) {
                                    syncService.forceFullSync()
                                }
                                lastSyncTimestamp = syncPrefs.getLong("last_sync_timestamp", 0L)
                                recipeCount = withContext(Dispatchers.IO) { repository.count() }
                                syncResult = if (count > 0) {
                                    "Synced $count recipe${if (count != 1) "s" else ""}"
                                } else {
                                    "Already up to date"
                                }
                                isSyncing = false
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sync Now")
                    }
                }
            }

            // Sync result feedback
            syncResult?.let { result ->
                Text(
                    result,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ── Database ──
            SettingsSectionHeader("Database")

            SettingsRow(
                label = "Database version",
                value = "${AmrosaDatabase.DB_VERSION}"
            )

            SettingsRow(
                label = "Storage",
                value = "Room (SQLite) + Firebase Firestore"
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ── About ──
            SettingsSectionHeader("About")

            SettingsRow(label = "App", value = "Amrosa")
            SettingsRow(label = "Version", value = "1.0")
            SettingsRow(label = "Company", value = "Aerion")

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

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SettingsRow(label: String, value: String) {
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

private fun formatTimestamp(millis: Long): String {
    val formatter = SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault())
    return formatter.format(Date(millis))
}
