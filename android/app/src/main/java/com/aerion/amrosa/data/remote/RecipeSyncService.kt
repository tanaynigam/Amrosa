package com.aerion.amrosa.data.remote

import android.content.Context
import android.util.Log
import com.aerion.amrosa.data.auth.AuthRepository
import com.aerion.amrosa.data.local.entity.*
import com.aerion.amrosa.data.repository.RecipeRepository
import com.aerion.amrosa.domain.model.Recipe
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import kotlinx.coroutines.tasks.await

/**
 * Syncs recipes between Firestore and the local Room DB.
 *
 * Firestore structure:
 *
 *   recipes/{recipeId}                         — shared/seeded recipes (pull-only)
 *
 *   personal_recipes/{uid}/recipes/{recipeId}  — per-user personal recipes (push + pull)
 *       All personal recipe data lives under the user's UID subcollection so
 *       no other user can ever read or write them.
 *
 * Pull sync (shared):
 *   Fetch docs from `recipes` where updatedAt > lastSyncTimestamp. Upsert into Room.
 *
 * Personal sync (named user only):
 *   pull  — fetch all docs from `personal_recipes/{uid}/recipes` → upsert into Room
 *   push  — upload all local isImported=false recipes to `personal_recipes/{uid}/recipes`
 */
class RecipeSyncService(
    private val context: Context,
    private val repository: RecipeRepository,
    private val authRepository: AuthRepository,
    private val gson: Gson
) {
    private val firestore = FirebaseFirestore.getInstance()
    private val prefs = context.getSharedPreferences("amrosa_sync", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "RecipeSyncService"
        private const val KEY_LAST_SYNC = "last_sync_timestamp"
        private const val COLLECTION_RECIPES = "recipes"
        private const val COLLECTION_PERSONAL = "personal_recipes"
        private const val SUBCOLLECTION_RECIPES = "recipes"
        private const val COLLECTION_SHARED = "shared_recipes"
        private const val COLLECTION_RECEIVED = "received_recipes"
        private const val SUBCOLLECTION_ITEMS = "items"
    }

    // ─── Shared recipes (pull-only) ───────────────────────────────────────────

    /**
     * No-op since Recipe Ownership Model v2: the seeded/"official" `recipes` collection
     * is retired. Every recipe is owned by a real author and synced via
     * [syncPersonalRecipes] (mine) or the received-reference refresh (Tab 2).
     */
    @Deprecated("Official/seeded recipes removed in v2", ReplaceWith("syncPersonalRecipes()"))
    suspend fun sync(): Int = 0

    /** Force a full re-pull of the signed-in user's personal recipes. */
    suspend fun forceFullSync(): Int {
        return syncPersonalRecipes().first
    }

    // ─── Personal recipes (per-user, push + pull) ─────────────────────────────

    /**
     * Push a single personal recipe to `personal_recipes/{uid}/recipes/{recipeId}`.
     * No-ops silently if the user is anonymous (no account to push to).
     */
    suspend fun pushPersonalRecipe(recipeId: String): Boolean {
        val uid = authRepository.uid
        if (uid == null || authRepository.currentUser?.isAnonymous == true) {
            Log.d(TAG, "pushPersonalRecipe: skipped — no signed-in user")
            return false
        }
        return try {
            val recipe = repository.getRecipeWithDetails(recipeId)
            if (recipe == null) {
                Log.w(TAG, "pushPersonalRecipe: recipe $recipeId not found in Room")
                return false
            }
            // Received recipes (Tab 2) are references to another user's recipe — never push them as ours.
            if (recipe.isReceived) {
                Log.d(TAG, "pushPersonalRecipe: skipped — $recipeId is a received recipe")
                return false
            }
            val doc = buildFirestoreDocument(recipe, authorIdOverride = uid)
            firestore.collection(COLLECTION_PERSONAL)
                .document(uid)
                .collection(SUBCOLLECTION_RECIPES)
                .document(recipeId)
                .set(doc, SetOptions.merge())
                .await()
            Log.d(TAG, "Pushed personal recipe $recipeId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push personal recipe $recipeId", e)
            false
        }
    }

    /**
     * Delete a personal recipe from Firestore (personal_recipes/{uid}/recipes/{recipeId}).
     * Must be called whenever an owned recipe is deleted locally, otherwise the next
     * pull would resurrect it. No-ops for anonymous users.
     */
    suspend fun deletePersonalRecipe(recipeId: String): Boolean {
        val uid = authRepository.uid
        if (uid == null || authRepository.currentUser?.isAnonymous == true) return false
        return try {
            firestore.collection(COLLECTION_PERSONAL)
                .document(uid)
                .collection(SUBCOLLECTION_RECIPES)
                .document(recipeId)
                .delete()
                .await()
            Log.d(TAG, "Deleted personal recipe $recipeId from Firestore")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete personal recipe $recipeId", e)
            false
        }
    }

    /**
     * Pull all personal recipes for the current user from Firestore → Room.
     * Used after sign-in so cloud recipes appear on this device.
     */
    suspend fun pullPersonalRecipes(): Int {
        val uid = authRepository.uid
        if (uid == null || authRepository.currentUser?.isAnonymous == true) return 0

        return try {
            val snapshot = firestore.collection(COLLECTION_PERSONAL)
                .document(uid)
                .collection(SUBCOLLECTION_RECIPES)
                .get()
                .await()

            if (snapshot.isEmpty) {
                Log.d(TAG, "No personal recipes in Firestore for $uid")
                return 0
            }

            var count = 0
            for (doc in snapshot.documents) {
                try {
                    val recipe = parseRecipe(doc.id, doc.data ?: continue)
                    repository.insertFullRecipe(
                        recipe.first, recipe.second, recipe.third, recipe.fourth, recipe.fifth
                    )
                    count++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse personal recipe ${doc.id}", e)
                }
            }
            Log.d(TAG, "Pulled $count personal recipes from Firestore")
            count
        } catch (e: Exception) {
            Log.e(TAG, "pullPersonalRecipes failed", e)
            0
        }
    }

    /**
     * Push all local personal (isImported=false) recipes to the user's Firestore subcollection.
     * Used after sign-in to back up any recipes that existed before the user logged in.
     */
    suspend fun pushAllPersonalRecipes(): Int {
        val uid = authRepository.uid
        if (uid == null || authRepository.currentUser?.isAnonymous == true) return 0

        return try {
            val recipes = repository.getAllPersonalRecipesWithDetails()
            var count = 0
            for (recipe in recipes) {
                try {
                    val doc = buildFirestoreDocument(recipe, authorIdOverride = uid)
                    firestore.collection(COLLECTION_PERSONAL)
                        .document(uid)
                        .collection(SUBCOLLECTION_RECIPES)
                        .document(recipe.id)
                        .set(doc, SetOptions.merge())
                        .await()
                    count++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to push recipe ${recipe.id}", e)
                }
            }
            Log.d(TAG, "Pushed $count/${recipes.size} personal recipes to Firestore")
            count
        } catch (e: Exception) {
            Log.e(TAG, "pushAllPersonalRecipes failed", e)
            0
        }
    }

    /**
     * Full personal sync: pull cloud → Room, then push local → cloud.
     * Call this immediately after the user signs in.
     * Returns (pulled, pushed).
     */
    suspend fun syncPersonalRecipes(): Pair<Int, Int> {
        val pulled = pullPersonalRecipes()
        val pushed = pushAllPersonalRecipes()
        return Pair(pulled, pushed)
    }

    // ─── Received recipes (Tab 2 — references to other users' recipes) ────────

    /**
     * Refresh the Tab 2 received-recipe cache from the canonical public mirrors.
     *
     * For each reference in `received_recipes/{uid}/items`:
     *   - read `shared_recipes/{recipeId}` (the author's public mirror)
     *   - if present → re-cache into Room with isReceived = true (propagates author edits)
     *   - if absent  → the author set it Private or deleted it → drop the reference + local copy
     *
     * Also prunes any locally-cached received recipe that no longer has a reference.
     * Returns the number of received recipes available after refresh.
     */
    suspend fun syncReceivedRecipes(): Int {
        val uid = authRepository.uid
        if (uid == null || authRepository.currentUser?.isAnonymous == true) return 0

        return try {
            val refsSnapshot = firestore.collection(COLLECTION_RECEIVED)
                .document(uid)
                .collection(SUBCOLLECTION_ITEMS)
                .get()
                .await()

            val liveIds = mutableSetOf<String>()

            for (refDoc in refsSnapshot.documents) {
                val recipeId = refDoc.getString("recipeId") ?: refDoc.id
                try {
                    val mirror = firestore.collection(COLLECTION_SHARED)
                        .document(recipeId)
                        .get()
                        .await()
                    val data = mirror.data
                    if (mirror.exists() && data != null) {
                        val parsed = parseRecipe(recipeId, data, markReceived = true)
                        repository.insertFullRecipe(
                            parsed.first, parsed.second, parsed.third, parsed.fourth, parsed.fifth
                        )
                        liveIds.add(recipeId)
                    } else {
                        // Author unpublished (went Private) or deleted → remove ref + local copy
                        refDoc.reference.delete().await()
                        repository.deleteFullRecipe(recipeId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh received recipe $recipeId", e)
                }
            }

            // Prune locally-cached received recipes that no longer have a reference
            for (localId in repository.getReceivedRecipeIds()) {
                if (localId !in liveIds) repository.deleteFullRecipe(localId)
            }

            Log.d(TAG, "Received refresh: ${liveIds.size} available")
            liveIds.size
        } catch (e: Exception) {
            Log.e(TAG, "syncReceivedRecipes failed", e)
            0
        }
    }

    // ─── Firestore serialisation ──────────────────────────────────────────────

    private fun buildFirestoreDocument(
        recipe: Recipe,
        authorIdOverride: String? = null
    ): Map<String, Any?> {
        val sections = recipe.sections.map { s ->
            mapOf("id" to s.id, "name" to s.name, "orderIndex" to s.orderIndex)
        }

        val ingredients = recipe.ingredients.map { ing ->
            mapOf(
                "id" to ing.id,
                "recipeId" to recipe.id,
                "sectionId" to ing.sectionId,
                "name" to ing.name,
                "quantityValue" to ing.quantityValue,
                "quantityUnit" to ing.quantityUnit,
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
                "id" to step.id,
                "recipeId" to recipe.id,
                "sectionId" to step.sectionId,
                "instruction" to step.instruction,
                "orderIndex" to step.orderIndex
            )
        }

        return mapOf(
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
            "changeLog" to recipe.changeLog.map { change ->
                mapOf(
                    "version" to change.version,
                    "timestamp" to change.timestamp,
                    "summary" to change.summary
                )
            },
            "createdAt" to recipe.createdAt,
            "updatedAt" to recipe.updatedAt,
            "authorId" to (recipe.authorId ?: authorIdOverride),
            "authorDisplayName" to recipe.authorDisplayName,
            "visibility" to recipe.visibility,
            "sections" to sections,
            "ingredients" to ingredients,
            "steps" to steps,
            "stepIngredientRefs" to stepRefs
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseRecipe(
        docId: String,
        data: Map<String, Any>,
        markReceived: Boolean = false
    ): RecipeData {
        val now = System.currentTimeMillis()

        val recipe = RecipeEntity(
            id = docId,
            title = data["title"] as? String ?: "",
            description = data["description"] as? String,
            sourceUrls = gson.toJson(data["sourceUrls"] as? List<*> ?: emptyList<String>()),
            baseServings = (data["baseServings"] as? Number)?.toInt() ?: 1,
            baseServingsMin = (data["baseServingsMin"] as? Number)?.toInt(),
            baseServingsMax = (data["baseServingsMax"] as? Number)?.toInt(),
            scaleIngredientId = data["scaleIngredientId"] as? String,
            scaleStep = (data["scaleStep"] as? Number)?.toDouble() ?: 1.0,
            prepTimeMinutes = (data["prepTimeMinutes"] as? Number)?.toInt(),
            cookTimeMinutes = (data["cookTimeMinutes"] as? Number)?.toInt(),
            imageUrl = data["imageUrl"] as? String,
            tags = gson.toJson(data["tags"] as? List<*> ?: emptyList<String>()),
            isCustomized = data["isCustomized"] as? Boolean ?: false,
            isImported = data["isImported"] as? Boolean ?: false,
            isReceived = markReceived,
            // A received recipe is always private locally (the canonical public mirror lives with the author)
            needsReview = false,
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: now,
            updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: now,
            authorId = data["authorId"] as? String,
            authorDisplayName = data["authorDisplayName"] as? String,
            visibility = if (markReceived) "private" else (data["visibility"] as? String ?: "private")
        )

        val sections = (data["sections"] as? List<Map<String, Any>>)?.map { s ->
            RecipeSectionEntity(
                id = s["id"] as? String ?: "",
                recipeId = docId,
                name = s["name"] as? String ?: "",
                orderIndex = (s["orderIndex"] as? Number)?.toInt() ?: 0
            )
        } ?: emptyList()

        val ingredients = (data["ingredients"] as? List<Map<String, Any>>)?.map { ing ->
            IngredientEntity(
                id = ing["id"] as? String ?: "",
                recipeId = docId,
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

        val steps = (data["steps"] as? List<Map<String, Any>>)?.map { step ->
            StepEntity(
                id = step["id"] as? String ?: "",
                recipeId = docId,
                sectionId = step["sectionId"] as? String,
                instruction = step["instruction"] as? String ?: "",
                orderIndex = (step["orderIndex"] as? Number)?.toInt() ?: 0
            )
        } ?: emptyList()

        val refs = (data["stepIngredientRefs"] as? List<Map<String, Any>>)?.map { ref ->
            StepIngredientRefEntity(
                stepId = ref["stepId"] as? String ?: "",
                ingredientId = ref["ingredientId"] as? String ?: "",
                quantityDisplay = ref["quantityDisplay"] as? String
            )
        } ?: emptyList()

        return RecipeData(recipe, sections, ingredients, steps, refs)
    }
}

/** Bundle type for parsed recipe data. */
private data class RecipeData(
    val first: RecipeEntity,
    val second: List<RecipeSectionEntity>,
    val third: List<IngredientEntity>,
    val fourth: List<StepEntity>,
    val fifth: List<StepIngredientRefEntity>
)
