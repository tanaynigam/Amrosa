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
            // Ensure there is always a Firebase session (anonymous until the user signs in)
            container.authRepository.signInAnonymouslyIfNeeded()
            // Local seed as fallback (runs once, skipped if DB already has data)
            container.seeder.seedIfNeeded()
            // Pull any new/updated recipes from Firestore
            container.syncService.sync()
        }
    }
}
