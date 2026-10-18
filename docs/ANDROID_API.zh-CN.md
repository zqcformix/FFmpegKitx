# Android 编辑 API 设计（M1 草案）

> **状态：已随 `v1.1.0` 发布（2026-10-18）。** 已实现：`FFmpeg.trim`/`FFmpeg.split`/`FFmpeg.probe`、`TrimRequest`（含 `TrimMode`）、`SplitRequest.byDuration`/`byRanges`、`MediaInput`/`MediaOutput`、`File`/`Uri` 的 `trimTo`/`splitTo`/`probe` 扩展、`MediaInfo`/`StreamInfo`、`TimeRange`、`FFmpegException`，以及第 3、4、7、8 节的行为和协程取消。**v1.1.0 之后（未发布）**：第 5 节的进度回调 `onProgress`/`MediaProgress`、任务日志 `MediaLog`（路线图 5.2）与内部会话状态机（5.1）。**未实现**：交付复制阶段的取消（5.3）。实现进度以[路线图](ROADMAP.zh-CN.md)为准。

本文是 M1 范围、行为与限制的唯一出处；其他文档只引用，不复述。

## 1. 入口与接入

- 新增 `FFmpeg` 门面承载编辑操作；现有 `FFmpegKit` 信息查询接口保持不变。原生库在首次执行编辑任务时才加载。
- 开发期接入：本仓库内用 `implementation(project(":ffmpegkit"))`，外部工程用 `publishToMavenLocal` 后的本地坐标，步骤与第一个媒体任务见[本地接入](LOCAL_INTEGRATION.zh-CN.md)。发布后的坐标见[发布文档](RELEASES.zh-CN.md)。
- 消费者要求：Kotlin 2.1 及以上（本库固定以 Kotlin 2.2 元数据发布）、compileSdk ≥ 26；本库不依赖 androidx 与资源。
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
- 省略终点表示到媒体结尾，`TimeRange.from(start)` 与 `TimeRange(start, null)` 等价。终点超过媒体时长时夹取到结尾，结果中的 `actualRange` 反映实际范围，不需要调用方先 probe。
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
  - 视频从起点之前（含起点）最近的关键帧开始；早于该关键帧的音频包丢弃。纯音频从包含起点的那个包开始。
  - 每条流在第一个解码时间（dts）达到终点的包处停止，与 `ffmpeg -c copy` 一致。尾部可能出现显示时间超过终点的 P 帧，而它前面的个别 B 帧缺失。
  - `requestedRange` 与 `actualRange` 都以输入的起始时间（`start_time`）为零点。`actualRange` 取写入包的最早显示时间与最晚结束时间；早于零点的包（如 AAC priming）按 0 计。MP4 报告的 `duration` 是各帧时长之和，可能比 `actualRange` 略短。
- **edit list 起点**（`TrimRequest(..., mode = TrimMode.EDIT_LIST)` 或 `trimTo(..., mode = TrimMode.EDIT_LIST)`，路线图 2.4）：只用于 MP4 家族输出（扩展名 `mp4`、`m4v`、`m4a`、`mov`、`3gp`、`3g2`，其他扩展名在执行前抛 `IllegalArgumentException`）。写入的数据与默认的 `TrimMode.KEYFRAME` 完全相同（仍从关键帧开始，`actualRange` 也相同），但输出的时间轴以请求起点为零点：关键帧到起点之间的帧得到负时间戳，MP4 muxer 据此写入 edit list，支持它的播放器解码这些帧但不显示，从请求起点开始播放，时长也按起点计算。做法与 ffmpeg CLI 的 `-ss` 输入 seek 加 `-c copy` 写 MP4 相同。起点恰好是关键帧时两种模式的输出相同。
  - 播放器实测（2.0 s 处关键帧、请求 2.5 s 起，见 `TrimEditListTest`）：Media3 ExoPlayer、FFmpeg（libavformat，mpv、VLC 等基于它）与小米相册（4.2.0.25）遵循 edit list，从 2.5 s 开始显示；Android 系统 `MediaPlayer`（API 34 小米、API 35 模拟器）忽略它，从关键帧 2.0 s 开始显示，时长也多出前导部分，MediaStore 扫描记录的时长同样包含前导部分。在忽略 edit list 的播放器上，效果与默认模式相同。小米相册的缩略图取请求起点之后的第一个关键帧（本例 3.0 s），播放时仍从 2.5 s 开始。
  - 按显式范围分割（`SplitRequest.byRanges`）总是使用默认模式。
