package com.nyaa.voicebridge.stt

import com.nyaa.voicebridge.ui.TuiLogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * SenseVoice 本地 STT 服务异步转写客户端 (兼容 OpenAI Whisper 协议)
 */
class SenseVoiceClient(
    private var apiUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    fun updateApiUrl(newUrl: String) {
        this.apiUrl = newUrl
    }

    /**
     * 上传内存 WAV 音频并获取识别出的文本
     */
    suspend fun transcribe(wavBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val audioBody = wavBytes.toRequestBody("audio/wav".toMediaTypeOrNull(), 0, wavBytes.size)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", audioBody)
                .addFormDataPart("model", "SenseVoiceSmall")
                .addFormDataPart("language", "zh")
                .build()

            val request = Request.Builder()
                .url(apiUrl)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val cost = System.currentTimeMillis() - startTime
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                TuiLogBus.logError("SenseVoice", "转写失败: HTTP ${response.code} (耗时: ${cost}ms) - $responseBody")
                return@withContext null
            }

            // 解析 JSON 响应 ({"text": "..."})
            val json = JSONObject(responseBody)
            val text = json.optString("text", "").trim()
            TuiLogBus.logSuccess("SenseVoice", "转写成功 (耗时: ${cost}ms): \"$text\"")
            text
        } catch (e: Exception) {
            val cost = System.currentTimeMillis() - startTime
            TuiLogBus.logError("SenseVoice", "转写网络异常 (耗时: ${cost}ms): ${e.message}")
            null
        }
    }
}
