# 本地接入与第一个媒体任务

本文面向在发布前（或需要改动库代码时）从源码使用编辑 API 的开发者：把库接进一个 App，并产出第一个可播放的片段。API 的行为、输入输出限制与错误模型以 [Android 编辑 API 设计](ANDROID_API.zh-CN.md) 为准，这里只给接入步骤和可运行的示例。发布后通过 JitPack 接入的方式见 README。

## 1. 环境

| 项目 | 要求 |
| --- | --- |
| JDK | 17（本仓库的 Gradle daemon 固定为 JetBrains JDK 17，见 `gradle/gradle-daemon-jvm.properties`） |
| Android SDK | compileSdk 37（构建本库需要）；NDK `28.2.13676358` 与 CMake 3.22.1 由 AGP 按需自动安装 |
| 你的 App | minSdk ≥ 26；compileSdk ≥ 26；Kotlin 2.1 及以上（本库以 Kotlin 2.2 元数据发布；已验证 AGP 9 内置的 Kotlin 2.2.10）。Java 调用暂不支持（路线图 12.2） |

## 2. 接入方式

### 2.1 在本仓库内新增模块（`project(":ffmpegkit")`）

本库模块读取根工程的 `compileSdk`、`minSdk`、`versionName` 与版本目录，只能在本仓库内以模块依赖使用。demo 应用 `app/` 就是这样接入的：

```kotlin
// settings.gradle.kts
include(":app")
include(":ffmpegkit")

// app/build.gradle.kts
dependencies {
    implementation(project(":ffmpegkit"))
    implementation(libs.kotlinx.coroutines.android) // 调用挂起函数用
}
```

无需 `System.loadLibrary()`：原生库在首次调用时由本库自己加载。

### 2.2 外部工程（Maven 本地仓库）

不要把 `ffmpegkit/` 目录复制或 `include` 进别的工程（它依赖本仓库的根工程配置）。先在本仓库发布到本机 Maven 仓库：

```powershell
.\gradlew.bat :ffmpegkit:publishToMavenLocal   # macOS / Linux: ./gradlew
```

坐标为 `com.github.zqcformix:ffmpegkit:<versionName>`，`versionName` 取根目录 `build.gradle.kts`。外部工程：

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.zqcformix:ffmpegkit:<versionName>")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
```

本库没有资源与 androidx 依赖，只传递 `kotlin-stdlib` 与 `kotlinx-coroutines-android`。混淆规则随 AAR 提供（`consumer-rules.pro`），开启 R8 的 release 构建无需额外配置。

## 3. 第一个媒体任务

```kotlin
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import io.github.nova.ffmpegkit.SplitResult
import io.github.nova.ffmpegkit.StreamInfo
import io.github.nova.ffmpegkit.probe
import io.github.nova.ffmpegkit.splitTo
import io.github.nova.ffmpegkit.trimTo
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** Trims [input] to [2 s, 8 s) into the app's own Movies directory and returns the clip. */
suspend fun makeFirstClip(context: Context, input: Uri): File {
    val resolver = context.contentResolver
    // Optional: look at the input first. Values the file does not state are null.
    val info = input.probe(resolver)
    require(info.streams.any { it is StreamInfo.Video }) { "not a video: $info" }

    val directory = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
    val output = File(directory, "clip-${System.currentTimeMillis()}.mp4")
    val result = input.trimTo(resolver, output, start = 2.seconds, endExclusive = 8.seconds)
    Log.i("FirstClip", "requested ${result.requestedRange}, wrote ${result.actualRange} to $output")
    return output
}

/** Splits [input] into segments of at least 3 s, each starting at a keyframe. */
suspend fun makeSegments(context: Context, input: File): SplitResult {
    val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "segments-${System.currentTimeMillis()}")
    directory.mkdirs() // the output directory must exist
    return input.splitTo(directory, segmentDuration = 3.seconds, fileNamePrefix = "part", containerExtension = "mp4")
}
```

在 `ViewModel` 中启动、取消，并处理失败：

```kotlin
class ClipViewModel(application: Application) : AndroidViewModel(application) {
    val status = MutableStateFlow("")
    private var job: Job? = null

