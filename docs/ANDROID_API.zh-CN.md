# Android 编辑 API 设计（M1 草案）

> **状态：设计草案，尚未实现。** 下面的代码描述 M1 计划提供的接口，当前源码中不存在，无法编译。已发布的 `v1.0.3` 只包含 `FFmpegKit` 信息查询接口。实现进度以[路线图](ROADMAP.zh-CN.md)为准。

本文是 M1 范围、行为与限制的唯一出处；其他文档只引用，不复述。

## 1. 入口与接入

- 新增 `FFmpeg` 门面承载编辑操作；现有 `FFmpegKit` 信息查询接口保持不变。原生库在首次执行编辑任务时才加载。
- 开发期接入：`implementation(project(":ffmpegkit"))`。发布后的坐标见[发布文档](RELEASES.zh-CN.md)。
- 最低 Android API 26。M1 只提供 Kotlin 挂起函数；Java 入口见路线图 12.2。

```kotlin
import android.content.Context
import android.net.Uri
import io.github.nova.ffmpegkit.FFmpeg
import io.github.nova.ffmpegkit.FFmpegException
import io.github.nova.ffmpegkit.MediaInput
import io.github.nova.ffmpegkit.MediaOutput
import io.github.nova.ffmpegkit.TimeRange
import io.github.nova.ffmpegkit.TrimRequest
import io.github.nova.ffmpegkit.splitTo
import io.github.nova.ffmpegkit.trimTo
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
```

## 2. 时间范围

```kotlin
TimeRange(2.seconds, 8.seconds)  // [2s, 8s)
TimeRange.from(10.seconds)       // 从 10s 到媒体结尾
```

- 区间为 `[start, endExclusive)`：有限、非负、严格递增，且必须是整数微秒（不暗中截断纳秒）。违反时在执行前抛 `IllegalArgumentException`。
- 省略终点表示到媒体结尾。终点超过媒体时长时夹取到结尾，结果中的 `actualRange` 反映实际范围，不需要调用方先 probe。
- 起点达到或超过媒体时长时失败（`Reason.RANGE_OUTSIDE_MEDIA`），不创建空文件。
- 时长未知的输入照常执行，只是进度变为不定（见第 5 节）。

## 3. 快速时间裁剪

```kotlin
suspend fun trimExample(input: File, directory: File) {
    val result = input.trimTo(File(directory, "clip.mp4"), start = 2.seconds, endExclusive = 8.seconds)
    println(result.requestedRange) // 调用方请求的范围
    println(result.actualRange)    // 实际写入的首末包时间
}

// SAF / MediaStore 来源
suspend fun trimFromUri(context: Context, uri: Uri, output: File) = FFmpeg.trim(
    TrimRequest(
        input = MediaInput.of(context.contentResolver, uri),
        output = MediaOutput.of(output),
        range = TimeRange.from(5.seconds),
    ),
)
```

- 快速模式复制压缩包，不重新编码。起点回退到前一个关键帧，所以 `actualRange.start` 可能早于请求；B 帧解码顺序可能让尾部略长。音视频保持共同的时间偏移。
- MP4/MOV 计划提供可选的 edit list 模式（路线图 2.4，评估中）：保留前导关键帧，让支持 edit list 的播放器从请求时间点开始显示。
- 输出扩展名决定容器；源编码必须与该容器兼容。改扩展名不等于转码。
- 需要帧精确边界时使用精确裁剪（路线图 10.1）。

## 4. 分割

```kotlin
suspend fun splitExample(input: File, directory: File) = input.splitTo(
    outputDirectory = directory,
    segmentDuration = 30.seconds,
    fileNamePrefix = "holiday",
    containerExtension = "mp4",
)
```

- 输出 `holiday-0001.mp4`、`holiday-0002.mp4`……，`SplitResult.segments` 按时间顺序排列，每段带 `actualRange`。
- 默认是**关键帧对齐的单遍分割**：顺序读取一次输入，每越过一个时长边界，就在下一个视频关键帧处开新段。
  - 片段互不重叠，按顺序拼接可还原原始包序列。
  - 除末段外每段 ≥ `segmentDuration`，实际长度取决于关键帧间隔；纯音频输入按包边界切分。
  - 一次最多 10,000 段；整除时不产生空段。
