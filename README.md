# FFmpegKit

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
    implementation("com.github.zqcformix:FFmpegKitx:v1.0.1")
}
```

FFmpeg .so 文件已包含在 AAR 中，无需手动下载。

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
| Target SDK | 36 |
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

**支持架构**: arm64-v8a, armeabi-v7a

## License

MIT
