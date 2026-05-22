package com.aerion.amrosa.ui.import_recipe

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.amrosa.data.auth.AuthRepository
import com.aerion.amrosa.data.local.entity.*
import com.aerion.amrosa.data.repository.RecipeRepository
import com.aerion.amrosa.domain.model.Recipe
import com.google.firebase.functions.FirebaseFunctions
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

// ─── UI state ────────────────────────────────────────────────────────────────

data class ImportUiState(
    val url: String = "",
    val isImporting: Boolean = false,       // URL import in flight
    val isImportingFile: Boolean = false,   // file import in flight
    val isReimporting: Boolean = false,     // reimport in flight
    val errorMessage: String? = null,
    // When non-null the review sheet is open
    val parsedRecipe: ParsedRecipeData? = null,
    val reviewingRecipeId: String? = null,  // Room ID of the recipe under review
    // Author choice on review sheet:
    //   false (default) = "Imported" — external recipe, shows "Imported" when shared
    //   true            = "My Recipe" — user's own recipe, shows real name when shared
    val isOwnRecipe: Boolean = false
)

// ─── Parsed recipe data models ────────────────────────────────────────────────

data class ParsedRecipeData(
    val title: String,
    val description: String?,
    val sourceUrls: List<String>,
    val baseServings: Int,
    val baseServingsMin: Int?,
    val baseServingsMax: Int?,
    val prepTimeMinutes: Int?,
    val cookTimeMinutes: Int?,
    val imageUrl: String?,
    val tags: List<String>,
    val sections: List<ParsedSection>,
    val ingredients: List<ParsedIngredient>,
    val steps: List<ParsedStep>,
    val stepIngredientRefs: List<ParsedStepRef>,
    /** Gemini's note about anything it couldn't confidently extract. Null if clean. */
    val parseNotes: String? = null
)

