# NyaaVoiceBridge 开发计划 (SSOT 蓝图)

## 一、 项目概览
- **项目名**：NyaaVoiceBridge
- **定位**：运行在 Linux (macmini) 环境下的后台语音网桥服务，专为 AstrBot 打造，实现本地实体硬件（蓝牙音箱、麦克风）与 AI 机器人（PixNyaa）的双向语音连续对话。
- **仓库地址**：https://github.com/NyaaCaster/NyaaVoiceBridge
- **语言技术栈**：Python 3.10+ / asyncio / websockets / aiohttp / PipeWire / Systemd

---

## 二、 核心架构与数据流

```
 ┌─────────────────────────────────────────────────────────────┐
 │                      NDZ-03-GA 蓝牙音箱                      │
 └─────────────┬─────────────────────────────────▲─────────────┘
               │ (音频输入: 麦克风录音)            │ (音频输出: 语音播放)
               ▼                                 │
 ┌───────────────────────────────────────────────┴─────────────┐
 │                      NyaaVoiceBridge 服务                    │
 │                                                             │
 │  [1. 音频采集与 VAD 模块]                                      │
 │     - PipeWire (pw-record) 监听 bluez_input 节点             │
 │     - 智能唤醒 / 按键录音 / VAD 自动静音分段切片                 │
 │                                                             │
 │  [2. STT 转录客户端]                                         │
 │     - 将切片音频 POST 局域网 SenseVoice (192.168.31.142:5052) │
 │     - 快速获取高精度纯净文本                                    │
 │                                                             │
 │  [3. OneBot V11 协议桥接引擎]                                │
 │     - WebSocket 客户端连接 AstrBot (ws://127.0.0.1:6199/ws) │
 │     - 封装 private_message 事件 (指定 user_id: 10001)       │
 │     - 触发 PixNyaa 人格、记忆、以及 Nyaa猫猫语音插件            │
 │                                                             │
 │  [4. 回复解析与播放模块]                                      │
 │     - 监听 AstrBot 下发的 send_msg 响应                       │
 │     - 解析语音节点 [CQ:record,file=...] 或 回退本地 TTS        │
 │     - 通过 pw-play 管道直接送入音箱播放                        │
 └─────────────────────────────────────────────────────────────┘
```

---

## 三、 关键设计决策 (ADR)
1. **进程形态**：Python 核心业务服务 + Systemd 系统守护。
2. **协议选择**：OneBot V11 反向 WebSocket。AstrBot 原生原生支持 `aiocqhttp`，无需改动 AstrBot 核心代码，且天然完全兼容全部插件与上下文生命周期。
3. **音频接口**：Direct PipeWire CLI / Python Binding（遵循已验证的环境变量 `XDG_RUNTIME_DIR=/run/user/0` 与节点名称绑定）。
4. **会话隔离**：为物理音箱分配专有虚拟账号标识（如 `user_id: 10001`, `sender.nickname: "主人"`），既享受独立对话记忆，又可在后台随时管理。

---

## 四、 版本与开发阶段划分 (V1 MVP)

### P1：基础工程架构与代码库就绪 (🟡 进行中)
- [x] 本地与远程 GitHub 仓库同步绑定（`origin/main`）
- [x] 撰写初始设计文档与 SSOT 蓝图
- [ ] 搭建标准 Python 项目骨架（`src/`, `config/`, `systemd/`, `tests/`）
- [ ] 配置开发依赖（`requirements.txt`, `.gitignore`, `README.md`）

### P2：音频硬件与录放管道抽象 (⬜ 未开始)
- [ ] 封装 PipeWire 录音与播放适配器 (`audio_io.py`)
- [ ] 蓝牙音频自动探活与自愈挂钩 (`bt-audio-setup.sh` 集成)
- [ ] 录音切片与本地试听回放验证单元

### P3：STT 与 OneBot V11 桥接引擎 (⬜ 未开始)
- [ ] SenseVoice-OpenAI-API 异步转写客户端 (`stt_client.py`)
- [ ] OneBot V11 虚拟客户端 (`onebot_bridge.py`)：实现握手、心跳、私聊/群聊消息事件发送与消息接收回调
- [ ] 连通性测试：控制台打字/模拟音频 -> AstrBot PixNyaa -> 接收文本/语音回复

### P4：交互状态机与全双工对话控制 (⬜ 未开始)
- [ ] 核心协调器 (`bridge_service.py`)：唤醒 -> 录音 -> STT -> LLM -> 播音
- [ ] 打断机制与音箱播放状态防自激反馈抑制（避免音箱说话自己录进去）

### P5：macmini 部署与 Systemd 守护上线 (⬜ 未开始)
- [ ] 编写一键部署脚本 `install.py` / `deploy.py`
- [ ] 编写 `nyaa-voice-bridge.service` 单元文件并启用开机自启
- [ ] 端到端实物验收（对着音箱呼叫 PixNyaa 语音对话）
