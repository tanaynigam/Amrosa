package com.aerion.amrosa.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.amrosa.data.auth.AuthRepository
import com.aerion.amrosa.data.remote.SharedRecipeService
import com.aerion.amrosa.data.remote.SocialRepository
import com.aerion.amrosa.data.repository.RecipeRepository
import com.aerion.amrosa.domain.model.DiscoverRecipe
import com.aerion.amrosa.domain.model.RecipeSource
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalTime

/** One horizontal shelf on the Discover feed. */
data class DiscoverShelf(val title: String, val recipes: List<DiscoverRecipe>)

data class DiscoverUiState(
    val isLoading: Boolean = true,
    val leadMeal: MealType = MealType.DINNER,
    val shelves: List<DiscoverShelf> = emptyList(),
    /** Ranked, meal-appropriate pool used by "Surprise me". */
    val surprisePool: List<DiscoverRecipe> = emptyList(),
)

class DiscoverViewModel(
    private val repository: RecipeRepository,
    private val socialRepository: SocialRepository,
    private val sharedRecipeService: SharedRecipeService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    init { load() }

    fun refresh() = load()

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val now = System.currentTimeMillis()
            val meal = MealClassifier.currentMeal(LocalTime.now().hour)
            val myUid = authRepository.uid

            // ── Local recipes (your kitchen) ──
            val local = repository.getAllRecipes().first()
            val ownTags = local.flatMap { it.tags }
            val topCuisines = DiscoverRanker.topCuisines(ownTags)
            val cookedAt = repository.cookedLogFlow().first()
            val localIds = local.map { it.id }.toSet()

            val ownCandidates = local.map { r ->
                DiscoverRecipe(
                    recipeId = r.id, title = r.title, tags = r.tags,
                    prepTimeMinutes = r.prepTimeMinutes, cookTimeMinutes = r.cookTimeMinutes,
                    source = RecipeSource.OWN, authorUid = r.authorId, authorName = r.authorDisplayName,
                    isLocal = true,
                )
            }

            // ── Co-chefs' recipes ──
            val friends = runCatching { socialRepository.getFriendsFlow().first() }.getOrDefault(emptyList())
            val friendCandidates = friends.take(FRIEND_CAP).flatMap { friend ->
                runCatching { socialRepository.getAuthorRecipes(friend.uid, includeFriendsOnly = true) }
                    .getOrDefault(emptyList())
                    .filter { it.recipeId !in localIds }
                    .map { s ->
                        DiscoverRecipe(
                            recipeId = s.recipeId, title = s.title, tags = s.tags,
                            prepTimeMinutes = s.prepTimeMinutes, cookTimeMinutes = s.cookTimeMinutes,
                            source = RecipeSource.FRIEND, authorUid = friend.uid, authorName = friend.displayName,
                            isLocal = false,
                        )
                    }
            }.distinctBy { it.recipeId }

            // ── Public recipes (exclude mine + anything already local/friend) ──
            val friendIds = friendCandidates.map { it.recipeId }.toSet()
            val publicCandidates = runCatching { sharedRecipeService.getPublicRecipeSummaries() }
                .getOrDefault(emptyList())
                .filter { it.authorUid != myUid && it.recipeId !in localIds && it.recipeId !in friendIds }

            // ── Rank + build shelves ──
            fun ranked(list: List<DiscoverRecipe>) =
                DiscoverRanker.rank(list, meal, topCuisines, cookedAt, now)

            val all = ownCandidates + friendCandidates + publicCandidates
            // Lead shelf: meal-appropriate (matching or unclassified) across all sources.
            val leadPool = ranked(all).filter {
                val m = MealClassifier.mealsFor(it.tags); m.isEmpty() || meal in m
            }

            val recentlyCooked = local
                .filter { cookedAt.containsKey(it.id) }
                .sortedByDescending { cookedAt[it.id] }
                .map { r ->
                    DiscoverRecipe(r.id, r.title, r.tags, r.prepTimeMinutes, r.cookTimeMinutes,
                        RecipeSource.OWN, r.authorId, r.authorDisplayName, isLocal = true)
                }

            val shelves = buildList {
                add(DiscoverShelf("${meal.label} ideas", leadPool.take(SHELF_LIMIT)))
                add(DiscoverShelf("From your kitchen", ranked(ownCandidates).take(SHELF_LIMIT)))
                add(DiscoverShelf("From your co-chefs", ranked(friendCandidates).take(SHELF_LIMIT)))
                add(DiscoverShelf("Fresh from the community", publicCandidates.take(SHELF_LIMIT)))
                add(DiscoverShelf("Recently cooked", recentlyCooked.take(SHELF_LIMIT)))
            }.filter { it.recipes.isNotEmpty() }

            _uiState.update {
                it.copy(isLoading = false, leadMeal = meal, shelves = shelves,
                    surprisePool = leadPool.ifEmpty { all })
            }
        }
    }

    companion object {
        private const val SHELF_LIMIT = 12
        private const val FRIEND_CAP = 10

        fun factory(
            repository: RecipeRepository,
            socialRepository: SocialRepository,
            sharedRecipeService: SharedRecipeService,
            authRepository: AuthRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DiscoverViewModel(repository, socialRepository, sharedRecipeService, authRepository) as T
        }
    }
}
