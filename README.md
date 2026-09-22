# NyaaVoiceBridge 🐱🎙️

> 连接物理蓝牙音频设备与 AstrBot AI 机器人的独立低延迟语音桥接服务。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Python: 3.10+](https://img.shields.io/badge/python-3.10+-blue.svg)](https://www.python.org/)
[![Platform: Linux](https://img.shields.io/badge/platform-Linux%20(PipeWire)-orange.svg)](https://pipewire.org/)

---

## 🌟 核心特性
- **物理 AI 音箱实体化**：使用 Linux (macmini) 现有的蓝牙音箱或麦克风硬件，直通 AstrBot 核心。
- **100% 继承机器人能力**：通过 OneBot V11 反向 WebSocket 协议交互，无损继承 PixNyaa 的全部人格、设定、长短期记忆及第三方插件（如 `Nyaa猫猫语音` TTS）。
- **纯局域网与零风控**：彻底摆脱 QQ 登录限制、NapCat 风控及公网传输延迟。
- **超快响应**：基于本地部署的 `SenseVoice-OpenAI-API` 毫秒级语音识别 + 硬件级 PipeWire 录放音流。
- **高可用守护**：采用 Python 异步微核心 + Systemd 守护进程托管，支持开机自启、崩溃自动重启与日志统一轮转。

---

## 🏗️ 系统架构

```
[ NDZ-03-GA 蓝牙音箱 / 麦克风 ]
            │ (PipeWire pw-record)
            ▼
   [ NyaaVoiceBridge ] ───(HTTP POST)───► [ SenseVoice STT (192.168.31.142:5052) ]
            │
            │ (OneBot V11 ws://astrbot:6199/ws)
            ▼
    [ AstrBot 机器人 (PixNyaa) ]
     (人格/记忆/插件流水线)
            │
            │ (TTS 音频回传)
            ▼
   [ NyaaVoiceBridge ]
            │ (PipeWire pw-play)
            ▼
[ NDZ-03-GA 蓝牙音箱音频播放 ]
```

---

## 📁 目录结构
```tree
NyaaVoiceBridge/
├── .docs/                  # 项目文档与 SSOT 蓝图
├── config/                 # 配置文件模板
│   └── config.example.yaml
├── src/                    # 核心源码
│   ├── __init__.py
│   ├── audio/              # 音频硬件输入输出与录放
│   ├── stt/                # 语音识别客户端
│   ├── bridge/             # OneBot V11 WebSocket 桥接协议
│   └── main.py             # 核心事件循环调度
├── systemd/                # Linux 守护进程单元文件
│   └── nyaa-voice-bridge.service
├── requirements.txt
├── README.md
└── LICENSE
```

---

## 📄 开源许可
本项目基于 [MIT License](LICENSE) 协议开源。
