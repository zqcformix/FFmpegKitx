# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FFmpegKit is an open-source **Android library** (not an app) wrapping FFmpeg 8.0.1 with Kotlin API and C++ JNI bridge. Published via JitPack for dependency consumption:

```kotlin
implementation("com.github.zqcformix:FFmpegKitx:v1.0.2")
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
├── jitpack.yml      # JitPack build configuration
└── settings.gradle.kts  # includes :app and :ffmpegkit
```

### ffmpegkit (Library Module)

The publishable library. Contains all native code, FFmpeg headers, and public Kotlin API.

```
ffmpegkit/src/main/
├── java/io/github/nova/ffmpegkit/
│   └── FFmpegKit.kt              # Public API: singleton object with @JvmStatic methods
├── cpp/
│   ├── CMakeLists.txt             # CMake config, imports FFmpeg as SHARED IMPORTED
│   ├── native-lib.cpp             # JNI implementation (maps to FFmpegKit object methods)
│   └── libav*/libsw*/             # FFmpeg C headers (compile-time only)
└── jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/  # FFmpeg .so files (committed, bundled in AAR)
```

- **Public API**: `FFmpegKit` object — `getVersion()`, `getBuildConfiguration()`, `checkLibassAvailability()`, `isLibassAvailable()`, `printInfo()`
- **JNI naming**: `Java_io_github_nova_ffmpegkit_FFmpegKit_methodName` (maps to `FFmpegKit` object, not Activity)
- **maven-publish** plugin configured for JitPack (`groupId: com.github.zqcformix`, `artifactId: ffmpegkit`)
- **consumer-rules.pro**: keeps `FFmpegKit` class for consuming apps with ProGuard

### app (Demo Module)

Sample app demonstrating library usage. Uses `FFmpegKit.xxx()` API calls — no direct JNI or `System.loadLibrary()`.

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
- **Kotlin**: 2.4.20, JVM target 17
- **SDK**: minSdk 26, targetSdk/compileSdk 37
- **Version catalog**: `gradle/libs.versions.toml`
- **Native build**: CMake 3.22.1, C++17, NDK pinned to `28.2.13676358` via `ndkVersion`, which AGP auto-installs on JitPack too (`sdk-manager` does not exist there; unpinned builds fell back to NDK r27 and shipped a 4 KB-aligned JNI lib in v1.0.2)
- **Publishing**: maven-publish plugin + JitPack; every publish task runs `verifyNativeAlignment` first

## Planning Docs (`docs/`, Chinese)

- `ROADMAP.zh-CN.md` — milestones and task status; the single source for what is implemented. Editing APIs (trim/split, `FFmpeg` facade) are **designed but not implemented**
- `ANDROID_API.zh-CN.md` — M1 editing API design draft; the only place that defines M1 scope and limits
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
