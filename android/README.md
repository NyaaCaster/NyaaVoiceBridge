# NyaaVoiceBridge Android App

Android 手机端语音桥接应用。它采集蓝牙麦克风音频，在本机外部的 SenseVoice 兼容 HTTP 服务完成语音转写，执行唤醒词过滤，再通过 OneBot 反向 WebSocket 将请求发送给 AstrBot。

## 语音对话链路

```text
蓝牙麦克风 / SCO
  → Android AudioRecord + RMS VAD
  → SenseVoice HTTP 转写
  → 唤醒词匹配并移除唤醒词
  → OneBot 反向 WebSocket 普通文本消息（开头带 [voice] 控制头）
  → AstrBot 插件剥离控制头，并要求 LLM 调用 reply_with_voice
  → 插件合成 TTS，通过 OneBot reverse action 返回 record 语音段
  → Android 下载/解码并播放语音回复
```

`[voice]` 是传输层的消息类型标记，不是语音转写内容或用户对话正文。`astrbot_plugin_nyaa_voice` 会在请求进入 LLM 前消费该标签，并追加临时控制说明要求调用 `reply_with_voice`。

App 发出的是普通 OneBot 文本消息，例如：

```text
[voice] 给猫猫盖被子
```

不会伪造 CQ `record` 消息段来表示已转写的语音输入。

## 主要模块

| 模块 | 职责 |
|---|---|
| `MainActivity.kt` | 配置界面、权限申请、前台服务控制 |
| `VoiceBridgeService.kt` | 编排录音、STT、唤醒词检测、AstrBot 连接与语音播放 |
| `AudioRecordDriver.kt` / `RmsVadEngine.kt` | 麦克风采集并检测语音片段 |
| `BluetoothScoManager.kt` | 控制蓝牙 SCO 音频路由 |
| `SenseVoiceClient.kt` / `WakeWordDetector.kt` | 调用转写服务、匹配并移除唤醒词 |
| `AstrBotClient.kt` | 维护反向 WebSocket、发送 OneBot 事件、处理下行动作与 `record` 回复 |
| `TtsAudioPlayer.kt` | 播放返回的语音数据或音频 URL |
| `ConfigManager.kt` | 持久化设置并提供首次安装默认值 |

## 配置默认值

首次启动时，`ConfigManager.kt` 在没有保存配置的情况下提供以下默认值：

- AstrBot WebSocket：`ws://h.nyaa.host:6199/ws`
- STT endpoint：`http://h.nyaa.host:5052/v1/audio/transcriptions`
- 唤醒词：`小猫同学`
- 自动蓝牙 SCO：开启
- AstrBot 用户 ID：由部署配置中的 `DEFAULT_USER_ID` 指定

配置保存在 `nyaa_bridge_prefs` SharedPreferences 中。启动语音服务时，界面上的配置会保存；为其他部署构建时，请按需修改 `ConfigManager.kt` 中的默认值。

## 构建环境

- 最低 Android API：26；target API：31；compile API：34。
- JDK 17、Gradle 8.2、Android SDK Platform 34。
- 需要麦克风录音、蓝牙连接等系统权限；Android 12 及以上还需要附近设备权限以使用 SCO 路由。
- 手机需要能访问所配置的 STT 服务和 AstrBot WebSocket。

项目提供 Gradle 构建配置，但没有提交 Gradle Wrapper 脚本。设置好 JDK、Android SDK 和 Gradle 8.2 后，在本目录执行：

```powershell
gradle :app:assembleDebug --no-daemon
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

当前开发机所用工具链放在 `H:\GitHub\AndroidKitTools`。

## 安装与更新

```powershell
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android 只有在新旧 APK 使用相同签名证书时才允许原位更新。签名不一致时，需要先导出并保留必需设置，再卸载旧包并安装新 APK；卸载会清除应用私有数据。

查看 App 进程日志：

```powershell
adb logcat --pid=<应用进程号> -v time
```

App 界面的实时终端也会显示服务、STT、唤醒词、WebSocket 和播放状态。