- 输出扩展名决定容器；源编码必须与该容器兼容。改扩展名不等于转码。
- 没有索引的输入（MPEG-TS、ADTS 等裸流）只能按字节位置 seek，可能落在所需关键帧（或包含起点的音频包）之后。此时依次在起点前 1、2、4…… s 处重新 seek，最后从输入开头读取，结果与从头读取完全相同，只是更慢；有索引的 MP4、Matroska 不受影响。
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
- 默认是**关键帧对齐的单遍分割**：顺序读取一次输入，读到显示时间 ≥ 本段起点 + `segmentDuration` 的第一个视频关键帧时，从它开始新段。
  - 边界从每段的实际起点算起，而不是按 `segmentDuration` 的整数倍（FFmpeg segment muxer 的做法会切出短于目标的段）。例如每秒一个关键帧、`segmentDuration` 为 2.5 s 时，在 3/6/9 s 处切分。
  - 片段互不重叠：每个被复制的包按读取顺序恰好进入一段，按顺序拼接可还原原始包序列（含首段开头的 AAC priming 等包）。
  - 除末段外每段 ≥ `segmentDuration`，实际长度取决于关键帧间隔；纯音频输入按包边界切分。
  - 每段的 `actualRange` 以输入起始时间为零点，取本段与下一段的切点（首段从 0 开始，末段到最后一个包结束）。切点后读到的、显示时间略早于切点的音频包属于下一段，所以各段的实际音频可能比 `actualRange` 早几十毫秒开始。
  - 编号固定四位，一次最多 9,999 段，保证文件名按文本排序即按时间排序。时长已知时，若 `segmentDuration` 可能产生超过 9,999 段（时长 / `segmentDuration` + 1），在写出任何文件前抛 `IllegalArgumentException`；时长未知时在超出时抛出并回滚。整除时不产生空段。
- 按显式时间范围分割（路线图 2.3）使用 `SplitRequest.byRanges(input, outputDirectory, ranges, fileNamePrefix, containerExtension)`，或 `File`/`Uri` 的 `splitTo(outputDirectory, ranges, …)`。
  - 每个范围输出一段，编号按 `ranges` 的给定顺序（不排序），范围可以乱序、重叠或重复；`SplitResult.segments` 也按此顺序，每段的 `requestedRange` 是对应的请求范围（按时长分割时为 `null`）。
  - 每段与对第 3 节 trim 对该范围的结果完全相同（起点回退到关键帧、尾部按解码时间截止），各段独立 seek。因此**相邻范围的输出通常重叠**：例如每秒一个关键帧时，`[0, 2.5 s)` 与 `[2.5 s, 5 s)` 两段实际覆盖约 `[0, 2.567 s)` 与 `[2 s, 5.133 s)`（测试样本，30 fps、带 B 帧）。这不是精确分割；需要帧精确边界时见路线图 10.1。
  - 至少 1 个、最多 9,999 个范围，否则在执行前抛 `IllegalArgumentException`。任一范围失败（如起点超出媒体时长报 `RANGE_OUTSIDE_MEDIA`，消息中注明是第几个范围）时整体失败，不发布任何段。命名冲突检查、临时目录、发布与回滚规则同下一条。
  - 每个范围都重新打开输入（描述符输入从偏移 0 重读），耗时约为逐个 trim 之和。
- 开始前检查目标目录中是否已有本次命名规则（`前缀-四位数字.扩展名`）的文件，有则报 `OUTPUT_EXISTS` 且不做任何处理。所有段先写入目标目录下的隐藏临时目录，全部成功后再逐段按第 7 节的方式发布，因此函数返回前目标目录里不会出现段文件。发布中途失败或取消时，回滚本次已发布的段；不删除既有文件或已被外部替换的文件。无法确认文件身份或清理失败时保守保留，并通过 suppressed 异常报告。

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

