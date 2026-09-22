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
        self.trigger_cfg = self.config.get("trigger", {})
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

        # 3. 检查并启动后台音频监听循环（唤醒词/VAD）
        vad_task = None
        mode = self.trigger_cfg.get("mode", "wakeword")
        if mode in ("wakeword", "continuous"):
            logger.info(f"后台监听模式 [{mode}] 已就绪，唤醒词: {self.trigger_cfg.get('wakewords', [])}")
            vad_task = asyncio.create_task(self._vad_loop())
        else:
            logger.info("运行于手动模式 (manual)，可通过外部 trigger 触发交互")

        try:
            await ws_task
        except asyncio.CancelledError:
            pass
        finally:
            if vad_task:
                vad_task.cancel()

    def _check_wakeword(self, text: str) -> Optional[str]:
        """检查文本是否包含唤醒词，并根据配置返回裁剪后的内容"""
        wakewords = self.trigger_cfg.get("wakewords", ["猫猫"])
        strip = self.trigger_cfg.get("strip_wakeword", True)
        clean_text = text.strip()

        matched_word = None
        for w in wakewords:
            if w in clean_text:
                matched_word = w
                break

        if not matched_word:
            return None

        if strip:
            # 找到唤醒词位置并截取后面的真实问询
            idx = clean_text.find(matched_word)
            sub = clean_text[idx + len(matched_word):].lstrip("，, 。.？！?!~ ")
            return sub if sub else ""  # 空字符串代表纯唤醒（例如只喊了句“猫猫”）
        return clean_text

    async def _vad_loop(self):
        """连续检测循环（支持唤醒词过滤与全量连续模式）"""
        env = self.audio._get_env()
        mode = self.trigger_cfg.get("mode", "wakeword")
        logger.info(f"音频监听循环已启动，当前工作模式: {mode}")

        while self._running:
            if self.is_busy:
                await asyncio.sleep(0.5)
                continue

            try:
                audio_path = await self.vad.record_until_silence(env)
                if not audio_path:
                    continue

                # 转写语音
                text = await self.stt.transcribe(audio_path)
                if not text:
                    continue

                if mode == "wakeword":
                    processed = self._check_wakeword(text)
                    if processed is None:
                        logger.debug(f"忽略未唤醒的语音输入: 【{text}】")
                        continue

                    logger.info(f"🌟 命中唤醒词！原始转写: 【{text}】")
                    self.is_busy = True

                    # 如果用户只是喊了句“猫猫”，没有带后续问题，默认打个招呼
                    msg_to_send = processed if processed else "你好呀"
                    logger.info(f"向 PixNyaa 发送有效提问: 【{msg_to_send}】")
                    await self.astrbot.send_user_message(msg_to_send)

                else:
                    # continuous 全量模式
                    self.is_busy = True
                    logger.info(f"VAD 捕获识别: 【{text}】")
                    await self.astrbot.send_user_message(text)

            except Exception as e:
                logger.error(f"VAD 循环异常: {e}")
                self.is_busy = False
                await asyncio.sleep(1.0)
