package com.nyaa.voicebridge.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Base64
import com.nyaa.voicebridge.ui.TuiLogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class TtsAudioPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private val _isPlaying = AtomicBoolean(false)
    val isPlaying: Boolean get() = _isPlaying.get()

    private var onPlaybackStateChanged: ((Boolean) -> Unit)? = null

    fun setPlaybackListener(listener: (Boolean) -> Unit) {
        this.onPlaybackStateChanged = listener
    }

    suspend fun playAudio(audioDataOrUrl: String): Boolean = withContext(Dispatchers.IO) {
        stop()
        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .build()
                )
            }

            if (audioDataOrUrl.startsWith("base64://") || audioDataOrUrl.length > 500) {
                val cleanBase64 = if (audioDataOrUrl.startsWith("base64://")) {
                    audioDataOrUrl.removePrefix("base64://")
                } else {
                    audioDataOrUrl
                }
                val rawBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                val tempFile = File(context.cacheDir, "tts_response_temp.mp3")
                FileOutputStream(tempFile).use { it.write(rawBytes) }

                player.setDataSource(context, Uri.fromFile(tempFile))
            } else {
                player.setDataSource(audioDataOrUrl)
            }

            player.prepare()
            _isPlaying.set(true)
            onPlaybackStateChanged?.invoke(true)
            TuiLogBus.info("TTS", "🔊 开始播放猫猫回复语音，已静音麦克风防自激")

            player.setOnCompletionListener {
                _isPlaying.set(false)
                onPlaybackStateChanged?.invoke(false)
                TuiLogBus.info("TTS", "🔈 猫猫语音播放完毕，恢复麦克风监听")
                releasePlayer()
            }

            player.setOnErrorListener { _, what, extra ->
                _isPlaying.set(false)
                onPlaybackStateChanged?.invoke(false)
                TuiLogBus.error("TTS", "播放错误: what=$what, extra=$extra")
                releasePlayer()
                true
            }

            player.start()
            mediaPlayer = player
            true
        } catch (e: Exception) {
            _isPlaying.set(false)
            onPlaybackStateChanged?.invoke(false)
            TuiLogBus.error("TTS", "播放准备失败: ${e.message}")
            releasePlayer()
            false
        }
    }

    fun stop() {
        if (_isPlaying.getAndSet(false)) {
            onPlaybackStateChanged?.invoke(false)
        }
        releasePlayer()
    }

    private fun releasePlayer() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }
}
