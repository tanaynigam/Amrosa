package com.aerion.amrosa.ui.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.data.auth.AuthRepository
import com.aerion.amrosa.data.repository.RecipeRepository
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountUiState(
    val user: FirebaseUser? = null,
    val isAnonymous: Boolean = true,
    val isSigningIn: Boolean = false,
    val signInError: String? = null,
    val recipeCount: Int = 0,
    val lastSyncTimestamp: Long = 0L
)

class AccountViewModel(
    private val authRepository: AuthRepository,
    private val repository: RecipeRepository,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    init {
        // Reflect auth state changes in real-time
        viewModelScope.launch {
            authRepository.authStateFlow().collect { user ->
                _uiState.update { it.copy(
                    user = user,
                    isAnonymous = user?.isAnonymous != false
                )}
            }
        }
        loadStats()
    }

    /** Called when Google Sign-In returns a token from the launcher. */
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

    fun signOut() {
        viewModelScope.launch {
            // Wipe all local data first so the next user starts clean
            val app = context.applicationContext as AmrosaApplication
            withContext(Dispatchers.IO) {
                app.container.clearAllLocalData(context)
            }
            authRepository.signOut()
            // Auth state change triggers AmrosaNavGraph to show AuthScreen automatically
        }
    }

    fun clearError() {
        _uiState.update { it.copy(signInError = null) }
    }

    private fun loadStats() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { repository.count() }
            val syncPrefs = context.getSharedPreferences("amrosa_sync", Context.MODE_PRIVATE)
            _uiState.update { it.copy(
                recipeCount = count,
                lastSyncTimestamp = syncPrefs.getLong("last_sync_timestamp", 0L)
            )}
        }
    }

    companion object {
        fun factory(
            authRepository: AuthRepository,
            repository: RecipeRepository,
            context: Context
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AccountViewModel(authRepository, repository, context) as T
        }
    }
}
