# 发布与第三方组件检查

## 1. 平台与发布渠道

统一源码仓库不意味着使用同一个依赖包。除 Android 现有 JitPack 坐标外，下表的名称都是**拟定、未发布**的，最终以包注册机构的命名权限和成功发布结果为准，不要复制到生产工程。不为填满表格编造下载链接。

| 平台 | 当前状态 | 渠道与拟定坐标 |
| --- | --- | --- |
| Android | JitPack `com.github.zqcformix:FFmpegKitx:v1.0.2`，只含信息查询 API；编辑 API 未实现 | 继续使用 JitPack。若迁移到 Maven Central，groupId 用 `io.github.zqcformix`（可通过 GitHub 账号验证命名空间），artifactId 拟为 `ffmpegkitx-android` |
| iOS / macOS（M4） | 未开始 | SwiftPM：本仓库的 `Package.swift` 加二进制 `FFmpegKitxCore.xcframework`，通过 Git URL + tag 引入 |
| HarmonyOS（M4） | 未开始 | OHPM 包 `@ffmpegkitx/harmony` + HAR，发布到 <https://ohpm.openharmony.cn/>；scope 未注册 |
| Linux / Windows（远期候选） | 未排期 | `ffmpegkitx-core` 原生归档 + CMake config，放在本仓库 GitHub Releases |
| Flutter（远期候选） | 未排期 | pub.dev 包 `ffmpegkitx`，名称可用性未核实 |
| React Native（远期候选） | 未排期 | npm 包 `@ffmpegkitx/react-native`，scope 未核实 |
| Web（远期候选） | 未排期 | npm 包 `@ffmpegkitx/web`，可选独立 wasm core；与 React Native 包分开安装 |

### Android 坐标需要统一的地方

- 当前 Maven publication：groupId `com.github.zqcformix`、artifactId `ffmpegkit`、version 取根目录 `build.gradle.kts` 的 `versionName`（已改为待发布的 `1.0.3`；v1.0.0–v1.0.2 都错误地带着 `1.0.0`）。JitPack 的坐标由仓库和标签决定，不能从本地 POM 推断。每次发布前把 `versionName` 与标签对齐，在空白消费者工程中验证实际解析地址后再更新 README。
- Kotlin 包名 `io.github.nova.ffmpegkit` 不必与 groupId 相同，但迁移到 Maven Central 时 groupId 不能使用 `io.github.nova`（需要拥有对应的 GitHub 账号）。

## 2. 每次发布的检查清单

1. **版本信息**：统一 `versionName` 与标签；记录提交、标签、AGP/Gradle/NDK 版本、各 ABI、FFmpeg 与外部库版本。稳定版从审阅通过的 `main` 打标签。
2. **构建与测试**：

   ```powershell
   # Windows（macOS / Linux 使用 ./gradlew 和相同的任务）
   .\gradlew.bat :ffmpegkit:assembleRelease :ffmpegkit:verifyNativeAlignment :app:assembleOtherDebug :app:testOtherDebugUnitTest
   .\gradlew.bat :ffmpegkit:publishToMavenLocal
   ```

   `publishToMavenLocal` 只生成本地内容，不等于发布远端包。所有 Maven 发布任务都会先运行 `verifyNativeAlignment`，JitPack 构建也一样。设备侧验证库加载、真实剪辑与分割、异常路径、长任务资源释放和最低支持版本。
3. **产物记录**：计算 SHA-256；生成组件清单或 SPDX/CycloneDX SBOM；保留原始构建日志、构建配置、工具链版本和来源定位。
4. **消费者验证**：在空白消费者工程中用拟发布坐标构建；检查传递依赖、R8/ProGuard、打包后的 `.so`、sources/Javadoc 和 POM。
5. **发布说明**：写清新增 API、行为变化、兼容性、限制、迁移示例和验证范围。签名与凭据由发布环境安全注入。

## 3. 原生二进制与 Android 兼容性

### 3.1 16 KB 页大小

Google Play 自 2025-11-01 起要求面向 Android 15+ 的新应用和更新支持 16 KB 页大小。库里任何一个 64 位 `.so` 不达标，都会让使用它的应用无法满足这一要求。

2026-10-07 检查结果：

| 对象 | 结果 |
| --- | --- |
| `jniLibs` 中 18 个预编译 FFmpeg `.so` | 全部 LOAD 段 ≥ `0x4000`，达标 |
| **已发布 v1.0.2 中的 `libffmpegkit.so`（JNI 库）** | **arm64-v8a、x86_64 均为 `0x1000`（4 KB），不达标**。`.comment` 显示由 NDK r27（clang 18.0.1，r522817）编译：`build.gradle.kts` 未固定 `ndkVersion`，AGP 使用了默认 NDK，`jitpack.yml` 安装的 NDK 没有被用到 |
| 用 NDK r28.2 的 clang 直接链接 `native-lib.cpp`（非 Gradle 构建；同样方式用 r27 链接得到 `0x1000`） | `0x4000`，达标 |

处理措施：

