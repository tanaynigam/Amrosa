package com.aerion.amrosa.data.remote

import android.util.Log
import com.aerion.amrosa.data.auth.AuthRepository
import com.aerion.amrosa.data.local.entity.*
import com.aerion.amrosa.domain.model.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Manages the `shared_recipes` Firestore collection:
 *
 *   shared_recipes/{recipeId}               — public recipe mirror
 *     /comments/{commentId}                 — community comments
 *
 * The recipe document structure mirrors personal_recipes so the same
 * parse logic applies. Additional fields: sharedAt, visibility = "public".
 */
class SharedRecipeService(
    private val authRepository: AuthRepository,
    private val gson: Gson
) {
    private val firestore = FirebaseFirestore.getInstance()

    companion object {
        private const val TAG = "SharedRecipeService"
        private const val COLLECTION_SHARED = "shared_recipes"
        private const val COLLECTION_COMMENTS = "comments"
    }

    // ─── Publish / Unpublish ──────────────────────────────────────────────────

    /** Mirror a recipe into `shared_recipes/{recipeId}`. Called when owner sets visibility = public. */
    suspend fun publish(recipe: Recipe): Boolean {
        return try {
            val doc = buildDocument(recipe)
            firestore.collection(COLLECTION_SHARED)
                .document(recipe.id)
                .set(doc, SetOptions.merge())
                .await()
            Log.d(TAG, "Published recipe ${recipe.id}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish recipe ${recipe.id}", e)
            false
        }
    }

    /** Remove a recipe from `shared_recipes`. Called when owner sets visibility = private. */
    suspend fun unpublish(recipeId: String): Boolean {
        return try {
            // Delete the recipe document (Firestore does NOT auto-delete subcollections)
            // Comments are left orphaned intentionally — Firestore subcollections persist
            // independently. A Cloud Function can clean them up later if needed.
            firestore.collection(COLLECTION_SHARED)
                .document(recipeId)
                .delete()
                .await()
            Log.d(TAG, "Unpublished recipe $recipeId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unpublish recipe $recipeId", e)
            false
        }
    }

    // ─── Notes lock (author freezes the community notes thread) ────────────────
    // Stored on the mirror doc only (merge-written, so publish/republish never clears it).

    suspend fun setNotesLocked(recipeId: String, locked: Boolean): Boolean = try {
        firestore.collection(COLLECTION_SHARED).document(recipeId)
            .set(mapOf("notesLocked" to locked), SetOptions.merge()).await()
        true
    } catch (e: Exception) {
        Log.e(TAG, "setNotesLocked failed for $recipeId", e); false
    }

    suspend fun getNotesLocked(recipeId: String): Boolean = try {
        firestore.collection(COLLECTION_SHARED).document(recipeId).get().await()
            .getBoolean("notesLocked") ?: false
    } catch (e: Exception) { false }

    // ─── Browse shared recipes ────────────────────────────────────────────────

    /**
     * Live stream of all public recipes. Used by SharedScreen.
     * Returns lightweight Recipe objects (sections/ingredients/steps are populated
     * from the document so full detail is available without a second fetch).
     */
    fun getSharedRecipesFlow(): Flow<List<Recipe>> = callbackFlow {
        var registration: ListenerRegistration? = null
        registration = firestore.collection(COLLECTION_SHARED)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "getSharedRecipesFlow error", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val recipes = snapshot?.documents?.mapNotNull { doc ->
                    try { parseRecipe(doc.id, doc.data ?: return@mapNotNull null) }
                    catch (e: Exception) { Log.e(TAG, "Parse error ${doc.id}", e); null }
                } ?: emptyList()
                trySend(recipes)
            }
        awaitClose { registration?.remove() }
    }

    private fun parsePublicSummary(doc: com.google.firebase.firestore.DocumentSnapshot): DiscoverRecipe? {
        val data = doc.data ?: return null
        return DiscoverRecipe(
            recipeId = doc.id,
            title = data["title"] as? String ?: "Untitled",
            tags = (data["tags"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            prepTimeMinutes = (data["prepTimeMinutes"] as? Number)?.toInt(),
            cookTimeMinutes = (data["cookTimeMinutes"] as? Number)?.toInt(),
            source = RecipeSource.PUBLIC,
            authorUid = data["authorId"] as? String,
            authorName = data["authorDisplayName"] as? String,
            isLocal = false,
            saveCount = (data["saveCount"] as? Number)?.toInt() ?: 0,
            likeCount = (data["likeCount"] as? Number)?.toInt() ?: 0,
        )
    }

    /**
     * Lightweight list of public recipes for the Discover feed. Newest first, capped.
     * Returns [DiscoverRecipe] cards (no sections/ingredients) — full detail is fetched on tap.
     */
    suspend fun getPublicRecipeSummaries(limit: Long = 50): List<DiscoverRecipe> {
        return try {
            firestore.collection(COLLECTION_SHARED)
                .whereEqualTo("visibility", "public")
                .orderBy("sharedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(limit)
                .get().await()
                .documents.mapNotNull { parsePublicSummary(it) }
        } catch (e: Exception) {
            Log.e(TAG, "getPublicRecipeSummaries failed", e)
            emptyList()
        }
    }

    /** Most-saved public recipes (the "Popular" shelf + popularity ranking). Requires index (visibility, saveCount desc). */
    suspend fun getPopularPublicRecipes(limit: Long = 20): List<DiscoverRecipe> {
        return try {
            firestore.collection(COLLECTION_SHARED)
                .whereEqualTo("visibility", "public")
                .orderBy("saveCount", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(limit)
                .get().await()
                .documents.mapNotNull { parsePublicSummary(it) }
        } catch (e: Exception) {
            Log.e(TAG, "getPopularPublicRecipes failed", e)
            emptyList()
        }
    }

    /**
     * Search public recipes by a query word (Discover Phase 3a). Firestore has no full-text, so we
     * match against the precomputed `searchTokens` array (title + tag words). Queries the longest
     * token via array-contains, then refines client-side to the full query so multi-word searches
     * narrow correctly. Requires index (visibility ==, searchTokens array-contains).
     */
    suspend fun searchPublicRecipes(query: String, limit: Long = 25): List<DiscoverRecipe> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val token = q.split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.maxByOrNull { it.length }
            ?: return emptyList()
        return try {
            firestore.collection(COLLECTION_SHARED)
                .whereEqualTo("visibility", "public")
                .whereArrayContains("searchTokens", token)
                .limit(limit)
                .get().await()
                .documents.mapNotNull { parsePublicSummary(it) }
                // Refine to the full query (substring on title or any tag).
                .filter { r -> r.title.contains(q, true) || r.tags.any { it.contains(q, true) } }
        } catch (e: Exception) {
            Log.e(TAG, "searchPublicRecipes failed", e)
            emptyList()
        }
    }

    // ─── Likes (Discover Phase 2) ─────────────────────────────────────────────

    data class LikeState(val isLiked: Boolean = false, val likeCount: Int = 0, val saveCount: Int = 0)

    /** Like / unlike a shared recipe as the current user (counter maintained by a Cloud Function). */
    suspend fun setLiked(recipeId: String, liked: Boolean) {
        val uid = authRepository.uid ?: return
        try {
            val ref = firestore.collection(COLLECTION_SHARED).document(recipeId)
                .collection("likes").document(uid)
            if (liked) ref.set(mapOf("likedAt" to System.currentTimeMillis())).await()
            else ref.delete().await()
        } catch (e: Exception) {
            Log.e(TAG, "setLiked $recipeId=$liked failed", e)
        }
    }

    /** Live like state for a recipe: whether the current user liked it + the like/save counts. */
    fun likeStateFlow(recipeId: String): Flow<LikeState> = callbackFlow {
        val uid = authRepository.uid
        var isLiked = false
        var likeCount = 0
        var saveCount = 0
        fun emit() { trySend(LikeState(isLiked, likeCount, saveCount)) }

        val recipeReg = firestore.collection(COLLECTION_SHARED).document(recipeId)
            .addSnapshotListener { snap, _ ->
                likeCount = (snap?.get("likeCount") as? Number)?.toInt() ?: 0
                saveCount = (snap?.get("saveCount") as? Number)?.toInt() ?: 0
                emit()
            }
        val likeReg = if (uid != null) {
            firestore.collection(COLLECTION_SHARED).document(recipeId)
                .collection("likes").document(uid)
                .addSnapshotListener { snap, _ -> isLiked = snap?.exists() == true; emit() }
        } else null

        awaitClose { recipeReg.remove(); likeReg?.remove() }
    }

    /** Load one shared recipe with full detail (sections, ingredients, steps). */
    suspend fun getSharedRecipeDetail(recipeId: String): Recipe? {
        return try {
            val doc = firestore.collection(COLLECTION_SHARED).document(recipeId).get().await()
            val data = doc.data ?: return null
            parseRecipe(doc.id, data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load shared recipe $recipeId", e)
            null
        }
    }

    // ─── Comments ─────────────────────────────────────────────────────────────

    /** Live stream of comments for a shared recipe, ordered by createdAt. */
    fun getCommentsFlow(recipeId: String): Flow<List<Comment>> = callbackFlow {
        var registration: ListenerRegistration? = null
        registration = firestore.collection(COLLECTION_SHARED)
            .document(recipeId)
            .collection(COLLECTION_COMMENTS)
            .orderBy("createdAt")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "getCommentsFlow error for $recipeId", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val comments = snapshot?.documents?.mapNotNull { doc ->
                    try { parseComment(recipeId, doc.id, doc.data ?: return@mapNotNull null) }
                    catch (e: Exception) { null }
                } ?: emptyList()
                trySend(comments)
            }
        awaitClose { registration?.remove() }
    }

    /** Post a comment on a shared recipe. Returns false if not signed in or on error. */
    suspend fun addComment(recipeId: String, content: String): Boolean {
        val user = authRepository.currentUser
        if (user == null || user.isAnonymous) return false
        if (content.isBlank()) return false

        return try {
            val id = UUID.randomUUID().toString()
            val doc = mapOf(
                "id" to id,
                "authorId" to user.uid,
                "authorDisplayName" to (user.displayName ?: user.email ?: "User"),
                "content" to content.trim(),
                "createdAt" to System.currentTimeMillis()
            )
            firestore.collection(COLLECTION_SHARED)
                .document(recipeId)
                .collection(COLLECTION_COMMENTS)
                .document(id)
                .set(doc)
                .await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add comment on $recipeId", e)
            false
        }
    }

    /**
     * Delete a comment. Security rules enforce that only the commenter or
     * the recipe owner can delete — this call will fail silently if neither.
     */
    suspend fun deleteComment(recipeId: String, commentId: String): Boolean {
        return try {
            firestore.collection(COLLECTION_SHARED)
                .document(recipeId)
                .collection(COLLECTION_COMMENTS)
                .document(commentId)
                .delete()
                .await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete comment $commentId on $recipeId", e)
            false
        }
    }

    // ─── Serialisation ────────────────────────────────────────────────────────

    private fun buildDocument(recipe: Recipe): Map<String, Any?> {
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
                "orderIndex" to ing.orderIndex,
                "shoppingNote" to ing.shoppingNote
            )
        }
        val stepRefs = recipe.steps.flatMap { step ->
            step.ingredientRefs.map { ref ->
                mapOf("stepId" to step.id, "ingredientId" to ref.ingredientId,
                    "quantityDisplay" to ref.quantityDisplay)
            }
        }
        val steps = recipe.steps.map { step ->
            mapOf("id" to step.id, "recipeId" to recipe.id, "sectionId" to step.sectionId,
                "instruction" to step.instruction, "orderIndex" to step.orderIndex)
        }
        return mapOf(
            "title" to recipe.title, "description" to recipe.description,
            "sourceUrls" to recipe.sourceUrls,
            "baseServings" to recipe.baseServings,
            "baseServingsMin" to recipe.baseServingsMin, "baseServingsMax" to recipe.baseServingsMax,
            "scaleIngredientId" to recipe.scaleIngredientId, "scaleStep" to recipe.scaleStep,
            "prepTimeMinutes" to recipe.prepTimeMinutes, "cookTimeMinutes" to recipe.cookTimeMinutes,
            "imageUrl" to recipe.imageUrl, "tags" to recipe.tags,
            "isCustomized" to recipe.isCustomized, "isImported" to recipe.isImported,
            "version" to recipe.version,
            "createdAt" to recipe.createdAt, "updatedAt" to recipe.updatedAt,
            // v2: always store the REAL author name + isImported flag. The "Imported by X"
            // vs "X" label is computed at display time — never overwrite the name here.
            "authorId" to recipe.authorId,
            "authorDisplayName" to (recipe.authorDisplayName ?: "User"),
            // Mirror records the actual shared tier — drives the read rule. "shared" recipes are
            // readable only by the author + the UIDs in sharedWith (per-recipient ACL); they never
            // appear in Discovery/profile (those query friends/public only).
            "visibility" to (recipe.visibility.takeIf { it == "shared" || it == "friends" || it == "public" } ?: "public"),
            "sharedWith" to recipe.sharedWith,
            "sharedAt" to System.currentTimeMillis(),
            // F13 Phase 3a — word tokens (title + tags, lowercased) for array-contains search.
            "searchTokens" to searchTokens(recipe.title, recipe.tags),
            "sections" to sections, "ingredients" to ingredients,
            "steps" to steps, "stepIngredientRefs" to stepRefs
        )
    }

    /** Lowercased distinct words (≥2 chars) from title + tags — the searchable token set. */
    private fun searchTokens(title: String, tags: List<String>): List<String> =
        (title + " " + tags.joinToString(" "))
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }
            .distinct()
            .take(30)

    @Suppress("UNCHECKED_CAST")
    private fun parseRecipe(docId: String, data: Map<String, Any>): Recipe {
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
                orderIndex = (ing["orderIndex"] as? Number)?.toInt() ?: 0,
                shoppingNote = ing["shoppingNote"] as? String
            )
        } ?: emptyList()

        val stepRefMap = (data["stepIngredientRefs"] as? List<Map<String, Any>>)
            ?.groupBy { it["stepId"] as? String ?: "" }
            ?: emptyMap()

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
            is String  -> gson.fromJson(rawUrls, Array<String>::class.java)?.toList() ?: emptyList()
            else       -> emptyList()
        }
        val rawTags = data["tags"]
        val tags = when (rawTags) {
            is List<*> -> rawTags.filterIsInstance<String>()
            is String  -> gson.fromJson(rawTags, Array<String>::class.java)?.toList() ?: emptyList()
            else       -> emptyList()
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
            isCustomized = data["isCustomized"] as? Boolean ?: false,
            isImported = data["isImported"] as? Boolean ?: false,
            version = (data["version"] as? Number)?.toInt() ?: 1,
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: now,
            updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: now,
            authorId = data["authorId"] as? String,
            authorDisplayName = data["authorDisplayName"] as? String,
            visibility = data["visibility"] as? String ?: "public",
            sharedWith = (data["sharedWith"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
        )
    }

    private fun parseComment(recipeId: String, docId: String, data: Map<String, Any>): Comment =
        Comment(
            id = docId,
            recipeId = recipeId,
            authorId = data["authorId"] as? String ?: "",
            authorDisplayName = data["authorDisplayName"] as? String ?: "Unknown",
            content = data["content"] as? String ?: "",
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L
        )
}
