package com.wasteinfoai.camera

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** Wires the Android implementations into [CameraViewModel] (only the application context is kept). */
object CameraViewModelFactory {
    fun create(context: Context): ViewModelProvider.Factory {
        val appContext = context.applicationContext
        return viewModelFactory {
            initializer { CameraViewModel(AppImageStore(appContext), AndroidImageValidator()) }
        }
    }
}
