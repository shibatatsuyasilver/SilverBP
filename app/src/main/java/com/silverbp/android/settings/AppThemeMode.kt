package com.silverbp.android.settings

/**
 * App color theme selectable in Settings → Appearance.
 *  - [System] follows the device's light/dark setting.
 *  - [Light] / [Dark] force the respective palette.
 *
 * [raw] is the stable token persisted in DataStore. Defaults to [Dark] — the app
 * is dark-first, so existing users keep their look until they opt into light.
 */
enum class AppThemeMode(val raw: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromRaw(raw: String?): AppThemeMode =
            entries.firstOrNull { it.raw == raw } ?: Dark
    }
}
