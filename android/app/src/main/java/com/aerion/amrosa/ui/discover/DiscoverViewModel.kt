package com.aerion.amrosa.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.amrosa.data.UserPreferences
import com.aerion.amrosa.data.auth.AuthRepository
import com.aerion.amrosa.data.remote.SharedRecipeService
import com.aerion.amrosa.data.remote.SocialRepository
import com.aerion.amrosa.data.repository.RecipeRepository
import com.aerion.amrosa.domain.model.DiscoverRecipe
import com.aerion.amrosa.domain.model.RecipeSource
import com.aerion.amrosa.domain.model.UserProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** Pull-to-refresh in progress (distinct from the initial full-screen load). */
    val isRefreshing: Boolean = false,
    // F13 Phase 3a — cross-scope search
    val searchQuery: String = "",
    val searchResults: List<DiscoverRecipe> = emptyList(),
    val isSearching: Boolean = false,
    // People search (name / email) — surfaced alongside recipe results.
    val userResults: List<UserProfile> = emptyList(),
    val followStatuses: Map<String, String> = emptyMap(),  // uid → none|pending|accepted
    val pendingFollow: String? = null,                     // uid currently being requested
)

class DiscoverViewModel(
    private val repository: RecipeRepository,
    private val socialRepository: SocialRepository,
    private val sharedRecipeService: SharedRecipeService,
    private val authRepository: AuthRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    // Cached candidates from the last feed load — searched client-side (own + friends).
    private var localCandidates: List<DiscoverRecipe> = emptyList()
    private var friendCandidatesCache: List<DiscoverRecipe> = emptyList()
    private var searchJob: Job? = null

    init { load(isRefresh = false) }

    fun refresh() = load(isRefresh = true)

    private fun load(isRefresh: Boolean) {
        viewModelScope.launch {
            _uiState.update { if (isRefresh) it.copy(isRefreshing = true) else it.copy(isLoading = true) }
            val now = System.currentTimeMillis()
            val meal = MealClassifier.currentMeal(LocalTime.now().hour)
            val myUid = authRepository.uid

            // ── Local recipes (your kitchen) ──
            val local = repository.getAllRecipes().first()
            val ownTags = local.flatMap { it.tags }
            // Explicit cuisine prefs (Account) override the implicit collection-based affinity.
            val explicit = userPreferences.cuisinePreferences()
            val topCuisines = explicit.ifEmpty { DiscoverRanker.topCuisines(ownTags) }
            val cookedAt = repository.cookedLogFlow().first()
            val localIds = local.map { it.id }.toSet()
            // Like counts for my own published recipes, so own cards show real numbers (not 0).
            val ownLikes = myUid?.let {
                runCatching { sharedRecipeService.getAuthorLikeCounts(it) }.getOrDefault(emptyMap())
            } ?: emptyMap()

            val ownCandidates = local.map { r ->
                DiscoverRecipe(
                    recipeId = r.id, title = r.title, tags = r.tags,
                    prepTimeMinutes = r.prepTimeMinutes, cookTimeMinutes = r.cookTimeMinutes,
                    source = RecipeSource.OWN, authorUid = r.authorId, authorName = r.authorDisplayName,
                    isLocal = true, likeCount = ownLikes[r.id] ?: 0,
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

            // ── Public recipes: recent ∪ popular (cold-start safe), exclude mine + dupes ──
            val friendIds = friendCandidates.map { it.recipeId }.toSet()
            val recentPublic = runCatching { sharedRecipeService.getPublicRecipeSummaries() }.getOrDefault(emptyList())
            val popularPublic = runCatching { sharedRecipeService.getPopularPublicRecipes() }.getOrDefault(emptyList())
            val publicCandidates = (popularPublic + recentPublic)
                .distinctBy { it.recipeId }
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
                        RecipeSource.OWN, r.authorId, r.authorDisplayName, isLocal = true,
                        likeCount = ownLikes[r.id] ?: 0)
                }

            val popularShelf = publicCandidates
                .filter { it.saveCount > 0 || it.likeCount > 0 }
                .sortedByDescending { it.saveCount * 2 + it.likeCount }

            val shelves = buildList {
                add(DiscoverShelf("${meal.label} ideas", leadPool.take(SHELF_LIMIT)))
                add(DiscoverShelf("From your kitchen", ranked(ownCandidates).take(SHELF_LIMIT)))
                add(DiscoverShelf("From your co-chefs", ranked(friendCandidates).take(SHELF_LIMIT)))
                add(DiscoverShelf("Popular", popularShelf.take(SHELF_LIMIT)))
                add(DiscoverShelf("Fresh from the community", publicCandidates.take(SHELF_LIMIT)))
                add(DiscoverShelf("Recently cooked", recentlyCooked.take(SHELF_LIMIT)))
            }.filter { it.recipes.isNotEmpty() }

            localCandidates = ownCandidates
            friendCandidatesCache = friendCandidates

            _uiState.update {
                it.copy(isLoading = false, isRefreshing = false, leadMeal = meal, shelves = shelves,
                    surprisePool = leadPool.ifEmpty { all })
            }
        }
    }

    // ── Cross-scope search (own > friends > public) ─────────────────────────────

    private fun DiscoverRecipe.matchesQuery(q: String): Boolean =
        title.contains(q, ignoreCase = true) || tags.any { it.contains(q, ignoreCase = true) }

    fun onSearchChange(raw: String) {
        _uiState.update { it.copy(searchQuery = raw) }
        searchJob?.cancel()
        val q = raw.trim()
        if (q.length < 2) {
            _uiState.update { it.copy(searchResults = emptyList(), userResults = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)  // debounce
            _uiState.update { it.copy(isSearching = true) }
            val myUid = authRepository.uid
            val own = localCandidates.filter { it.matchesQuery(q) }
            val ownIds = own.map { it.recipeId }.toSet()
            val friends = friendCandidatesCache.filter { it.matchesQuery(q) && it.recipeId !in ownIds }
            val excluded = ownIds + friends.map { it.recipeId }
            val public = runCatching { sharedRecipeService.searchPublicRecipes(q) }.getOrDefault(emptyList())
                .filter { it.recipeId !in excluded && it.authorUid != myUid }
            // People search (name / email) + their follow status.
            val users = runCatching { socialRepository.searchUsers(q) }.getOrDefault(emptyList())
            val statuses = users.associate { it.uid to runCatching { socialRepository.getFollowStatus(it.uid) }.getOrDefault("none") }
            _uiState.update {
                it.copy(
                    searchResults = (own + friends + public).distinctBy { r -> r.recipeId },
                    userResults = users,
                    followStatuses = statuses,
                    isSearching = false
                )
            }
        }
    }

    /** Send a co-chef request to a user from the People search results. */
    fun sendFollowRequest(user: UserProfile) {
        _uiState.update { it.copy(pendingFollow = user.uid) }
        viewModelScope.launch {
            runCatching { socialRepository.sendFollowRequest(user.uid, user.displayName) }
            _uiState.update {
                it.copy(pendingFollow = null, followStatuses = it.followStatuses + (user.uid to "pending"))
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update { it.copy(searchQuery = "", searchResults = emptyList(), userResults = emptyList(), isSearching = false) }
    }

    companion object {
        private const val SHELF_LIMIT = 12
        private const val FRIEND_CAP = 10

        fun factory(
            repository: RecipeRepository,
            socialRepository: SocialRepository,
            sharedRecipeService: SharedRecipeService,
            authRepository: AuthRepository,
            userPreferences: UserPreferences,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DiscoverViewModel(repository, socialRepository, sharedRecipeService, authRepository, userPreferences) as T
        }
    }
}
