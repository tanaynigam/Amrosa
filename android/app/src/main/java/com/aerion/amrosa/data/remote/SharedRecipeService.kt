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
                "groupLabel" to ing.groupLabel,
                "isOptional" to ing.isOptional,
                "substituteGroupId" to ing.substituteGroupId,
                "substituteRatio" to ing.substituteRatio,
                "orderIndex" to ing.orderIndex
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
            "authorId" to recipe.authorId,
            "authorDisplayName" to recipe.authorDisplayName,
            "visibility" to "public",
            "sharedAt" to System.currentTimeMillis(),
            "sections" to sections, "ingredients" to ingredients,
            "steps" to steps, "stepIngredientRefs" to stepRefs
        )
    }

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
                groupLabel = ing["groupLabel"] as? String,
                isOptional = ing["isOptional"] as? Boolean ?: false,
                substituteGroupId = ing["substituteGroupId"] as? String,
                substituteRatio = (ing["substituteRatio"] as? Number)?.toFloat() ?: 1.0f,
                orderIndex = (ing["orderIndex"] as? Number)?.toInt() ?: 0
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
            visibility = data["visibility"] as? String ?: "public"
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