- 按显式时间范围分割（路线图 2.3）使用 `SplitRequest.byRanges(...)`。各段独立 seek，相邻段可能因关键帧回退而重叠。
- 开始前检查全部目标路径。中途失败或取消时，回滚本次已生成的段；不删除既有文件或已被外部替换的文件。无法确认文件身份或清理失败时保守保留，并通过 suppressed 异常报告。

## 5. 进度与取消

```kotlin
fun startTrim(
    scope: CoroutineScope,
    input: File,
    output: File,
    progress: MutableStateFlow<Double?>,
) = scope.launch {
    val result = input.trimTo(output, 2.seconds, 8.seconds) {
        progress.value = it.fraction
    }
    // 成功返回后才能读取 result.output。
}
// 调用方保存返回的 Job，需要取消时调用 job.cancel()。
```

- `scope` 应来自调用方的生命周期，例如 `viewModelScope`。回调在 IO 工作线程执行：更新 `StateFlow` 是安全的，但不能直接操作 View。
- `MediaProgress` 包含 `fraction`（0–1，单调不减；总时长未知时为 `null`）和从 1 开始的 `segmentIndex`。关键帧对齐分割的总段数要读完才能确定，因此不提供总段数。
- `1.0` 留给输出交付阶段。成功与否以挂起函数正常返回或抛出异常为准，不以进度为准。
- 取消覆盖原生探测、包读写检查点和交付复制。中断依赖底层 I/O 检查点，不保证任意阻塞系统调用立即结束。
- 进度回调抛出的异常会终止任务并原样传播。

## 6. 错误模型

| 情况 | 异常 |
| --- | --- |
| 参数非法（范围、单位、空前缀等） | `IllegalArgumentException`，执行前抛出 |
| 协程取消 | `CancellationException`，不包装 |
| 其他所有失败 | `FFmpegException`，带 `reason` |

```kotlin
class FFmpegException(
    val reason: Reason,
    message: String,
    val nativeErrorCode: Int? = null, // FFmpeg AVERROR，便于诊断
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Reason {
        INPUT_NOT_FOUND, ACCESS_DENIED, OUTPUT_EXISTS, INSUFFICIENT_SPACE,
        RANGE_OUTSIDE_MEDIA, UNSUPPORTED_MEDIA, INVALID_MEDIA, IO, NATIVE,
    }
}
```

```kotlin
try {
    input.trimTo(output, 2.seconds, 8.seconds)
} catch (e: FFmpegException) {
    when (e.reason) {
        FFmpegException.Reason.OUTPUT_EXISTS -> askBeforeOverwrite(output)
        else -> showError(e)
    }
}
```

- 取消不是 `FFmpegException`，按类型捕获不会吞掉取消。
- `cause` 和 `suppressed` 携带清理失败等诊断信息。
- 错误模型在首次发布前冻结。之后只新增 `Reason` 值，调用方的 `when` 应保留 `else` 分支。

## 7. M1 范围与限制

- **输入**：可读的本地常规文件，或可通过 `ContentResolver` 打开的 `Uri`。M1 要求输入可 seek，不可 seek 的来源（管道、部分云盘流）报 `UNSUPPORTED_MEDIA`。
- **输出**：本地文件（父目录已存在、目标未被占用），或以 `"rw"` 打开的可 seek `Uri`。`Uri` 输出无法使用同目录临时文件，其交付与清理语义随路线图 3.1 确定。
- **不经过 shell**：不接收命令字符串，路径不做 shell 拼接。
- **轨道**：最多一个视频轨及音频轨。多个视频轨、所选范围内某音视频轨完全没有包会失败。不复制字幕、封面附件和数据轨。
- **容器**：只输出单文件容器，不支持 HLS/DASH/图片序列。容器与编码不兼容时报错，不自动转码。
- **输出交付**：先写同目录临时文件，trailer 写入和关闭成功后才交付。优先用硬链接排他发布；不支持硬链接的存储用 `CREATE_NEW` 复制，复制期间路径可能已出现但内容不完整，调用方必须等函数返回再读取。
- **并发**：多个任务可以处理不同文件。不要在执行期间替换输入、输出或父目录。同一输出路径的竞争会失败，而不是覆盖。回滚只处理本次创建且身份仍匹配的文件，不是跨进程事务。
- **不在 M1**：多轨选择、字幕与 metadata 保留、网络输入、精确裁剪、画面 crop、Java 入口，见路线图。
