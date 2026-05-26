package com.silverbp.android.settings

/**
 * 用於 backup snapshot / SETTINGS_KV sync 的 type-erased DataStore 值表示.
 *
 * DataStore Preferences.Key 是 typed(`Preferences.Key<Boolean>` 等),不能跨 type
 * 透過 generic 直接走 wire. 這個 sealed class 把 5 種底層型別統一收進來,讓
 * [com.silverbp.android.sync.SettingsKvSyncMapper] 可以用一致的形式處理.
 */
sealed class KvValue {
    data class B(val value: Boolean) : KvValue()
    data class I(val value: Int) : KvValue()
    data class L(val value: Long) : KvValue()
    data class F(val value: Float) : KvValue()
    data class T(val value: String) : KvValue()
}
