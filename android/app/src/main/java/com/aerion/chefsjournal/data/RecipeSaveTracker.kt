package com.aerion.chefsjournal.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tracks which recipes are currently being saved in the background.
 *
 * Editor saves run here (not on the screen's `viewModelScope`) so a save survives the user
 * leaving the recipe — they're never locked in while it persists. Screens observe [saving]
 * to show a small "updating" spinner on the relevant recipe card / header.
 */
object RecipeSaveTracker {
    /** Long-lived scope so an in-flight save isn't cancelled when the detail screen closes. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _saving = MutableStateFlow<Set<String>>(emptySet())
    val saving: StateFlow<Set<String>> = _saving.asStateFlow()

    fun isSaving(recipeId: String): Boolean = recipeId in _saving.value

    /** Run [block] as a background save for [recipeId], marking it busy for the duration. */
    fun launchSave(recipeId: String, block: suspend () -> Unit) {
        _saving.update { it + recipeId }
        scope.launch {
            try { block() } finally { _saving.update { it - recipeId } }
        }
    }
}
