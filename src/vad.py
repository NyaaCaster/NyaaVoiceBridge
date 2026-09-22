import asyncio
import logging
import math
import os
import struct
import wave
from typing import Optional, AsyncGenerator

logger = logging.getLogger("NyaaVoiceBridge.VAD")

class EnergyVAD:
    """基于音频 RMS 分贝能量的轻量级流式 VAD 检测器 (常驻单管道无损架构)"""

    def __init__(self, config: dict):
        vad_cfg = config.get("vad", {})
        device_cfg = config.get("device", {})
        runtime_cfg = config.get("runtime", {})

        self.enabled = vad_cfg.get("enabled", True)
        self.energy_threshold = vad_cfg.get("energy_threshold", -40.0)  # dBFS
        self.silence_duration = vad_cfg.get("silence_duration", 1.5)   # 语音后静音截断时间 (s)
        self.max_duration = vad_cfg.get("max_record_duration", 15.0)   # 单次最长录音 (s)
        self.min_duration = vad_cfg.get("min_speech_duration", 0.6)   # 有效语音最小长度 (s)

        self.input_node = device_cfg.get("input_node", "bluez_input.A0_E9_DB_10_B9_D9.0")
        self.sample_rate = device_cfg.get("sample_rate", 16000)
        self.channels = device_cfg.get("channels", 1)
        self.temp_dir = runtime_cfg.get("temp_dir", "/tmp/nyaa_voice_bridge")
        os.makedirs(self.temp_dir, exist_ok=True)

        self._proc: Optional[asyncio.subprocess.Process] = None
        self._running = False

    @staticmethod
    def calculate_rms_db(pcm_data: bytes) -> float:
        """计算 PCM 16bit 音频切片的分贝 RMS (dBFS)"""
        count = len(pcm_data) // 2
        if count == 0:
            return -100.0

        shorts = struct.unpack(f"<{count}h", pcm_data)
        sum_squares = sum(s * s for s in shorts)
        rms = math.sqrt(sum_squares / count)
        if rms <= 0.0001:
            return -100.0
        db = 20 * math.log10(rms / 32768.0)
        return db

    async def start_stream(self, env: dict):
        """启动单一常驻 pw-record 进程"""
        if self._proc and self._proc.returncode is None:
            return

        cmd = [
            "pw-record",
            f"--target={self.input_node}",
            f"--rate={self.sample_rate}",
            f"--channels={self.channels}",
            "--format=s16",
            "-",
        ]
        logger.info(f"启动常驻麦克风音频流: {' '.join(cmd)}")
        self._proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
            env=env
        )
        self._running = True

    async def stop_stream(self):
        """关闭常驻麦克风流"""
        self._running = False
        if self._proc:
            try:
                self._proc.terminate()
                await self._proc.wait()
            except Exception:
                pass
            self._proc = None

    async def listen_segments(self, is_busy_check) -> AsyncGenerator[str, None]:
        """从常驻流持续产生人声切片文件"""
        chunk_ms = 0.05  # 50ms 超低延迟切片
        chunk_size = int(self.sample_rate * 2 * chunk_ms)
        
        pre_buffer = bytearray()
        pre_buffer_limit = int(1.0 / chunk_ms) * chunk_size  # 保留 1.0 秒预缓冲
        speech_pcm = bytearray()
        speaking = False
        silence_frames = 0
        silence_frame_limit = int(self.silence_duration / chunk_ms)
        max_frame_limit = int(self.max_duration / chunk_ms)
        speech_frames = 0

        while self._running and self._proc and self._proc.returncode is None:
            # 播放回复时暂停检测，丢弃录音数据防回环
            if is_busy_check():
                await self._proc.stdout.read(chunk_size)
                speaking = False
                speech_pcm.clear()
                pre_buffer.clear()
                await asyncio.sleep(0.02)
                continue

            chunk = await self._proc.stdout.read(chunk_size)
            if not chunk:
                break

            db = self.calculate_rms_db(chunk)

            if not speaking:
                # 静默期，只维护 1 秒预缓冲
                pre_buffer.extend(chunk)
                if len(pre_buffer) > pre_buffer_limit:
                    pre_buffer = pre_buffer[-pre_buffer_limit:]

                if db > self.energy_threshold:
                    speaking = True
                    speech_pcm.clear()
                    speech_pcm.extend(pre_buffer)
                    logger.info(f"🎤 捕获人声起始 (能量: {db:.1f} dBFS, 门限: {self.energy_threshold} dBFS)")
                    silence_frames = 0
                    speech_frames = 0
            else:
                # 录制人声期
                speech_pcm.extend(chunk)
                speech_frames += 1

                if db > self.energy_threshold:
                    silence_frames = 0
                else:
                    silence_frames += 1

                # 判定人声是否自然停顿断句
                if silence_frames >= silence_frame_limit or speech_frames >= max_frame_limit:
                    recorded_duration = len(speech_pcm) / (self.sample_rate * 2)
                    speaking = False
                    silence_frames = 0
                    speech_frames = 0

                    if recorded_duration >= self.min_duration:
                        output_path = os.path.join(
                            self.temp_dir,
                            f"vad_record_{int(asyncio.get_event_loop().time() * 1000)}.wav"
                        )
                        with wave.open(output_path, "wb") as wf:
                            wf.setnchannels(self.channels)
                            wf.setsampwidth(2)
                            wf.setframerate(self.sample_rate)
                            wf.writeframes(speech_pcm)

                        logger.info(f"📦 语音断句完成: {output_path} (时长: {recorded_duration:.2f}s)")
                        speech_pcm.clear()
                        pre_buffer.clear()
                        yield output_path
                    else:
                        logger.debug(f"丢弃过短噪音频 (时长={recorded_duration:.2f}s)")
                        speech_pcm.clear()
                        pre_buffer.clear()
