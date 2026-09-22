# NyaaVoiceBridge 🐱🎙️

> 「世界の中心で、AIをさけぶ」<br>——连接物理蓝牙音频设备与 AstrBot 机器人的独立低延迟语音桥接服务。
>- ……这不是小爱同学，是贾维斯……喵维斯？
>- 当然，除非你有猫猫全套智能框架~

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Python: 3.10+](https://img.shields.io/badge/python-3.10+-blue.svg)](https://www.python.org/)
[![Platform: Linux](https://img.shields.io/badge/platform-Linux%20(PipeWire)-orange.svg)](https://pipewire.org/)

---

## 🌟 核心特性
- **物理音箱 AI 实体化**：使用 Linux (macmini) 现有的蓝牙音箱或麦克风硬件，直通 AstrBot 核心。
- **100% 继承机器人能力**：通过 OneBot V11 反向 WebSocket 协议交互，无损继承 PixNyaa 的全部人格、设定、长短期记忆及第三方插件（如 `Nyaa猫猫语音` 和 `Nyaa猫猫智能框架`）。
- **纯局域网与零风控**：彻底摆脱 QQ 登录限制、NapCat 风控及公网传输延迟。
- **超快响应**：基于本地部署的 `SenseVoice-OpenAI-API` 毫秒级语音识别 + 硬件级 PipeWire 录放音流。
- **高可用守护**：采用 Python 异步微核心 + Systemd 守护进程托管，支持开机自启、崩溃自动重启与日志统一轮转。

---

## 🏗️ 系统架构

```
[ NDZ-03-GA 蓝牙音箱 / 麦克风 ]
            │ (PipeWire pw-record)
            ▼
   [ NyaaVoiceBridge ] ───(HTTP POST)───► [ SenseVoice STT (OpenAI API Endpoint) ]
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
├── .docs/                  # 项目文档、SSOT 蓝图与阶段交接
├── config/                 # 配置文件模板
│   └── config.example.yaml
├── scripts/                # 蓝牙硬件配置与自愈看门狗
│   ├── bt-audio-setup.sh   # 蓝牙配对与 HFP 双向模式自愈配置
│   └── bt-audio-monitor.sh # 蓝牙音频连接与 PipeWire 状态看门狗
├── src/                    # 核心源码
│   ├── __init__.py
│   ├── config.py           # 配置模型与解析
│   ├── audio.py            # PipeWire 硬件录放音驱动
│   ├── stt.py              # SenseVoice 本地语音识别客户端
│   ├── astrbot.py          # OneBot V11 反向 WebSocket 协议客户端
│   ├── vad.py              # 能量检测与静音切片
│   ├── service.py          # 异步状态机编排与唤醒词过滤
│   └── main.py             # 服务入口与信号优雅退出
├── systemd/                # Linux 守护进程单元文件
│   ├── nyaa-voice-bridge.service # 语音核心服务守护
│   └── bt-audio-monitor.service  # 蓝牙自愈看门狗守护
├── requirements.txt
├── README.md
└── LICENSE
```

---

## 🚀 快速开始与部署

### 1. 克隆代码
```bash
git clone https://github.com/NyaaCaster/NyaaVoiceBridge.git /opt/NyaaVoiceBridge
cd /opt/NyaaVoiceBridge
```

### 2. 环境准备
```bash
python3 -m venv venv
./venv/bin/pip install -r requirements.txt
```

### 3. 配置
```bash
cp config/config.example.yaml config/config.yaml
# 根据实际环境调整配置
vim config/config.yaml
```

> 💡 **唤醒词与触发配置说明**：
> 打开 `config/config.yaml` 找到 `trigger:` 小节：
> - `mode`: 运行模式，可选 `wakeword`（带唤醒词）或 `continuous`（免唤醒连续拾音对话）。
> - `wakewords`: 唤醒词列表，默认已配置为 `小猫同学`（并兼容 `小猫`、`猫猫`、`喵喵` 及各类常见谐音）。
> - `strip_wakeword`: 是否在发送给 AstrBot 前自动剔除唤醒词（默认 `true`）。

### 4. 注册并启动 Systemd 服务
```bash
# 安装看门狗与语音核心服务
cp systemd/*.service /etc/systemd/system/
systemctl daemon-reload

# 启动并开机自启
systemctl enable --now nyaa-voice-bridge.service
systemctl enable --now bt-audio-monitor.service
```

### 5. 实时监控服务状态与日志
```bash
# 实时跟踪语音桥接核心服务日志（查看麦克风录音、唤醒词命中、STT识别与TTS回复）
journalctl -u nyaa-voice-bridge.service -f

# 实时跟踪蓝牙自愈看门狗日志
journalctl -u bt-audio-monitor.service -f
```

---

## 📚 深度架构与技术文档
想要深入了解整个语音系统底层 PipeWire 交互原理、防吞字 VAD 环形缓冲、OneBot 协议逆向以及 AstrBot 下挂**猫猫全套智能框架（人格、提示词、记忆提炼与外部工具）**的完整运作机制？
👉 请查阅：**[技术架构与拓扑文档](.docs/技术架构与拓扑文档.md)**

---

## 📄 开源许可
本项目基于 [MIT License](LICENSE) 协议开源。
