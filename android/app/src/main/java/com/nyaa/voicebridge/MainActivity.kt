package com.nyaa.voicebridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nyaa.voicebridge.data.BridgeConfig
import com.nyaa.voicebridge.data.ConfigManager
import com.nyaa.voicebridge.service.VoiceBridgeService
import com.nyaa.voicebridge.ui.LogLevel
import com.nyaa.voicebridge.ui.TuiLogBus

class MainActivity : AppCompatActivity() {

    private lateinit var configManager: ConfigManager

    private lateinit var tvStatusBadge: TextView
    private lateinit var btnToggleService: Button
    private lateinit var btnSaveConfig: Button
    private lateinit var btnClearLog: Button

    private lateinit var etAstrbotWs: EditText
    private lateinit var etSttApi: EditText
    private lateinit var etUserId: EditText
    private lateinit var etWakeWord: EditText

    private lateinit var scrollTerminal: ScrollView
    private lateinit var tvTerminalOutput: TextView

    private val logSubscriber: (com.nyaa.voicebridge.ui.LogEntry) -> Unit = { entry ->
        runOnUiThread {
            appendTuiLog(entry)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configManager = ConfigManager(this)
        initViews()
        loadConfigToUi()
        checkAndRequestPermissions()

        TuiLogBus.subscribe(logSubscriber)
        TuiLogBus.log(LogLevel.INFO, "系统环境就绪，等待服务指令...")
    }

    private fun initViews() {
        tvStatusBadge = findViewById(R.id.tv_status_badge)
        btnToggleService = findViewById(R.id.btn_toggle_service)
        btnSaveConfig = findViewById(R.id.btn_save_config)
        btnClearLog = findViewById(R.id.btn_clear_log)

        etAstrbotWs = findViewById(R.id.et_astrbot_ws)
        etSttApi = findViewById(R.id.et_stt_api)
        etUserId = findViewById(R.id.et_user_id)
        etWakeWord = findViewById(R.id.et_wake_word)

        scrollTerminal = findViewById(R.id.scroll_terminal)
        tvTerminalOutput = findViewById(R.id.tv_terminal_output)

        btnToggleService.setOnClickListener {
            toggleServiceState()
        }

        btnSaveConfig.setOnClickListener {
            saveConfigFromUi()
        }

        btnClearLog.setOnClickListener {
            tvTerminalOutput.text = ""
            TuiLogBus.clear()
        }

        updateServiceUiState()
    }

    private fun loadConfigToUi() {
        val cfg = configManager.loadConfig()
        etAstrbotWs.setText(cfg.astrbotWsUrl)
        etSttApi.setText(cfg.sttApiUrl)
        etUserId.setText(cfg.userId.toString())
        etWakeWord.setText(cfg.wakeWord)
    }

    private fun saveConfigFromUi() {
        val uid = etUserId.text.toString().toLongOrNull() ?: 10001L
        val cfg = BridgeConfig(
            astrbotWsUrl = etAstrbotWs.text.toString(),
            sttApiUrl = etSttApi.text.toString(),
            userId = uid,
            wakeWord = etWakeWord.text.toString(),
            isAutoBluetoothSco = true
        )
        configManager.saveConfig(cfg)
        TuiLogBus.log(LogLevel.INFO, "[Config] 参数配置已更新并持久化保存")
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun toggleServiceState() {
        if (!checkPermissionsGranted()) {
            Toast.makeText(this, "请先授予录音和附近设备(蓝牙)权限", Toast.LENGTH_LONG).show()
            checkAndRequestPermissions()
            return
        }

        val intent = Intent(this, VoiceBridgeService::class.java)
        if (VoiceBridgeService.isServiceRunning) {
            intent.action = VoiceBridgeService.ACTION_STOP
            startService(intent)
        } else {
            // 启动前先自动保存最新配置
            saveConfigFromUi()
            intent.action = VoiceBridgeService.ACTION_START
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                TuiLogBus.error("Activity", "启动前台服务异常: ${e.message}")
                Toast.makeText(this, "启动服务失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        tvTerminalOutput.postDelayed({ updateServiceUiState() }, 300)
    }

    private fun checkPermissionsGranted(): Boolean {
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val btOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return micOk && btOk
    }

    private fun updateServiceUiState() {
        if (VoiceBridgeService.isServiceRunning) {
            tvStatusBadge.text = "🟢 LISTENING"
            tvStatusBadge.setTextColor(Color.parseColor("#00FF66"))
            btnToggleService.text = "STOP"
            btnToggleService.setBackgroundColor(Color.parseColor("#FF3366"))
        } else {
            tvStatusBadge.text = "🔴 STOPPED"
            tvStatusBadge.setTextColor(Color.parseColor("#FF3366"))
            btnToggleService.text = "START"
            btnToggleService.setBackgroundColor(Color.parseColor("#00FF66"))
        }
    }

    private fun appendTuiLog(entry: com.nyaa.voicebridge.ui.LogEntry) {
        val color = Color.parseColor(entry.level.colorHex)
        val line = "[${entry.timestamp}] [${entry.level.tag}] ${entry.message}\n"
        val spannable = SpannableString(line)
        spannable.setSpan(
            ForegroundColorSpan(color),
            0,
            line.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvTerminalOutput.append(spannable)
        scrollTerminal.post {
            scrollTerminal.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        }
    }

    override fun onDestroy() {
        TuiLogBus.unsubscribe(logSubscriber)
        super.onDestroy()
    }
}
