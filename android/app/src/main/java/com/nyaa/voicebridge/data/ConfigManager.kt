package com.nyaa.voicebridge.data

import android.content.Context
import android.content.SharedPreferences

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

        // 安全默认脱敏值 (对齐 SSOT 规范)
        const val DEFAULT_ASTRBOT_WS = "ws://h.nyaa.host:6199/ws"
        const val DEFAULT_STT_API = "http://127.0.0.1:5052/v1/audio/transcriptions"
        const val DEFAULT_USER_ID = 10001L
        const val DEFAULT_WAKE_WORD = "小猫同学"
    }

    fun loadConfig(): BridgeConfig {
        return BridgeConfig(
            astrbotWsUrl = prefs.getString(KEY_ASTRBOT_WS, DEFAULT_ASTRBOT_WS) ?: DEFAULT_ASTRBOT_WS,
            sttApiUrl = prefs.getString(KEY_STT_API, DEFAULT_STT_API) ?: DEFAULT_STT_API,
            userId = prefs.getLong(KEY_USER_ID, DEFAULT_USER_ID),
            wakeWord = prefs.getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD) ?: DEFAULT_WAKE_WORD,
            isAutoBluetoothSco = prefs.getBoolean(KEY_AUTO_SCO, true)
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
