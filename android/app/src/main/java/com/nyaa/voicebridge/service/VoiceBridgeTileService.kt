package com.nyaa.voicebridge.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.nyaa.voicebridge.ui.TuiLogBus

/**
 * Android 系统下拉快捷设置磁贴 (Quick Settings Tile)
 * 允许用户在手机控制中心（与 WiFi、蓝牙并列）直接一键启停随身猫猫耳语监听
 */
@RequiresApi(Build.VERSION_CODES.N)
class VoiceBridgeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = VoiceBridgeService.isServiceRunning

        if (isRunning) {
            // 当前正在运行，点击关闭
            TuiLogBus.info("QS Tile", "用户通过系统下拉快捷开关停止服务")
            val stopIntent = Intent(this, VoiceBridgeService::class.java).apply {
                action = VoiceBridgeService.ACTION_STOP
            }
            startService(stopIntent)
        } else {
            // 当前处于停止状态，点击启动
            TuiLogBus.info("QS Tile", "用户通过系统下拉快捷开关启动服务")
            val startIntent = Intent(this, VoiceBridgeService::class.java).apply {
                action = VoiceBridgeService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
        }

        // 短暂延迟后刷新磁贴状态
        qsTile?.let { tile ->
            tile.state = if (!isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.subtitle = if (!isRunning) "监听中" else "已就绪"
            tile.updateTile()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = VoiceBridgeService.isServiceRunning

        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "猫猫耳语"
        tile.subtitle = if (isRunning) "小猫同学监听中" else "点击开启"
        tile.updateTile()
    }

    companion object {
        /**
         * 当后台服务状态主动发生变化时，请求系统刷新下拉栏磁贴状态
         */
        fun requestListeningState(context: android.content.Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                requestListeningState(
                    context,
                    android.content.ComponentName(context, VoiceBridgeTileService::class.java)
                )
            }
        }
    }
}
