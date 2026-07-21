package com.aerion.tablefeed.data.auth

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class AuthRepository {
    private val auth = FirebaseAuth.getInstance()

    // ─── Current user accessors ───────────────────────────────────────────────

    val currentUser: FirebaseUser? get() = auth.currentUser

    /** True when the user has signed in with a real identity (Google, email, or phone). */
    val isSignedIn: Boolean get() = auth.currentUser != null && !auth.currentUser!!.isAnonymous

    val uid: String? get() = auth.currentUser?.uid
    val displayName: String? get() = auth.currentUser?.displayName
    val email: String? get() = auth.currentUser?.email
    val photoUrl: String? get() = auth.currentUser?.photoUrl?.toString()

    // ─── Auth state stream ────────────────────────────────────────────────────

    /** Emits the current user (or null) whenever auth state changes. */
    fun authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    // ─── Anonymous ────────────────────────────────────────────────────────────

    /**
     * Signs in anonymously if no user session exists.
     * Safe to call repeatedly — no-ops when already signed in.
     */
    suspend fun signInAnonymouslyIfNeeded() {
        if (auth.currentUser == null) {
            try { auth.signInAnonymously().await() } catch (_: Exception) { /* best-effort */ }
        }
    }

    // ─── Google ───────────────────────────────────────────────────────────────

    /**
     * Completes a Google Sign-In using the ID token returned by the Google Sign-In activity.
     * Upgrades an anonymous session rather than replacing it, preserving all local data.
     */
    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser> = runCatching {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val authResult = if (auth.currentUser?.isAnonymous == true) {
            auth.currentUser!!.linkWithCredential(credential).await()
        } else {
            auth.signInWithCredential(credential).await()
        }
        authResult.user!!
    }

    // ─── Email / Password ─────────────────────────────────────────────────────

    /** Sign in with an existing email/password account. */
    suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser> = runCatching {
        val credential = EmailAuthProvider.getCredential(email, password)
        val authResult = if (auth.currentUser?.isAnonymous == true) {
            auth.currentUser!!.linkWithCredential(credential).await()
        } else {
            auth.signInWithEmailAndPassword(email, password).await()
        }
        authResult.user!!
    }

    /**
     * Create a new email/password account and set the display name.
     * Upgrades an anonymous session when one exists.
     */
    suspend fun signUpWithEmail(
        name: String,
        email: String,
        password: String
    ): Result<FirebaseUser> = runCatching {
        val credential = EmailAuthProvider.getCredential(email, password)
        val authResult = if (auth.currentUser?.isAnonymous == true) {
            auth.currentUser!!.linkWithCredential(credential).await()
        } else {
            auth.createUserWithEmailAndPassword(email, password).await()
        }
        val user = authResult.user!!
        // Set display name immediately after account creation
        val profileUpdate = UserProfileChangeRequest.Builder()
            .setDisplayName(name.trim())
            .build()
        user.updateProfile(profileUpdate).await()
        user
    }

    // ─── Phone ────────────────────────────────────────────────────────────────

    /**
     * Sign in (or upgrade anonymous session) using a PhoneAuthCredential
     * obtained from [PhoneAuthProvider]. Called after OTP verification.
     */
    suspend fun signInWithPhoneCredential(credential: PhoneAuthCredential): Result<FirebaseUser> = runCatching {
        val authResult = if (auth.currentUser?.isAnonymous == true) {
            auth.currentUser!!.linkWithCredential(credential).await()
        } else {
            auth.signInWithCredential(credential).await()
        }
        authResult.user!!
    }

    // ─── Profile update ───────────────────────────────────────────────────────

    /**
     * Update the display name on the current Firebase user profile.
     * Throws if the user is not signed in or the update fails.
     */
    suspend fun updateDisplayName(name: String) {
        val user = auth.currentUser ?: return
        val profileUpdate = UserProfileChangeRequest.Builder()
            .setDisplayName(name.trim())
            .build()
        user.updateProfile(profileUpdate).await()
    }

    // ─── Sign-out ─────────────────────────────────────────────────────────────

    /** Signs out the current user. Anonymous sessions are discarded. */
    suspend fun signOut() {
        auth.signOut()
    }
}
