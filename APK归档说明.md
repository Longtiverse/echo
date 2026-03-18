# Echo APK 自动归档系统

## 📁 文件夹结构

```
app/build/outputs/apk/
├── debug/
│   └── app-debug.apk              ← 当前最新构建
├── release/
│   └── app-release.apk            ← Release构建 (需要签名)
├── latest/                        ← ⭐ 最新版本文件夹 (只保留最新)
│   ├── Echo_Debug_latest.apk      ← 最新Debug版本
│   └── Echo_Release_latest.apk    ← 最新Release版本
└── archive/                       ← 📦 归档文件夹 (保留历史)
    ├── Echo_Debug_20260318_012027.apk
    ├── Echo_Debug_20260318_012217.apk
    └── ...                        ← 每次构建自动生成
```

---

## ⭐ Latest 文件夹说明

**用途**: 存放最新版本的APK，方便快速获取

**特点**:
- ✅ 只保留**最新版本**（自动替换旧文件）
- ✅ 文件名固定格式: `Echo_{BuildType}_latest.apk`
- ✅ 同时包含 Debug 和 Release 最新版本
- ✅ 每次构建自动更新

**最新版本位置**:
```
app/build/outputs/apk/latest/
├── Echo_Debug_latest.apk    ← 最新Debug版本
└── Echo_Release_latest.apk  ← 最新Release版本
```

---

## 🎯 自动归档配置

### 文件名格式

```
Echo_{BuildType}_{YYYYMMDD}_{HHMMSS}.apk
```

**示例**:
- `Echo_Debug_20260318_012027.apk` - Debug版本，2026年3月18日 01:20:27
- `Echo_Release_20260318_013000.apk` - Release版本，2026年3月18日 01:30:00

---

## 🚀 使用方法

### 构建并自动归档

```bash
# Debug版本 (带自动归档)
./gradlew :app:assembleDebug

# Release版本 (带自动归档)
./gradlew :app:assembleRelease
```

### 仅归档现有APK

```bash
# 执行归档任务
./gradlew :app:archiveApk_debug
./gradlew :app:archiveApk_release
```

### 查看最新版本

```bash
# 查看最新版本APK (始终是最新的)
ls -la app/build/outputs/apk/latest/
```

### 查看归档历史

```bash
# 列出所有归档的APK
ls -la app/build/outputs/apk/archive/
```

---

## ⚙️ 配置说明

归档配置位于 `app/build.gradle`:

```gradle
android.applicationVariants.all { variant ->
    // 自动归档任务
    def archiveTask = task("archiveApk_${variant.name}", type: Copy) {
        def timestamp = new Date().format("yyyyMMdd_HHmmss")
        def archiveDir = file("${project.buildDir}/outputs/apk/archive")
        
        from(variant.outputs[0].outputFile)
        into(archiveDir)
        rename { "Echo_${variant.buildType.name.capitalize()}_${timestamp}.apk" }
    }
    
    // 在assemble后自动执行
    variant.assembleProvider.get().finalizedBy(archiveTask)
}
```

---

## 📋 当前归档列表

查看所有归档的APK:

```bash
ls -lh app/build/outputs/apk/archive/
```

**当前归档**:
- Echo_Debug_20260318_012027.apk (11M)
- Echo_Debug_20260318_012217.apk (11M)

---

## 🧹 清理归档

### 保留最近N个归档

```bash
# 保留最近5个归档，删除旧的
cd app/build/outputs/apk/archive/
ls -t Echo_*.apk | tail -n +6 | xargs rm -f
```

### 清空所有归档

```bash
rm -f app/build/outputs/apk/archive/*.apk
```

---

## ⚠️ 注意事项

1. **自动归档**: 每次运行 `./gradlew :app:assembleDebug` 或 `assembleRelease` 都会自动创建归档
2. **存储空间**: 定期检查归档文件夹大小，避免占用过多磁盘空间
3. **命名规范**: 不要手动重命名归档文件，保持时间戳格式以便排序
4. **Release版本**: Release APK需要签名配置才能正确构建

---

## 🔧 手动归档脚本

如果没有Gradle环境，可以使用手动脚本:

### Windows (PowerShell)
```powershell
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$source = "app/build/outputs/apk/debug/app-debug.apk"
$dest = "app/build/outputs/apk/archive/Echo_Debug_${timestamp}.apk"
Copy-Item $source $dest
Write-Host "Archived to: $dest"
```

### Linux/Mac
```bash
timestamp=$(date +"%Y%m%d_%H%M%S")
cp app/build/outputs/apk/debug/app-debug.apk \
   app/build/outputs/apk/archive/Echo_Debug_${timestamp}.apk
echo "Archived to: app/build/outputs/apk/archive/Echo_Debug_${timestamp}.apk"
```

---

## 📱 获取最新APK

### 快速路径

**最新Debug版本**:
```
app/build/outputs/apk/latest/Echo_Debug_latest.apk
```

**最新Release版本**:
```
app/build/outputs/apk/latest/Echo_Release_latest.apk
```

### 完整路径示例

```
D:\Project\Echo\app\build\outputs\apk\latest\
├── Echo_Debug_latest.apk      (11 MB)
└── Echo_Release_latest.apk    (8.0 MB)
```

### 推荐使用

- **开发测试**: 使用 `Echo_Debug_latest.apk` (含调试信息)
- **正式发布**: 使用 `Echo_Release_latest.apk` (体积更小，性能更好)

---

**归档系统已启用！每次构建都会自动产生归档文件并更新latest文件夹。** ✅