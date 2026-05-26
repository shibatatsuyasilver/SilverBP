package com.silverbp.android.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 持久保存使用者生成的恢復碼(52 字元 Crockford Base32). 用 EncryptedSharedPreferences
 * 確保檔案在 device-at-rest 是 Keystore 加密的.
 *
 * **重要**: Keystore 金鑰在解除安裝時會被清掉,所以這個 store 內的恢復碼只能用於
 * 「本機 UI 顯示」("讓使用者再看一次自己的恢復碼") 與「同裝置 export 時免再輸入」.
 * 跨裝置/重灌後的還原靠**使用者已抄寫下來的紙本**,不能靠這個 store.
 *
 * Mirror 設計來自 [com.silverbp.android.security.DbKeyStore]: 同樣是
 * EncryptedSharedPreferences 隔離成獨立檔案,prefs name = "silverbp.backup.recovery".
 */
class RecoveryCodeStore(private val prefs: SharedPreferences) {

    /** 取出已存的恢復碼;尚未生成回傳 null. */
    fun get(): String? = prefs.getString(KEY_CODE, null)

    /** 寫入恢復碼(52 字元正規化形式 — 無連字號). */
    fun set(code: String) {
        require(code.length == RecoveryCode.ENCODED_CHAR_COUNT) {
            "recovery code must be ${RecoveryCode.ENCODED_CHAR_COUNT} chars"
        }
        prefs.edit().putString(KEY_CODE, code).apply()
    }

    /** 清除已存的恢復碼(使用者選擇重新生成時呼叫). */
    fun clear() {
        prefs.edit().remove(KEY_CODE).apply()
    }

    companion object {
        const val PREFS_NAME = "silverbp.backup.recovery"
        const val KEY_CODE = "__backup.recovery_code"

        fun create(context: Context): RecoveryCodeStore {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return RecoveryCodeStore(prefs)
        }
    }
}
