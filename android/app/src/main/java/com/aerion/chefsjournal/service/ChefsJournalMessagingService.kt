package com.aerion.chefsjournal.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aerion.chefsjournal.MainActivity
import com.aerion.chefsjournal.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ChefsJournalMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called when a new FCM token is generated (first launch, or after token is invalidated).
     * Stores it in Firestore so Cloud Functions can send notifications to this device.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null || user.isAnonymous) return
        serviceScope.launch {
            try {
                // Private subdoc — owner-only readable; CF reads via Admin SDK.
                FirebaseFirestore.getInstance()
                    .collection("users").document(user.uid)
                    .collection("private").document("push")
                    .set(mapOf("fcmToken" to token, "updatedAt" to System.currentTimeMillis()))
                    .await()
                Log.d(TAG, "FCM token updated for uid=${user.uid}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to store FCM token", e)
            }
        }
    }

    /**
     * Called when a message is received while the app is in the foreground.
     * Background messages with a notification payload are displayed automatically by the OS.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body  = message.notification?.body  ?: message.data["body"]  ?: ""
        showNotification(title, body, message.data["type"], message.data["shareId"])
    }

    private fun showNotification(title: String, body: String, type: String?, shareId: String?) {
        // Tapping the notification opens the app and routes to the relevant screen
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            type?.let { putExtra("type", it) }
            shareId?.let { putExtra("shareId", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        private const val TAG = "ChefsJournalFCM"
        const val CHANNEL_ID = "chefsjournal_social"
    }
}
