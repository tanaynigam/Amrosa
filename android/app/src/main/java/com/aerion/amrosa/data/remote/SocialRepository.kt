package com.aerion.amrosa.data.remote

import android.util.Log
import com.aerion.amrosa.data.auth.AuthRepository
import com.aerion.amrosa.domain.model.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Manages all social Firestore collections:
 *
 *   users/{uid}                            — public user profiles
 *   follows/{followerId}_{followeeId}      — follow relationships (pending / accepted)
 *   notifications/{uid}/items/{notifId}    — per-user notification inbox
 *   shared_to/{recipientUid}/recipes/{shareId} — directly shared recipe data
 *
 * Composite indexes required (Firestore will log a link to create them on first run):
 *   follows: (followeeId ASC, status ASC)
 *   follows: (followerId ASC, status ASC)
 *   notifications/{uid}/items: (read ASC)
 */
class SocialRepository(
    private val authRepository: AuthRepository
) {
    private val firestore = FirebaseFirestore.getInstance()

    companion object {
        private const val TAG = "SocialRepository"
        private const val COL_USERS = "users"
        private const val COL_FOLLOWS = "follows"
        private const val COL_NOTIFICATIONS = "notifications"
        private const val COL_SHARED_TO = "shared_to"
    }

    // ── User profile ───────────────────────────────────────────────────────────

    /**
     * Create or merge the current user's public profile.
     * Called on every sign-in so display name changes propagate.
     */
    suspend fun upsertProfile() {
        val user = authRepository.currentUser ?: return
        if (user.isAnonymous) return
        try {
            firestore.collection(COL_USERS).document(user.uid).set(
                mapOf(
                    "displayName" to (user.displayName ?: user.email ?: "User"),
                    "photoUrl" to user.photoUrl?.toString(),
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            Log.e(TAG, "upsertProfile failed", e)
        }
    }

    // ── User search ────────────────────────────────────────────────────────────

    /**
     * Search users whose display name starts with [query].
     * Excludes the current user. Returns up to 20 results.
     */
    suspend fun searchUsers(query: String): List<UserProfile> {
        if (query.isBlank()) return emptyList()
        val uid = authRepository.uid ?: return emptyList()
        return try {
            val snapshot = firestore.collection(COL_USERS)
                .whereGreaterThanOrEqualTo("displayName", query)
                .whereLessThanOrEqualTo("displayName", query + "")
                .limit(20)
                .get().await()
            snapshot.documents
                .filter { it.id != uid }
                .mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    UserProfile(
                        uid = doc.id,
                        displayName = data["displayName"] as? String ?: return@mapNotNull null,
                        photoUrl = data["photoUrl"] as? String,
                        createdAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                    )
                }
        } catch (e: Exception) {
            Log.e(TAG, "searchUsers failed", e)
            emptyList()
        }
    }

    // ── Follows ────────────────────────────────────────────────────────────────

    private fun followDocId(followerId: String, followeeId: String) =
        "${followerId}_${followeeId}"

    /**
     * Send a follow request to [targetUid].
     * Creates a pending follow doc and delivers a notification to the target.
     */
    suspend fun sendFollowRequest(targetUid: String, targetName: String) {
        val user = authRepository.currentUser ?: return
        if (user.isAnonymous) return
        val docId = followDocId(user.uid, targetUid)
        try {
            firestore.collection(COL_FOLLOWS).document(docId).set(
                mapOf(
                    "followerId" to user.uid,
                    "followerName" to (user.displayName ?: user.email ?: "User"),
                    "followeeId" to targetUid,
                    "followeeName" to targetName,
                    "status" to "pending",
                    "createdAt" to System.currentTimeMillis()
                )
            ).await()
            deliverNotification(
                toUid = targetUid,
                type = "follow_request",
                fromDisplayName = user.displayName ?: user.email ?: "Someone"
            )
        } catch (e: Exception) {
            Log.e(TAG, "sendFollowRequest failed", e)
        }
    }

    /**
     * Accept a pending follow request from [fromUid].
     * Marks the follow as accepted and notifies the requester.
     */
    suspend fun acceptFollowRequest(fromUid: String) {
        val uid = authRepository.uid ?: return
        val docId = followDocId(fromUid, uid)
        try {
            firestore.collection(COL_FOLLOWS).document(docId)
                .update(mapOf("status" to "accepted"))
                .await()
            deliverNotification(
                toUid = fromUid,
                type = "follow_accepted",
                fromDisplayName = authRepository.currentUser?.displayName
                    ?: authRepository.email ?: "Someone"
            )
            // Auto-read any follow_request notifications from this user
            markFollowRequestsRead(fromUid)
        } catch (e: Exception) {
            Log.e(TAG, "acceptFollowRequest failed", e)
        }
    }

    /**
     * Decline (and delete) a pending follow request from [fromUid].
     */
    suspend fun declineFollowRequest(fromUid: String) {
        val uid = authRepository.uid ?: return
        try {
            firestore.collection(COL_FOLLOWS)
                .document(followDocId(fromUid, uid))
                .delete().await()
            markFollowRequestsRead(fromUid)
        } catch (e: Exception) {
            Log.e(TAG, "declineFollowRequest failed", e)
        }
    }

    /** Stop following [targetUid]. */
    suspend fun unfollow(targetUid: String) {
        val uid = authRepository.uid ?: return
        try {
            firestore.collection(COL_FOLLOWS)
                .document(followDocId(uid, targetUid))
                .delete().await()
        } catch (e: Exception) {
            Log.e(TAG, "unfollow failed", e)
        }
    }

    /**
     * Returns the follow status from current user → [targetUid].
     * One of: "none", "pending", "accepted".
     */
    suspend fun getFollowStatus(targetUid: String): String {
        val uid = authRepository.uid ?: return "none"
        return try {
            val doc = firestore.collection(COL_FOLLOWS)
                .document(followDocId(uid, targetUid))
                .get().await()
            if (!doc.exists()) "none" else doc.getString("status") ?: "none"
        } catch (e: Exception) {
            Log.e(TAG, "getFollowStatus failed", e)
            "none"
        }
    }

    /**
     * Live stream of pending follow requests directed at the current user.
     * Requires composite index: follows (followeeId, status).
     */
    fun getPendingRequestsFlow(): Flow<List<UserProfile>> = callbackFlow {
        val uid = authRepository.uid
        if (uid == null) { trySend(emptyList()); close(); return@callbackFlow }
        var reg: ListenerRegistration? = null
        reg = firestore.collection(COL_FOLLOWS)
            .whereEqualTo("followeeId", uid)
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "getPendingRequestsFlow error", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val profiles = snapshot?.documents?.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    UserProfile(
                        uid = data["followerId"] as? String ?: return@mapNotNull null,
                        displayName = data["followerName"] as? String ?: "Unknown",
                        createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L
                    )
                } ?: emptyList()
                trySend(profiles)
            }
        awaitClose { reg?.remove() }
    }

    /**
     * Live stream of accepted follows — people the current user follows.
     * Requires composite index: follows (followerId, status).
     */
    fun getFollowingFlow(): Flow<List<UserProfile>> = callbackFlow {
        val uid = authRepository.uid
        if (uid == null) { trySend(emptyList()); close(); return@callbackFlow }
        var reg: ListenerRegistration? = null
        reg = firestore.collection(COL_FOLLOWS)
            .whereEqualTo("followerId", uid)
            .whereEqualTo("status", "accepted")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "getFollowingFlow error", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val profiles = snapshot?.documents?.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    UserProfile(
                        uid = data["followeeId"] as? String ?: return@mapNotNull null,
                        displayName = data["followeeName"] as? String ?: "Unknown",
                        createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L
                    )
                } ?: emptyList()
                trySend(profiles)
            }
        awaitClose { reg?.remove() }
    }

    // ── Notifications ──────────────────────────────────────────────────────────

    /**
     * Live stream of all notifications for the current user, newest first.
     * Capped at 50 most recent.
     */
    fun getNotificationsFlow(): Flow<List<SocialNotification>> = callbackFlow {
        val uid = authRepository.uid
        if (uid == null) { trySend(emptyList()); close(); return@callbackFlow }
        var reg: ListenerRegistration? = null
        reg = firestore.collection(COL_NOTIFICATIONS).document(uid)
            .collection("items")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "getNotificationsFlow error", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val notifs = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        val data = doc.data ?: return@mapNotNull null
                        SocialNotification(
                            id = doc.id,
                            type = data["type"] as? String ?: "",
                            fromUid = data["fromUid"] as? String ?: "",
                            fromDisplayName = data["fromDisplayName"] as? String ?: "",
                            shareId = data["shareId"] as? String,
                            recipeName = data["recipeName"] as? String,
                            createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L,
                            read = data["read"] as? Boolean ?: false
                        )
                    } catch (e: Exception) { null }
                } ?: emptyList()
                trySend(notifs)
            }
        awaitClose { reg?.remove() }
    }

    /** Live unread notification count for the current user. */
    fun getUnreadCountFlow(): Flow<Int> = callbackFlow {
        val uid = authRepository.uid
        if (uid == null) { trySend(0); close(); return@callbackFlow }
        var reg: ListenerRegistration? = null
        reg = firestore.collection(COL_NOTIFICATIONS).document(uid)
            .collection("items")
            .whereEqualTo("read", false)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { trySend(0); return@addSnapshotListener }
                trySend(snapshot?.size() ?: 0)
            }
        awaitClose { reg?.remove() }
    }

    suspend fun markNotificationRead(notifId: String) {
        val uid = authRepository.uid ?: return
        try {
            firestore.collection(COL_NOTIFICATIONS).document(uid)
                .collection("items").document(notifId)
                .update("read", true).await()
        } catch (e: Exception) {
            Log.e(TAG, "markNotificationRead failed", e)
        }
    }

    suspend fun markAllNotificationsRead() {
        val uid = authRepository.uid ?: return
        try {
            val docs = firestore.collection(COL_NOTIFICATIONS).document(uid)
                .collection("items")
                .whereEqualTo("read", false)
                .get().await()
            if (docs.isEmpty) return
            val batch = firestore.batch()
            docs.documents.forEach { doc -> batch.update(doc.reference, "read", true) }
            batch.commit().await()
        } catch (e: Exception) {
            Log.e(TAG, "markAllNotificationsRead failed", e)
        }
    }

    // ── Direct recipe sharing ──────────────────────────────────────────────────

    /**
     * Share a recipe directly to [recipientUid].
     * Stores full recipe data in shared_to/{recipientUid}/recipes/{shareId}
     * and delivers a notification.
     * Returns the shareId on success, null on failure.
     */
    suspend fun shareRecipeTo(recipientUid: String, recipe: Recipe): String? {
        val user = authRepository.currentUser ?: return null
        if (user.isAnonymous) return null
        val shareId = UUID.randomUUID().toString()
        return try {
            val doc = buildSharedToDocument(recipe, user.uid, user.displayName ?: "User")
            firestore.collection(COL_SHARED_TO)
                .document(recipientUid)
                .collection("recipes")
                .document(shareId)
                .set(doc).await()
            deliverNotification(
                toUid = recipientUid,
                type = "recipe_shared",
                fromDisplayName = user.displayName ?: user.email ?: "Someone",
                shareId = shareId,
                recipeName = recipe.title
            )
            shareId
        } catch (e: Exception) {
            Log.e(TAG, "shareRecipeTo failed", e)
            null
        }
    }

    /**
     * Load a recipe that was shared directly to the current user.
     * Fetches from shared_to/{currentUid}/recipes/{shareId}.
     */
    suspend fun getReceivedRecipe(shareId: String): Recipe? {
        val uid = authRepository.uid ?: return null
        return try {
            val doc = firestore.collection(COL_SHARED_TO)
                .document(uid)
                .collection("recipes")
                .document(shareId)
                .get().await()
            val data = doc.data ?: return null
            parseSharedRecipe(doc.id, data)
        } catch (e: Exception) {
            Log.e(TAG, "getReceivedRecipe $shareId failed", e)
            null
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private suspend fun deliverNotification(
        toUid: String,
        type: String,
        fromDisplayName: String,
        shareId: String? = null,
        recipeName: String? = null
    ) {
        val fromUid = authRepository.uid ?: return
        val notifId = UUID.randomUUID().toString()
        try {
            val data = mutableMapOf<String, Any>(
                "type" to type,
                "fromUid" to fromUid,
                "fromDisplayName" to fromDisplayName,
                "createdAt" to System.currentTimeMillis(),
                "read" to false
            )
            shareId?.let { data["shareId"] = it }
            recipeName?.let { data["recipeName"] = it }
            firestore.collection(COL_NOTIFICATIONS)
                .document(toUid)
                .collection("items")
                .document(notifId)
                .set(data).await()
        } catch (e: Exception) {
            Log.e(TAG, "deliverNotification type=$type failed", e)
        }
    }

    private suspend fun markFollowRequestsRead(fromUid: String) {
        val uid = authRepository.uid ?: return
        try {
            val docs = firestore.collection(COL_NOTIFICATIONS).document(uid)
                .collection("items")
                .whereEqualTo("type", "follow_request")
                .whereEqualTo("fromUid", fromUid)
                .whereEqualTo("read", false)
                .get().await()
            if (docs.isEmpty) return
            val batch = firestore.batch()
            docs.documents.forEach { batch.update(it.reference, "read", true) }
            batch.commit().await()
        } catch (e: Exception) {
            Log.e(TAG, "markFollowRequestsRead failed", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSharedRecipe(docId: String, data: Map<String, Any>): Recipe {
        val now = System.currentTimeMillis()
        val sections = (data["sections"] as? List<Map<String, Any>>)?.map { s ->
            RecipeSection(
                id = s["id"] as? String ?: "",
                name = s["name"] as? String ?: "",
                orderIndex = (s["orderIndex"] as? Number)?.toInt() ?: 0
            )
        } ?: emptyList()

        val ingredients = (data["ingredients"] as? List<Map<String, Any>>)?.map { ing ->
            Ingredient(
                id = ing["id"] as? String ?: "",
                sectionId = ing["sectionId"] as? String,
                name = ing["name"] as? String ?: "",
                quantityValue = (ing["quantityValue"] as? Number)?.toDouble(),
                quantityUnit = ing["quantityUnit"] as? String,
                quantityDisplay = ing["quantityDisplay"] as? String,
                quantityValueMetric = (ing["quantityValueMetric"] as? Number)?.toDouble(),
                quantityUnitMetric = ing["quantityUnitMetric"] as? String,
                quantityDisplayMetric = ing["quantityDisplayMetric"] as? String,
                quantityValueImperial = (ing["quantityValueImperial"] as? Number)?.toDouble(),
                quantityUnitImperial = ing["quantityUnitImperial"] as? String,
                quantityDisplayImperial = ing["quantityDisplayImperial"] as? String,
                groupLabel = ing["groupLabel"] as? String,
                isOptional = ing["isOptional"] as? Boolean ?: false,
                substituteGroupId = ing["substituteGroupId"] as? String,
                substituteRatio = (ing["substituteRatio"] as? Number)?.toFloat() ?: 1.0f,
                orderIndex = (ing["orderIndex"] as? Number)?.toInt() ?: 0
            )
        } ?: emptyList()

        val stepRefMap = (data["stepIngredientRefs"] as? List<Map<String, Any>>)
            ?.groupBy { it["stepId"] as? String ?: "" } ?: emptyMap()

        val steps = (data["steps"] as? List<Map<String, Any>>)?.map { step ->
            val stepId = step["id"] as? String ?: ""
            val refs = stepRefMap[stepId]?.map { ref ->
                StepIngredientRef(
                    ingredientId = ref["ingredientId"] as? String ?: "",
                    quantityDisplay = ref["quantityDisplay"] as? String
                )
            } ?: emptyList()
            Step(
                id = stepId,
                sectionId = step["sectionId"] as? String,
                instruction = step["instruction"] as? String ?: "",
                orderIndex = (step["orderIndex"] as? Number)?.toInt() ?: 0,
                ingredientRefs = refs
            )
        } ?: emptyList()

        val rawUrls = data["sourceUrls"]
        val sourceUrls = when (rawUrls) {
            is List<*> -> rawUrls.filterIsInstance<String>()
            else -> emptyList()
        }
        val rawTags = data["tags"]
        val tags = when (rawTags) {
            is List<*> -> rawTags.filterIsInstance<String>()
            else -> emptyList()
        }

        return Recipe(
            id = docId,
            title = data["title"] as? String ?: "",
            description = data["description"] as? String,
            sourceUrls = sourceUrls,
            baseServings = (data["baseServings"] as? Number)?.toInt() ?: 1,
            baseServingsMin = (data["baseServingsMin"] as? Number)?.toInt(),
            baseServingsMax = (data["baseServingsMax"] as? Number)?.toInt(),
            scaleIngredientId = data["scaleIngredientId"] as? String,
            scaleStep = (data["scaleStep"] as? Number)?.toDouble() ?: 1.0,
            prepTimeMinutes = (data["prepTimeMinutes"] as? Number)?.toInt(),
            cookTimeMinutes = (data["cookTimeMinutes"] as? Number)?.toInt(),
            imageUrl = data["imageUrl"] as? String,
            tags = tags,
            sections = sections,
            ingredients = ingredients,
            steps = steps,
            isCustomized = false,
            isImported = false,
            version = (data["version"] as? Number)?.toInt() ?: 1,
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: now,
            updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: now,
            authorId = data["fromUid"] as? String,
            authorDisplayName = data["fromDisplayName"] as? String,
            visibility = "private"
        )
    }

    private fun buildSharedToDocument(
        recipe: Recipe,
        fromUid: String,
        fromName: String
    ): Map<String, Any?> {
        val sections = recipe.sections.map { s ->
            mapOf("id" to s.id, "name" to s.name, "orderIndex" to s.orderIndex)
        }
        val ingredients = recipe.ingredients.map { ing ->
            mapOf(
                "id" to ing.id, "recipeId" to recipe.id, "sectionId" to ing.sectionId,
                "name" to ing.name,
                "quantityValue" to ing.quantityValue, "quantityUnit" to ing.quantityUnit,
                "quantityDisplay" to ing.quantityDisplay,
                "quantityValueMetric" to ing.quantityValueMetric,
                "quantityUnitMetric" to ing.quantityUnitMetric,
                "quantityDisplayMetric" to ing.quantityDisplayMetric,
                "quantityValueImperial" to ing.quantityValueImperial,
                "quantityUnitImperial" to ing.quantityUnitImperial,
                "quantityDisplayImperial" to ing.quantityDisplayImperial,
                "groupLabel" to ing.groupLabel,
                "isOptional" to ing.isOptional,
                "substituteGroupId" to ing.substituteGroupId,
                "substituteRatio" to ing.substituteRatio,
                "orderIndex" to ing.orderIndex
            )
        }
        val stepRefs = recipe.steps.flatMap { step ->
            step.ingredientRefs.map { ref ->
                mapOf(
                    "stepId" to step.id,
                    "ingredientId" to ref.ingredientId,
                    "quantityDisplay" to ref.quantityDisplay
                )
            }
        }
        val steps = recipe.steps.map { step ->
            mapOf(
                "id" to step.id, "recipeId" to recipe.id, "sectionId" to step.sectionId,
                "instruction" to step.instruction, "orderIndex" to step.orderIndex
            )
        }
        return mapOf(
            "fromUid" to fromUid,
            "fromDisplayName" to fromName,
            "sharedAt" to System.currentTimeMillis(),
            "title" to recipe.title,
            "description" to recipe.description,
            "sourceUrls" to recipe.sourceUrls,
            "baseServings" to recipe.baseServings,
            "baseServingsMin" to recipe.baseServingsMin,
            "baseServingsMax" to recipe.baseServingsMax,
            "scaleIngredientId" to recipe.scaleIngredientId,
            "scaleStep" to recipe.scaleStep,
            "prepTimeMinutes" to recipe.prepTimeMinutes,
            "cookTimeMinutes" to recipe.cookTimeMinutes,
            "imageUrl" to recipe.imageUrl,
            "tags" to recipe.tags,
            "isCustomized" to recipe.isCustomized,
            "isImported" to recipe.isImported,
            "version" to recipe.version,
            "createdAt" to recipe.createdAt,
            "updatedAt" to recipe.updatedAt,
            "sections" to sections,
            "ingredients" to ingredients,
            "steps" to steps,
            "stepIngredientRefs" to stepRefs
        )
    }
}
