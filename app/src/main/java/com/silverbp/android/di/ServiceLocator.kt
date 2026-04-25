package com.silverbp.android.di

import android.content.Context
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.db.SilverBpDatabase
import com.silverbp.android.recognition.ModelLoadStatus
import com.silverbp.android.settings.UserSettingsRepository

/**
 * Hand-rolled DI. All services are application-scoped and lazily initialised
 * the first time they're requested. Replace with Hilt once the Hilt Gradle
 * plugin catches up to AGP 9.
 */
object ServiceLocator {
    @Volatile private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    val context: Context
        get() = appCtx ?: error("ServiceLocator not initialised — did SilverBpApplication.onCreate run?")

    val database: SilverBpDatabase by lazy { SilverBpDatabase.get(context) }

    val bpRepository: BpRepository by lazy { BpRepository(database.bpDao()) }

    val userSettings: UserSettingsRepository by lazy { UserSettingsRepository(context) }

    val modelLoadStatus: ModelLoadStatus by lazy { ModelLoadStatus() }
}
