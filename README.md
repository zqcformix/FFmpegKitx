# FFmpegKitx

[![Release](https://img.shields.io/github/v/release/zqcformix/FFmpegKitx)](https://github.com/zqcformix/FFmpegKitx/releases)
[![JitPack](https://jitpack.io/v/zqcformix/FFmpegKitx.svg)](https://jitpack.io/#zqcformix/FFmpegKitx)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Android FFmpeg 8.0.1 封装库，提供 Kotlin API，支持 ASS/SSA 字幕渲染（libass）和 MediaCodec 硬件加速。

## 依赖使用

**1. 添加 JitPack 仓库**

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

**2. 添加依赖**

`build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.zqcformix:FFmpegKitx:v1.0.2")
}
```

FFmpeg .so 文件已包含在 AAR 中，无需手动下载。

**3. 按需裁剪 ABI（可选）**

FFmpeg `.so` 未压缩体积约为：arm64-v8a 22 MB、armeabi-v7a 20 MB、x86_64 27 MB。用 App Bundle 发布时 Google Play 会按设备分发；直接分发 APK 时，可以去掉不需要的 ABI（例如只用于模拟器的 x86_64）：

```kotlin
android {
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
}
```

> **16 KB 页大小**：`v1.0.2` 中的 JNI 库 `libffmpegkit.so` 只有 4 KB 对齐，不满足 Google Play 对面向 Android 15+ 应用的 16 KB 要求（FFmpeg 的 `.so` 已达标）。修复已在源码中完成，将随下一个版本发布。

## API

```kotlin
import io.github.nova.ffmpegkit.FFmpegKit

FFmpegKit.getVersion()              // "8.0.1"
FFmpegKit.getBuildConfiguration()   // FFmpeg 编译配置参数
FFmpegKit.isLibassAvailable()       // true / false
FFmpegKit.checkLibassAvailability() // 详细信息字符串
FFmpegKit.printInfo()               // 输出所有信息到 Logcat（tag: FFmpegKit）
```

## 环境要求

| 项目 | 版本 |
|------|------|
| Min SDK | 26 (Android 8.0) |
| Target SDK | 37 |
| JDK | 17 |

## FFmpeg 库版本

| 库 | 版本 | 说明 |
|----|------|------|
| libavcodec | 62.11.100 | 编解码 |
| libavformat | 62.3.100 | 封装格式 |
| libavfilter | 11.4.100 | 滤镜（字幕、缩放等） |
| libavutil | 60.8.100 | 工具函数 |
| libswresample | 6.1.100 | 音频重采样 |
| libswscale | 9.1.100 | 图像缩放/格式转换 |

**启用特性**: libass, libfreetype, libfribidi, mediacodec, jni

**支持架构**: arm64-v8a, armeabi-v7a, x86_64

## License

MIT
