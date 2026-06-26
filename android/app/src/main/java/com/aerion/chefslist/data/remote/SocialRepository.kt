package com.aerion.chefslist.data.remote

import android.util.Log
import com.aerion.chefslist.data.auth.AuthRepository
import com.aerion.chefslist.domain.model.*
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
 *   users/{uid}                            — public user profiles (includes fcmToken)
 *   follows/{followerId}_{followeeId}      — follow relationships (pending / accepted)
 *   notifications/{uid}/items/{notifId}    — notification inbox (written here, read by Cloud Function → FCM push)
 *   shared_to/{recipientUid}/recipes/{shareId} — directly shared recipe data
 *
 * Composite indexes required (Firestore will log a link to create them on first run):
 *   follows: (followeeId ASC, status ASC)
 *   follows: (followerId ASC, status ASC)
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
        private const val COL_RECEIVED = "received_recipes"
        private const val SUB_ITEMS = "items"
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
            val data = mutableMapOf<String, Any?>(
                "displayName" to (user.displayName ?: user.email ?: "User"),
                "photoUrl" to user.photoUrl?.toString(),
                "updatedAt" to System.currentTimeMillis()
            )
            // Store email so users can be searched by email address
            user.email?.let { data["email"] = it }
            firestore.collection(COL_USERS).document(user.uid).set(
                data,
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            Log.e(TAG, "upsertProfile failed", e)
        }
    }

    // ── User search ────────────────────────────────────────────────────────────

    /**
     * Search users by display name prefix or exact email address.
     * If [query] contains '@', performs an exact email lookup.
     * Otherwise, does a display name prefix search.
     * Excludes the current user. Returns up to 20 results.
     */
    suspend fun searchUsers(query: String): List<UserProfile> {
        if (query.isBlank()) return emptyList()
        val uid = authRepository.uid ?: return emptyList()
        val isEmailQuery = query.contains('@')
        return try {
            val snapshot = if (isEmailQuery) {
                firestore.collection(COL_USERS)
                    .whereEqualTo("email", query.trim().lowercase())
                    .limit(5)
                    .get().await()
            } else {
                firestore.collection(COL_USERS)
                    .whereGreaterThanOrEqualTo("displayName", query)
                    .whereLessThanOrEqualTo("displayName", query + "")
                    .limit(20)
                    .get().await()
            }
            snapshot.documents
                .filter { it.id != uid }
                .mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    UserProfile(
                        uid = doc.id,
                        displayName = data["displayName"] as? String ?: return@mapNotNull null,
                        email = data["email"] as? String,
                        photoUrl = data["photoUrl"] as? String,
                        createdAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                    )
                }
        } catch (e: Exception) {
            Log.e(TAG, "searchUsers failed", e)
            emptyList()
        }
    }

    /** Resolve a set of UIDs to their profiles (for showing a recipe's share recipients). */
    suspend fun getUsers(uids: List<String>): List<UserProfile> {
        if (uids.isEmpty()) return emptyList()
        return uids.distinct().mapNotNull { id ->
            try {
                val doc = firestore.collection(COL_USERS).document(id).get().await()
                val data = doc.data ?: return@mapNotNull null
                UserProfile(
                    uid = doc.id,
                    displayName = data["displayName"] as? String ?: id.take(6),
                    email = data["email"] as? String,
                    photoUrl = data["photoUrl"] as? String,
                    createdAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L,
                )
            } catch (e: Exception) {
                Log.e(TAG, "getUsers $id failed", e); null
            }
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
     * Accept a pending friend request from [fromUid].
     * Marks the existing doc accepted AND creates the reverse doc so both
     * users see each other as friends (mutual/bidirectional friendship).
     */
    suspend fun acceptFollowRequest(fromUid: String) {
        val uid  = authRepository.uid ?: return
        val myName = authRepository.currentUser?.displayName ?: authRepository.email ?: "Someone"
        val incomingDocId = followDocId(fromUid, uid)
        try {
            // Read the incoming request to get the requester's name
            val incomingDoc = firestore.collection(COL_FOLLOWS).document(incomingDocId).get().await()
            val requesterName = incomingDoc.getString("followerName") ?: "Unknown"

            val batch = firestore.batch()

            // 1. Mark the original request as accepted
            batch.update(firestore.collection(COL_FOLLOWS).document(incomingDocId),
                mapOf("status" to "accepted"))

            // 2. Create the reverse doc so the requester also sees us as a friend
            val reverseDocId = followDocId(uid, fromUid)
            batch.set(
                firestore.collection(COL_FOLLOWS).document(reverseDocId),
                mapOf(
                    "followerId"   to uid,
                    "followerName" to myName,
                    "followeeId"   to fromUid,
                    "followeeName" to requesterName,
                    "status"       to "accepted",
                    "createdAt"    to System.currentTimeMillis()
                )
            )

            batch.commit().await()

            deliverNotification(
                toUid = fromUid,
                type = "follow_accepted",
                fromDisplayName = myName
            )
        } catch (e: Exception) {
            Log.e(TAG, "acceptFollowRequest failed", e)
        }
    }

    /**
     * Decline (and delete) a pending friend request from [fromUid].
     */
    suspend fun declineFollowRequest(fromUid: String) {
        val uid = authRepository.uid ?: return
        try {
            firestore.collection(COL_FOLLOWS)
                .document(followDocId(fromUid, uid))
                .delete().await()
        } catch (e: Exception) {
            Log.e(TAG, "declineFollowRequest failed", e)
        }
    }

    /**
     * Remove friendship with [targetUid] — deletes both direction docs
     * so neither user sees the other in their friends list.
     * Also handles cancelling a pending outgoing request (only one doc exists then).
     */
    suspend fun unfriend(targetUid: String) {
        val uid = authRepository.uid ?: return
        try {
            val batch = firestore.batch()
            batch.delete(firestore.collection(COL_FOLLOWS).document(followDocId(uid, targetUid)))
            batch.delete(firestore.collection(COL_FOLLOWS).document(followDocId(targetUid, uid)))
            batch.commit().await()
        } catch (e: Exception) {
            Log.e(TAG, "unfriend failed", e)
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
     * Live stream of accepted friends — people the current user is friends with.
     * Since friendship is mutual, every accepted doc with followerId == uid represents
     * a friend (the reverse doc is also created on accept).
     * Requires composite index: follows (followerId, status).
     */
    fun getFriendsFlow(): Flow<List<UserProfile>> = callbackFlow {
        val uid = authRepository.uid
        if (uid == null) { trySend(emptyList()); close(); return@callbackFlow }
        var reg: ListenerRegistration? = null
        reg = firestore.collection(COL_FOLLOWS)
            .whereEqualTo("followerId", uid)
            .whereEqualTo("status", "accepted")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "getFriendsFlow error", error)
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

    // ── FCM token ──────────────────────────────────────────────────────────────

    /**
     * Stores the device's FCM token in the user's profile so Cloud Functions
     * can send push notifications to this device.
     */
    suspend fun updateFcmToken(token: String) {
        val uid = authRepository.uid ?: return
        try {
            // Stored in a private subdoc so it isn't world-readable. The Cloud Function
            // reads it via the Admin SDK (bypasses rules).
            firestore.collection(COL_USERS).document(uid)
                .collection("private").document("push")
                .set(mapOf("fcmToken" to token, "updatedAt" to System.currentTimeMillis()))
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "updateFcmToken failed: ${e.message}")
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
     * Live stream of recipe summaries shared directly to the current user.
     * Ordered by sharedAt descending. Each item contains enough info to render a list card.
     */
    fun getReceivedRecipesSummaryFlow(): Flow<List<ReceivedRecipeSummary>> = callbackFlow {
        val uid = authRepository.uid
        if (uid == null) { trySend(emptyList()); close(); return@callbackFlow }
        var reg: ListenerRegistration? = null
        reg = firestore.collection(COL_SHARED_TO)
            .document(uid)
            .collection("recipes")
            .orderBy("sharedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "getReceivedRecipesSummaryFlow error", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val summaries = snapshot?.documents?.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    val rawTags = data["tags"]
                    val tags = when (rawTags) {
                        is List<*> -> rawTags.filterIsInstance<String>()
                        else -> emptyList()
                    }
                    ReceivedRecipeSummary(
                        shareId = doc.id,
                        title = data["title"] as? String ?: "Untitled",
                        // authorDisplayName = original recipe author; fall back to sender for old docs
                        authorDisplayName = data["authorDisplayName"] as? String
                            ?: data["fromDisplayName"] as? String ?: "Someone",
                        fromDisplayName = data["fromDisplayName"] as? String ?: "Someone",
                        sharedAt = (data["sharedAt"] as? Number)?.toLong() ?: 0L,
                        prepTimeMinutes = (data["prepTimeMinutes"] as? Number)?.toInt(),
                        cookTimeMinutes = (data["cookTimeMinutes"] as? Number)?.toInt(),
                        tags = tags
                    )
                } ?: emptyList()
                trySend(summaries)
            }
        awaitClose { reg?.remove() }
    }

    /**
     * Read a pending share pointer from shared_to/{uid}/recipes/{shareId}.
     * Returns the canonical recipeId + original author + sender — the actual recipe
     * is read from shared_recipes/{recipeId} (the public mirror).
     */
    suspend fun getReceivedPointer(shareId: String): ReceivedPointer? {
        val uid = authRepository.uid ?: return null
        return try {
            val doc = firestore.collection(COL_SHARED_TO)
                .document(uid).collection("recipes").document(shareId)
                .get().await()
            val data = doc.data ?: return null
            ReceivedPointer(
                shareId = shareId,
                // recipeId is the canonical id; fall back to shareId for legacy snapshot docs
                recipeId = data["recipeId"] as? String ?: shareId,
                authorUid = data["authorUid"] as? String ?: data["fromUid"] as? String ?: "",
                authorName = data["authorDisplayName"] as? String ?: "Someone",
                fromDisplayName = data["fromDisplayName"] as? String ?: "Someone"
            )
        } catch (e: Exception) {
            Log.e(TAG, "getReceivedPointer $shareId failed", e)
            null
        }
    }

    /** Write a saved-received reference: received_recipes/{uid}/items/{recipeId}. */
    suspend fun saveReceivedReference(recipeId: String, authorUid: String, authorName: String) {
        val uid = authRepository.uid ?: return
        try {
            firestore.collection(COL_RECEIVED).document(uid).collection(SUB_ITEMS)
                .document(recipeId)
                .set(
                    mapOf(
                        "recipeId" to recipeId,
                        "authorUid" to authorUid,
                        "authorName" to authorName,
                        "savedAt" to System.currentTimeMillis()
                    )
                ).await()
        } catch (e: Exception) {
            Log.e(TAG, "saveReceivedReference $recipeId failed", e)
        }
    }

    /** Consume (delete) a pending share pointer once it has been saved. */
    suspend fun deleteReceivedPointer(shareId: String) {
        val uid = authRepository.uid ?: return
        try {
            firestore.collection(COL_SHARED_TO).document(uid).collection("recipes")
                .document(shareId).delete().await()
        } catch (e: Exception) {
            Log.e(TAG, "deleteReceivedPointer $shareId failed", e)
        }
    }

    /** Remove a saved-received reference (the "Remove from my recipes" action). */
    suspend fun removeReceivedReference(recipeId: String) {
        val uid = authRepository.uid ?: return
        try {
            firestore.collection(COL_RECEIVED).document(uid).collection(SUB_ITEMS)
                .document(recipeId).delete().await()
        } catch (e: Exception) {
            Log.e(TAG, "removeReceivedReference $recipeId failed", e)
        }
    }

    // ── Profile: another chef's shared recipes ──────────────────────────────────

    /**
     * Load [authorUid]'s recipes from the `shared_recipes` mirror for their profile page.
     * Co-chefs see both `friends` and `public` tiers; non-friends (future public profile)
     * see only `public`. Reads are gated by Firestore rules — a non-co-chef querying with
     * `includeFriendsOnly = true` would be rejected, so callers must pass the right flag.
     *
     * Requires composite index: shared_recipes (authorId ASC, visibility ASC).
     */
    suspend fun getAuthorRecipes(authorUid: String, includeFriendsOnly: Boolean): List<ProfileRecipeSummary> {
        if (authorUid.isBlank()) return emptyList()
        val tiers = if (includeFriendsOnly) listOf("friends", "public") else listOf("public")
        return try {
            val snapshot = firestore.collection("shared_recipes")
                .whereEqualTo("authorId", authorUid)
                .whereIn("visibility", tiers)
                .get().await()
            snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                val rawTags = data["tags"]
                ProfileRecipeSummary(
                    recipeId = doc.id,
                    title = data["title"] as? String ?: "Untitled",
                    prepTimeMinutes = (data["prepTimeMinutes"] as? Number)?.toInt(),
                    cookTimeMinutes = (data["cookTimeMinutes"] as? Number)?.toInt(),
                    tags = (rawTags as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                    visibility = data["visibility"] as? String ?: "public",
                )
            }.sortedBy { it.title.lowercase() }
        } catch (e: Exception) {
            Log.e(TAG, "getAuthorRecipes $authorUid failed", e)
            emptyList()
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
                quantityValueMax = (ing["quantityValueMax"] as? Number)?.toDouble(),
                quantityValueMaxMetric = (ing["quantityValueMaxMetric"] as? Number)?.toDouble(),
                quantityValueMaxImperial = (ing["quantityValueMaxImperial"] as? Number)?.toDouble(),
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
            // Use original recipe author; fall back to sender for old docs that don't store it
            authorDisplayName = data["authorDisplayName"] as? String
                ?: data["fromDisplayName"] as? String,
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
                "quantityValueMax" to ing.quantityValueMax,
                "quantityValueMaxMetric" to ing.quantityValueMaxMetric,
                "quantityValueMaxImperial" to ing.quantityValueMaxImperial,
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
            // Canonical recipe id + original author — used by Tab 2 to resolve the
            // live recipe from shared_recipes/{recipeId} (Recipe Ownership Model v2)
            "recipeId" to recipe.id,
            "authorUid" to (recipe.authorId ?: fromUid),
            "authorDisplayName" to recipe.authorDisplayName,
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