- `FFmpeg.trim`/`FFmpeg.split` 有带 `onProgress: ((MediaProgress) -> Unit)?` 的重载，`trimTo`/`splitTo` 扩展的最后一个参数也是它（可写成尾随 lambda）；probe 不报告进度。v1.1.0 的签名保留，已编译的调用方不受影响。
- `scope` 应来自调用方的生命周期，例如 `viewModelScope`。回调在 IO 工作线程执行，不会与自身并发，函数返回或抛出后不再调用：更新 `StateFlow` 是安全的，但不能直接操作 View。
- 写出开始后（输入已打开、容器头已写入）每 100 ms 读取一次原生计数，有变化才回调；输出交付后再回调一次。很短的任务可能只收到最后这一次。
- `MediaProgress` 的时间都在**目标时间轴**上：trim 从请求起点算起，按时长分割从输入起点算起，按范围分割为各范围首尾相接之和。
  - `processed`：已写出的媒体时长，单调不减，已知 `total` 时不超过它。trim 的起点回退到关键帧时，起点之前的部分不计入。
  - `total`：请求的范围按输入时长夹取后的总长；输入时长未知、且某个范围（或按时长分割的整个输入）通向结尾时为 `null`。只能按码率估算的时长（ADTS 等）与第 8 节一样视为未知。
  - `fraction`：0–1，单调不减，`total` 未知时为 `null`，即不定进度。它覆盖写出与交付两段：已知交付要复制（`Uri` 输出；Android 11 以下的文件输出，见第 7 节"输出交付"）时写出占 0–0.5、复制占 0.5–0.99；否则交付是一次重命名，写出占 0–0.99。全部交付后为 `1.0`。实际交付方式与预期不同时（例如 Android 11+ 的文件系统不支持 `renameat2`），复制期间停在 0.99。
  - `bytesWritten`：写入各输出的字节数，含容器头与尾，不含交付复制。
  - `segmentIndex`：从 1 开始的当前段号，trim 恒为 1。关键帧对齐分割的总段数要读完才能确定，因此不提供总段数。
  - `speed`：`processed` 与开始执行以来耗时之比（如 `12.0` 表示每秒写出 12 s 媒体），无法测量时为 `null`。
- 成功与否以挂起函数正常返回或抛出异常为准，不以进度为准：取消或失败时最后一次进度不是 `1.0`。
- 进度回调抛出的异常会终止任务并原样传播：原生工作停止、临时文件删除，不发布任何输出。交付完成后的那次回调抛出时，输出已经发布并保留。
- 取消覆盖原生探测、包读写检查点和交付复制。中断依赖底层 I/O 检查点，不保证任意阻塞系统调用立即结束。
  - 当前实现：取消标志挂在 FFmpeg 的 `interrupt_callback` 上，并在每个包的读写之间检查；被取消的任务删除临时文件、关闭描述符后，挂起函数才以原样的 `CancellationException` 结束，不会发布任何输出。交付开始（重命名、链接或复制）之后的取消不再中断，已完成的交付保留；复制阶段的取消随路线图 5.3 实现。
- **会话**（路线图 5.1，内部实现，暂不公开）：每次调用对应一个进程内唯一 ID 的会话，状态为排队 → 执行 → 成功 | 失败 | 取消，终态只设置一次，并且在原生资源与临时文件释放之后才设置。终态就是调用的结束方式：正常返回为成功，抛出 `CancellationException` 为取消，抛出其他异常为失败。优先级规则如下：
  - 取消请求在工作结束前到达时，即使中断导致了原生错误，也以取消结束，不报 `FFmpegException`。
  - 失败在取消请求之前已经结束工作时，以失败结束。
  - 进度回调抛出的异常优先于它引发的中断，原样抛出。
  - 排队中（尚未开始执行）被取消的调用不会运行。
  - 公开的会话对象（`Flow`/`StateFlow`）见路线图 12.1。
