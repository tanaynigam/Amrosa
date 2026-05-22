package com.aerion.amrosa.ui.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aerion.amrosa.data.auth.AuthRepository
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

// ─── UI state ─────────────────────────────────────────────────────────────────

enum class AuthMode { SIGN_IN, SIGN_UP }

enum class PhoneStep { NONE, ENTERING_NUMBER, ENTERING_OTP }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    // Phone-specific
    val phoneStep: PhoneStep = PhoneStep.NONE,
    val verificationId: String? = null,
    // Navigation trigger — non-null after a successful sign-in
    val authenticatedUserId: String? = null
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // ── Mode toggle ───────────────────────────────────────────────────────────

    fun setMode(mode: AuthMode) {
        _uiState.update { it.copy(mode = mode, errorMessage = null, phoneStep = PhoneStep.NONE) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ── Google ────────────────────────────────────────────────────────────────

    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.signInWithGoogle(idToken)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    authenticatedUserId = result.getOrNull()?.uid,
                    errorMessage = result.exceptionOrNull()?.let { e -> friendlyError(e) }
                )
            }
        }
    }

    // ── Email / Password ──────────────────────────────────────────────────────

    fun signInWithEmail(email: String, password: String) {
        if (!validateEmail(email) || !validatePassword(password)) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.signInWithEmail(email.trim(), password)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    authenticatedUserId = result.getOrNull()?.uid,
                    errorMessage = result.exceptionOrNull()?.let { e -> friendlyError(e) }
                )
            }
        }
    }

    fun signUpWithEmail(name: String, email: String, password: String, confirmPassword: String) {
        if (name.isBlank()) { _uiState.update { it.copy(errorMessage = "Please enter your name.") }; return }
        if (!validateEmail(email)) return
        if (password != confirmPassword) { _uiState.update { it.copy(errorMessage = "Passwords don't match.") }; return }
        if (!validatePassword(password)) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.signUpWithEmail(name.trim(), email.trim(), password)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    authenticatedUserId = result.getOrNull()?.uid,
                    errorMessage = result.exceptionOrNull()?.let { e -> friendlyError(e) }
                )
            }
        }
    }

    // ── Phone OTP ─────────────────────────────────────────────────────────────

    fun showPhoneEntry() {
        _uiState.update { it.copy(phoneStep = PhoneStep.ENTERING_NUMBER, errorMessage = null) }
    }

    fun cancelPhone() {
        _uiState.update { it.copy(phoneStep = PhoneStep.NONE, verificationId = null, errorMessage = null) }
    }

    fun sendPhoneOtp(phoneNumber: String, activity: Activity) {
        if (phoneNumber.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter a phone number.") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                // Auto-verified (e.g. SIM-based on some Android devices)
                viewModelScope.launch {
                    val result = authRepository.signInWithPhoneCredential(credential)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            phoneStep = PhoneStep.NONE,
                            authenticatedUserId = result.getOrNull()?.uid,
                            errorMessage = result.exceptionOrNull()?.let { e -> friendlyError(e) }
                        )
                    }
                }
            }

            override fun onVerificationFailed(e: FirebaseException) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = friendlyError(e))
                }
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        verificationId = verificationId,
                        phoneStep = PhoneStep.ENTERING_OTP
                    )
                }
            }
        }

        PhoneAuthProvider.verifyPhoneNumber(
            PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
                .setPhoneNumber(phoneNumber.trim())
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(callbacks)
                .build()
        )
    }

    fun verifyPhoneOtp(code: String) {
        val verificationId = _uiState.value.verificationId ?: return
        if (code.length < 6) {
            _uiState.update { it.copy(errorMessage = "Please enter the 6-digit code.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val credential = PhoneAuthProvider.getCredential(verificationId, code.trim())
            val result = authRepository.signInWithPhoneCredential(credential)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    phoneStep = if (result.isSuccess) PhoneStep.NONE else it.phoneStep,
                    authenticatedUserId = result.getOrNull()?.uid,
                    errorMessage = result.exceptionOrNull()?.let { e -> friendlyError(e) }
                )
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun validateEmail(email: String): Boolean {
        if (email.isBlank() || !email.contains('@')) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid email address.") }
            return false
        }
        return true
    }

    private fun validatePassword(password: String): Boolean {
        if (password.length < 6) {
            _uiState.update { it.copy(errorMessage = "Password must be at least 6 characters.") }
            return false
        }
        return true
    }

    private fun friendlyError(e: Throwable): String = when {
        e.message?.contains("ERROR_USER_NOT_FOUND") == true ||
        e.message?.contains("no user record") == true -> "No account found with that email."
        e.message?.contains("ERROR_WRONG_PASSWORD") == true ||
        e.message?.contains("INVALID_LOGIN_CREDENTIALS") == true -> "Incorrect email or password."
        e.message?.contains("ERROR_EMAIL_ALREADY_IN_USE") == true ||
        e.message?.contains("email address is already in use") == true -> "An account already exists with that email."
        e.message?.contains("ERROR_CREDENTIAL_ALREADY_IN_USE") == true -> "This account is already linked to another user."
        e.message?.contains("ERROR_INVALID_VERIFICATION_CODE") == true -> "Incorrect OTP code. Please try again."
        e.message?.contains("ERROR_SESSION_EXPIRED") == true -> "The OTP has expired. Please request a new one."
        e.message?.contains("ERROR_TOO_MANY_REQUESTS") == true -> "Too many attempts. Please try again later."
        e.message?.contains("NETWORK_ERROR") == true -> "No network connection."
        else -> e.localizedMessage ?: "Something went wrong. Please try again."
    }

    companion object {
        fun factory(authRepository: AuthRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AuthViewModel(authRepository) as T
            }
    }
}
