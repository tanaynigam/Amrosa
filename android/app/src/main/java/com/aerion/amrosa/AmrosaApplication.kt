package com.aerion.amrosa

import android.app.Application
import com.aerion.amrosa.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AmrosaApplication : Application() {

    lateinit var container: AppContainer

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            // Whenever the user is signed in with a real account (on app start or after sign-in),
            // seed local data and pull from Firestore.
            container.authRepository.authStateFlow().collect { user ->
                if (user != null && !user.isAnonymous) {
                    container.seeder.seedIfNeeded()
                    container.syncService.sync()
                    container.syncService.syncPersonalRecipes()
                    // Upsert public profile so other users can find / follow this user
                    container.socialRepository.upsertProfile()
                }
            }
        }
    }
}
