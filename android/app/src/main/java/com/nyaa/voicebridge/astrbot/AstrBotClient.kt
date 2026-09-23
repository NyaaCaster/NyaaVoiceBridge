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

            // AstrBot sends OneBot reverse-WS API calls (e.g. send_private_msg).
            // Execute each request and return its result using the same echo.
            if (action.isNotEmpty() && json.has("params")) {
                val params = json.optJSONObject("params")
                val result = if (params != null) executeIncomingAction(action, params) else null
                val ack = JSONObject().apply {
                    put("status", if (result != null) "ok" else "failed")
                    put("retcode", if (result != null) 0 else 1)
                    put("data", result ?: JSONObject())
                    if (echo.isNotEmpty()) put("echo", echo)
                }
                webSocket?.send(ack.toString())
                return
            }

            // Ignore responses to actions initiated by this client; they are not commands.
            if (echo.isNotEmpty() && (json.has("status") || json.has("retcode"))) return

            TuiLogBus.warn("AstrBot", "忽略未处理的下行 WS 数据: ${text.take(240)}")
        } catch (e: Exception) {
            TuiLogBus.error("AstrBot", "解析下行消息失败: ${e.message}")
        }
    }

    private fun executeIncomingAction(action: String, params: JSONObject): JSONObject? {
        return when (action) {
            "send_msg", "send_private_msg" -> {
                val user = params.optLong("user_id", userId)
                if (user != userId) {
                    TuiLogBus.warn("AstrBot", "忽略发往其他用户的私聊: user_id=$user")
                    return JSONObject().put("message_id", -1)
                }
                val message = params.opt("message")
                val voice = extractRecordFile(message)
                if (voice != null) {
                    TuiLogBus.info("AstrBot", "收到 AstrBot 语音回复: ${voice.take(100)}")
                    onVoiceMessageReceived(voice)
                } else {
                    val textReply = extractTextMessage(message)
                    if (textReply.isNotBlank()) {
                        TuiLogBus.warn("AstrBot", "收到文本回复但无 record 语音段，无法播放: ${textReply.take(180)}")
                    } else {
                        TuiLogBus.warn("AstrBot", "收到无可播放语音段的回复消息")
                    }
                }
                JSONObject().put("message_id", System.currentTimeMillis() % 1_000_000)
            }
            "get_msg" -> JSONObject().put("message_id", params.optLong("message_id", -1))
            else -> {
                TuiLogBus.warn("AstrBot", "不支持的下行 OneBot action: $action")
                null
            }
        }
    }

    private fun extractRecordFile(message: Any?): String? {
        if (message is JSONArray) {
            for (i in 0 until message.length()) {
                val segment = message.optJSONObject(i) ?: continue
                if (segment.optString("type") == "record") {
                    val file = segment.optJSONObject("data")?.optString("file")?.takeIf { it.isNotBlank() }
                    if (file != null) return file
                }
            }
        } else if (message is String) {
            val match = """\[CQ:record,file=([^,\]]+)\]""".toRegex().find(message)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun extractTextMessage(message: Any?): String {
        if (message is JSONArray) {
            return (0 until message.length()).mapNotNull { index ->
                val segment = message.optJSONObject(index) ?: return@mapNotNull null
                when (segment.optString("type")) {
                    "text" -> segment.optJSONObject("data")?.optString("text")
                    "plain" -> segment.optJSONObject("data")?.optString("text")
                    else -> null
                }
            }.joinToString("")
        }
        return message as? String ?: ""
    }

    fun sendVoiceText(cleanText: String) {
        if (!isConnected.get() || webSocket == null) {
            TuiLogBus.warn("AstrBot", "WS 未连通，消息丢弃: $cleanText")
            return
        }

        val messageId = (System.currentTimeMillis() % 1000000).toInt()
        val messageSegments = JSONArray().put(
            JSONObject().put("type", "text").put("data", JSONObject().put("text", cleanText))
        )
        val payload = JSONObject().apply {
            put("time", System.currentTimeMillis() / 1000)
            put("self_id", botId)
            put("post_type", "message")
            put("message_type", "private")
            put("sub_type", "friend")
            put("message_id", messageId)
            put("user_id", userId)
            put("message", messageSegments)
            put("raw_message", cleanText)
            put("font", 0)
            put("sender", JSONObject().apply {
                put("user_id", userId)
                put("nickname", "NyaaMaster")
                put("card", "")
                put("role", "owner")
            })
        }

        if (!webSocket!!.send(payload.toString())) {
            TuiLogBus.error("AstrBot", "发送消息失败，WebSocket 未接受数据")
            return
        }
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