- `ffmpegkit/build.gradle.kts` 固定 `ndkVersion = "28.2.13676358"`（NDK r28 起默认 16 KB 对齐），`jitpack.yml` 安装同一版本。修改两者时必须保持一致。
- `verifyNativeAlignment` 检查 release AAR 中每个 64 位 `.so` 的 LOAD 段对齐，不足 16 KB 时让构建失败，并挂在所有 Maven 发布任务之前。32 位 ABI 不会运行在 16 KB 页设备上，因此不检查。
- 下一个版本发布后，用 v1.0.2 相同的方式下载 AAR 复查 JNI 库，并在发布说明中提示 v1.0.2 用户升级。
- APK 侧另用 Build Tools 的 `zipalign -c -P 16 -v 4 <app.apk>` 检查，并在 16 KB 页的模拟器或设备上实际运行。参考：[Android 16 KB 页大小指南](https://developer.android.com/guide/practices/page-sizes)。

### 3.2 API 级别、ABI 与体积

- `minSdk` 26；ABI 为 `arm64-v8a`、`armeabi-v7a`、`x86_64`。预编译 FFmpeg 以 API 24 编译，低于 `minSdk`。要在最低 API 和有代表性的新版本设备上验证。
- 未压缩 `.so` 合计：arm64-v8a 约 22 MB、armeabi-v7a 约 20 MB、x86_64 约 27 MB。用 App Bundle 发布时 Play 会按设备分发；直接分发 APK 的消费者可以用 `abiFilters` 去掉不需要的 ABI。README 中有示例。
- iOS XCFramework 分开设备与模拟器 slice，记录最低部署版本、架构、系统框架依赖和签名策略；HarmonyOS HAR 记录 SDK、API 级别与 ABI。各平台能力以目标平台验证结果为准。

## 4. 许可证

**本项目封装代码**：根目录 `LICENSE` 为 MIT 正文（权利人 Joe Zhou），README 和 POM 与之一致。第三方组件的许可、版本、源码地址和 configure 参数见根目录 `THIRD_PARTY_NOTICES.md`，许可全文在 `licenses/`。MIT 只适用于本项目封装代码，不能把预编译 FFmpeg、其头文件和静态链接的依赖都标成 MIT，也不构成对第三方代码的重新授权。

**FFmpeg 与外部库**：2026-10-07 对三个 ABI 的 `libavutil.so` 做了静态字符串检查，自报 `LGPL version 2.1 or later`，configure 未包含 `--enable-gpl`、`--enable-version3`、`--enable-nonfree`，启用了 `--enable-libass --enable-libfreetype --enable-libfribidi`。这只说明这些文件的自报信息，不能证明全部二进制和分发材料合规。

| 组件 | 上游许可 | 分发注意 |
| --- | --- | --- |
| FFmpeg 8.0.1 | LGPL-2.1-or-later（未启用 gpl/nonfree 时） | 以动态库分发；提供源码获取方式、configure 参数与构建脚本，满足重新链接的要求 |
| libass | ISC | 保留版权与许可声明 |
| FreeType | FreeType License（FTL）与 GPLv2 二选一 | 选 FTL 时须在文档中致谢 FreeType Project |
| FriBidi | LGPL-2.1-or-later | 静态链接进 FFmpeg 的 `.so`，按 LGPL 提供对应源码与重建方式 |
| HarfBuzz | MIT（"Old MIT"） | 保留版权声明 |

上表的版本和许可须按实际构建使用的源码核对。核查步骤：

1. 收集 `FFmpegKit.getBuildConfiguration()`、各 `libav*`/`libsw*` 的配置与 license 信息，与二进制内嵌字符串相互核对，按每个 ABI 检查，而不是只看版本号。遇到 nonfree 配置时先确认该组合是否可以再分发。
2. 各外部库单独核对对应源码的 LICENSE/COPYING 和链接方式；不要因为参与了 FFmpeg 构建就把它们统一归为某一种许可。
3. 核对源代码归档、补丁、完整 configure 参数、依赖版本、工具链和构建脚本，是否足以对应当前分发的二进制并完成重建。仓库里有头文件和 `.so` 并不自动满足对应源代码与再链接材料的要求。
4. 按实际许可整理需要随产物分发的文本、声明和源码获取方式；静态链接、动态链接和平台打包方式分别检查。公开发布前完成对最终发行组合的审查。

来源：[FFmpeg Legal](https://ffmpeg.org/legal.html)、[FFmpeg License](https://ffmpeg.org/doxygen/trunk/md_LICENSE.html)、[libass COPYING](https://github.com/libass/libass/blob/master/COPYING)。

## 5. 首次扩大分发前的待办

- [ ] 发布带固定 NDK 的新版本，修复 v1.0.2 JNI 库的 16 KB 对齐问题（见 3.1）
- [x] 根目录补齐 LICENSE（MIT 正文与权利人）
- [x] 新增第三方声明文件，包含 FFmpeg、libass、FreeType、FriBidi、HarfBuzz 的许可文本与版权声明。FreeType 版本未记录在二进制中，HarfBuzz 版本（10.1.0）是从字符串推断的，补齐来源记录时一并核实
- [ ] 为每个预编译二进制建立来源、源码/补丁、构建配方、SHA-256 与组件清单的对应记录（当前缺少完整的可复建来源链，`build_android.sh` 也不在仓库中，见路线图 7.1）
- [x] 统一 `versionName` 与发布标签（`1.0.3`）
- [ ] 实际发布后，把本文的规划坐标替换为真实包名、版本与可访问地址，并更新各平台的使用示例
