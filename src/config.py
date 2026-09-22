import os
import sys
from typing import Any, Dict
import yaml

DEFAULT_CONFIG: Dict[str, Any] = {
    "device": {
        "input_node": "bluez_input.00_00_00_00_00_00.0",
        "output_node": "bluez_output.00_00_00_00_00_00.1",
        "setup_script": "/usr/local/bin/bt-audio-setup.sh",
        "sample_rate": 48000,
        "channels": 1,
    },
    "stt": {
        "api_url": "http://127.0.0.1:5052/v1/audio/transcriptions",
        "model": "SenseVoiceSmall",
        "language": "zh",
        "timeout": 15,
    },
    "astrbot": {
        "ws_url": "ws://127.0.0.1:6199/ws",
        "token": "",
        "bot_id": 10000,
        "user_id": 10001,
        "group_id": 0,
        "is_group": False,
        "send_voice_flag": True,
        "reconnect_interval": 5,
    },
    "trigger": {
        "mode": "wakeword",  # "wakeword" (唤醒词触发) | "continuous" (连续全量VAD) | "manual" (外部触发)
        "wakewords": ["猫猫", "喵喵", "pixnyaa"],
        "strip_wakeword": True,  # 发送给 AstrBot 时是否裁剪掉开头的唤醒词
        "wake_sound_path": "",   # 唤醒成功时的提示音(可选)
    },
    "vad": {
        "enabled": False,
        "energy_threshold": -42.0,
        "silence_duration": 1.2,
        "max_record_duration": 15.0,
        "min_speech_duration": 0.5,
    },
    "manual": {
        "default_record_duration": 8.0,
        "lead_in_delay": 0.8,
    },
    "runtime": {
        "log_level": "INFO",
        "temp_dir": "/tmp/nyaa_voice_bridge",
    },
}

def load_config(config_path: str) -> Dict[str, Any]:
    """加载 YAML 配置文件，并与默认配置深度合并（支持从同目录 .env 或系统环境变量覆盖敏感字段）"""
    # 尝试加载可能存在的 .env 文件
    env_path = os.path.join(os.path.dirname(config_path), "..", ".env")
    if os.path.exists(env_path):
        try:
            with open(env_path, "r", encoding="utf-8") as ef:
                for line in ef:
                    line = line.strip()
                    if line and not line.startswith("#") and "=" in line:
                        k, v = line.split("=", 1)
                        os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))
        except Exception:
            pass

    if not os.path.exists(config_path):
        print(f"[警告] 配置文件 {config_path} 不存在，使用内置默认配置")
        user_config = {}
    else:
        with open(config_path, "r", encoding="utf-8") as f:
            user_config = yaml.safe_load(f) or {}

    merged = DEFAULT_CONFIG.copy()
    for section, values in user_config.items():
        if isinstance(values, dict) and section in merged:
            merged[section].update(values)
        else:
            merged[section] = values

    # 环境变量覆盖（优先级别最高，方便容器或无配置文件场景安全注入）
    if "NYAA_BT_INPUT_NODE" in os.environ:
        merged["device"]["input_node"] = os.environ["NYAA_BT_INPUT_NODE"]
    if "NYAA_BT_OUTPUT_NODE" in os.environ:
        merged["device"]["output_node"] = os.environ["NYAA_BT_OUTPUT_NODE"]
    if "NYAA_STT_API_URL" in os.environ:
        merged["stt"]["api_url"] = os.environ["NYAA_STT_API_URL"]
    if "NYAA_ASTRBOT_WS_URL" in os.environ:
        merged["astrbot"]["ws_url"] = os.environ["NYAA_ASTRBOT_WS_URL"]
    if "NYAA_ASTRBOT_USER_ID" in os.environ:
        merged["astrbot"]["user_id"] = int(os.environ["NYAA_ASTRBOT_USER_ID"])

    return merged
