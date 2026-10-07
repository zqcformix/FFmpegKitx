# FFmpegKitx 架构与设计依据

目标是为各平台提供便捷的媒体 API，并共享经过验证的媒体语义和原生核心。先在 Android 上把本地与 `Uri` 来源的快速时间裁剪和分割做成可用功能，再扩展会话、精确编辑和其他平台。逐章任务与当前状态见[路线图](ROADMAP.zh-CN.md)。

## 1. 上游事实与取舍

以下是 2026-10-04 从官方仓库和文档核对的设计依据。上游可能继续更新；引用不代表已把这些项目引入为依赖。

| 参考项目 | 官方证据 | 采纳的设计 | FFmpegKitx 的调整 |
| --- | --- | --- | --- |
| FFmpegKit | [README](https://github.com/arthenica/ffmpeg-kit#readme)、[Android API](https://github.com/arthenica/ffmpeg-kit/blob/main/android/README.md) | 每次执行有独立 session；结构化的完成、日志、统计；可取消单个任务；媒体探测与平台 URI 适配 | 使用平台自然的并发模型；限制日志与历史的内存；显式建模文件来源；不假设 `libav*` 自带命令执行器 |
| FFmpegKitNext | [官方 README](https://github.com/arthenica/ffmpeg-kit-next#readme) | 固定工具链与输入、跨平台构建脚本、平台资源桥接 | 作为源码构建方案的评估对象；先对齐当前 FFmpeg 8.0.1 ABI 和许可证，不混用不同版本的预构建库 |
| ffmpeg-python | [README 与复杂图示例](https://github.com/kkroening/ffmpeg-python#readme) | 输入/滤镜/输出构成有向无环图；流式组合；简单任务与复杂图共享表示 | 基本编辑功能成熟后再引入强类型 DSL；移动端直接调用 native engine，不依赖外部 `ffmpeg` 可执行文件 |
| ffmpeg.wasm | [架构](https://ffmpegwasm.netlify.app/docs/overview/)、[FFmpeg 类源码](https://github.com/ffmpegwasm/ffmpeg.wasm/blob/main/packages/ffmpeg/src/classes.ts) | Worker 隔离耗时操作、可切换 core、异步文件操作、日志与进度订阅 | Web 作为远期候选，需要单独后端与能力表；记录虚拟文件系统的复制与内存成本；未知总时长不伪造百分比 |
| AndroidX Media3 Transformer（设计比较，不是 FFmpeg 封装） | [入门](https://developer.android.com/media/media3/transformer/getting-started)、[Transformer 源码](https://github.com/androidx/media/blob/release/libraries/transformer/src/main/java/androidx/media3/transformer/Transformer.java) | 专用的媒体任务模型、导出结果、明确的线程规则、利用平台硬件 | 可以评估 Android 专用的任务后端；公开 API 必须暴露能力差异，不承诺与 FFmpeg 的滤镜/编码器完全等价 |

**维护状态**：据 2026-10-04 核对，原 FFmpegKit README 在 2026 年 7 月的更新中写明旧项目已正式退休，原作者维护的续作是 FFmpegKitNext；Next 官方说明只分发源码，不向 Maven Central、CocoaPods、pub.dev 或 npm 发布现成包。可以学习其设计与构建方式，但不能把旧二进制下载地址或 Next 当成可直接安装的长期依赖。

**许可边界**：本仓库封装代码的许可不自动覆盖 FFmpeg 和第三方编解码库；FFmpegKit/Next 的许可说明也不能代替对本项目实际构建配置的审核。核对清单见[发布文档](RELEASES.zh-CN.md#4-许可证)。采用已有源代码时须单独保留其许可与归属；借鉴设计不等于复制实现。

## 2. 架构分层

```text
应用与平台示例
 ├─ Android: Kotlin suspend / File、Uri 扩展 / Flow / Java 入口
 ├─ Apple: Swift async throws / URL 扩展 / AsyncStream / actor
 ├─ HarmonyOS: ArkTS Promise / 事件订阅 / ArkUI / Node-API
 └─ 远期候选: Desktop、Flutter、React Native、Web 各自独立包
                ↓
平台 facade：资源授权、调度、生命周期、错误映射
                ↓
媒体操作契约：trim / split / probe / concat / crop / transcode
                ↓
任务运行时：会话状态、事件、取消、配额、输出事务
                ↓
稳定 C ABI（M2）：不透明句柄、版本化结构、显式释放
                ↓
可移植 C++ 核心：libavformat / libavcodec / libavfilter / libavutil
                ↓
按平台独立编译的 FFmpeg 与系统能力
```

M1 从现有 Android JNI 入口接入可移植 C++ 操作，提供 `FFmpeg.trim`/`FFmpeg.split` 与 `File`/`Uri` 扩展（设计见 [Android 编辑 API](ANDROID_API.zh-CN.md)）。稳定 C ABI、完整任务运行时和跨平台 facade 属于 M2 及以后。不为尚未落地的平台添加只会抛"未实现"的空壳 SDK。

Android、Apple、HarmonyOS 共享媒体语义、测试样本和 C ABI，不共享 UI、权限 API 或线程对象。Web 如果进入排期，可以使用独立的 wasm 运行时，但必须明确支持的操作集合与行为差异。

## 3. 基础操作的契约

本节是跨平台通用契约。Android M1 的具体范围与限制见 [Android 编辑 API 第 7 节](ANDROID_API.zh-CN.md#7-m1-范围与限制)。

### 3.1 时间与精度

- 对外使用带单位的类型：Kotlin `Duration`、Swift 明确的时间值、ArkTS 字段名带单位。native 边界统一使用有符号 64 位微秒，并检查溢出。不接受非整数微秒边界，避免暗中截断。
- 用户范围采用 `[start, end)`：起点非负，终点大于起点；终点可省略，表示到媒体结尾。超过时长的终点夹取到结尾，并通过结果中的实际范围告知调用方。起点达到或超过时长时报错，不创建空的"成功"文件。
- **快速 trim**：复制压缩包，按可用的 seek 点和关键帧边界工作。实际起点可能早于请求点，结尾和音频包边界也可能有偏差；音视频时间戳按共同时间基准处理，保留原始相对偏移。结果同时返回请求范围与实际范围。MP4/MOV 可选用 edit list 隐藏前导内容，前提是目标播放器支持。
- **快速 split**：默认单遍顺序读取，在每个时长边界之后的第一个关键帧处切段。片段互不重叠，可以无损拼接；代价是每段长度按关键帧对齐，不严格相等。按显式范围分割时各段独立 seek，相邻段可能重叠。
- **精确 trim**：需要解码、丢弃区间外的帧与采样、再编码，成本与质量契约都和快速模式不同。smart cut（只重编码起点所在 GOP）是两者之间的折中。
- **crop**：指像素区域裁剪，属于视频滤镜，不能用快速时间裁剪实现。

[FFmpeg 官方 `-ss` 说明](https://ffmpeg.org/ffmpeg-all.html#Main-options)明确指出多数格式无法精确 seek，流复制会保留从 seek 点到指定时间之间的内容。因此便捷 API 要把"快、免重编码"和"非帧精确"一起表达。

### 3.2 文件、错误和输出

- 用类型或受限入口区分文件路径、URI、文件描述符和 URL。字符串参数必须作为独立参数传递或直接调用 native，不经过 shell 拼接。
- 默认保护输入和已有输出。同文件别名、符号链接、目标冲突、目录权限、可用空间都应有可解释的结果；避免检查与写入之间的覆盖竞争。
- 输出先写临时文件，只有 trailer、关闭和必要校验都成功后才交付目标。失败或取消时，只清理本任务创建且身份仍匹配的输出，保留用户既有文件。清理失败通过 suppressed 异常报告。
- 分割先明确每段的结果和整体失败策略：不能在失败后只返回前几段并把整个任务标为成功。大量分段要设上限，避免内存和文件句柄失控。
- 错误区分参数、权限、资源、不支持、媒体损坏、FFmpeg 执行和取消。参数错误在执行前报告；其余失败使用带原因枚举和原生错误码的平台异常；取消不包装成普通失败。稳定的数值错误码随 C ABI 一起定义。

### 3.3 原生资源

用 RAII 管理 `AVFormatContext`、packet、I/O 句柄和临时文件。头文件与实际 `.so` 的 FFmpeg ABI 必须一致。JNI 和 Node-API 层只做类型转换与句柄/生命周期绑定；媒体算法应该能在不依赖 Android 类的 native 测试程序中运行。

## 4. 会话与事件（M2 目标契约）

状态按 `Queued → Running → Succeeded | Failed | Cancelled` 演进；排队中的任务可以直接取消。终态唯一，确认终态后不再发出成功结果。取消分为"请求"和"完成"两个阶段：调用方结束时要确认 native 资源已释放，而不只是取消语言层的 Promise 或 continuation。

任务 ID 关联结果、日志和统计。FFmpeg 的全局日志回调本身不知道日志属于哪个任务。实现前需要设计线程上下文、原生执行线程和缓冲区所有权；无法归属的日志标为引擎日志，不能挂到最新的 session 上。

进度模型至少包含已处理的媒体时间、可选的目标时长、写入字节和终态。总量未知时显示不定进度；trim、concat、变速等操作按目标时间轴计算。ffmpeg.wasm 的官方类文档也提醒，其进度只在输入与输出时长相同时准确，不能把输入百分比直接套用到所有编辑场景。

取消使用原子标记与 FFmpeg 的 I/O interrupt callback，覆盖包循环、队列、网络读取和写出过程。回调必须声明所在线程、重入规则和销毁时机。native 回调不能直接更新 UI；Android、Swift、ArkTS 各自转移到合适的执行上下文。

## 5. 平台 API 设计

| 平台 | 推荐公开形式 | 平台必须处理的细节 |
| --- | --- | --- |
| Android | `suspend` 基础函数、`File` 与 `Uri` 扩展（M1）、`Duration`、明确的结果；之后加 Flow，需要组合时再加 DSL；Java 入口 | Dispatchers 与调用者 scope、SAF 文件描述符与 seekability、生命周期、MediaStore、前台长任务、三 ABI 与 16 KB 页兼容 |
| Swift | `async throws`、`URL` 扩展、`AsyncStream<Progress>`、actor 管理会话 | continuation 恰好恢复一次、Task 取消传递、Sendable、Photos 与 security-scoped URL、后台额度、XCFramework slices |
| ArkTS | Promise 任务、显式订阅/解除订阅、强类型参数与取消句柄 | Node-API 异步工作与回调线程、`napi_ref` 生命周期、应用沙箱与文件描述符、ArkUI 页面退出、HAR 依赖 |
| Flutter / React Native（远期） | Future/Stream 或 Promise/事件；平台资源引用 | 避免跨桥复制整份媒体，映射会话与取消，公布每个平台的能力 |
| Web（远期） | Promise、Worker、File/Blob、可重新加载的 backend | wasm 体积、虚拟文件系统内存、CSP/CORS、COOP/COEP、终止 Worker 对并行任务的影响 |

HarmonyOS 采用 Node-API 的依据是 [OpenHarmony 官方 Node-API 概述](https://github.com/openharmony/docs/blob/master/en/application-dev/napi/napi-introduction.md)。商业 HarmonyOS 的 SDK/API 兼容范围需要用实际 SDK 和设备验证，不能直接由 OpenHarmony 文档推导。

公开的便捷 API 必须有实际后端支持。未来的底层 `execute(arguments)` 与高层操作并列存在，而不是所有调用者都必须学习的命令行入口。若采用 CLI 引擎，必须先审查全局变量、日志、退出流程、线程与重入，不能在应用里直接调用未经改造的 CLI `main()`。

## 6. 依赖与构建边界

每个平台有自己的 facade 包和原生制品，平台之间不互相传递 AAR/HAR/XCFramework。拟定的包名、渠道和当前状态见[发布文档](RELEASES.zh-CN.md#1-平台与发布渠道)。

构建要锁定 FFmpeg、外部库、补丁、编译器、SDK、配置和校验和；先生成最小功能变体，再按需求加入字幕和编码器。每份制品附实际能力清单与构建信息。API 版本和 FFmpeg 版本分开记录，FFmpeg 升级不能伪装成 facade 的无行为变化补丁。

当前 Android 预构建库可以支撑开发，但要先补齐可复现的原生来源与链接配置，才能声称具备跨平台分发能力。Linux host 上的 FFmpeg 测试通过，不能表述为 Android 原生库已经运行，更不能推导 iOS/HarmonyOS 可用。

## 7. 验收证据

1. 参数单元测试覆盖边界、单位、命名和错误模型；不用简单的算术单测充当媒体能力证明。
2. native 集成测试生成已知时长、GOP、音轨和时间戳的素材，执行操作后用 FFprobe 或解码核对，并记录使用的是 host 还是目标平台的 FFmpeg。
3. Android 仪器测试、Apple XCTest、HarmonyOS 设备测试分别验证绑定、取消、资源来源和线程行为；没有设备时明确标注"未验证"。
4. 发布前，由空白消费者工程从真实的包仓库安装目标版本，确认原生依赖、ABI、签名/校验和、许可资料完整。
5. 合并的依据是源码、测试命令和证据记录；路线图随每次实现更新。源码编译通过、host 测试通过、设备测试通过、公开包可安装是四个独立的结论。
