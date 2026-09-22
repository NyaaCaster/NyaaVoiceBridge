import asyncio
import logging
import math
import os
import shutil
import struct
import wave
from typing import Optional

logger = logging.getLogger("NyaaVoiceBridge.VAD")

class EnergyVAD:
    """基于音频 RMS 分贝能量的轻量级 VAD 静音检测器"""

    def __init__(self, config: dict):
        vad_cfg = config.get("vad", {})
        device_cfg = config.get("device", {})
        runtime_cfg = config.get("runtime", {})

        self.enabled = vad_cfg.get("enabled", False)
        self.energy_threshold = vad_cfg.get("energy_threshold", -42.0)  # dBFS
        self.silence_duration = vad_cfg.get("silence_duration", 1.2)   # 语音后静音截断时间 (s)
        self.max_duration = vad_cfg.get("max_record_duration", 15.0)   # 单次最长录音 (s)
        self.min_duration = vad_cfg.get("min_speech_duration", 0.5)   # 最短有效语音时长 (s)

        self.input_node = device_cfg.get("input_node", "bluez_input.A0_E9_DB_10_B9_D9.0")
        self.sample_rate = device_cfg.get("sample_rate", 16000)
        self.channels = device_cfg.get("channels", 1)
        self.temp_dir = runtime_cfg.get("temp_dir", "/tmp/nyaa_voice_bridge")

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

    async def record_until_silence(self, env: dict) -> Optional[str]:
        """流式监听 PipeWire 并在说话结束（连续静音）后自动切断并保存音频"""
        chunk_size = int(self.sample_rate * 2 * 0.1)  # 100ms 切片大小 (16bit 单声道 = 2 字节/采样)
        output_path = os.path.join(self.temp_dir, f"vad_record_{int(asyncio.get_event_loop().time()*1000)}.wav")

        cmd = [
            "pw-record",
            f"--target={self.input_node}",
            f"--rate={self.sample_rate}",
            f"--channels={self.channels}",
            "--format=s16",
            "-",  # 输出到 stdout
        ]

        logger.info(f"启动 VAD 监听: 阈值={self.energy_threshold} dBFS, 静音截断={self.silence_duration}s")
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
            env=env,
        )

        all_pcm = bytearray()
        speaking = False
        silence_frames = 0
        silence_frame_limit = int(self.silence_duration / 0.1)
        max_frame_limit = int(self.max_duration / 0.1)
        total_frames = 0

        try:
            while total_frames < max_frame_limit:
                chunk = await proc.stdout.read(chunk_size)
                if not chunk:
                    break

                all_pcm.extend(chunk)
                total_frames += 1
                db = self.calculate_rms_db(chunk)

                if db > self.energy_threshold:
                    if not speaking:
                        logger.debug(f"检测到人声起始 (能量 {db:.1f} dBFS)")
                    speaking = True
                    silence_frames = 0
                else:
                    if speaking:
                        silence_frames += 1
                        if silence_frames >= silence_frame_limit:
                            logger.info(f"检测到人声结束 (持续静音 {self.silence_duration}s)")
                            break

        finally:
            try:
                proc.terminate()
                await proc.wait()
            except ProcessLookupError:
                pass

        # 检查人声有效性
        recorded_duration = len(all_pcm) / (self.sample_rate * 2)
        if not speaking or recorded_duration < self.min_duration:
            logger.debug(f"录音未达有效人声阈值，放弃转写 (时长={recorded_duration:.2f}s, speaking={speaking})")
            return None

        # 保存为标准 WAV 文件
        with wave.open(output_path, "wb") as wf:
            wf.setnchannels(self.channels)
            wf.setsampwidth(2)
            wf.setframerate(self.sample_rate)
            wf.writeframes(all_pcm)

        logger.info(f"VAD 录音截断就绪: {output_path}, 时长={recorded_duration:.2f}s")
        return output_path
