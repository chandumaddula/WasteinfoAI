package com.wasteinfoai

import android.app.Application
import android.util.Log
import com.wasteinfoai.core.di.AppContainer

/**
 * WasteInfoAI application class initializing application-level singletons and background work.
 */
class WasteInfoAIApp : Application() {

    companion object {
        private const val TAG = "WasteInfoAIApp"
    }

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Initializing WasteInfoAI application...")
        appContainer = AppContainer(this)

        // Schedule background periodic sync for pending reports
        try {
            appContainer.syncScheduler.schedulePeriodicSync()
            Log.i(TAG, "WorkManager periodic sync scheduled successfully.")
        } catch (e: Exception) {
            Log.w(TAG, "Could not initialize WorkManager sync schedule", e)
        }
    }
}
