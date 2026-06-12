package com.silverbp.android.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/**
 * In-app language selectable in Settings → App language.
 *  - [System] follows the device language (empty per-app LocaleList).
 *  - [English] / [TraditionalChinese] force the respective app locale.
 *
 * Unlike [AppThemeMode], the chosen language is NOT persisted in DataStore.
 * On API 33+ the framework's per-app locale ([LocaleManager.applicationLocales])
 * is the single source of truth: it persists the choice, survives restarts,
 * and recreates the Activity on change. Persisting it ourselves would only
 * risk drifting out of sync with the system value (and it must stay per-device,
 * never backed up or synced).
 *
 * [tag] is the BCP-47 language tag handed to [LocaleList.forLanguageTags];
 * `null` means "follow system".
 */
enum class AppLanguage(val tag: String?) {
    System(null),
    English("en"),
    TraditionalChinese("zh-TW"),
}

/** Reads/writes the framework per-app locale (API 33+). */
object LocaleHelper {

    /** The language currently applied to the app, derived from the system per-app locale. */
    fun current(context: Context): AppLanguage {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        if (locales.isEmpty) return AppLanguage.System
        val lang = locales[0].language
        return when {
            lang.equals("zh", ignoreCase = true) -> AppLanguage.TraditionalChinese
            lang.equals("en", ignoreCase = true) -> AppLanguage.English
            else -> AppLanguage.System
        }
    }

    /** Applies [lang]; the system recreates the Activity with the new configuration. */
    fun apply(context: Context, lang: AppLanguage) {
        val list = lang.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        context.getSystemService(LocaleManager::class.java).applicationLocales = list
    }
}