data class ParsedSection(val id: String, val name: String, val orderIndex: Int)
data class ParsedIngredient(
    val id: String, val sectionId: String?, val name: String,
    val quantityValue: Double?, val quantityUnit: String?,
    val quantityDisplay: String?, val groupLabel: String?,
    val isOptional: Boolean, val orderIndex: Int,
    // F6: unit conversions
    val quantityValueMetric: Double? = null,
    val quantityUnitMetric: String? = null,
    val quantityDisplayMetric: String? = null,
    val quantityValueImperial: Double? = null,
    val quantityUnitImperial: String? = null,
    val quantityDisplayImperial: String? = null,
)
data class ParsedStep(
    val id: String, val sectionId: String?,
    val instruction: String, val orderIndex: Int
)
data class ParsedStepRef(
    val stepId: String, val ingredientId: String, val quantityDisplay: String?
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

class ImportViewModel(
    private val repository: RecipeRepository,
    private val gson: Gson,
    private val context: Context,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    private val functions = FirebaseFunctions.getInstance("us-central1")


    // ─── URL import ───────────────────────────────────────────────────────────

    fun onUrlChange(url: String) {
        _uiState.update { it.copy(url = url, errorMessage = null) }
    }

    fun importRecipe() {
        val url = _uiState.value.url.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter a URL") }
            return
        }
        _uiState.update { it.copy(isImporting = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    functions.getHttpsCallable("parseRecipeUrl")
                        .call(hashMapOf("url" to url))
                        .await()
                }
                val parsed = extractParsedRecipe(result.getData())
                val recipeId = savePendingRecipe(parsed)
                _uiState.update {
                    it.copy(isImporting = false, parsedRecipe = parsed,
                        reviewingRecipeId = recipeId, errorMessage = null)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isImporting = false, errorMessage = friendlyError(e)) }
            }
        }
    }

    // ─── File import ──────────────────────────────────────────────────────────

    fun importFromFile(uri: Uri) {
        _uiState.update { it.copy(isImportingFile = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val (content, fileType, fileName) = readFile(uri)
                val result = withContext(Dispatchers.IO) {
                    functions.getHttpsCallable("parseRecipeContent")
                        .call(hashMapOf("content" to content, "type" to fileType, "fileName" to fileName))
                        .await()
                }
                val parsed = extractParsedRecipe(result.getData())
                val recipeId = savePendingRecipe(parsed)
                _uiState.update {
                    it.copy(isImportingFile = false, parsedRecipe = parsed,
                        reviewingRecipeId = recipeId, errorMessage = null)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isImportingFile = false, errorMessage = friendlyError(e)) }
            }
        }
    }

    // ─── Review sheet actions ─────────────────────────────────────────────────

    /** Toggle whether the recipe being reviewed is the user's own or an external import. */
    fun setIsOwnRecipe(isOwn: Boolean) {
        _uiState.update { it.copy(isOwnRecipe = isOwn) }
    }

    /**
     * User confirmed the review — clears needsReview and applies the author choice:
     *   isOwnRecipe = true  → isImported = false (personal recipe, real name shown when shared)
     *   isOwnRecipe = false → isImported = true  (external import, "Imported" shown when shared)
     */
    fun confirmRecipe() {
        val state = _uiState.value
        val recipeId = state.reviewingRecipeId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.confirmImportedRecipe(recipeId)
                repository.updateIsImported(recipeId, !state.isOwnRecipe)
            }
            _uiState.update { it.copy(parsedRecipe = null, reviewingRecipeId = null, url = "", isOwnRecipe = false) }
        }
    }

    /**
     * User dismissed the review sheet (back gesture).
     * The recipe stays in Room with needsReview = true — no data is lost.
     */
    fun dismissReview() {
        _uiState.update { it.copy(parsedRecipe = null, reviewingRecipeId = null, isOwnRecipe = false) }
    }

    /**
     * Re-import from the same URL (for URL / Google Sheets / Google Docs sources).
     * Replaces the existing pending Room record with the fresh parse result.
     */
    fun reimportUrl() {
        val recipeId = _uiState.value.reviewingRecipeId ?: return
        val sourceUrl = _uiState.value.parsedRecipe?.sourceUrls?.firstOrNull() ?: return

        _uiState.update { it.copy(isReimporting = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    functions.getHttpsCallable("parseRecipeUrl")
                        .call(hashMapOf("url" to sourceUrl))
                        .await()
                }
                val parsed = extractParsedRecipe(result.getData())
                replacePendingRecipe(parsed, recipeId)
                _uiState.update {
                    it.copy(isReimporting = false, parsedRecipe = parsed, errorMessage = null)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isReimporting = false, errorMessage = friendlyError(e)) }
            }
        }
    }

    /**
     * Re-import from a freshly picked file.
     * Replaces the existing pending Room record with the fresh parse result.
     */
    fun reimportFromFile(uri: Uri) {
        val recipeId = _uiState.value.reviewingRecipeId ?: return
        _uiState.update { it.copy(isReimporting = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val (content, fileType, fileName) = readFile(uri)
                val result = withContext(Dispatchers.IO) {
                    functions.getHttpsCallable("parseRecipeContent")
                        .call(hashMapOf("content" to content, "type" to fileType, "fileName" to fileName))
                        .await()
                }
                val parsed = extractParsedRecipe(result.getData())
                replacePendingRecipe(parsed, recipeId)
                _uiState.update {
                    it.copy(isReimporting = false, parsedRecipe = parsed, errorMessage = null)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isReimporting = false, errorMessage = friendlyError(e)) }
            }
        }
    }

    /**
     * Open the review sheet for a recipe that already exists in Room
     * with needsReview = true (e.g. user taps the card after hitting back).
     */
    fun openReviewForRecipe(recipeId: String) {
        viewModelScope.launch {
            val recipe = withContext(Dispatchers.IO) {
                repository.getRecipeWithDetails(recipeId)
            } ?: return@launch
            _uiState.update {
                it.copy(
                    parsedRecipe = recipe.toParsedRecipeData(),
                    reviewingRecipeId = recipeId,
                    isOwnRecipe = !recipe.isImported  // reflect what was last saved
                )
            }
        }
    }

    fun deleteImportedRecipe(recipeId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteFullRecipe(recipeId) }
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Save a parsed recipe to Room immediately with [needsReview] = true.
     * Returns the new recipe ID.
     */
    private suspend fun savePendingRecipe(parsed: ParsedRecipeData): String {
        val recipeId = "imported-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        persistParsed(parsed, recipeId, now)
        return recipeId
    }

    /**
     * Delete an existing pending record and re-insert with fresh parse data,
     * preserving the same [recipeId] so the card in the list updates in place.
     */
    private suspend fun replacePendingRecipe(parsed: ParsedRecipeData, recipeId: String) {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            repository.deleteFullRecipe(recipeId)
            persistParsed(parsed, recipeId, now)
        }
    }

    /** Build Room entities from [parsed] and insert with needsReview = true. */
    private suspend fun persistParsed(parsed: ParsedRecipeData, recipeId: String, now: Long) {
        val sectionIdMap = mutableMapOf<String, String>()
        val ingredientIdMap = mutableMapOf<String, String>()
        val stepIdMap = mutableMapOf<String, String>()

        parsed.sections.forEachIndexed { i, s -> sectionIdMap[s.id] = "sec-$recipeId-$i" }
        parsed.ingredients.forEachIndexed { i, ing -> ingredientIdMap[ing.id] = "ing-$recipeId-$i" }
        parsed.steps.forEachIndexed { i, step -> stepIdMap[step.id] = "step-$recipeId-$i" }

        val recipe = RecipeEntity(
            id = recipeId,
            title = parsed.title,
            description = parsed.description,
            sourceUrls = gson.toJson(parsed.sourceUrls),
            baseServings = parsed.baseServings,
            baseServingsMin = parsed.baseServingsMin,
            baseServingsMax = parsed.baseServingsMax,
            scaleIngredientId = null,
            scaleStep = 1.0,
            prepTimeMinutes = parsed.prepTimeMinutes,
            cookTimeMinutes = parsed.cookTimeMinutes,
            imageUrl = parsed.imageUrl,
            tags = gson.toJson(parsed.tags),
            isCustomized = true,
            isImported = true,
            needsReview = true,
            createdAt = now,
            updatedAt = now,
            // Track who imported it (used for ownership/security; display name is
            // overridden to "Imported" by SharedRecipeService when published)
            authorId = authRepository.uid,
            authorDisplayName = authRepository.displayName ?: authRepository.email
        )

        val sections = parsed.sections.map { s ->
            RecipeSectionEntity(
                id = sectionIdMap[s.id]!!,
                recipeId = recipeId,
                name = s.name,
                orderIndex = s.orderIndex
            )
        }

        val ingredients = parsed.ingredients.map { ing ->
            IngredientEntity(
                id = ingredientIdMap[ing.id]!!,
                recipeId = recipeId,
                sectionId = ing.sectionId?.let { sectionIdMap[it] },
                name = ing.name,
                quantityValue = ing.quantityValue,
                quantityUnit = ing.quantityUnit,
                quantityDisplay = ing.quantityDisplay,
                quantityValueMetric = ing.quantityValueMetric,
                quantityUnitMetric = ing.quantityUnitMetric,
                quantityDisplayMetric = ing.quantityDisplayMetric,
                quantityValueImperial = ing.quantityValueImperial,
                quantityUnitImperial = ing.quantityUnitImperial,
                quantityDisplayImperial = ing.quantityDisplayImperial,
                groupLabel = ing.groupLabel,
                isOptional = ing.isOptional,
                substituteGroupId = null,
                substituteRatio = 1.0f,
                orderIndex = ing.orderIndex
            )
        }

        val steps = parsed.steps.map { step ->
            StepEntity(
                id = stepIdMap[step.id]!!,
                recipeId = recipeId,
                sectionId = step.sectionId?.let { sectionIdMap[it] },
                instruction = step.instruction,
                orderIndex = step.orderIndex
            )
        }

        val refs = parsed.stepIngredientRefs.mapNotNull { ref ->
            val newStepId = stepIdMap[ref.stepId] ?: return@mapNotNull null
            val newIngId = ingredientIdMap[ref.ingredientId] ?: return@mapNotNull null
            StepIngredientRefEntity(newStepId, newIngId, ref.quantityDisplay)
        }

        withContext(Dispatchers.IO) {
            repository.insertFullRecipe(recipe, sections, ingredients, steps, refs)
        }
    }

    /** Read a file from [uri] and return (content, fileType, fileName). */
    private suspend fun readFile(uri: Uri): Triple<String, String, String> {
        val cr = context.contentResolver

        val (fileName, fileSize) = withContext(Dispatchers.IO) {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to
                        cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                    } else "file" to 0L
                } ?: ("file" to 0L)
        }

        if (fileSize > MAX_FILE_BYTES) {
            throw Exception("File too large. Max size is 5 MB.")
        }

        val bytes = withContext(Dispatchers.IO) {
            cr.openInputStream(uri)?.use { it.readBytes() }
                ?: throw Exception("Could not open file")
        }

        val mimeType = cr.getType(uri)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val fileType = when {
            mimeType?.contains("spreadsheet") == true ||
            mimeType?.contains("excel") == true ||
            ext == "xlsx" || ext == "xls" -> "xlsx"
            mimeType == "text/csv" || ext == "csv" -> "csv"
            else -> "text"
        }

        val content = when (fileType) {
            "xlsx" -> Base64.encodeToString(bytes, Base64.NO_WRAP)
            else   -> bytes.toString(Charsets.UTF_8)
        }

        return Triple(content, fileType, fileName)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractParsedRecipe(raw: Any?): ParsedRecipeData {
        val data = raw as? Map<String, Any> ?: throw Exception("Empty response from server")
        val recipeMap = data["recipe"] as? Map<String, Any> ?: throw Exception("No recipe in response")
        return mapToParsedRecipe(recipeMap)
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapToParsedRecipe(map: Map<String, Any>): ParsedRecipeData {
        val sections = (map["sections"] as? List<Map<String, Any>>)?.map { s ->
            ParsedSection(
                id = s["id"] as? String ?: "",
                name = s["name"] as? String ?: "",
                orderIndex = (s["orderIndex"] as? Number)?.toInt() ?: 0
            )
        } ?: emptyList()

        val ingredients = (map["ingredients"] as? List<Map<String, Any>>)?.map { ing ->
            ParsedIngredient(
                id = ing["id"] as? String ?: "",
                sectionId = ing["sectionId"] as? String,
                name = ing["name"] as? String ?: "",
                quantityValue = (ing["quantityValue"] as? Number)?.toDouble(),
                quantityUnit = ing["quantityUnit"] as? String,
                quantityDisplay = ing["quantityDisplay"] as? String,
                groupLabel = ing["groupLabel"] as? String,
                isOptional = ing["isOptional"] as? Boolean ?: false,
                orderIndex = (ing["orderIndex"] as? Number)?.toInt() ?: 0,
                quantityValueMetric = (ing["quantityValueMetric"] as? Number)?.toDouble(),
                quantityUnitMetric = ing["quantityUnitMetric"] as? String,
                quantityDisplayMetric = ing["quantityDisplayMetric"] as? String,
                quantityValueImperial = (ing["quantityValueImperial"] as? Number)?.toDouble(),
                quantityUnitImperial = ing["quantityUnitImperial"] as? String,
                quantityDisplayImperial = ing["quantityDisplayImperial"] as? String,
            )
        } ?: emptyList()

        val steps = (map["steps"] as? List<Map<String, Any>>)?.map { step ->
            ParsedStep(
                id = step["id"] as? String ?: "",
                sectionId = step["sectionId"] as? String,
                instruction = step["instruction"] as? String ?: "",
                orderIndex = (step["orderIndex"] as? Number)?.toInt() ?: 0
            )
        } ?: emptyList()

        val refs = (map["stepIngredientRefs"] as? List<Map<String, Any>>)?.map { ref ->
            ParsedStepRef(
                stepId = ref["stepId"] as? String ?: "",
                ingredientId = ref["ingredientId"] as? String ?: "",
                quantityDisplay = ref["quantityDisplay"] as? String
            )
        } ?: emptyList()

        return ParsedRecipeData(
            title = map["title"] as? String ?: "Untitled Recipe",
            description = map["description"] as? String,
            sourceUrls = (map["sourceUrls"] as? List<String>) ?: emptyList(),
            baseServings = (map["baseServings"] as? Number)?.toInt() ?: 1,
            baseServingsMin = (map["baseServingsMin"] as? Number)?.toInt(),
            baseServingsMax = (map["baseServingsMax"] as? Number)?.toInt(),
            prepTimeMinutes = (map["prepTimeMinutes"] as? Number)?.toInt(),
            cookTimeMinutes = (map["cookTimeMinutes"] as? Number)?.toInt(),
            imageUrl = map["imageUrl"] as? String,
            tags = (map["tags"] as? List<String>) ?: emptyList(),
            sections = sections,
            ingredients = ingredients,
            steps = steps,
            stepIngredientRefs = refs,
            parseNotes = map["parseNotes"] as? String
        )
    }

    private fun friendlyError(e: Exception): String = when {
        e.message?.contains("INVALID_ARGUMENT") == true  -> "Invalid URL format"
        e.message?.contains("NOT_FOUND") == true         -> "Could not reach that URL"
        e.message?.contains("FAILED_PRECONDITION") == true ->
            e.message?.substringAfter("FAILED_PRECONDITION: ")
                ?: "Could not find a recipe on this page"
        e.message?.contains("INTERNAL") == true ->
            e.message?.substringAfter("INTERNAL: ") ?: "Failed to parse the recipe"
        else -> "Import failed: ${e.message}"
    }

    companion object {
        private const val MAX_FILE_BYTES = 5 * 1024 * 1024L // 5 MB

        fun factory(
            repository: RecipeRepository,
            gson: Gson,
            context: Context,
            authRepository: AuthRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ImportViewModel(repository, gson, context, authRepository) as T
            }
    }
}