- **任务日志**（路线图 5.2）：FFmpeg 在一次 trim/split/probe 期间记录的日志由该次调用收集，挂在 `TrimResult.log`、`SplitResult.log` 与 `FFmpegException.log`（`MediaLog`）上；probe 成功时不返回日志，失败时随异常返回。

  ```kotlin
  try {
      input.trimTo(output, 2.seconds, 8.seconds)
  } catch (e: FFmpegException) {
      Log.w(TAG, "trim failed: $e\n${e.log}") // 如 "ERROR [mov,mp4,m4a,3gp,3g2,mj2] moov atom not found"
  }
  ```

  - `MediaLog.entries` 按时间顺序给出每条的 `level`（`ERROR`、`WARNING`、`INFO`、`VERBOSE`、`DEBUG`，FFmpeg 的 panic/fatal 归入 `ERROR`，trace 归入 `DEBUG`）、`component`（记录它的 FFmpeg 组件，如 MP4 解封装器 `mov,mp4,m4a,3gp,3g2,mj2`、`aac`）与 `message`（不含结尾换行，最长 1,024 字节 UTF-8）。
  - 归属：FFmpeg 的日志回调是全局的，本库在每次原生调用期间把执行线程标记给该任务，只收集这个线程在调用期间写的日志。并行任务不会拿到彼此的日志；不属于任何任务的日志（如 FFmpeg 自己的线程）是引擎日志，只写 logcat（标签 `FFmpegKit`，仅警告与错误，不脱敏）。
  - 有界：每个任务只保留最近 `maxEntries` 条，更早的丢弃并计入 `droppedCount`，因此日志占用的内存有上限，不随任务时长增长。
  - 配置：`FFmpeg.logOptions = MediaLog.Options(level, maxEntries, redactPaths)`，对之后开始的调用生效。默认 `WARNING` 及以上、100 条（上限 10,000）、脱敏。`DEBUG` 会产生大量日志（MP4 解封装器每读一个包都记一条），只用于诊断。
  - 脱敏：`redactPaths` 为真时，本次调用交给 FFmpeg 的输入路径与输出路径（临时文件、分割的临时目录）在日志中替换为 `<input>`、`<output>`，例如 `Opening '<input>' for reading`。只替换这些已知路径；`Uri` 与描述符输入没有路径可替换。日志本身不包含最终的输出文件名。

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
- 同一个底层错误按输入侧或输出侧区分：文件不存在在输入侧是 `INPUT_NOT_FOUND`，在输出侧表示父目录不存在，归为 `IO`。`nativeErrorCode` 只用于诊断，不要据此分支。
- `cause` 和 `suppressed` 携带清理失败等诊断信息；`log` 是 FFmpeg 在这次操作中记录的日志（见第 5 节"任务日志"），操作开始前就失败时为空。
- 错误模型在首次发布前冻结。之后只新增 `Reason` 值，调用方的 `when` 应保留 `else` 分支。

## 7. M1 范围与限制

- **输入**：可读的本地常规文件、可通过 `ContentResolver` 打开的 `Uri`，或调用方提供的 `ParcelFileDescriptor`。`content://` 以 `openFileDescriptor(uri, "r")` 打开后由原生自定义 I/O 读取描述符，从不当作路径；`file://` 按本地文件处理。调用方提供的描述符从偏移 0 读取，仍归调用方所有、由调用方关闭。M1 要求输入可 seek，不可 seek 的来源（管道、部分云盘流）报 `UNSUPPORTED_MEDIA`。Provider 找不到条目时报 `INPUT_NOT_FOUND`，拒绝访问时报 `ACCESS_DENIED`。
- **输出**：本地文件（父目录已存在、目标未被占用，从不覆盖，见下条），或调用方创建并拥有的 `Uri`（如 MediaStore 条目、SAF 文档）。`Uri` 输出先完整写入应用缓存目录中的临时文件，成功后再以 `"wt"` 模式一次性复制进 `Uri`，因此不要求可 seek 的输出描述符，写入失败时 `Uri` 原内容不变；`Uri` 的已有内容会被替换。容器由显式给出的扩展名决定，未给出时按 `Uri` 的 MIME 类型推断，推断不出则在执行前抛 `IllegalArgumentException`。复制阶段失败时 `Uri` 中的数据不完整，由调用方删除或重试；写 MediaStore 时建议保持 `IS_PENDING` 直到函数返回。临时文件需要与输出等量的缓存空间。分割的输出在 M1 只支持本地目录。
- **不经过 shell**：不接收命令字符串，路径不做 shell 拼接。
- **轨道**：最多一个视频轨及音频轨。多个视频轨、所选范围内某音视频轨完全没有包会失败。不复制字幕、封面附件和数据轨。
- **容器**：只输出单文件容器，不支持 HLS/DASH/图片序列。容器与编码不兼容时报错，不自动转码。
- **输出交付**：先写同目录的隐藏临时文件（`.名称.随机串.part.扩展名`，保留扩展名以选择容器），trailer 写入和关闭成功后才交付。交付不覆盖任何已有文件，依次尝试：`renameat2(RENAME_NOREPLACE)` 原子发布（libc 自 Android 11 提供）；硬链接（Android 的 SELinux 拒绝应用对自身文件建硬链接，主要用于其他平台）；最后用 `CREATE_NEW` 排他创建后复制。复制期间路径可能已出现但内容不完整，调用方必须等函数返回再读取。复制失败时只删除本次创建且身份（设备号与 inode）仍匹配的文件；无法确认时保留，并以 suppressed 异常说明。
- **并发**：多个任务可以处理不同文件。不要在执行期间替换输入、输出或父目录。同一输出路径的竞争会失败，而不是覆盖。回滚只处理本次创建且身份仍匹配的文件，不是跨进程事务。
- **不在 M1**：多轨选择、字幕与 metadata 保留、网络输入、精确裁剪、画面 crop、Java 入口，见路线图。