    fun trim(input: Uri) {
        job = viewModelScope.launch {
            status.value = try {
                "saved ${makeFirstClip(getApplication(), input)}"
            } catch (e: FFmpegException) {
                "failed: ${e.reason} ${e.message}" // 不会是取消：取消以 CancellationException 结束
            }
        }
    }

    fun cancel() {
        job?.cancel() // 原生任务停止、临时文件清理后协程才结束，不会留下输出
    }
}
```

输入可以来自照片选择器或系统文件选择器：

```kotlin
val pick = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    uri?.let(viewModel::trim)
}
pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
```

要点（完整规则见 ANDROID_API 第 3、4、7 节）：

- 挂起函数自己切到 IO 线程，可以在主线程协程里直接调用。
- 输出文件必须不存在，父目录必须存在；本库从不覆盖文件，冲突时报 `OUTPUT_EXISTS`。
- 输出扩展名决定容器，编码必须能放进该容器（例如 H.264/AAC 放进 `mp4`）；不转码。用 `probe` 可以先看编码。
- 裁剪是关键帧对齐的：`actualRange` 通常早于请求的起点。
- 写到相册：用 `MediaOutput.of(resolver, uri)` 写入 `IS_PENDING` 的 MediaStore 条目，成功后再清除 pending；demo 应用的 `app/.../editor/Gallery.kt` 是完整示例（含分割与失败时删除条目）。

## 4. 检查产出的片段

- 在设备上：demo 应用的编辑页把结果写进相册，可以直接点 *Play* 播放。
- 在电脑上：`getExternalFilesDir` 在 Android 11+ 不能被 `adb pull` 读取；调试时可把输出写到相册（见上），或复制到 `filesDir` 后用 `adb exec-out run-as <包名> cat files/<文件> > clip.mp4` 取回。然后：

  ```powershell
  pwsh scripts/verify-outputs.ps1 -Mode trim -InputFile 原视频.mp4 -Outputs clip.mp4
  ```

  脚本用桌面 FFmpeg 严格解码、检查首包为关键帧，并确认片段是原视频的一段连续数据。

## 5. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| 编译报 `Module was compiled with an incompatible version of Kotlin` | 你的 Kotlin 低于 2.1。升级 Kotlin，或使用 AGP 9 内置的 Kotlin |
| `FFmpegException(OUTPUT_EXISTS)` | 输出已存在；换文件名或先删除。分割时目录中已有同前缀的 `前缀-0001.扩展名` 等文件也会报此错 |
| `FFmpegException(UNSUPPORTED_MEDIA)` | 输入不可 seek（管道、部分云盘流），或编码放不进输出容器 |
| `FFmpegException(RANGE_OUTSIDE_MEDIA)` | 起点达到或超过媒体时长 |
| `IllegalArgumentException` | 参数非法（范围倒置、非整数微秒、空前缀等），在执行前抛出 |

## 验证记录

2026-10-17 按本文 2.2 节新建空白消费者工程（AGP 9.4.1、内置 Kotlin 2.2.10、compileSdk 36、minSdk 26，release 开启 R8），代码即第 3 节示例：debug 与 release 均编译通过，release 包在 x86_64 模拟器（API 35）上裁剪出 [2 s, 8.133 s) 的片段并分割出 4 段；取回后经 `verify-outputs.ps1` 核对，片段严格解码无错误、为原视频第 60–241 个视频包的连续一段，4 段拼接与原视频逐包相同。验证过程中发现并修复了两处会让这类工程无法编译的问题：库以 Kotlin 2.4 元数据发布（现固定为 2.2），以及 AAR 要求消费者 compileSdk ≥ 37（去掉了未使用的 `androidx.core` 依赖，`minCompileSdk` 改为 26）。
