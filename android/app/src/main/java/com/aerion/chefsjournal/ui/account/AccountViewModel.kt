package com.aerion.chefsjournal.ui.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.chefsjournal.ChefsJournalApplication
import com.aerion.chefsjournal.data.auth.AuthRepository
import com.aerion.chefsjournal.data.remote.SocialRepository
import com.aerion.chefsjournal.data.repository.RecipeRepository
import com.aerion.chefsjournal.domain.model.UserProfile
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountUiState(
    val user: FirebaseUser? = null,
    val isAnonymous: Boolean = true,
    val isSigningIn: Boolean = false,
    val signInError: String? = null,
    val recipeCount: Int = 0,
    val lastSyncTimestamp: Long = 0L,
    // Social
    val pendingRequests: List<UserProfile> = emptyList(),
    val friendCount: Int = 0,
    /** uid of pending accept/decline action. */
    val pendingFollowAction: String? = null,
    /** Non-null for one snackbar display after a successful name update. */
    val nameUpdateMessage: String? = null,
    // F13 — explicit cuisine preferences (override Discover's implicit affinity)
    val availableCuisines: List<String> = emptyList(),
    val selectedCuisines: Set<String> = emptySet(),
)

class AccountViewModel(
    private val authRepository: AuthRepository,
    private val repository: RecipeRepository,
    private val socialRepository: SocialRepository,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.authStateFlow().collect { user ->
                _uiState.update {
                    it.copy(
                        user = user,
                        isAnonymous = user?.isAnonymous != false
                    )
                }
            }
        }
        loadStats()
        observeSocial()
        loadCuisinePrefs()
    }

    private val userPreferences
        get() = (context.applicationContext as ChefsJournalApplication).container.userPreferences

    private fun loadCuisinePrefs() {
        viewModelScope.launch {
            val selected = userPreferences.cuisinePreferences()
            val ownTags = withContext(Dispatchers.IO) {
                runCatching { repository.getAllRecipes().first().flatMap { it.tags } }.getOrDefault(emptyList())
            }
            val available = (CURATED_CUISINES + ownTags)
                .map { it.trim() }.filter { it.isNotBlank() }
                .filter { it.lowercase() !in com.aerion.chefsjournal.ui.discover.MealClassifier.allMealKeywords }
                .distinctBy { it.lowercase() }
                .sortedBy { it.lowercase() }
            _uiState.update { it.copy(availableCuisines = available, selectedCuisines = selected) }
        }
    }

    /** Toggle a cuisine preference and persist it. */
    fun toggleCuisine(cuisine: String) {
        val key = cuisine.lowercase()
        val updated = _uiState.value.selectedCuisines.let { if (key in it) it - key else it + key }
        _uiState.update { it.copy(selectedCuisines = updated) }
        userPreferences.setCuisinePreferences(updated)
    }

    private fun observeSocial() {
        viewModelScope.launch {
            socialRepository.getPendingRequestsFlow().collect { requests ->
                _uiState.update { it.copy(pendingRequests = requests) }
            }
        }
        viewModelScope.launch {
            socialRepository.getFriendsFlow().collect { friends ->
                _uiState.update { it.copy(friendCount = friends.size) }
            }
        }
    }

    fun acceptFollowRequest(profile: UserProfile) {
        viewModelScope.launch {
            _uiState.update { it.copy(pendingFollowAction = profile.uid) }
            socialRepository.acceptFollowRequest(profile.uid)
            _uiState.update { it.copy(pendingFollowAction = null) }
        }
    }

    fun declineFollowRequest(profile: UserProfile) {
        viewModelScope.launch {
            _uiState.update { it.copy(pendingFollowAction = profile.uid) }
            socialRepository.declineFollowRequest(profile.uid)
            _uiState.update { it.copy(pendingFollowAction = null) }
        }
    }

    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSigningIn = true, signInError = null) }
            val result = authRepository.signInWithGoogle(idToken)
            _uiState.update {
                it.copy(
                    isSigningIn = false,
                    signInError = result.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }

    /**
     * Update the Firebase display name then re-sync the public user profile.
     * Shows a snackbar message on success.
     */
    fun updateDisplayName(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                authRepository.updateDisplayName(name)
                socialRepository.upsertProfile()
                // Reload user so the profile card reflects the new name
                _uiState.update { it.copy(user = authRepository.currentUser, nameUpdateMessage = "Name updated") }
            } catch (e: Exception) {
                _uiState.update { it.copy(nameUpdateMessage = "Failed to update name") }
            }
        }
    }

    fun clearNameUpdateMessage() {
        _uiState.update { it.copy(nameUpdateMessage = null) }
    }

    fun signOut() {
        viewModelScope.launch {
            val app = context.applicationContext as ChefsJournalApplication
            withContext(Dispatchers.IO) {
                app.container.clearAllLocalData(context)
            }
            authRepository.signOut()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(signInError = null) }
    }

    private fun loadStats() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { repository.count() }
            val syncPrefs = context.getSharedPreferences("chefsjournal_sync", Context.MODE_PRIVATE)
            _uiState.update {
                it.copy(
                    recipeCount = count,
                    lastSyncTimestamp = syncPrefs.getLong("last_sync_timestamp", 0L)
                )
            }
        }
    }

    companion object {
        private val CURATED_CUISINES = listOf(
            "Indian", "Italian", "Mexican", "Chinese", "Thai", "Japanese",
            "American", "Mediterranean", "French", "Korean", "Middle Eastern"
        )

        fun factory(
            authRepository: AuthRepository,
            repository: RecipeRepository,
            socialRepository: SocialRepository,
            context: Context
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AccountViewModel(authRepository, repository, socialRepository, context) as T
        }
    }
}
