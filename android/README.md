# NyaaVoiceBridge Android 猫猫私语App
> - 猫猫私语App 的链路设计完全来自机器人猫猫PixNyaa的应用涌现，而非开发者 NyaaCaster 的原初设计，详见 [NyaaVoiceBridge应用方案.md](../.docs/NyaaVoiceBridge应用方案.md)
> - 这是一次令人惊艳的偶然，也是猫猫第一次在框架应用设计层展现出超越开发者本身设计思维范畴的飞跃表现。
> - 即使故此即使呕心沥血我也要将这个app开发出来！

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

## 私有部署配置与打包

WebSocket 地址、STT endpoint 和 AstrBot 用户 ID 属于部署私有配置，不要写入受 Git 跟踪的源码或文档。首次打包时，将 `deployment.properties.example` 复制为未跟踪的 `deployment.properties`，填写本机值，然后运行：

```powershell
.\package-apk.ps1
```

脚本读取本地配置，并通过 Gradle 构建参数注入 APK 默认值。`deployment.properties` 已列入 `.gitignore`；提交前请确认未将其强制加入 Git。提交仓库中的示例只包含占位符。

配置保存在 `nyaa_bridge_prefs` SharedPreferences 中。首次启动默认值来自 APK 构建时注入的部署参数；唤醒词默认值为 `小猫同学`，自动蓝牙 SCO 默认开启。

## 构建环境

- 最低 Android API：26；target API：31；compile API：34。
- JDK 17、Gradle 8.2、Android SDK Platform 34。
- 需要麦克风录音、蓝牙连接等系统权限；Android 12 及以上还需要附近设备权限以使用 SCO 路由。
- 手机需要能访问所配置的 STT 服务和 AstrBot WebSocket。
- 当前开发机工具链放在 `H:\GitHub\AndroidKitTools`；可直接使用上面的打包脚本。

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

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
