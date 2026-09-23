# NyaaVoiceBridge V2 移动端方案 - 开发计划 SSOT (权威蓝图)

## 一、 项目愿景与边界定义

- **核心愿景**：打造一款专为 Android 设计的**极简极客、随身耳语级 AI 伴侣后台客户端（`NyaaVoiceBridge Android`）**。
- **产品核心定位**：
  - **无感盲操**：手机常驻后台（口袋/背包），用户全程通过无线/蓝牙耳机，以纯语音触发词 **`小猫同学`** 与 PixNyaa（猫猫）实现低延迟、双向随身交流。
  - **TUI 极简极客控制台**：拒绝花哨界面，UI 仅由核心状态开关、参数配置与一个滚动刷新的**黑客终端风格日志输出区**组成，专注于纯粹的诊断与稳定性。
  - **边缘轻量路由**：手机端只做硬件音频路由、轻量 RMS 静音切片与 HTTP/WS 消息通道，所有深度大模型思考、长期记忆检索与 TTS 音频合成均依托 macmini 云端中枢。

---

## 二、 核心架构与技术规范

1. **技术栈**：**原生 Android (Kotlin + Gradle)**。
   - 最纯粹的 Google 原生生态，对 `ForegroundService` 后台保活、`AudioManager` 蓝牙 SCO 路由控制拥有 100% 确定性，零框架中间层黑盒。
2. **后台保活与抗杀死矩阵**：
   - `ForegroundService` 绑定常驻系统通知栏。
   - 适配 Android 14+ 麦克风前台服务类型（`foregroundServiceType="microphone|connectedDevice"`）。
   - `PARTIAL_WAKE_LOCK` 防止 CPU 休眠挂起录音流。
   - 引导加入系统“电池优化白名单”（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）。
3. **音频硬件与蓝牙路由规范**：
   - 自动监听蓝牙耳机连接状态，调用 `startBluetoothSco()` 劫持音频麦克风通路至蓝牙耳机。
   - 音频采集：单声道 16bit PCM（默认 48kHz，自适应 SenseVoice 最佳识别率）。
   - 硬件与软件双重互斥：收到 TTS 播放时立即暂停 VAD 采集，播放结束延迟 500ms 重启监听，彻底杜绝自发自收。
4. **网络拓扑规范**：
   - 复用既有的 macmini Nginx 反代网络（AstrBot 控制面板：`http://astrbot.example.invalid:6185`，OneBot11 反向 WS 地址：`ws://astrbot.example.invalid:6199/ws`）。
   - 若移动端 Android WebView / OkHttp 对加密通道有严格要求，统一配置 Nginx WSS / HTTPS 代理证书。

---

## 三、 版本与阶段划分 (V2 Phases)

| 阶段 | 交付目标 | 核心产出物 | 状态 |
|---|---|---|---|
| **V2-P1** | **Android 原生工程初始化与 TUI 控制台骨架** | Android 原生工程（Kotlin）、暗黑终端风格 LogView 组件、本地安全配置管理（URL/用户ID/参数持久化） | ✅ 已完成 |
| **V2-P2** | **原生音频采集、蓝牙 SCO 路由与 RMS VAD 引擎** | `AudioRecord` 驱动、蓝牙 SCO 自动激活/复位广播、低功耗 RMS 能量检测、WAV 内存流封装 | ✅ 已完成 |
| **V2-P3** | **SenseVoice STT 异步转写与「小猫同学」盲操唤醒** | OkHttp 异步 Multipart 转写模块、唤醒词模糊匹配过滤算法（“小猫同学”及谐音容错）、唤醒自愈状态机 | ✅ 已完成 |
| **V2-P4** | **AstrBot OneBot V11 反向 WS 通道与 TTS 互斥播放** | OkHttp WebSocket 适配器、32MB 载荷与全量 Echo 响应、MediaPlayer 播放器与采集互斥锁 | ✅ 已完成 |
| **V2-P5** | **前台服务保活、系统下拉快捷磁贴与发布规范** | `VoiceBridgeForegroundService` 前台常驻、WakeLock、Quick Settings Tile 快捷磁贴开关、全域容灾演练与发布指引 | ✅ 已完成 |

---

## 四、 阶段独立验证标准 (Acceptance Criteria)

- **V2-P1 验收**：
  - App 安装后能成功打开极简 TUI 控制台；
  - 能够修改并保存 AstrBot 地址、STT 地址、用户 ID 等参数，重启 App 配置不丢失。
- **V2-P2 验收**：
  - 用户戴上蓝牙耳机说话，控制台实时打印人声能量与 VAD 切片日志，并能录制生成清晰的 WAV 测试音频。
- **V2-P3 验收**：
  - 用户戴着耳机轻呼“小猫同学，你在吗？”，控制台秒级输出识别结果，并精准剔除“小猫同学”，提取出“你在吗？”。
- **V2-P4 验收**：
  - WebSocket 成功连入 AstrBot，发送文本后，App 控制台打印接收到 Base64 语音，耳机流畅播报出猫猫的声音，播放期间录音自动静音。
- **V2-P5 验收**：
  - 手机锁屏后放入口袋，用户随时在耳机边呼唤“小猫同学”，猫猫能在 1~2 秒内通过耳机回答，无缝实现随身耳语级伴侣体验！
