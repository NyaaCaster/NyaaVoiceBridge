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

    async def _busy_timeout_guard(self, timeout: float = 30.0):
        """超时看门狗：防止 AstrBot 异常或无语音回复导致 is_busy 永久死锁"""
        await asyncio.sleep(timeout)
        if self.is_busy:
            logger.warning(f"等待 PixNyaa 语音回复超过 {timeout}s，自动重置繁忙状态")
            self.is_busy = False

    def _check_wakeword(self, text: str) -> Optional[str]:
        """检查文本是否包含唤醒词或近似音，并根据配置返回裁剪后的内容"""
        # 默认唤醒词调整为“小猫同学”，同时支持“小猫”、“猫猫”、“喵喵”等
        wakewords = self.trigger_cfg.get("wakewords", [
            "小猫同学", "小猫同学生", "小猫", "猫猫同学", "猫猫", "喵喵同学", "喵喵"
        ])
        # 常见 SenseVoice 近似音/同音错别字字典放宽
        fuzzy_patterns = [
            "小毛同学", "小茅同学", "小帽同学", "熊猫同学",
            "小猫同居", "小猫统一", "小毛同", "小猫痛",
            "小毛", "小茅", "小帽",
            "毛毛", "矛矛", "喵喵"
        ]
        all_candidates = list(wakewords) + [w for w in fuzzy_patterns if w not in wakewords]
        # 按长度降序排序，优先匹配最长词（例如优先匹配“小猫同学”而不是“小猫”）
        all_candidates.sort(key=len, reverse=True)

        strip = self.trigger_cfg.get("strip_wakeword", True)
        clean_text = text.strip()

        matched_word = None
        for w in all_candidates:
            if w in clean_text:
                matched_word = w
                break

        if not matched_word:
            return None

        if strip:
            # 找到唤醒词位置并截取后面的真实问询
            idx = clean_text.find(matched_word)
            sub = clean_text[idx + len(matched_word):].lstrip("，, 。.？！?!~ ")
            return sub if sub else ""  # 空字符串代表纯唤醒（例如只喊了句“小猫同学”）
        return clean_text

    async def _vad_loop(self):
        """连续检测循环（基于常驻无损单流与生成器，彻底避免反复进程启停导致吞音）"""
        env = self.audio._get_env()
        mode = self.trigger_cfg.get("mode", "wakeword")
        logger.info(f"音频流式监听守护已启动，当前工作模式: {mode}")

        await self.vad.start_stream(env)

        try:
            async for audio_path in self.vad.listen_segments(lambda: self.is_busy):
                if not self._running:
                    break

                # 转写语音
                logger.info("检测到有效人声并完成截断，提交 SenseVoice 识别...")
                text = await self.stt.transcribe(audio_path)
                if not text:
                    logger.debug("SenseVoice 转写为空，继续监听")
                    continue

                logger.info(f"🎤 语音转写结果: 【{text}】")

                if mode == "wakeword":
                    processed = self._check_wakeword(text)
                    if processed is None:
                        logger.info(f"未匹配到唤醒词 (当前唤醒词: {self.trigger_cfg.get('wakewords')})，忽略: 【{text}】")
                        continue

                    logger.info(f"🌟 命中唤醒词！原始转写: 【{text}】")
                    self.is_busy = True
                    asyncio.create_task(self._busy_timeout_guard(30.0))

                    # 如果用户只是喊了句“猫猫”，没有带后续问题，默认打个招呼
                    msg_to_send = processed if processed else "你好呀"
                    logger.info(f"向 PixNyaa 发送有效提问: 【{msg_to_send}】")
                    await self.astrbot.send_user_message(msg_to_send)

                else:
                    # continuous 全量模式
                    self.is_busy = True
                    asyncio.create_task(self._busy_timeout_guard(30.0))
                    logger.info(f"VAD 捕获识别: 【{text}】")
                    await self.astrbot.send_user_message(text)

        except Exception as e:
            logger.error(f"VAD 循环异常: {e}", exc_info=True)
            self.is_busy = False
            # 异常发生后，继续重启 _vad_loop
            await asyncio.sleep(1.0)
            asyncio.create_task(self._vad_loop())
        finally:
            await self.vad.stop_stream()
