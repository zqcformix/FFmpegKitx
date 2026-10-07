# Git 分支、提交与合并规范

本项目采用简化流程：`main` 始终可发布，所有改动都在从 `main` 创建的短期分支上完成，再合回 `main`。不维护长期的 `develop` 分支。

## 1. 分支

| 分支 | 用途 | 约定 |
| --- | --- | --- |
| `main` | 稳定版本与发布基线 | 只通过审阅后的合并更新；发布标签 `vX.Y.Z` 打在 `main` 上，JitPack 据此构建 |
| `feat/<topic>` | 单个功能 | 如 `feat/trim`、`feat/uri-input`；完成后尽快合并并删除 |
| `fix/<topic>` | 缺陷修复 | 附复现条件与回归测试；紧急修复同样走这个分支，合并后立即打补丁标签 |
| `docs/<topic>` | 文档、计划与示例说明 | 如 `docs/roadmap` |
| `build/<topic>`、`chore/<topic>` | 构建、依赖升级与杂项 | 如 `build/agp-9` |
| `release/<major.minor>` | 可选的旧版本维护线 | 只在需要同时维护多个已发布版本时建立；修复后打补丁标签，并合回或 cherry-pick 到 `main` |

## 2. 开始工作

```bash
git status --short
git switch main
git pull --ff-only origin main
git switch -c feat/trim
```

- 保留本地已有改动，不用 `reset --hard` 清理别人的文件。
- `--ff-only` 失败说明本地 `main` 有独立提交，先分析分叉原因，不要强制重置。
- 吸收 `main` 的新内容时：只有自己使用的分支可以 `git rebase main`；已共享的分支用 `git merge main`。不对 `main` 或他人依赖的分支强推。
- 没有远端访问时，记录本地基线和待同步事项，不声称已与远端同步。

## 3. 提交规范

格式：`<type>(<scope>): <简短行为说明>`，scope 可以省略。

```text
feat(core): add timestamp-aware stream copy trimming
feat(android): expose Uri input for trim and split
fix(android): preserve output on failed trim
build(ffmpegkit): pin NDK version for 16 KB alignment
docs(plan): reorder roadmap milestones
```

- **type**：`feat`、`fix`、`docs`、`test`、`refactor`、`build`、`ci`、`chore`。
- **scope**：`core`、`jni`、`android`、`ffmpegkit`、`app`、`plan`、`ios`、`harmony` 等。
- 破坏性变更在 type 后加 `!`，并在正文写 `BREAKING CHANGE:` 和迁移办法。

每个提交解决一个完整问题，包含所需测试和直接相关的文档；不要把格式化、二进制升级和功能重构混在一个提交里。提交信息只描述实际完成的内容，不把计划中的工作写成已交付。

按路径显式暂存，提交前检查：

```bash
git diff --check
git add ffmpegkit/src/main/cpp ffmpegkit/src/main/java
git diff --cached --stat
git diff --cached
git commit -m "feat(core): add timestamp-aware stream copy trimming"
```

避免用 `git add .` 把无关改动（IDE 文件、其他进行中的升级）混进提交。

## 4. 审阅、验证与合并

PR 描述包含：要解决的问题、API 示例、兼容性、验证命令与结果、已知限制。Android 的基础验证：

```powershell
# Windows（macOS / Linux 使用 ./gradlew 和相同的任务）
.\gradlew.bat :ffmpegkit:testDebugUnitTest :ffmpegkit:assembleRelease :ffmpegkit:verifyNativeAlignment :app:assembleOtherDebug :app:testOtherDebugUnitTest
```

- 根据改动补充原生媒体测试和设备测试。没有对应设备或工具链时明确标注"未验证"，不用 Android 构建通过代替其他平台。
- 合并方式：单一功能的分支用 squash merge，保持 `main` 线性；包含多个有意义提交的分支用 `--no-ff` 保留脉络。合并后删除分支。
- 发布：在 `main` 上按[发布文档](RELEASES.zh-CN.md)检查后打标签并推送，JitPack 根据标签构建。

```bash
git switch main
git pull --ff-only origin main
git tag -a v1.1.0 -m "v1.1.0"
git push origin v1.1.0
```

## 5. 自动化工具与 AI 助手

- 只暂存本次任务涉及的路径，不提交别人正在进行的改动。
- 推送、创建 PR、打标签、删除远端分支前，必须得到维护者的明确授权。
- 没有运行过的验证不写成已通过；路线图只记录已合入 `main` 的事实。

## 6. 冲突与回滚

发生冲突时，先用 `git status` 确认涉及的文件，与该路径的负责人核对意图，手工解决后重新验证。不能靠整文件选择 ours/theirs 掩盖跨功能冲突。无法当场解决时，用 `git merge --abort` 回到合并前的状态再继续调查。

已共享的提交用 `git revert <commit>` 回滚，保留审计记录。回滚合并提交前先检查父节点：

```bash
git show --no-patch --pretty=raw <merge-commit>
git revert -m 1 <merge-commit>
```

`-m 1` 只在第一个父节点确实是要保留的主线时使用。回滚后重新验证。之后要重新引入被回滚的功能时，先检查原合并的祖先关系，必要时先撤销这次回滚，再合并修复，避免 Git 把旧提交视为已经合入。
