import os
import sys
from typing import Any, Dict
import yaml

DEFAULT_CONFIG: Dict[str, Any] = {
    "device": {
        "input_node": "bluez_input.A0_E9_DB_10_B9_D9.0",
        "output_node": "bluez_output.A0_E9_DB_10_B9_D9.1",
        "setup_script": "/usr/local/bin/bt-audio-setup.sh",
        "sample_rate": 16000,
        "channels": 1,
    },
    "stt": {
        "api_url": "http://192.168.31.142:5052/v1/audio/transcriptions",
        "model": "SenseVoiceSmall",
        "language": "zh",
        "timeout": 15,
    },
    "astrbot": {
        "ws_url": "ws://127.0.0.1:6199/ws",
        "token": "",
        "bot_id": 943653038,
        "user_id": 10001,
        "group_id": 0,
        "is_group": False,
        "send_voice_flag": True,
        "reconnect_interval": 5,
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
    """加载 YAML 配置文件，并与默认配置深度合并"""
    if not os.path.exists(config_path):
        print(f"[警告] 配置文件 {config_path} 不存在，使用内置默认配置")
        return DEFAULT_CONFIG.copy()

    with open(config_path, "r", encoding="utf-8") as f:
        user_config = yaml.safe_load(f) or {}

    merged = DEFAULT_CONFIG.copy()
    for section, values in user_config.items():
        if isinstance(values, dict) and section in merged:
            merged[section].update(values)
        else:
            merged[section] = values

    return merged
