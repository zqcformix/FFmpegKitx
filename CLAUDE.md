# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FFmpegKit is an open-source **Android library** (not an app) wrapping FFmpeg 8.0.1 with Kotlin API and C++ JNI bridge. Published via JitPack for dependency consumption:

```kotlin
implementation("com.github.zqcformix:FFmpegKitx:v1.0.1")
```

## Build Commands

```bash
./gradlew assembleDebug           # Build demo app + library
./gradlew :ffmpegkit:assembleRelease  # Build library AAR only
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
└── jniLibs/{arm64-v8a,armeabi-v7a}/  # FFmpeg .so files (committed, bundled in AAR)
```

- **Public API**: `FFmpegKit` object — `getVersion()`, `getBuildConfiguration()`, `checkLibassAvailability()`, `isLibassAvailable()`, `printInfo()`
- **JNI naming**: `Java_io_github_nova_ffmpegkit_FFmpegKit_methodName` (maps to `FFmpegKit` object, not Activity)
- **maven-publish** plugin configured for JitPack (`groupId: com.github.zqcformix`, `artifactId: ffmpegkit`)
- **consumer-rules.pro**: keeps `FFmpegKit` class for consuming apps with ProGuard

### app (Demo Module)

Sample app demonstrating library usage. Uses `FFmpegKit.xxx()` API calls — no direct JNI or `System.loadLibrary()`.

## FFmpeg Integration

- **FFmpeg 8.0.1** cross-compiled for Android (NDK r29, API 24)
- **ABIs**: arm64-v8a, armeabi-v7a
- **Libraries**: libavcodec, libavformat, libavfilter, libavutil, libswresample, libswscale
- **Enabled features**: libass, libfreetype, libfribidi, mediacodec, jni
- **Build chain**: freetype → fribidi → harfbuzz → libass → FFmpeg (see `build_android.sh`)
- libass and dependencies are statically linked into FFmpeg .so files
- Prebuilt .so available at [GitHub Releases](https://github.com/zqcformix/FFmpegKitx/releases)
- .so files are **committed to repo** and bundled into AAR — users get them automatically via dependency

## Build Configuration

- **Gradle**: 8.14.4 with Kotlin DSL, AGP 8.13.2
- **Kotlin**: 2.3.10, JVM target 17
- **SDK**: minSdk 26, targetSdk/compileSdk 36
- **Version catalog**: `gradle/libs.versions.toml`
- **Native build**: CMake 3.22.1, C++17
- **Publishing**: maven-publish plugin + JitPack

## Git Workflow

- **Repository**: https://github.com/zqcformix/FFmpegKitx.git
- **Branch strategy** (Git Flow):
  - `main` — stable releases, tagged with version (e.g. `v1.0.0`)
  - `develop` — integration branch, all feature PRs merge here
  - `feature/*` — new features, branch from `develop`
  - `bugfix/*` — bug fixes, branch from `develop`
  - `hotfix/*` — urgent fixes from `main`
  - `release/*` — release preparation from `develop`
- **Commit convention**: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`
- **Release flow**: tag on `main` → JitPack auto-builds AAR → users update version
- **Gitignored**: `*.jks`, `.claude/`
- **Important**: every significant update must sync both `CLAUDE.md` and `README.md`
