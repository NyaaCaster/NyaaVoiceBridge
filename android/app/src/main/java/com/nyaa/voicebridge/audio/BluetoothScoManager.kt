package com.nyaa.voicebridge.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import com.nyaa.voicebridge.ui.TuiLogBus

/**
 * 蓝牙 SCO 音频路由管理器 (自动将录音/放音通道切换至随身蓝牙耳机)
 */
class BluetoothScoManager(
    private val context: Context,
    private val onScoConnected: () -> Unit = {},
    private val onScoDisconnected: () -> Unit = {}
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isReceiverRegistered = false
    private var isScoStarted = false

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED == intent?.action) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        TuiLogBus.logSuccess("SCO", "蓝牙 SCO 耳麦通道已建立连接，音频流已切入耳机")
                        onScoConnected()
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        TuiLogBus.logWarn("SCO", "蓝牙 SCO 耳麦通道已断开")
                        onScoDisconnected()
                    }
                    AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                        TuiLogBus.logInfo("SCO", "正在尝试连接蓝牙 SCO 耳机麦克风...")
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        TuiLogBus.logError("SCO", "蓝牙 SCO 建立失败 (EXTRA_SCO_AUDIO_STATE_ERROR)")
                    }
                }
            }
        }
    }

    fun start() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            context.registerReceiver(scoReceiver, filter)
            isReceiverRegistered = true
        }

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            isScoStarted = true
            TuiLogBus.logInfo("SCO", "已请求启动 startBluetoothSco (MODE_IN_COMMUNICATION)")
        } catch (e: Exception) {
            TuiLogBus.logError("SCO", "请求蓝牙 SCO 失败: ${e.message}")
        }
    }

    fun startSco() = start()
    fun stopSco() = stop()

    fun stop() {
        try {
            if (isScoStarted) {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.mode = AudioManager.MODE_NORMAL
                isScoStarted = false
                TuiLogBus.logInfo("SCO", "已注销蓝牙 SCO 路由，恢复系统默认音频通道")
            }
        } catch (e: Exception) {
            TuiLogBus.logError("SCO", "停止蓝牙 SCO 异常: ${e.message}")
        }

        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(scoReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
    }
}
