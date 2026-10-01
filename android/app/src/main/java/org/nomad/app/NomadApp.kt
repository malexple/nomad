package org.nomad.app

import android.app.Application

class NomadApp : Application() {
    var container: AppContainer? = null
        private set
    var startError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        try {
            container = AppContainer(applicationContext)
        } catch (e: Exception) {
            // never replace a state that cannot be opened with a new identity silently
            startError = e.toString()
        }
    }
}
