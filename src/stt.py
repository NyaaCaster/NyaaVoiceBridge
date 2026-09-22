import aiohttp
import logging
import os
from typing import Optional

logger = logging.getLogger("NyaaVoiceBridge.STT")

class STTClient:
    """SenseVoice OpenAI 兼容 STT 客户端"""

    def __init__(self, config: dict):
        self.stt_cfg = config.get("stt", {})
        self.api_url = self.stt_cfg.get("api_url", "http://127.0.0.1:5052/v1/audio/transcriptions")
        self.model = self.stt_cfg.get("model", "SenseVoiceSmall")
        self.language = self.stt_cfg.get("language", "zh")
        self.timeout = aiohttp.ClientTimeout(total=self.stt_cfg.get("timeout", 15))

    async def transcribe(self, audio_path: str) -> Optional[str]:
        """将指定音频文件提交给 SenseVoice API 转写为文字"""
        if not os.path.exists(audio_path):
            logger.error(f"STT 转写失败，文件不存在: {audio_path}")
            return None

        data = aiohttp.FormData()
        data.add_field("model", self.model)
        if self.language:
            data.add_field("language", self.language)

        with open(audio_path, "rb") as f:
            data.add_field(
                "file",
                f,
                filename=os.path.basename(audio_path),
                content_type="audio/wav",
            )

            try:
                async with aiohttp.ClientSession(timeout=self.timeout) as session:
                    async with session.post(self.api_url, data=data) as resp:
                        if resp.status == 200:
                            res_json = await resp.json()
                            text = res_json.get("text", "").strip()
                            logger.info(f"STT 转写成功: '{text}'")
                            return text
                        else:
                            error_text = await resp.text()
                            logger.error(f"STT API 请求异常 [{resp.status}]: {error_text}")
                            return None
            except Exception as e:
                logger.error(f"STT 识别异常: {e}")
                return None
