# APK编译指南

由于当前环境缺少Android开发工具，提供以下**3种编译方案**：

---

## 方案一：GitHub Actions自动编译（推荐）⭐

无需本地环境，推送到GitHub即可自动编译APK。

### 步骤：

1. **创建GitHub仓库**
   - 访问 https://github.com/new
   - 创建名为 `Echo` 的仓库
   - 选择 **Public**（免费使用Actions）

2. **推送代码到GitHub**
   ```bash
   cd Echo
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/你的用户名/Echo.git
   git push -u origin main
   ```

3. **触发自动编译**
   - 访问仓库页面
   - 点击 **Actions** 标签
   - 工作流会自动开始编译
   - 等待约5-10分钟

4. **下载APK**
   - 编译完成后，进入最新工作流运行
   - 在 **Artifacts** 部分下载 `Echo-Debug`
   - 解压下载的文件即可获得 `app-debug.apk`

### 优势：
- ✅ 无需本地安装Android Studio
- ✅ 每次推送代码自动编译
- ✅ 支持Release签名版本
- ✅ 完全免费

---

## 方案二：本地Android Studio编译

### 步骤：

1. **安装Android Studio**
   - 下载：https://developer.android.com/studio
   - 安装时选择 **Standard** 配置

2. **打开项目**
   ```bash
   # 启动Android Studio
   # File → Open → 选择Echo文件夹
   ```

3. **等待同步**
   - 首次打开会自动下载Gradle和依赖
   - 可能需要3-5分钟

4. **构建APK**
   ```bash
   # 方式1：使用菜单
   Build → Build Bundle(s) / APK(s) → Build APK(s)
   
   # 方式2：使用命令行（在Android Studio Terminal中）
   ./gradlew assembleDebug
   ```

5. **找到APK**
   - 位置：`app/build/outputs/apk/debug/app-debug.apk`
   - 点击右下角弹出通知可直达文件夹

---

## 方案三：命令行编译（需要完整环境）

### 环境要求：
- JDK 17
- Android SDK 34
- NDK 25.1.8937393
- Gradle 8.2

### 安装步骤（Windows）：

1. **安装JDK 17**
   ```bash
   # 下载：https://www.oracle.com/java/technologies/downloads/#java17
   # 配置环境变量 JAVA_HOME
   ```

2. **安装Android SDK**
   ```bash
   # 下载Android Studio并安装SDK
   # 或使用命令行工具：https://developer.android.com/studio#command-tools
   ```

3. **设置环境变量**
   ```bash
   # Windows PowerShell
   [Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Users\你的用户名\AppData\Local\Android\Sdk", "User")
   [Environment]::SetEnvironmentVariable("PATH", $env:PATH + ";%ANDROID_HOME%\platform-tools;%ANDROID_HOME\cmdline-tools\latest\bin", "User")
   ```

4. **安装NDK和CMake**
   ```bash
   sdkmanager "ndk;25.1.8937393"
   sdkmanager "cmake;3.22.1"
   sdkmanager "build-tools;34.0.0"
   sdkmanager "platforms;android-34"
   ```

5. **编译APK**
   ```bash
   cd Echo
   ./gradlew assembleDebug
   ```

---

## 推荐的快速开始

如果你是**新手**，推荐按以下顺序：

1. **立即尝试** → 使用GitHub Actions（无需安装任何软件）
2. **长期开发** → 安装Android Studio

---

## FAQ

### Q: GitHub Actions编译失败怎么办？
**A**: 查看Actions日志，常见问题：
- NDK版本不匹配 → 修改 `.github/workflows/build.yml` 中的NDK版本
- 权限问题 → 检查仓库是否Public

### Q: APK安装后无法运行？
**A**: 检查：
- 设备Android版本 ≥ 8.0 (API 26)
- 已授予录音权限
- 某些厂商需要额外权限（如自启动）

### Q: 如何编译Release版本？
**A**: 
- GitHub Actions：修改workflow添加签名步骤
- Android Studio：`Build → Generate Signed Bundle/APK`

---

## 编译时间参考

| 方式 | 首次编译 | 增量编译 |
|------|---------|---------|
| GitHub Actions | 5-10分钟 | 3-5分钟 |
| Android Studio | 3-5分钟 | 30秒-1分钟 |
| 命令行 | 5-10分钟 | 1-2分钟 |
