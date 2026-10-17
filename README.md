# FFmpegKitx

[![Release](https://img.shields.io/github/v/release/zqcformix/FFmpegKitx)](https://github.com/zqcformix/FFmpegKitx/releases)
[![JitPack](https://jitpack.io/v/zqcformix/FFmpegKitx.svg)](https://jitpack.io/#zqcformix/FFmpegKitx)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Android 上的 FFmpeg 8.0.1 预编译库，附带查询 FFmpeg 版本与编译配置的 Kotlin API。

## 能力一览

| 项目 | 内容 |
|------|------|
| 平台 | Android 8.0+（API 26） |
| ABI | arm64-v8a、armeabi-v7a、x86_64（x86_64 主要用于模拟器） |
| FFmpeg | 8.0.1，LGPL 构建（未启用 `--enable-gpl`、`--enable-version3`、`--enable-nonfree`） |
| 打包的库 | libavcodec、libavformat、libavfilter、libavutil、libswresample、libswscale（版本见[下文](#ffmpeg-库版本)） |
| 编译启用的特性 | libass、libfreetype、libfribidi、mediacodec、jni |
| Kotlin API | FFmpeg 版本、编译配置、libass 是否可用（见 [API](#api)） |

### 当前不提供

- **媒体处理 API**：没有剪辑、分割、转码、合并、截图等接口。
- **`ffmpeg` / `ffprobe` 命令**：编译时使用了 `--disable-programs`，AAR 中只有共享库，没有可执行的命令行程序，也不能传入命令字符串执行。
- **字幕渲染与 MediaCodec 硬件编解码的 Kotlin 接口**：libass 和 mediacodec 已编译进 FFmpeg，但本库没有封装调用它们的 API。`isLibassAvailable()` 只表示编译配置中启用了 libass。
- **在你自己的 native 代码中链接 FFmpeg**：AAR 不包含 FFmpeg 头文件，也没有 Prefab 元数据。
- **libavdevice**：编译时使用了 `--disable-avdevice`。
- **GPL 或 nonfree 组件**（如 x264、x265、fdk-aac）：为保持 LGPL 许可，不会打包。
- **Android 以外的平台**。

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
    implementation("com.github.zqcformix:FFmpegKitx:v1.0.3")
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

> **16 KB 页大小**：从 `v1.0.3` 起，AAR 中所有 64 位 `.so` 都满足 Google Play 对面向 Android 15+ 应用的 16 KB 页大小要求。`v1.0.2` 及更早版本的 JNI 库 `libffmpegkit.so` 只有 4 KB 对齐，请升级。

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

三个 ABI 的库版本和编译配置相同（各 ABI 的 `--arch`、`--cpu` 等目标参数不同）。可以在运行时用 `FFmpegKit.printInfo()` 核对。

## License

本项目的封装代码（Kotlin、JNI、构建脚本）使用 [MIT](LICENSE) 许可。

AAR 内的预编译 FFmpeg 库按 LGPL-2.1-or-later 分发，并静态链接了 libass（ISC）、FreeType（FTL）、FriBidi（LGPL-2.1-or-later）和 HarfBuzz（Old MIT）。这些组件不适用 MIT；发布使用本库的应用时需同时遵守它们的许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
