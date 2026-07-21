package com.aerion.chefsjournal

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.aerion.chefsjournal.di.AppContainer
import com.aerion.chefsjournal.service.ChefsJournalMessagingService
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ChefsJournalApplication : Application() {

    lateinit var container: AppContainer

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Navigation target requested by tapping a push notification.
     * Set by MainActivity from the launch/new intent; consumed by the nav graph.
     */
    val pendingDeepLink = MutableStateFlow<String?>(null)

    companion object {
        /** Maps a notification's (type, shareId) to an in-app route, or null if none. */
        fun routeFor(type: String?, shareId: String?): String? = when (type) {
            "recipe_shared" -> shareId?.let { "received/$it" }
            "follow_request", "follow_accepted" -> "account_tab"
            else -> null
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannel()
        appScope.launch {
            // Whenever the user is signed in with a real account (on app start or after sign-in),
            // pull their recipes from Firestore. (No seeded/"official" recipes in v2.)
            container.authRepository.authStateFlow().collect { user ->
                if (user != null && !user.isAnonymous) {
                    container.syncService.syncPersonalRecipes()
                    // Refresh Tab 2 received recipes from their canonical public mirrors
                    container.syncService.syncReceivedRecipes()
                    // Upsert public profile so other users can find / follow this user
                    container.socialRepository.upsertProfile()
                    // Register FCM token so we can receive push notifications
                    refreshFcmToken()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ChefsJournalMessagingService.CHANNEL_ID,
                "Chef's Journal",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Follow requests and recipe shares"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private suspend fun refreshFcmToken() {
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            container.socialRepository.updateFcmToken(token)
        } catch (e: Exception) {
            // Non-fatal — token will be stored next time onNewToken fires
        }
    }
}
