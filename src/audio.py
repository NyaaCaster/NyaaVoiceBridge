import asyncio
import logging
import os
import shutil
from typing import Optional

logger = logging.getLogger("NyaaVoiceBridge.Audio")

class AudioManager:
    """PipeWire 音频录制与播放管理器"""

    def __init__(self, config: dict):
        self.device_cfg = config.get("device", {})
        self.runtime_cfg = config.get("runtime", {})
        self.input_node = self.device_cfg.get("input_node", "bluez_input.A0_E9_DB_10_B9_D9.0")
        self.output_node = self.device_cfg.get("output_node", "bluez_output.A0_E9_DB_10_B9_D9.1")
        self.sample_rate = self.device_cfg.get("sample_rate", 16000)
        self.channels = self.device_cfg.get("channels", 1)
        self.setup_script = self.device_cfg.get("setup_script", "/usr/local/bin/bt-audio-setup.sh")
        self.temp_dir = self.runtime_cfg.get("temp_dir", "/tmp/nyaa_voice_bridge")
        os.makedirs(self.temp_dir, exist_ok=True)

    def _get_env(self) -> dict:
        """获取 PipeWire 与 D-Bus 运行环境"""
        env = os.environ.copy()
        if "XDG_RUNTIME_DIR" not in env:
            env["XDG_RUNTIME_DIR"] = f"/run/user/{os.getuid()}"
        if "DBUS_SESSION_BUS_ADDRESS" not in env:
            env["DBUS_SESSION_BUS_ADDRESS"] = f"unix:path={env['XDG_RUNTIME_DIR']}/bus"
        return env

    async def ensure_bluetooth_setup(self) -> bool:
        """执行自愈脚本，确保蓝牙音频处于 HFP 双向模式"""
        if not os.path.exists(self.setup_script):
            logger.warning(f"自愈脚本 {self.setup_script} 不存在，跳过自愈")
            return False

        logger.info(f"正在执行蓝牙自愈: {self.setup_script}")
        proc = await asyncio.create_subprocess_exec(
            self.setup_script,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env=self._get_env(),
        )
        stdout, stderr = await proc.communicate()
        if proc.returncode == 0:
            logger.info("蓝牙音频链路自愈完成")
            return True
        else:
            logger.error(f"自愈脚本执行失败: {stderr.decode('utf-8', errors='replace')}")
            return False

    async def record_audio(self, duration: float, output_path: Optional[str] = None) -> str:
        """使用 pw-record 录制指定时长的音频"""
        if output_path is None:
            output_path = os.path.join(self.temp_dir, f"record_{int(asyncio.get_event_loop().time()*1000)}.wav")

        cmd = [
            "pw-record",
            f"--target={self.input_node}",
            f"--rate={self.sample_rate}",
            f"--channels={self.channels}",
            output_path,
        ]

        logger.info(f"开始录音: 目标节点={self.input_node}, 时长={duration}s -> {output_path}")
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env=self._get_env(),
        )

        try:
            await asyncio.wait_for(proc.wait(), timeout=duration)
        except asyncio.TimeoutError:
            try:
                proc.terminate()
                await proc.wait()
            except ProcessLookupError:
                pass

        if os.path.exists(output_path) and os.path.getsize(output_path) > 44:
            logger.info(f"录音成功: {output_path}, 大小={os.path.getsize(output_path)} 字节")
            return output_path
        else:
            raise RuntimeError(f"录音失败，文件为空或未生成: {output_path}")

    async def play_audio(self, audio_path: str) -> bool:
        """使用 pw-play 播放音频到蓝牙音箱"""
        if not os.path.exists(audio_path):
            logger.error(f"播放失败，文件不存在: {audio_path}")
            return False

        cmd = [
            "pw-play",
            f"--target={self.output_node}",
            audio_path,
        ]

        logger.info(f"开始播放音频: 目标节点={self.output_node} <- {audio_path}")
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env=self._get_env(),
        )
        stdout, stderr = await proc.communicate()
        if proc.returncode == 0:
            logger.info("音频播放完成")
            return True
        else:
            logger.error(f"音频播放异常，返回码={proc.returncode}: {stderr.decode('utf-8', errors='replace')}")
            return False
