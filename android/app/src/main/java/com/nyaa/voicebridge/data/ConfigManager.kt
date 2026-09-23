package com.nyaa.voicebridge.data

import android.content.Context
import android.content.SharedPreferences
import com.nyaa.voicebridge.BuildConfig

data class BridgeConfig(
    val astrbotWsUrl: String,
    val sttApiUrl: String,
    val userId: Long,
    val wakeWord: String,
    val isAutoBluetoothSco: Boolean
)

class ConfigManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("nyaa_bridge_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ASTRBOT_WS = "key_astrbot_ws"
        private const val KEY_STT_API = "key_stt_api"
        private const val KEY_USER_ID = "key_user_id"
        private const val KEY_WAKE_WORD = "key_wake_word"
        private const val KEY_AUTO_SCO = "key_auto_sco"

        // 默认值由本地部署配置在打包时注入
        const val DEFAULT_ASTRBOT_WS = BuildConfig.DEFAULT_ASTRBOT_WS
        const val DEFAULT_STT_API = BuildConfig.DEFAULT_STT_API
        const val DEFAULT_USER_ID = BuildConfig.DEFAULT_USER_ID
        const val DEFAULT_WAKE_WORD = "小猫同学"
        const val DEFAULT_AUTO_BLUETOOTH_SCO = true
    }

    fun loadConfig(): BridgeConfig {
        return BridgeConfig(
            astrbotWsUrl = prefs.getString(KEY_ASTRBOT_WS, DEFAULT_ASTRBOT_WS) ?: DEFAULT_ASTRBOT_WS,
            sttApiUrl = prefs.getString(KEY_STT_API, DEFAULT_STT_API) ?: DEFAULT_STT_API,
            userId = prefs.getLong(KEY_USER_ID, DEFAULT_USER_ID),
            wakeWord = prefs.getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD) ?: DEFAULT_WAKE_WORD,
            isAutoBluetoothSco = prefs.getBoolean(KEY_AUTO_SCO, DEFAULT_AUTO_BLUETOOTH_SCO)
        )
    }

    fun saveConfig(config: BridgeConfig) {
        prefs.edit()
            .putString(KEY_ASTRBOT_WS, config.astrbotWsUrl.trim())
            .putString(KEY_STT_API, config.sttApiUrl.trim())
            .putLong(KEY_USER_ID, config.userId)
            .putString(KEY_WAKE_WORD, config.wakeWord.trim())
            .putBoolean(KEY_AUTO_SCO, config.isAutoBluetoothSco)
            .apply()
    }
}
