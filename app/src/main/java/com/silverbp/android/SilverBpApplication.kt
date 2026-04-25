package com.silverbp.android

import android.app.Application
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.ModelBootstrap

class SilverBpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // If the Gemma 3n .task file already exists in filesDir/models/,
        // kick a background preload so capture isn't blocked. No-op
        // otherwise — the user triggers a download from Settings.
        ModelBootstrap.start(this)
    }
}
