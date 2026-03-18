# Echo - 低延迟音频监听应用

一个Android实时环境音监听应用，支持最小化延迟播放、音量限制和后台保活。

## 功能特性

- ✅ **实时收音播放**: 麦克风输入即时播放，延迟 < 20ms
- ✅ **音量限制**: 可设置最大播放音量，保护听力
- ✅ **后台保活**: 前台服务，支持长时间后台运行
- ✅ **系统级覆盖**: 在其他应用之上播放
- ✅ **低延迟优化**: 使用Oboe音频库，AAudio低延迟路径

## 技术架构

### 核心组件

```
MainActivity (UI层)
    ↓
AudioMonitoringService (前台服务)
    ↓
AudioEngine (Kotlin-C++桥接)
    ↓
Oboe Audio Engine (C++)
    ↓
麦克风 → 环形缓冲区 → 扬声器
```

### 技术栈

- **开发语言**: Kotlin + C++ (NDK)
- **音频库**: Oboe 1.8.1
- **最低API**: Android 8.0 (API 26)
- **架构**: MVVM + Service

## 开发阶段

### Phase 1: 基础架构与权限 ✅
- Android项目初始化
- 权限管理系统 (RECORD_AUDIO, FOREGROUND_SERVICE)
- 前台服务框架
- 权限管理器 PermissionManager

### Phase 2: 低延迟音频实现 ✅
- Oboe音频库集成
- 音频引擎C++实现
- 环形缓冲区管理
- 目标延迟: < 20ms

### Phase 3: 音量控制系统 ✅
- VolumeController 音量管理
- 软限幅算法 (Soft Clipping)
- 最大音量限制 (dB转线性)
- 防止音频削波

### Phase 4: 后台保活机制 ✅
- 前台服务通知 (不可滑动删除)
- WakeLock防止CPU休眠
- 电池优化白名单引导
- 开机自启动支持

### Phase 5: 系统级音频覆盖 ✅
- 音频焦点管理 (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
- USAGE_ASSISTANCE_SONIFICATION
- 音频路由策略 (扬声器/耳机/蓝牙)
- 强制可听配置

## 项目结构

```
Echo/
├── app/
│   ├── src/main/
│   │   ├── java/com/echo/
│   │   │   ├── MainActivity.kt
│   │   │   ├── EchoApplication.kt
│   │   │   ├── service/
│   │   │   │   ├── AudioMonitoringService.kt
│   │   │   │   └── BootReceiver.kt
│   │   │   ├── audio/
│   │   │   │   ├── AudioEngine.kt
│   │   │   │   └── VolumeController.kt
│   │   │   └── permission/
│   │   │       └── PermissionManager.kt
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt
│   │   │   ├── audio_engine.h/.cpp
│   │   │   ├── volume_limiter.h/.cpp
│   │   │   └── jni_bridge.cpp
│   │   └── res/
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── README.md
```

## 构建说明

### 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高
- NDK 25.1.8937393
- CMake 3.22.1
- JDK 17

### 构建步骤

1. 克隆项目
```bash
git clone <repository-url>
cd Echo
```

2. 在Android Studio中打开项目

3. 同步Gradle并构建
```bash
./gradlew assembleDebug
```

4. 安装到设备
```bash
./gradlew installDebug
```

## 权限说明

应用需要以下权限：

| 权限 | 用途 | 申请方式 |
|------|------|----------|
| `RECORD_AUDIO` | 麦克风录制 | 运行时 |
| `FOREGROUND_SERVICE` | 前台服务 | 安装时 |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Android 14+ 服务类型 | 安装时 |
| `WAKE_LOCK` | 防止CPU休眠 | 安装时 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 忽略电池优化 | 引导用户 |

## 性能指标

- **目标延迟**: < 20ms
- **采样率**: 48000Hz
- **缓冲区**: 最小化 (2 bursts)
- **音频格式**: I16 (16位整数)

## 注意事项

1. **音量安全**: 默认最大音量限制为70% (-3dB)
2. **耳机检测**: 插入耳机时自动暂停，防止听力损伤
3. **电池优化**: 建议将应用加入电池优化白名单
4. **后台限制**: Android 12+ 对后台服务有限制，已适配

## 许可证

MIT License

## 致谢

- [Oboe](https://github.com/google/oboe) - Google低延迟音频库
- Android Open Source Project
