package com.nyaa.voicebridge.stt

import com.nyaa.voicebridge.ui.TuiLogBus
import java.util.regex.Pattern

/**
 * 唤醒词与指令载荷解析结果
 */
data class WakeWordMatchResult(
    val isTriggered: Boolean,
    val matchedWord: String? = null,
    val commandPayload: String = ""
)

/**
 * 纯粹盲操「小猫同学」唤醒词检测器 (移植自 Linux 生产端验证通过的高精度规则)
 */
class WakeWordDetector(
    private var primaryWakeWord: String = "小猫同学"
) {
    // 兼容同音、谐音、STT 常见误识别字
    // 匹配: 小猫同学、小猫同行、小帽同学、猫猫同学、晓猫同学、小喵同学、小猫等
    private val defaultRegexPattern = Pattern.compile(
        """^(?:.*?)(小[猫帽毛喵秒][同痛通桐][学靴雪血鞋]|猫猫同学|晓猫同学|小猫小猫|小猫)(?:[，,。！!？?\s]*)(.*)$""",
        Pattern.CASE_INSENSITIVE
    )

    fun updatePrimaryWakeWord(word: String) {
        this.primaryWakeWord = word
    }

    /**
     * 对 STT 识别出的原始文本进行唤醒判定并提取有效指令
     */
    fun process(rawText: String): WakeWordMatchResult {
        val cleanText = rawText.trim()
        if (cleanText.isEmpty()) {
            return WakeWordMatchResult(isTriggered = false)
        }

        // 1. 正则匹配谐音与前缀
        val matcher = defaultRegexPattern.matcher(cleanText)
        if (matcher.find()) {
            val matchedTrigger = matcher.group(1) ?: primaryWakeWord
            val payload = matcher.group(2)?.trim()?.trim(',', '，', '。', '！', '!', '？', '?') ?: ""
            TuiLogBus.logSuccess("WakeWord", "盲操唤醒命中! 触发词: [$matchedTrigger], 载荷: \"$payload\"")
            return WakeWordMatchResult(
                isTriggered = true,
                matchedWord = matchedTrigger,
                commandPayload = payload
            )
        }

        // 2. 备用直接包含判定 (防止用户自定义了非默认唤醒词)
        if (primaryWakeWord.isNotEmpty() && cleanText.contains(primaryWakeWord)) {
            val idx = cleanText.indexOf(primaryWakeWord)
            val payload = cleanText.substring(idx + primaryWakeWord.length)
                .trim()
                .trim(',', '，', '。', '！', '!', '？', '?')
            TuiLogBus.logSuccess("WakeWord", "精确唤醒命中! 触发词: [$primaryWakeWord], 载荷: \"$payload\"")
            return WakeWordMatchResult(
                isTriggered = true,
                matchedWord = primaryWakeWord,
                commandPayload = payload
            )
        }

        TuiLogBus.logInfo("WakeWord", "未检测到唤醒词 (丢弃环境杂音): \"$cleanText\"")
        return WakeWordMatchResult(isTriggered = false)
    }
}
