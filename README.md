# FFmpegKitx

[![Release](https://img.shields.io/github/v/release/zqcformix/FFmpegKitx)](https://github.com/zqcformix/FFmpegKitx/releases)
[![JitPack](https://jitpack.io/v/zqcformix/FFmpegKitx.svg)](https://jitpack.io/#zqcformix/FFmpegKitx)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Android 上的 FFmpeg 8.0.1 预编译库，附带 Kotlin API：不转码的快速裁剪与分割、媒体信息探测，以及 FFmpeg 版本与编译配置查询。

## 能力一览

| 项目 | 内容 |
|------|------|
| 平台 | Android 8.0+（API 26） |
| ABI | arm64-v8a、armeabi-v7a、x86_64（x86_64 主要用于模拟器） |
| FFmpeg | 8.0.1，LGPL 构建（未启用 `--enable-gpl`、`--enable-version3`、`--enable-nonfree`） |
| 打包的库 | libavcodec、libavformat、libavfilter、libavutil、libswresample、libswscale（版本见[下文](#ffmpeg-库版本)） |
| 编译启用的特性 | libass、libfreetype、libfribidi、mediacodec、jni |
| 编辑 API（`v1.1.0` 起） | 快速裁剪（可选 MP4 edit list 起点）、按时长或按时间段分割、媒体信息探测；输入可以是文件、`content://` Uri 或文件描述符，输出可以是文件或 Uri（见 [编辑 API](#编辑-api)） |
| 信息 API | FFmpeg 版本、编译配置、libass 是否可用（见 [信息 API](#信息-api)） |

### 当前不提供

- **转码、合并、截图、精确到帧的裁剪**：裁剪与分割都是流复制（不重新编码），起点按关键帧对齐。
- **`ffmpeg` / `ffprobe` 命令**：编译时使用了 `--disable-programs`，AAR 中只有共享库，没有可执行的命令行程序，也不能传入命令字符串执行。
- **进度回调与 Java 调用入口**：编辑 API 目前只有 Kotlin 挂起函数。
- **字幕、轨道选择与 metadata 保留**：裁剪与分割复制唯一的视频轨（有多个视频轨的输入会报 `UNSUPPORTED_MEDIA`）和全部音频轨，不复制字幕、封面与数据轨。
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
    implementation("com.github.zqcformix:FFmpegKitx:v1.1.0")
    // 调用挂起函数用
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
```

FFmpeg .so 文件已包含在 AAR 中，无需手动下载。本库不依赖 androidx，混淆规则随 AAR 提供，开启 R8 无需额外配置。

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

## 编辑 API

所有操作都是挂起函数：自己切到 IO 线程，可以在主线程协程中直接调用；取消调用方协程会停止原生任务，清理完临时文件后才以 `CancellationException` 结束，不留下任何输出。原生库在第一次调用时自动加载。

### 裁剪

```kotlin
import io.github.nova.ffmpegkit.trimTo
import kotlin.time.Duration.Companion.seconds

val result = input.trimTo(File(dir, "clip.mp4"), start = 2.5.seconds, endExclusive = 8.seconds)
result.requestedRange // [2.5s, 8s)
result.actualRange    // 实际写入的范围，例如关键帧在 2 s 时为 [2s, 8.133333s)
```

- 不重新编码：视频从请求起点之前（含起点）最近的关键帧开始，所以 `actualRange.start` 可能早于请求；B 帧可能让结尾略长。`endExclusive` 省略时到媒体结尾。
- 输出扩展名决定容器，编码必须能放进该容器（例如 H.264/AAC 放进 `mp4`）。
- **从请求的时间点开始播放**：对 MP4/MOV 输出传入 `mode = TrimMode.EDIT_LIST`。数据仍从关键帧开始，但文件带有 edit list，支持它的播放器（Media3 ExoPlayer、基于 FFmpeg 的播放器、小米相册等）从请求起点开始显示；Android 系统 `MediaPlayer` 不支持 edit list，会从关键帧开始播放。

### 分割

```kotlin
import io.github.nova.ffmpegkit.TimeRange
import io.github.nova.ffmpegkit.splitTo

// 每段至少 30 秒，在关键帧处切分；拼接起来与原文件逐包相同
val parts = input.splitTo(dir, segmentDuration = 30.seconds, fileNamePrefix = "holiday", containerExtension = "mp4")
parts.segments // holiday-0001.mp4、holiday-0002.mp4……及各自的 actualRange

// 按给定的时间段各输出一段（各段独立裁剪，相邻段可能因关键帧回退而重叠）
val ranges = input.splitTo(dir, listOf(TimeRange(0.seconds, 10.seconds), TimeRange(20.seconds, 25.seconds)), "pick", "mp4")
```

所有分段先写入目标目录下的隐藏临时目录，全部成功后才发布；失败或取消时不留下任何分段。

### 探测

```kotlin
import io.github.nova.ffmpegkit.StreamInfo
import io.github.nova.ffmpegkit.probe

val info = uri.probe(contentResolver)
info.duration    // 未知时为 null，不会是 0
info.streams.forEach {
    when (it) {
        is StreamInfo.Video -> println("${it.codecName} ${it.width}x${it.height} 旋转 ${it.rotation}°")
        is StreamInfo.Audio -> println("${it.codecName} ${it.sampleRate} Hz ${it.channelLayout}")
        is StreamInfo.Other -> println(it.mediaType)
    }
}
```

只读取容器与流的头信息，不解码。`rotation` 是显示时需要顺时针旋转的角度，与 `MediaFormat.KEY_ROTATION` 一致。

### 输入与输出

```kotlin
import io.github.nova.ffmpegkit.FFmpeg
import io.github.nova.ffmpegkit.MediaInput
import io.github.nova.ffmpegkit.MediaOutput
import io.github.nova.ffmpegkit.TimeRange
import io.github.nova.ffmpegkit.TrimMode
import io.github.nova.ffmpegkit.TrimRequest

// 照片选择器 / 系统文件选择器给出的 content:// Uri，写入调用方创建的 MediaStore 条目
FFmpeg.trim(
    TrimRequest(
        input = MediaInput.of(contentResolver, pickedUri),
        output = MediaOutput.of(contentResolver, mediaStoreUri, "mp4"),
        range = TimeRange(5.seconds, 15.seconds),
        mode = TrimMode.EDIT_LIST,
    ),
)
```

- **输入**：`File`、`content://` 或 `file://` 的 `Uri`、`ParcelFileDescriptor`。`content://` 通过文件描述符读取，必须可以 seek（管道和部分云盘流不行）。
- **文件输出**：从不覆盖已有文件，目标已存在时报 `OUTPUT_EXISTS`；先写同目录的临时文件，函数返回后才出现在目标位置。
- **Uri 输出**（如 `IS_PENDING` 的 MediaStore 条目）：先完整写入缓存目录中的临时文件，成功后再复制进 Uri，失败时 Uri 原内容不变。

### 错误

| 情况 | 异常 |
| --- | --- |
| 参数非法（范围倒置、非整数微秒、空前缀等） | `IllegalArgumentException`，在执行前抛出 |
| 协程取消 | `CancellationException`，不包装 |
| 其他所有失败 | `FFmpegException`，`reason` 为 `INPUT_NOT_FOUND`、`ACCESS_DENIED`、`OUTPUT_EXISTS`、`INSUFFICIENT_SPACE`、`RANGE_OUTSIDE_MEDIA`、`UNSUPPORTED_MEDIA`、`INVALID_MEDIA`、`IO`、`NATIVE` 之一 |

`Reason` 以后只会新增，`when` 中请保留 `else` 分支。

## 信息 API

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
| Compile SDK（你的 App） | 26 及以上 |
| Kotlin（你的 App） | 2.1 及以上；AGP 9 内置的 Kotlin 可直接使用 |
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
