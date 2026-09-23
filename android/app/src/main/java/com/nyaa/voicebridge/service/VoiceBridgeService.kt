package com.nyaa.voicebridge.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nyaa.voicebridge.MainActivity
import com.nyaa.voicebridge.astrbot.AstrBotClient
import com.nyaa.voicebridge.audio.AudioRecordDriver
import com.nyaa.voicebridge.audio.BluetoothScoManager
import com.nyaa.voicebridge.audio.TtsAudioPlayer
import com.nyaa.voicebridge.data.ConfigManager
import com.nyaa.voicebridge.stt.SenseVoiceClient
import com.nyaa.voicebridge.stt.WakeWordDetector
import com.nyaa.voicebridge.ui.TuiLogBus
import kotlinx.coroutines.*

class VoiceBridgeService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var configManager: ConfigManager

    private var audioRecordDriver: AudioRecordDriver? = null
    private var scoManager: BluetoothScoManager? = null
    private var senseVoiceClient: SenseVoiceClient? = null
    private var wakeWordDetector: WakeWordDetector? = null
    private var astrBotClient: AstrBotClient? = null
    private var ttsAudioPlayer: TtsAudioPlayer? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START = "com.nyaa.voicebridge.START"
        const val ACTION_STOP = "com.nyaa.voicebridge.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "nyaa_voice_bridge_channel"
        var isServiceRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        configManager = ConfigManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 任何情况下收到 startForegroundService 必须在 5 秒内无条件呼叫 startForeground 绑定通知
        ensureForegroundNotification()

        when (intent?.action) {
            ACTION_START -> startVoiceBridge()
            ACTION_STOP -> stopVoiceBridge()
        }
        return START_STICKY
    }

    private fun ensureForegroundNotification() {
        val notification = buildForegroundNotification("小猫同学随时随地耳语守护中...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                // ignore
            }
        }
    }

    private fun startVoiceBridge() {
        if (isServiceRunning) return
        isServiceRunning = true
        ensureForegroundNotification()
        TuiLogBus.info("Service", "🚀 NyaaVoiceBridge 随身服务已启动")

        // 获取部分唤醒锁 (PARTIAL_WAKE_LOCK)，保证锁屏放口袋时 CPU 保持工作
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = powerManager.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "NyaaVoiceBridge::VoiceLock"
            ).apply {
                acquire()
            }
            TuiLogBus.info("Service", "已获取 PARTIAL_WAKE_LOCK，锁屏后台保活已激活")
        } catch (e: Exception) {
            TuiLogBus.warn("Service", "获取 WakeLock 失败: ${e.message}")
        }

        TuiLogBus.info("Service", "🚀 NyaaVoiceBridge 随身服务已启动")

        ttsAudioPlayer = TtsAudioPlayer(this).apply {
            setPlaybackListener { playing ->
                audioRecordDriver?.setSpeechMuted(playing)
            }
        }

        val config = configManager.loadConfig()
        astrBotClient = AstrBotClient(
            wsUrl = config.astrbotWsUrl,
            botId = 943653038L,
            userId = config.userId,
            onVoiceMessageReceived = { audioDataOrUrl ->
                serviceScope.launch {
                    ttsAudioPlayer?.playAudio(audioDataOrUrl)
                }
            }
        ).also { it.connect() }

        senseVoiceClient = SenseVoiceClient(config.sttApiUrl)
        wakeWordDetector = WakeWordDetector(config.wakeWord)

        scoManager = BluetoothScoManager(this)
        scoManager?.startSco()

        try {
            val vadEngine = com.nyaa.voicebridge.audio.RmsVadEngine()
            audioRecordDriver = AudioRecordDriver(
                sampleRate = 48000,
                channels = 1,
                vadEngine = vadEngine,
                onSpeechSegmentReady = { wavBytes ->
                    onSpeechCaptured(wavBytes)
                }
            ).also { it.startRecording() }
        } catch (e: Exception) {
            TuiLogBus.error("Service", "启动 AudioRecord 驱动失败: ${e.message}")
        }

        // 同步通知系统下拉快捷开关磁贴刷新状态
        VoiceBridgeTileService.requestListeningState(this)
    }

    private fun onSpeechCaptured(wavBytes: ByteArray) {
        serviceScope.launch {
            TuiLogBus.info("STT", "正在向 SenseVoice 发送切片识别 (${wavBytes.size} bytes)...")
            val rawText = senseVoiceClient?.transcribe(wavBytes) ?: ""
            if (rawText.isEmpty()) {
                TuiLogBus.warn("STT", "转写结果为空或噪声")
                return@launch
            }

            TuiLogBus.info("STT", "SenseVoice 转写: \"$rawText\"")
            val cleanCmd = wakeWordDetector?.processText(rawText)
            if (cleanCmd != null) {
                TuiLogBus.success("WakeWord", "🎯 唤醒命中！有效指令: \"$cleanCmd\"")
                astrBotClient?.sendVoiceText(cleanCmd)
            } else {
                TuiLogBus.warn("WakeWord", "未命中唤醒词或不在会话窗口，已过滤")
            }
        }
    }

    private fun stopVoiceBridge() {
        if (!isServiceRunning) return
        isServiceRunning = false

        audioRecordDriver?.stopRecording()
        audioRecordDriver = null

        scoManager?.stopSco()
        scoManager = null

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                TuiLogBus.info("Service", "已释放 WakeLock")
            }
        } catch (e: Exception) {
            // ignore
        }
        wakeLock = null

        astrBotClient?.disconnect()
        astrBotClient = null

        ttsAudioPlayer?.stop()
        ttsAudioPlayer = null

        serviceScope.coroutineContext.cancelChildren()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        TuiLogBus.info("Service", "🛑 NyaaVoiceBridge 随身服务已停止")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NyaaVoiceBridge 常驻守护",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持随身耳麦音频录音与 AstrBot WebSocket 心跳"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NyaaVoiceBridge 随身伴侣")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopVoiceBridge()
        VoiceBridgeTileService.requestListeningState(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
