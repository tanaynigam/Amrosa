package com.aerion.amrosa.ui.freeform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.amrosa.data.auth.AuthRepository
import com.aerion.amrosa.data.local.entity.*
import com.aerion.amrosa.data.repository.RecipeRepository
import com.aerion.amrosa.ui.import_recipe.*
import com.google.firebase.functions.FirebaseFunctions
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

data class FreeformUiState(
    val text: String = "",
    val isFormatting: Boolean = false,
    val errorMessage: String? = null,
    val parsedRecipe: ParsedRecipeData? = null,
    val savedRecipeId: String? = null,  // non-null after save → triggers back navigation
    val editRecipeId: String? = null    // non-null after save-and-edit → triggers editor navigation
)

class FreeformViewModel(
    private val repository: RecipeRepository,
    private val gson: Gson,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FreeformUiState())
    val uiState: StateFlow<FreeformUiState> = _uiState.asStateFlow()

    private val functions = FirebaseFunctions.getInstance("us-central1")

    // ─── Text input ───────────────────────────────────────────────────────────

    fun onTextChange(text: String) {
        _uiState.update { it.copy(text = text, errorMessage = null) }
    }

    // ─── Format with Gemini ───────────────────────────────────────────────────

    fun formatWithGemini() {
        val text = _uiState.value.text.trim()
        if (text.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter some recipe text first.") }
            return
        }
        _uiState.update { it.copy(isFormatting = true, errorMessage = null, parsedRecipe = null) }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    functions.getHttpsCallable("formatRecipeText")
                        .call(hashMapOf("text" to text))
                        .await()
                }
                val parsed = extractParsedRecipe(result.getData())
                _uiState.update { it.copy(isFormatting = false, parsedRecipe = parsed) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isFormatting = false, errorMessage = friendlyError(e)) }
            }
        }
    }

    /** Re-run Gemini on the current text — dismisses the current review sheet first. */
    fun reformat() {
        _uiState.update { it.copy(parsedRecipe = null) }
        formatWithGemini()
    }

    // ─── Review sheet actions ─────────────────────────────────────────────────

    /** Save the reviewed recipe to Room as a confirmed personal recipe. */
    fun saveRecipe() {
        val parsed = _uiState.value.parsedRecipe ?: return
        viewModelScope.launch {
            val recipeId = "personal-${UUID.randomUUID()}"
            val now = System.currentTimeMillis()
            persistParsed(parsed, recipeId, now)
            _uiState.update { it.copy(parsedRecipe = null, savedRecipeId = recipeId) }
        }
    }

    /** Save to Room and then open the Recipe Editor for fine-grained editing. */
    fun saveAndEdit() {
        val parsed = _uiState.value.parsedRecipe ?: return
        viewModelScope.launch {
            val recipeId = "personal-${UUID.randomUUID()}"
            val now = System.currentTimeMillis()
            persistParsed(parsed, recipeId, now)
            _uiState.update { it.copy(parsedRecipe = null, editRecipeId = recipeId) }
        }
    }

    /** Dismiss the review sheet without saving (user can edit text and reformat). */
    fun dismissReview() {
        _uiState.update { it.copy(parsedRecipe = null) }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

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
            isImported = false,     // freeform = personal recipe
            needsReview = false,    // saved directly after user review
            createdAt = now,
            updatedAt = now,
            authorId = authRepository.uid,
            authorDisplayName = authRepository.displayName ?: authRepository.email
        )

        val sections = parsed.sections.map { s ->
            RecipeSectionEntity(sectionIdMap[s.id]!!, recipeId, s.name, s.orderIndex)
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
            StepEntity(stepIdMap[step.id]!!, recipeId, step.sectionId?.let { sectionIdMap[it] },
                step.instruction, step.orderIndex)
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

    @Suppress("UNCHECKED_CAST")
    private fun extractParsedRecipe(raw: Any?): ParsedRecipeData {
        val data = raw as? Map<String, Any> ?: throw Exception("Empty response from server")
        val recipeMap = data["recipe"] as? Map<String, Any> ?: throw Exception("No recipe in response")
        return mapToParsedRecipe(recipeMap)
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapToParsedRecipe(map: Map<String, Any>): ParsedRecipeData {
        val sections = (map["sections"] as? List<Map<String, Any>>)?.map { s ->
            ParsedSection(s["id"] as? String ?: "", s["name"] as? String ?: "",
                (s["orderIndex"] as? Number)?.toInt() ?: 0)
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
            ParsedStep(step["id"] as? String ?: "", step["sectionId"] as? String,
                step["instruction"] as? String ?: "", (step["orderIndex"] as? Number)?.toInt() ?: 0)
        } ?: emptyList()

        val refs = (map["stepIngredientRefs"] as? List<Map<String, Any>>)?.map { ref ->
            ParsedStepRef(ref["stepId"] as? String ?: "", ref["ingredientId"] as? String ?: "",
                ref["quantityDisplay"] as? String)
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
            sections = sections, ingredients = ingredients, steps = steps, stepIngredientRefs = refs,
            parseNotes = map["parseNotes"] as? String
        )
    }

    private fun friendlyError(e: Exception): String = when {
        e.message?.contains("INVALID_ARGUMENT") == true ->
            e.message?.substringAfter("INVALID_ARGUMENT: ") ?: "Invalid input"
        e.message?.contains("FAILED_PRECONDITION") == true ->
            e.message?.substringAfter("FAILED_PRECONDITION: ") ?: "Could not parse the recipe"
        e.message?.contains("INTERNAL") == true ->
            e.message?.substringAfter("INTERNAL: ") ?: "Failed to format the recipe"
        else -> "Format failed: ${e.message}"
    }

    companion object {
        fun factory(
            repository: RecipeRepository,
            gson: Gson,
            authRepository: AuthRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    FreeformViewModel(repository, gson, authRepository) as T
            }
    }
}
