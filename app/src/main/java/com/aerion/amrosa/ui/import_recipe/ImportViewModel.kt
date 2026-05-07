package com.aerion.amrosa.ui.import_recipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

data class ImportUiState(
    val url: String = "",
    val isImporting: Boolean = false,
    val errorMessage: String? = null,
    val importedRecipes: List<Recipe> = emptyList(),
    val isLoading: Boolean = true,
    // Parsed recipe awaiting review
    val parsedRecipe: ParsedRecipeData? = null
)

/**
 * Holds the raw parsed data from the Cloud Function, before it's saved to Room.
 */
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
    val stepIngredientRefs: List<ParsedStepRef>
)

data class ParsedSection(val id: String, val name: String, val orderIndex: Int)
data class ParsedIngredient(
    val id: String, val sectionId: String?, val name: String,
    val quantityValue: Double?, val quantityUnit: String?,
    val quantityDisplay: String?, val groupLabel: String?,
    val isOptional: Boolean, val orderIndex: Int
)
data class ParsedStep(
    val id: String, val sectionId: String?,
    val instruction: String, val orderIndex: Int
)
data class ParsedStepRef(
    val stepId: String, val ingredientId: String, val quantityDisplay: String?
)

class ImportViewModel(
    private val repository: RecipeRepository,
    private val gson: Gson
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    private val functions = FirebaseFunctions.getInstance("us-central1")

    init {
        viewModelScope.launch {
            repository.getImportedRecipes().collect { recipes ->
                _uiState.update { it.copy(importedRecipes = recipes, isLoading = false) }
            }
        }
    }

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
                    functions
                        .getHttpsCallable("parseRecipeUrl")
                        .call(hashMapOf("url" to url))
                        .await()
                }

                @Suppress("UNCHECKED_CAST")
                val data = result.getData() as? Map<String, Any>
                    ?: throw Exception("Empty response from server")

                @Suppress("UNCHECKED_CAST")
                val recipeMap = data["recipe"] as? Map<String, Any>
                    ?: throw Exception("No recipe in response")

                val parsed = mapToParsedRecipe(recipeMap)

                _uiState.update {
                    it.copy(
                        isImporting = false,
                        parsedRecipe = parsed,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                val message = when {
                    e.message?.contains("INVALID_ARGUMENT") == true -> "Invalid URL format"
                    e.message?.contains("NOT_FOUND") == true -> "Could not reach that URL"
                    e.message?.contains("FAILED_PRECONDITION") == true ->
                        e.message?.substringAfter("FAILED_PRECONDITION: ")
                            ?: "Could not find a recipe on this page"
                    e.message?.contains("INTERNAL") == true ->
                        e.message?.substringAfter("INTERNAL: ") ?: "Failed to parse the recipe."
                    else -> "Import failed: ${e.message}"
                }
                _uiState.update { it.copy(isImporting = false, errorMessage = message) }
            }
        }
    }

    fun dismissReview() {
        _uiState.update { it.copy(parsedRecipe = null) }
    }

    fun saveImportedRecipe() {
        val parsed = _uiState.value.parsedRecipe ?: return

        viewModelScope.launch {
            try {
                val recipeId = "imported-${UUID.randomUUID()}"
                val now = System.currentTimeMillis()

                // Remap all IDs so they're unique and prefixed with the recipe ID
                val sectionIdMap = mutableMapOf<String, String>()
                val ingredientIdMap = mutableMapOf<String, String>()
                val stepIdMap = mutableMapOf<String, String>()

                parsed.sections.forEachIndexed { i, s ->
                    sectionIdMap[s.id] = "sec-${recipeId}-${i}"
                }
                parsed.ingredients.forEachIndexed { i, ing ->
                    ingredientIdMap[ing.id] = "ing-${recipeId}-${i}"
                }
                parsed.steps.forEachIndexed { i, step ->
                    stepIdMap[step.id] = "step-${recipeId}-${i}"
                }

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
                    createdAt = now,
                    updatedAt = now
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

                _uiState.update {
                    it.copy(parsedRecipe = null, url = "", errorMessage = null)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to save: ${e.message}") }
            }
        }
    }

    fun deleteImportedRecipe(recipeId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.deleteFullRecipe(recipeId)
            }
        }
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
                orderIndex = (ing["orderIndex"] as? Number)?.toInt() ?: 0
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
            stepIngredientRefs = refs
        )
    }

    companion object {
        fun factory(repository: RecipeRepository, gson: Gson): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ImportViewModel(repository, gson) as T
            }
    }
}
