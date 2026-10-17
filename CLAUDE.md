# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FFmpegKit is an open-source **Android library** (not an app) wrapping FFmpeg 8.0.1 with Kotlin API and C++ JNI bridge. Published via JitPack for dependency consumption:

```kotlin
implementation("com.github.zqcformix:FFmpegKitx:v1.0.3")
```

## Build Commands

```bash
./gradlew assembleDebug           # Build demo app + library
./gradlew :ffmpegkit:assembleRelease  # Build library AAR only
./gradlew :ffmpegkit:verifyNativeAlignment  # Fail if a 64-bit .so in the release AAR is below 16 KB alignment
./gradlew test                    # Unit tests
./gradlew connectedAndroidTest    # Instrumented tests
./gradlew clean
```

## Module Structure

```
FFmpegKit/
├── ffmpegkit/       # Library module (com.android.library, published via JitPack)
├── app/             # Demo app module (com.android.application, depends on :ffmpegkit)
├── scripts/generate-test-media.ps1  # Regenerates the androidTest media samples (needs desktop ffmpeg with libx264); reproduces the committed files byte for byte
├── scripts/verify-outputs.ps1       # Checks pulled trim/split outputs against their input with desktop ffmpeg (strict decode, keyframe start, packet runs)
├── jitpack.yml      # JitPack build configuration
└── settings.gradle.kts  # includes :app and :ffmpegkit
```

### ffmpegkit (Library Module)

The publishable library. Contains all native code, FFmpeg headers, and public Kotlin API.

```
ffmpegkit/src/main/
├── java/io/github/nova/ffmpegkit/
│   ├── FFmpegKit.kt              # Public API: singleton object with @JvmStatic methods (info queries)
│   ├── FFmpeg.kt                 # Public editing API: FFmpeg.trim/split/probe, TrimRequest, SplitRequest (byDuration/byRanges), results, trimTo/splitTo/probe extensions (M1 4.2, 3.2, 2.3)
│   ├── MediaInfo.kt              # Public probe result: MediaInfo, StreamInfo.Video/Audio/Other; unknown values are null (M1 3.2)
│   ├── Prober.kt                 # Internal blocking probe over probe.cpp; NativeProbeResult parsing (layout in probe.cpp)
│   ├── Cancellation.kt           # NativeCancellation (flag shared with native) and runNative (IO dispatcher + cancel-then-wait)
│   ├── TimeRange.kt              # [start, endExclusive) in whole microseconds; validated on construction (M1 4.1)
│   ├── FFmpegException.kt        # Public error type with frozen, append-only Reason enum (M1 4.3)
│   ├── ErrorMapping.kt           # Internal: AVERROR/errno → Reason table, mapIoFailures, nativeFailure
│   ├── MediaIo.kt                # Public MediaInput (File / content Uri / ParcelFileDescriptor) and MediaOutput (File / Uri) with internal subclasses; UriDelivery (M1 3.1)
│   ├── Trimmer.kt                # Internal blocking fast trim (MediaInput → MediaOutput) over trim.cpp (M1 2.1); TrimMode.EDIT_LIST shifts timestamps so MP4 gets an edit list (M1 2.4)
│   ├── Splitter.kt               # Internal blocking split: keyframe-aligned over split.cpp (M1 2.2), by ranges as one native trim per range (M1 2.3); SegmentPublisher rollback
│   ├── NativeRemux.kt            # NativeRemuxStatus: JNI result of trim/split → FFmpegException / IllegalArgumentException
│   ├── OutputDelivery.kt         # Temp file + no-overwrite publish: renameat2 NOREPLACE → hard link → CREATE_NEW copy (M1 2.5)
│   └── MediaInspector.kt         # Internal: full-decode summary and per-packet digests for verifying outputs
├── cpp/
│   ├── CMakeLists.txt             # CMake config, imports FFmpeg as SHARED IMPORTED
│   ├── native-lib.cpp             # JNI implementation (maps to FFmpegKit object methods)
│   ├── errors.cpp                 # JNI for NativeErrors: av_strerror text and AVERROR constants
│   ├── remux.h / remux.cpp        # Shared stream copy: open input (path or seekable fd via custom AVIOContext) + stream selection, output creation, rebased writes, JNI status
│   ├── trim.cpp                   # Stream-copy trim: keyframe anchor, per-stream dts end; re-seeks earlier when an index-less seek overshoots; edit list mode shifts by the requested start
│   ├── split.cpp                  # Single-pass split: cut at the first reference keyframe ≥ segment start + duration
│   ├── probe.cpp                  # Structured probe after avformat_find_stream_info (no decoding); bit-rate-estimated durations → unknown
│   ├── inspect.cpp                # Full decode summary and per-packet hashes used to verify outputs
│   ├── files.cpp                  # renameat2(RENAME_NOREPLACE) via dlsym (libc has it from API 30; never the raw syscall)
│   ├── jni_util.h                 # Paths cross JNI as UTF-8 byte arrays (not modified UTF-8 strings)
│   └── libav*/libsw*/             # FFmpeg C headers (compile-time only)
└── jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/  # FFmpeg .so files (committed, bundled in AAR)
```

