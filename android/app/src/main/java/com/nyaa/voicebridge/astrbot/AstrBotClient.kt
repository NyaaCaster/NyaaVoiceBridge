package com.nyaa.voicebridge.astrbot

import com.nyaa.voicebridge.ui.TuiLogBus
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AstrBotClient(
    private val wsUrl: String,
    private val botId: Long,
    private val userId: Long,
    private val onVoiceMessageReceived: (audioDataOrUrl: String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect() {
        if (isConnected.get()) return

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("X-Client-Role", "Universal")
            .addHeader("X-Self-ID", botId.toString())
            .addHeader("User-Agent", "CQHttp/4.15.0")
            .build()

        TuiLogBus.info("AstrBot", "正在连接反向 WebSocket: $wsUrl")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected.set(true)
                TuiLogBus.success("AstrBot", "✅ 反向 WebSocket 握手成功！")
                sendLifecycleConnect()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected.set(false)
                TuiLogBus.warn("AstrBot", "WebSocket 关闭中: code=$code, reason=$reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected.set(false)
                TuiLogBus.error("AstrBot", "WebSocket 异常断开: ${t.message}，5秒后自动重连")
                scheduleReconnect()
            }
        })
    }

    private fun sendLifecycleConnect() {
        val json = JSONObject().apply {
            put("time", System.currentTimeMillis() / 1000)
            put("self_id", botId)
            put("post_type", "meta_event")
            put("meta_event_type", "lifecycle")
            put("sub_type", "connect")
        }
        webSocket?.send(json.toString())
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val echo = json.optString("echo", "")
            val action = json.optString("action", "")

            if (echo.isNotEmpty() && action.isNotEmpty()) {
                val ack = JSONObject().apply {
                    put("status", "ok")
                    put("retcode", 0)
                    put("data", JSONObject())
                    put("echo", echo)
                }
                webSocket?.send(ack.toString())
            }

            if (action == "send_msg" || action == "send_private_msg") {
                val params = json.optJSONObject("params")
                if (params != null) {
                    parseAndDispatchMessage(params)
                }
            }
        } catch (e: Exception) {
            TuiLogBus.error("AstrBot", "解析下行消息失败: ${e.message}")
        }
    }

    private fun parseAndDispatchMessage(params: JSONObject) {
        val message = params.opt("message")
        if (message is JSONArray) {
            for (i in 0 until message.length()) {
                val seg = message.getJSONObject(i)
                if (seg.optString("type") == "record") {
                    val data = seg.optJSONObject("data")
                    val file = data?.optString("file", "") ?: ""
                    if (file.isNotEmpty()) {
                        onVoiceMessageReceived(file)
                        return
                    }
                }
            }
        } else if (message is String) {
            val recordRegex = """\[CQ:record,file=([^,\]]+)\]""".toRegex()
            val match = recordRegex.find(message)
            if (match != null) {
                val file = match.groupValues[1]
                onVoiceMessageReceived(file)
            }
        }
    }

    fun sendVoiceText(cleanText: String) {
        if (!isConnected.get() || webSocket == null) {
            TuiLogBus.warn("AstrBot", "WS 未连通，消息丢弃: $cleanText")
            return
        }

        val silentWavBase64 = "base64://UklGRigAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA="
        val cqMessage = "$cleanText [CQ:record,file=$silentWavBase64]"

        val msgId = (System.currentTimeMillis() % 1000000).toInt()
        val payload = JSONObject().apply {
            put("time", System.currentTimeMillis() / 1000)
            put("self_id", botId)
            put("post_type", "message")
            put("message_type", "private")
            put("sub_type", "friend")
            put("message_id", msgId)
            put("user_id", userId)
            put("message", cqMessage)
            put("raw_message", cqMessage)
            put("font", 0)
            put("sender", JSONObject().apply {
                put("user_id", userId)
                put("nickname", "NyaaMaster")
            })
        }

        webSocket?.send(payload.toString())
        TuiLogBus.info("AstrBot", "📤 已推送到 AstrBot -> PixNyaa: \"$cleanText\"")
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000)
            TuiLogBus.info("AstrBot", "正在尝试重连 AstrBot...")
            connect()
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "App Service Stopped")
        isConnected.set(false)
    }
}
