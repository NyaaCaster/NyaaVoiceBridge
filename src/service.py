import asyncio
import logging
import os
import signal
import sys
from typing import Optional

from src.config import load_config
from src.audio import AudioManager
from src.stt import STTClient
from src.astrbot import AstrBotClient
from src.vad import EnergyVAD

logger = logging.getLogger("NyaaVoiceBridge")

class VoiceBridgeService:
    """NyaaVoiceBridge 核心调度服务"""

    def __init__(self, config_path: str):
        self.config = load_config(config_path)
        self._setup_logging()

        self.audio = AudioManager(self.config)
        self.stt = STTClient(self.config)
        self.vad = EnergyVAD(self.config)
        self.astrbot = AstrBotClient(self.config, on_tts_received=self.handle_tts_reply)

        self.manual_cfg = self.config.get("manual", {})
        self.is_busy = False
        self._running = False

    def _setup_logging(self):
        log_level = getattr(logging, self.config.get("runtime", {}).get("log_level", "INFO").upper(), logging.INFO)
        logging.basicConfig(
            level=log_level,
            format="%(asctime)s [%(levelname)s] [%(name)s] %(message)s",
            datefmt="%Y-%m-%d %H:%M:%S",
        )

    async def handle_tts_reply(self, audio_path: str):
        """处理来自 AstrBot 的 TTS 语音回复"""
        logger.info(f"开始通过蓝牙音箱播放 PixNyaa 回复: {audio_path}")
        await self.audio.play_audio(audio_path)
        # 播放完成后稍作延迟释放忙碌状态
        await asyncio.sleep(0.5)
        self.is_busy = False

    async def trigger_one_dialogue(self, duration: Optional[float] = None):
        """触发单次完整对话流：录音 -> STT -> 推送给 AstrBot"""
        if self.is_busy:
            logger.warning("当前系统正在处理上一轮交互或正在播放语音，忽略本次触发")
            return

        self.is_busy = True
        try:
            # 1. 确保蓝牙处于双向就绪状态
            await self.audio.ensure_bluetooth_setup()

            # 2. 前置提示延迟
            lead_in = self.manual_cfg.get("lead_in_delay", 0.5)
            if lead_in > 0:
                logger.info(f"等待 {lead_in} 秒准备开麦...")
                await asyncio.sleep(lead_in)

            # 3. 录音
            rec_duration = duration or self.manual_cfg.get("default_record_duration", 8.0)
            audio_file = await self.audio.record_audio(rec_duration)

            # 4. 转写
            logger.info("录音完毕，正在请求 SenseVoice 进行识别...")
            text = await self.stt.transcribe(audio_file)
            if not text:
                logger.warning("未识别出有效文本")
                self.is_busy = False
                return

            # 5. 推送 AstrBot
            logger.info(f"识别结果: 【{text}】，正在递交给 AstrBot PixNyaa...")
            await self.astrbot.send_user_message(text)

        except Exception as e:
            logger.error(f"单次对话链路异常: {e}", exc_info=True)
            self.is_busy = False

    async def run(self):
        """主服务运行循环"""
        self._running = True
        logger.info("NyaaVoiceBridge 语音网桥服务启动中...")

        # 1. 蓝牙链路初次巡检
        await self.audio.ensure_bluetooth_setup()

        # 2. 启动 AstrBot WebSocket 异步守护连接
        ws_task = asyncio.create_task(self.astrbot.connect_loop())

        # 3. 如果启用了连续 VAD 静音检测模式
        vad_task = None
        if self.vad.enabled:
            logger.info("连续 VAD 静音自适应检测已启用")
            vad_task = asyncio.create_task(self._vad_loop())
        else:
            logger.info("运行于手动/触发模式，可通过 trigger 接口进行单轮交互")

        try:
            await ws_task
        except asyncio.CancelledError:
            pass
        finally:
            if vad_task:
                vad_task.cancel()

    async def _vad_loop(self):
        """连续 VAD 检测循环"""
        env = self.audio._get_env()
        while self._running:
            if self.is_busy:
                await asyncio.sleep(0.5)
                continue

            try:
                audio_path = await self.vad.record_until_silence(env)
                if audio_path:
                    self.is_busy = True
                    text = await self.stt.transcribe(audio_path)
                    if text:
                        logger.info(f"VAD 捕获识别: 【{text}】")
                        await self.astrbot.send_user_message(text)
                    else:
                        self.is_busy = False
            except Exception as e:
                logger.error(f"VAD 循环异常: {e}")
                await asyncio.sleep(1.0)