- **Public API**: `FFmpegKit` object — `getVersion()`, `getBuildConfiguration()`, `checkLibassAvailability()`, `isLibassAvailable()`, `printInfo()` (released). Unreleased on `main`: `FFmpeg.trim`/`FFmpeg.split`/`FFmpeg.probe`, `TrimRequest`/`TrimMode`, `SplitRequest.byDuration`/`byRanges`, `MediaInput`, `MediaOutput`, `TrimResult`/`SplitResult`/`SplitSegment`, `MediaInfo`/`StreamInfo`, `TimeRange`, `FFmpegException`, `File`/`Uri` `trimTo`/`splitTo`/`probe`. README documents only released API, so it is updated at the next release
- **Cancellation**: every editing call runs through `runNative`; native loops and FFmpeg's `interrupt_callback` poll the `NativeCancellation` flag, and Kotlin calls `throwIfCancelled()` before publishing. Work cancelled this way must end as `CancellationException`, never `FFmpegException`
- **Error handling**: media operations report every failure as `FFmpegException` via `mapIoFailures` / `nativeFailure`; never wrap `CancellationException` or `IllegalArgumentException`. Only append to `FFmpegException.Reason` (`FFmpegExceptionTest` pins the order). JNI symbol names depend on class names, so `consumer-rules.pro` keeps classes with native methods
- **Writing outputs**: every operation that creates a file goes through `OutputDelivery` (`deliver` for one file; `Splitter` writes into a temp directory next to the outputs and publishes each with `publishFile`). Nothing ever overwrites a file; a `Uri` output (caller-owned) is written to a cache temp file and copied in with `"wt"` by `UriDelivery`. `content://` inputs are read through their file descriptor, never as paths. Android's SELinux denies hard links to app files (`AccessDeniedException`), so on Android 11+ publishing uses `renameat2`; host tests on the JVM exercise the hard-link and copy paths
- **Unit tests**: host tests in `ffmpegkit/src/test` (`:ffmpegkit:testDebugUnitTest`)
- **Instrumented tests**: `ffmpegkit/src/androidTest` (`:ffmpegkit:connectedDebugAndroidTest`, needs a device or emulator). `PackagedLibrariesTest` pins the FFmpeg version, library versions and configure flags that README documents — change README and the test together when the prebuilt .so files change. Media tests use the samples in `ffmpegkit/src/androidTest/assets/media/`; their expected ranges and packet counts come from `ffprobe -show_packets`, so recompute them if the samples are regenerated. `-e keepOutputs true` keeps `TrimTest`/`SplitTest`/`SplitRangesTest`/`MediaRegressionTest` outputs in the test app's cache for pulling with `run-as` (install the test APK with `adb install -t`, run with `am instrument`; `connectedDebugAndroidTest` uninstalls it afterwards) and checking with `scripts/verify-outputs.ps1`. `MediaRegressionTest` (roadmap 2.6) covers long GOP, VFR, MPEG-TS with a non-zero start time, index-less ADTS, and Unicode/space paths. `TrimEditListTest` (roadmap 2.4) plays outputs with Media3 ExoPlayer (test-only dependency) and `MediaPlayer` and reads the first rendered frame back through OpenGL (`GlFrameGrabber`); an `ImageReader` crashes in the platform on Xiaomi hardware decoders. Xiaomi devices ask to confirm every test APK install; a cancelled install still ends in `BUILD SUCCESSFUL` with no tests run, so check the result XML. Unattended runs: boot AVD `375x812_Unit_IN` (x86_64, API 35) and set `ANDROID_SERIAL=emulator-5554`. `MediaUriTest` inserts and deletes its own MediaStore items under `Movies/FFmpegKitTest` (API 29+, no permission needed)
- **JNI naming**: `Java_io_github_nova_ffmpegkit_FFmpegKit_methodName` (maps to `FFmpegKit` object, not Activity)
- **maven-publish** plugin configured for JitPack (`groupId: com.github.zqcformix`, `artifactId: ffmpegkit`)
- **consumer-rules.pro**: keeps `FFmpegKit`, classes with native methods, and the JNI-constructed `NativeRemuxStatus`

### app (Demo Module)

Sample app demonstrating library usage through the public API only — no direct JNI or `System.loadLibrary()`. Two tabs:

- **Edit** (`editor/`): pick a video with the system file picker (`ACTION_OPEN_DOCUMENT`) or the photo picker, show its `FFmpeg.probe` result, trim or split it, save to `Movies/FFmpegKit`. `Gallery` writes MediaStore items as pending and deletes them on failure or cancellation (Android 10+; split goes through a cache directory, then is copied in); Android 8–9 writes the public Movies directory with `WRITE_EXTERNAL_STORAGE` and the media scanner. Each run logs a report under tag `FFmpegKitDemo` (picker, input Uri authority, probe result, requested and actual ranges), also copyable from the UI — the evidence format for roadmap 3.1's picker tests
- **FFmpeg info**: `FFmpegKit.getVersion()` etc.

