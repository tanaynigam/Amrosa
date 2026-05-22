package com.aerion.amrosa.data.remote

import android.content.Context
import android.util.Log
import com.aerion.amrosa.data.local.entity.*
import com.aerion.amrosa.data.repository.RecipeRepository
import com.aerion.amrosa.domain.model.Recipe
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import kotlinx.coroutines.tasks.await

/**
 * Syncs recipes from Firestore → local Room DB.
 *
 * Firestore structure:
 *   recipes/{recipeId}           — recipe metadata + nested arrays
 *     .sections[]                — inline
 *     .ingredients[]             — inline
 *     .steps[]                   — inline
 *     .stepIngredientRefs[]      — inline
 *     .updatedAt: Long           — millis, used for delta sync
 *
 * The app only READS from the shared "recipes" collection.
 * Sync is pull-only: fetch docs where updatedAt > lastSyncTimestamp.
 */
class RecipeSyncService(
    private val context: Context,
    private val repository: RecipeRepository,
    private val gson: Gson
) {
    private val firestore = FirebaseFirestore.getInstance()
    private val prefs = context.getSharedPreferences("amrosa_sync", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "RecipeSyncService"
        private const val KEY_LAST_SYNC = "last_sync_timestamp"
        private const val COLLECTION_RECIPES = "recipes"
        private const val COLLECTION_PERSONAL = "personal_recipes"
    }

    /**
     * Pull recipes updated since last sync. Upserts into Room.
     * Returns number of recipes synced.
     */
    suspend fun sync(): Int {
        val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
        Log.d(TAG, "Syncing recipes updated after $lastSync")

        return try {
            val snapshot = firestore.collection(COLLECTION_RECIPES)
                .whereGreaterThan("updatedAt", lastSync)
                .get()
                .await()

            if (snapshot.isEmpty) {
                Log.d(TAG, "No new recipes to sync")
                return 0
            }

            var count = 0
            val total = snapshot.documents.size
            for (doc in snapshot.documents) {
                try {
                    val recipe = parseRecipe(doc.id, doc.data ?: continue)
                    repository.insertFullRecipe(
                        recipe.first,
                        recipe.second,
                        recipe.third,
                        recipe.fourth,
                        recipe.fifth
                    )
                    count++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse recipe ${doc.id}", e)
                }
            }

            // Only advance the sync timestamp when every document in this batch
            // was parsed and inserted successfully. If any failed, leave the
            // timestamp unchanged so those recipes are re-fetched next sync.
            // Re-upserting already-synced recipes is harmless (REPLACE strategy).
            if (count == total) {
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_SYNC, now).apply()
                Log.d(TAG, "Synced $count/$total recipes — timestamp advanced")
            } else {
                Log.w(TAG, "Synced $count/$total recipes — ${total - count} failed, timestamp NOT advanced (will retry next sync)")
            }
            count
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            0
        }
    }

    /**
     * Force a full re-sync (clear timestamp, pull everything).
     */
    suspend fun forceFullSync(): Int {
        prefs.edit().putLong(KEY_LAST_SYNC, 0L).apply()
        return sync()
    }

    /**
     * Push a personal (non-imported) recipe from Room up to Firestore's
     * [COLLECTION_PERSONAL] collection so it is backed up in the cloud.
     *
     * The document structure mirrors the shared `recipes` collection so the
     * same [parseRecipe] logic can re-hydrate it if needed in the future.
     *
     * @return true if the push succeeded, false on error.
     */
    suspend fun pushPersonalRecipe(recipeId: String): Boolean {
        return try {
            val recipe = repository.getRecipeWithDetails(recipeId)
            if (recipe == null) {
                Log.w(TAG, "pushPersonalRecipe: recipe $recipeId not found in Room")
                return false
            }
            val doc = buildFirestoreDocument(recipe)
            firestore.collection(COLLECTION_PERSONAL)
                .document(recipeId)
                .set(doc, SetOptions.merge())
                .await()
            Log.d(TAG, "Pushed personal recipe $recipeId to Firestore")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push personal recipe $recipeId", e)
            false
        }
    }

    // ─── Firestore serialisation ──────────────────────────────────────────────

    /**
     * Serialise a full [Recipe] domain model into a Firestore-compatible Map.
     * The structure mirrors what [parseRecipe] expects so the pull path can
     * re-hydrate documents written here.
     */
    private fun buildFirestoreDocument(recipe: Recipe): Map<String, Any?> {
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
                "groupLabel" to ing.groupLabel,
                "isOptional" to ing.isOptional,
                "substituteGroupId" to ing.substituteGroupId,
                "substituteRatio" to ing.substituteRatio,
                "orderIndex" to ing.orderIndex
            )
        }

        // Flatten step-ingredient refs from all steps
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
            "sections" to sections,
            "ingredients" to ingredients,
            "steps" to steps,
            "stepIngredientRefs" to stepRefs
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseRecipe(
        docId: String,
        data: Map<String, Any>
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
            isCustomized = false,
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: now,
            updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: now
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
