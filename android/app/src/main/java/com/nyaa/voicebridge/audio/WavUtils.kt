package com.nyaa.voicebridge.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 内存 PCM 转标准 WAV 格式编码工具
 */
object WavUtils {

    fun pcmToWav(pcmData: ByteArray, sampleRate: Int = 48000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val out = ByteArrayOutputStream(totalAudioLen + 44)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        header.put('R'.code.toByte())
        header.put('I'.code.toByte())
        header.put('F'.code.toByte())
        header.put('F'.code.toByte())
        header.putInt(totalDataLen)
        header.put('W'.code.toByte())
        header.put('A'.code.toByte())
        header.put('V'.code.toByte())
        header.put('E'.code.toByte())

        // fmt subchunk
        header.put('f'.code.toByte())
        header.put('m'.code.toByte())
        header.put('t'.code.toByte())
        header.put(' '.code.toByte())
        header.putInt(16) // SubChunk1Size (16 for PCM)
        header.putShort(1.toShort()) // AudioFormat (1 for PCM)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort()) // BlockAlign
        header.putShort(bitsPerSample.toShort())

        // data subchunk
        header.put('d'.code.toByte())
        header.put('a'.code.toByte())
        header.put('t'.code.toByte())
        header.put('a'.code.toByte())
        header.putInt(totalAudioLen)

        out.write(header.array())
        out.write(pcmData)

        return out.toByteArray()
    }
}
