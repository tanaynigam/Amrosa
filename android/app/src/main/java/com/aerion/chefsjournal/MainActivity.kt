package com.aerion.chefsjournal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aerion.chefsjournal.navigation.ChefsJournalNavGraph
import com.aerion.chefsjournal.ui.theme.ChefsJournalTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotificationIntent(intent)
        setContent {
            ChefsJournalTheme {
                ChefsJournalNavGraph()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    /**
     * When the app is launched/resumed by tapping a push notification, FCM puts the
     * Cloud Function's data payload (type, shareId) into the intent extras. Map it to
     * an in-app route and hand it to the nav graph to navigate.
     */
    private fun handleNotificationIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        val type = extras.getString("type")
        val shareId = extras.getString("shareId")?.ifBlank { null }
        val route = ChefsJournalApplication.routeFor(type, shareId) ?: return
        (application as ChefsJournalApplication).pendingDeepLink.value = route
    }
}