## 8. 媒体探测

```kotlin
suspend fun describe(context: Context, uri: Uri) {
    val info = uri.probe(context.contentResolver) // 或 FFmpeg.probe(MediaInput.of(...))、file.probe()
    println(info.duration)                        // null 表示时长未知
    info.streams.forEach { stream ->
        when (stream) {
            is StreamInfo.Video -> println("${stream.width}x${stream.height} 旋转 ${stream.rotation}°")
            is StreamInfo.Audio -> println("${stream.sampleRate} Hz ${stream.channelLayout}")
            is StreamInfo.Other -> println(stream.mediaType)
        }
    }
}
```

- 只读容器与流头（`avformat_find_stream_info`），不解码，不写任何文件。输入、错误与取消同其他操作：输入要求见第 7 节，失败抛 `FFmpegException`（输入缺失 `INPUT_NOT_FOUND`、不是可识别的媒体 `INVALID_MEDIA` 等），取消以原样的 `CancellationException` 结束。
- 报告文件中的**全部**流（包括封面、字幕、数据流和多个视频流），不套用第 7 节对 trim/split 的轨道限制，调用方可据此先判断能否裁剪。
- `MediaInfo`：`formatName`（与 ffprobe 的 `format_name` 相同，如 `mov,mp4,m4a,3gp,3g2,mj2`）、`startTime`（trim/split 的时间以它为零点）、`duration`、`bitRate`（bit/s）、`streams`（按容器顺序）。
- 每条流有 `index`、`codecName`（同 ffprobe 的 `codec_name`）、`duration`、`bitRate`，并按类型细分：
  - `StreamInfo.Video`：编码尺寸 `width`/`height`（未应用旋转）、`frameRate`（平均帧率，同 ffprobe 的 `avg_frame_rate`）、`rotation`、`isAttachedPicture`（封面）。
  - `StreamInfo.Audio`：`sampleRate`、`channels`、`channelLayout`（如 `stereo`、`5.1`）。
  - `StreamInfo.Other`：`mediaType` 为 `subtitle`、`data`、`attachment` 或 `unknown`；字幕语言、metadata 等随路线图 9.2 补充。
- **缺失值为 `null`，不伪造为零**。数值与同版本 ffprobe 一致，但有一处刻意不同：FFmpeg 只能按码率估算的时长（ADTS AAC 等裸流，ffprobe 会提示 "Estimating duration from bitrate"）报为 `null`，因为估算值可能偏差明显（测试样本实际 10 s，估算为 10.1 s）。裸 H.264 等没有时间信息的流，`startTime`、`duration`、`bitRate` 均为 `null`；其 `frameRate` 可能是解封装器的默认值（H.264 裸流为 25），与 ffprobe 相同。
- `rotation` 是显示时需要**顺时针**旋转的角度，取值 `0 until 360`，与 Android `MediaFormat.KEY_ROTATION`、`MediaMetadataRetriever` 的旋转值一致；ffprobe 的 `rotation` 是逆时针角度，符号相反（ffprobe 的 `-90` 对应这里的 `90`）。没有显示矩阵时为 0，矩阵不是纯旋转时为 `null`。
- 探测只读取文件开头（FFmpeg 默认最多约 5 MB、5 s 的数据）以及容器索引，通常很快；对时长未知的输入，trim/split 照常执行。