## FFmpeg Integration

- **FFmpeg 8.0.1** cross-compiled for Android (NDK r29, API 24)
- **ABIs**: arm64-v8a, armeabi-v7a, x86_64
- **Libraries**: libavcodec, libavformat, libavfilter, libavutil, libswresample, libswscale
- **Enabled features**: libass, libfreetype, libfribidi, mediacodec, jni
- **Build chain**: freetype → fribidi → harfbuzz → libass → FFmpeg (the `build_android.sh` that produced the .so files is not in this repo)
- libass and dependencies are statically linked into FFmpeg .so files
- **Licensing**: root `LICENSE` (MIT) covers only the wrapper code; FFmpeg (LGPL-2.1+), libass 0.17.3, FreeType, FriBidi 1.0.16 and HarfBuzz are listed in `THIRD_PARTY_NOTICES.md` with full texts in `licenses/`. Update both when the prebuilt .so files change
- Prebuilt .so available at [GitHub Releases](https://github.com/zqcformix/FFmpegKitx/releases)
- .so files are **committed to repo** and bundled into AAR — users get them automatically via dependency

## Build Configuration

- **Gradle**: 9.8.0 with Kotlin DSL, AGP 9.4.1; daemon JVM pinned to JetBrains JDK 17 via `gradle/gradle-daemon-jvm.properties`
- **AGP 9 compatibility mode**: `gradle.properties` sets `android.builtInKotlin=false` and `android.newDsl=false`, so modules still apply `org.jetbrains.kotlin.android` and use the legacy `android {}` DSL (deprecation warnings expected; removed in AGP 10)
- **Build script gotcha**: AGP 9 registers a `java` extension that shadows `java.*` packages in `build.gradle.kts` — `import` JDK classes instead of writing `java.nio.ByteBuffer` inline
- **Kotlin**: 2.4.20, JVM target 17. The library compiles with `languageVersion`/`apiVersion` 2.2 and `coreLibrariesVersion` 2.2.20 so consumers on Kotlin 2.1+ (AGP 9's built-in 2.2.10) can read its metadata; `aarMetadata.minCompileSdk` is 26 (AGP 9 would otherwise demand compileSdk 37). Keep the library free of androidx dependencies
- **SDK**: minSdk 26, targetSdk/compileSdk 37
- **Version catalog**: `gradle/libs.versions.toml`
- **Native build**: CMake 3.22.1, C++17, NDK pinned to `28.2.13676358` via `ndkVersion`, which AGP auto-installs on JitPack too (`sdk-manager` does not exist there; unpinned builds fell back to NDK r27 and shipped a 4 KB-aligned JNI lib in v1.0.2)
- **Publishing**: maven-publish plugin + JitPack; every publish task runs `verifyNativeAlignment` first

## Planning Docs (`docs/`, Chinese)

- `ROADMAP.zh-CN.md` — milestones and task status; the single source for what is implemented. Editing APIs (trim/split, `FFmpeg` facade) are **designed but not implemented**. Its "当前进度与下一步" section says where work stopped and what comes next; read it when starting a session and update it when a task lands or the plan changes
- `ANDROID_API.zh-CN.md` — M1 editing API design draft; the only place that defines M1 scope and limits
- `LOCAL_INTEGRATION.zh-CN.md` — integrating from source (`project(":ffmpegkit")` in this repo, `publishToMavenLocal` elsewhere) and the first media task; its code is verified in a blank AGP 9 consumer project
- `ARCHITECTURE.zh-CN.md` — layering, media semantics, upstream trade-offs
- `RELEASES.zh-CN.md` — release checklist, platform package plan, 16 KB alignment, licensing
- `GIT_WORKFLOW.zh-CN.md` — branching, commits, merging

Only check a roadmap task after its code is merged to `main` with test evidence.

## Git Workflow

- **Repository**: https://github.com/zqcformix/FFmpegKitx.git
- **Branch strategy** (simplified; details in `docs/GIT_WORKFLOW.zh-CN.md`):
  - `main` — always releasable; release tags (e.g. `v1.0.2`) are created here
  - `feat/*`, `fix/*`, `docs/*`, `build/*`, `chore/*` — short-lived branches from `main`, merged back after review
  - `release/<major.minor>` — optional, only when maintaining an older release line
  - No long-lived `develop` branch
- **Commit convention**: `<type>(<scope>): summary`, scope optional — `feat`, `fix`, `docs`, `test`, `refactor`, `build`, `ci`, `chore`
- **Release flow**: tag on `main` → JitPack auto-builds AAR → users update version
- **Gitignored**: `*.jks`, `.claude/`
- **Important**: every significant update must sync both `CLAUDE.md` and `README.md`. README is for library users only (usage, API, requirements, FFmpeg versions); never add roadmap, planning-doc links or other development plans to it
