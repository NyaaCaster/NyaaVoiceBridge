package com.nyaa.voicebridge.audio

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * 轻量低功耗 RMS 能量计算与滑动静音切片引擎 (基于 PCM 16bit 48kHz)
 */
class RmsVadEngine(
    private val sampleRate: Int = 16000,
    private val channels: Int = 1,
    private val energyThreshold: Double = 0.02,
    private val silenceDurationSec: Double = 0.7,
    private val preSpeechDurationSec: Double = 0.5,
    private val minSpeechDurationSec: Double = 0.8,
    private val maxSpeechDurationSec: Double = 15.0
) {
    private var isSpeaking = false
    private var speechStartTimeMs = 0L
    private var lastSpeechTimeMs = 0L

    // 预滚环形缓冲区 (保存唤醒词前缀)
    private val preSpeechQueue = ArrayDeque<ByteArray>()
    private val maxPreSpeechBytes = (sampleRate * channels * 2 * preSpeechDurationSec).toInt()
    private var currentPreSpeechBytes = 0

    // 语音段输出缓冲
    private val activeSpeechBuffer = ByteArrayOutputStream()

    fun calculateRms(pcm16Data: ByteArray): Double {
        if (pcm16Data.size < 2) return 0.0
        var sumSquares = 0.0
        val sampleCount = pcm16Data.size / 2

        for (i in 0 until pcm16Data.size step 2) {
            val sample = (pcm16Data[i].toInt() and 0xFF) or (pcm16Data[i + 1].toInt() shl 8)
            val normalized = sample.toShort() / 32768.0
            sumSquares += normalized * normalized
        }
        return sqrt(sumSquares / sampleCount)
    }

    @Synchronized
    fun processChunk(pcmChunk: ByteArray): ByteArray? {
        val rms = calculateRms(pcmChunk)
        val now = System.currentTimeMillis()

        if (rms >= energyThreshold) {
            // 检测到有效声音
            if (!isSpeaking) {
                isSpeaking = true
                speechStartTimeMs = now
                activeSpeechBuffer.reset()
                // 倾倒预缓冲音频
                while (!preSpeechQueue.isEmpty()) {
                    activeSpeechBuffer.write(preSpeechQueue.poll())
                }
            }
            lastSpeechTimeMs = now
            activeSpeechBuffer.write(pcmChunk)
        } else {
            // 当前帧为静音
            if (isSpeaking) {
                activeSpeechBuffer.write(pcmChunk)
                val silenceElapsed = (now - lastSpeechTimeMs) / 1000.0
                val totalSpeechElapsed = (now - speechStartTimeMs) / 1000.0

                if (silenceElapsed >= silenceDurationSec || totalSpeechElapsed >= maxSpeechDurationSec) {
                    // 静音超时或达到最大限制，切片完成
                    isSpeaking = false
                    val speechPcm = activeSpeechBuffer.toByteArray()
                    activeSpeechBuffer.reset()

                    if (totalSpeechElapsed >= minSpeechDurationSec) {
                        return speechPcm
                    }
                }
            } else {
                // 未说话状态，维护预缓冲环形队列
                preSpeechQueue.addLast(pcmChunk)
                currentPreSpeechBytes += pcmChunk.size
                while (currentPreSpeechBytes > maxPreSpeechBytes && !preSpeechQueue.isEmpty()) {
                    val removed = preSpeechQueue.pollFirst()
                    if (removed != null) {
                        currentPreSpeechBytes -= removed.size
                    }
                }
            }
        }
        return null
    }

    @Synchronized
    fun reset() {
        isSpeaking = false
        speechStartTimeMs = 0L
        lastSpeechTimeMs = 0L
        preSpeechQueue.clear()
        currentPreSpeechBytes = 0
        activeSpeechBuffer.reset()
    }
}
