package com.nyaa.voicebridge.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.nyaa.voicebridge.ui.TuiLogBus
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原生 AudioRecord 硬件录音驱动管道
 */
class AudioRecordDriver(
    private val sampleRate: Int = 48000,
    private val channels: Int = 1,
    private val vadEngine: RmsVadEngine,
    private val onSpeechSegmentReady: (ByteArray) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private val isRunning = AtomicBoolean(false)
    private var recordingJob: Job? = null

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (isRunning.get()) return

        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                TuiLogBus.logError("AudioDriver", "AudioRecord 初始化失败，状态异常")
                return
            }

            audioRecord?.startRecording()
            isRunning.set(true)
            vadEngine.reset()
            TuiLogBus.logSuccess("AudioDriver", "AudioRecord 录音管道已启动 (Rate: ${sampleRate}Hz, Channels: $channels)")

            recordingJob = scope.launch(Dispatchers.IO) {
                val chunk = ByteArray(2048) // 约 21ms 的 PCM 块
                while (isRunning.get() && isActive) {
                    val readBytes = audioRecord?.read(chunk, 0, chunk.size) ?: -1
                    if (readBytes > 0) {
                        val validChunk = if (readBytes == chunk.size) chunk else chunk.copyOf(readBytes)
                        val speechPcm = vadEngine.processChunk(validChunk)
                        if (speechPcm != null) {
                            val wavBytes = WavUtils.pcmToWav(speechPcm, sampleRate, channels)
                            TuiLogBus.logInfo("VAD", "检测到一段有效语音切片 (WAV 大小: ${wavBytes.size} bytes)")
                            onSpeechSegmentReady(wavBytes)
                        }
                    } else if (readBytes < 0) {
                        TuiLogBus.logWarn("AudioDriver", "AudioRecord read 返回错误码: $readBytes")
                        delay(50)
                    }
                }
            }
        } catch (e: Exception) {
            TuiLogBus.logError("AudioDriver", "启动录音驱动异常: ${e.message}")
            stop()
        }
    }

    fun stop() {
        isRunning.set(false)
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            TuiLogBus.logError("AudioDriver", "释放 AudioRecord 异常: ${e.message}")
        } finally {
            audioRecord = null
            vadEngine.reset()
            TuiLogBus.logInfo("AudioDriver", "AudioRecord 录音管道已关闭")
        }
    }
}