// ─── Extension: Recipe → ParsedRecipeData (for re-opening review) ────────────

/**
 * Convert a full [Recipe] domain model back into [ParsedRecipeData] so the
 * review sheet can be re-opened for a recipe that's already in Room.
 */
internal fun Recipe.toParsedRecipeData() = ParsedRecipeData(
    title = title,
    description = description,
    sourceUrls = sourceUrls,
    baseServings = baseServings,
    baseServingsMin = baseServingsMin,
    baseServingsMax = baseServingsMax,
    prepTimeMinutes = prepTimeMinutes,
    cookTimeMinutes = cookTimeMinutes,
    imageUrl = imageUrl,
    tags = tags,
    sections = sections.map { ParsedSection(it.id, it.name, it.orderIndex) },
    ingredients = ingredients.map { ing ->
        ParsedIngredient(
            id = ing.id, sectionId = ing.sectionId, name = ing.name,
            quantityValue = ing.quantityValue, quantityUnit = ing.quantityUnit,
            quantityDisplay = ing.quantityDisplay, groupLabel = ing.groupLabel,
            isOptional = ing.isOptional, orderIndex = ing.orderIndex
        )
    },
    steps = steps.map { step ->
        ParsedStep(step.id, step.sectionId, step.instruction, step.orderIndex)
    },
    stepIngredientRefs = steps.flatMap { step ->
        step.ingredientRefs.map { ref ->
            ParsedStepRef(step.id, ref.ingredientId, ref.quantityDisplay)
        }
    },
    parseNotes = null  // not stored in Room; null when re-opening a saved recipe
)
