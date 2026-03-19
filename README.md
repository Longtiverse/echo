# Echo - 实时环境音监听应用

一个 Android 实时环境音监听应用，目标是 **稳定收音并即时播放**，同时提供音量限制、后台运行、自动恢复与真机兼容回退能力。

## 当前实现

当前默认链路已调整为：

- `AudioRecord + AudioTrack` 实时回环
- 运行时自动尝试不同采样率与音源模式
- 前台服务负责保活、状态同步、日志广播与自动恢复

原有 `cpp/` / Oboe 代码仍保留，后续可继续做机型专项优化。

## 已完成的关键能力

- 实时收音播放
- 音量限制与软限幅
- 前台服务后台运行
- WakeLock 保活
- 音频焦点处理
- 耳机断开自动停止
- 启动失败可见，不再“假成功”
- 异常后自动恢复（最多 3 次）
- 音源模式切换
- 输出路由模式切换
- 运行日志面板

## 主界面新增设置

- **输入音源模式**
  - 自动选择
  - 语音识别
  - 标准麦克风
  - 摄像机麦克风
  - 系统默认

- **输出路由模式**
  - 系统默认
  - 扬声器优先
  - 听筒优先

- **异常时自动恢复**
  - 默认开启
  - 最多自动重试 3 次

## 主要实现文件

- `D:/Project/Echo/app/src/main/java/com/echo/app/MainActivity.kt`
- `D:/Project/Echo/app/src/main/java/com/echo/app/service/AudioMonitoringService.kt`
- `D:/Project/Echo/app/src/main/java/com/echo/app/audio/OboeAudioEngine.kt`
- `D:/Project/Echo/app/src/main/java/com/echo/app/audio/AudioRouteMode.kt`
- `D:/Project/Echo/app/src/main/res/layout/activity_main.xml`
- `D:/Project/Echo/app/src/main/res/values/strings.xml`

## 构建

```bash
./gradlew assembleDebug
```

## 最新验证

已通过：

- `assembleDebug`
- `lintDebug`
- `testDebugUnitTest`

最新 APK：

- `D:/Project/Echo/app/build/outputs/apk/latest/Echo_Debug_latest.apk`

## 注意事项

1. 外放监听天然存在啸叫风险，建议先从 30% - 50% 音量开始测试。
2. 如果某个机型收音或播放异常，可优先切换：
   - 输入音源模式
   - 输出路由模式
3. 若系统后台限制严格，建议手动加入电池优化白名单。
