# T-MergeDevR2Round3 开发计划：dev 合入 feature/r2-competition-response 第三轮增量合并

> 立项时间：2026-09-12 09:50
> 执行分支：feature/r2-competition-response（worktree：F:/code/Java/Gitee/SongRenShuo/mpsv-r2）
> 合并来源：dev（ffe3ff7）
> merge-base：ed7723f

## 一、立项背景

feature/r2-competition-response（竞品应答集成分支，tip ba2403c，v2026.9.10）自第二轮合并后，dev 侧又前进了 69 个提交（v2026.9.11 发行、T-DynUpdate 自更新免重启热替换套件、T-HistParam 历史参数持久化、T-ReverseParenWira、T-PickMpjErr、262 签名护栏、M56 计划文档等）。需将 dev 合入 r2 分支，解决冲突、全量测试、确保构建校验通过。

## 二、增量分析

| 维度 | dev 侧 | r2 侧 |
|---|---|---|
| 提交数 | 69（含 2 个 merge） | 59（含 1 个 merge） |
| 变更文件 | 174 个（+9575/−1073） | 113 个（+7138/−391） |
| 版本号 | 2026.9.11 | 2026.9.10 |

关键事实：dev 已通过 afa2942 / 28765fa 两次 master 合并吸收了 r2 的 v2026.9.9 / v2026.9.10 发行面与功能修复（T-VPairAnno、T-HibDualCapture、T-PreviewSlow、T-ExecSilentNoop、T-PreviewDocLock2 等），故 **dev 侧对绝大多数共同文件是超集或更新版**。

## 三、merge-tree 预演冲突清单（21 文件 40 冲突块）

预演命令：`git merge-tree --write-tree feature/r2-competition-response dev`

| 文件 | 冲突块 | 类别 |
|---|---|---|
| gradle.properties | 1 | 版本号 |
| build.gradle.kts | 1 | 构建/changelog |
| update/preview.update.json | 1 | 更新清单 |
| PluginSettingsPanel.kt | 5 | 源码-设置面板 |
| DataSourceSelector.kt | 2 | 源码-执行控制台 |
| LogEntryPreviewRecognizer.kt | 1 | 源码-日志识别 |
| SqlLogPanel.kt | 1 | 源码-日志面板 |
| MpSqlVisualizerBundle(.zh).properties | 1+1 | 资源键 |
| PluginSettingsPersistenceTest.kt | 1 | 测试基线 |
| docs/MEMORY.md | 4 | 台账 |
| docs/变更记录.md | 1 | 台账 |
| record/gifkit.py + 7 个 scene_*.py | 19 | 录制脚本 |
| docs/发行说明/marketplace-custom-page.html | modify/delete | 双副本合并遗留 |

## 四、逐文件解决策略（定案）

### 4.1 直接取 dev 侧（dev 为超集/更新版，已逐一核实）

- **gradle.properties / update/preview.update.json**：版本号定案取 dev 值 2026.9.11（沿第二轮定例「版本号取 dev 值」）。
- **build.gradle.kts**：dev 侧已含 v2026.9.10 + v2026.9.11 两段 changelog HTML 及 T-GifRecordSwitch 开关，完全覆盖 r2 侧的 2026.9.10 changelog 增量，取 dev。
- **PluginSettingsPanel.kt**：r2 侧仅 T-VPairAnno 开关（dev 已含）；dev 侧另有 T-HistParam literalParamRecovery 开关，dev 超集，取 dev。
- **PluginSettingsPersistenceTest.kt**：复选框基线 dev 侧 24（含 T-HistParam +1）覆盖 r2 侧 23，取 dev。
- **LogEntryPreviewRecognizer.kt**：双方共有 T-HibDualCapture（内容一致），dev 另有 T-HistParam +83 行，r2 零独有行（已 diff 核实），取 dev。
- **SqlLogPanel.kt**：r2 独有行均为 dev T-HistParam 双面板识别通道统一重构所替换的旧预览路径，dev 超集，取 dev。
- **DataSourceSelector.kt**：双方共有 T-PreviewDocLock2 / T-ExecSilentNoop；dev 的 T-ReverseParenWira WIRA 包裹方案（三轮踩坑收口）**取代** r2 的 T-PreviewSlow F2 拆包方案，取 dev。
- **MpSqlVisualizerBundle(.zh).properties**：dev 侧新增 4 键（literalParamRecovery、installedLive×3），r2 无独有键，取 dev。
- **record/gifkit.py + 7 个 scene_*.py**：dev 侧 41f6edb 修订九构图重录管线（scene 全量重标）晚于 r2 侧修订八，dev 取代，取 dev。

### 4.2 语义融合（两侧各自保留）

- **docs/MEMORY.md（4 块）/ docs/变更记录.md（1 块）**：两侧均在非表格区/台账表各自插行，禁用整表重写，逐块融合两侧条目。沿用第二轮踩坑定例「MEMORY 非表格区不可整取一侧」。

### 4.3 modify/delete

- **docs/发行说明/marketplace-custom-page.html**：dev 已删（e1a9807 单源化，src/main/resources/html 为唯一源）。r2 对 docs 副本的竞品应答增量（FAQ-13、适配承诺等）经核实**已全部镜像到 r2 的 src/main/resources/html 副本**（faq-13 计数 2=2、适配承诺行在），故接受 dev 的删除，不回迁；src 副本走自动合并后需人工核验两侧增量共存。

### 4.4 自动合并区需人工复核

- r2 独有新增 11 文件（dev-support/ci-verify.ps1、5 个开发计划/调研文档、设计规范-弹窗克制红线、2 个测试类）应完整进入合并结果。
- dev 的 f949d8c 将 7 个调研文档迁至 docs/调研报告/，r2 在旧路径 docs/开发计划/ 下有同名新增，合并后须查重防双份留存。

## 五、实施步骤

1. **试合并**：在 mpsv-r2 worktree 执行 `git merge --no-ff dev`，逐文件按第四节策略解决。
2. **合并后核查**：`git diff dev --stat` 确认 r2 独有资产在场；调研文档查重；`grep -r "<<<<<<<" src/ docs/` 确认零残留冲突标记。
3. **全量测试**：跑前按并行铁律 `parallel_tasks.py check`；`./gradlew test --rerun-tasks`（沿回归惯例：先清 build/classes 残留防陈旧类劫持，假失败先证伪再排逻辑）。预期基线 ≥ dev 侧 295 类 / 3788 用例 + r2 独有测试。
4. **构建校验**：`./gradlew buildPlugin` + verifyPlugin 三目标（243/253/262）Compatible。
5. **修复问题**：任何测试失败按「先取证（陈旧类/首跑崩溃两类已知假失败）→ 再排逻辑」处置；源码冲突解决引入的问题就地修复并补测。
6. **文档同步**：变更记录总册 + docs/MEMORY.md 开发计划表 + 本计划 §九 实施记录回填（三处同步）。
7. **提交**：merge commit + 交付同步 commit，格式沿既往 `merge: dev into r2——…` / `docs: T-MergeDevR2Round3 交付同步——…`。

## 六、验收标准

- [ ] 工作树零冲突标记，r2 独有 11 文件与竞品应答资产完整在场
- [ ] 全量测试 0 失败（跳过数为固有 6）
- [ ] buildPlugin 产出 2026.9.11 插件包
- [ ] verifyPlugin 三目标 Compatible
- [ ] 三处文档同步完成

## 七、风险与回退

- 录制脚本取 dev 修订九后，r2 侧录制环境改动（8a48f45 还原 243 常规态）与 dev 的 gifRecord 开关并存——build.gradle.kts 已取 dev 的开关方案，无叠加冲突。
- 若全量测试出现源码融合类失败（预估低风险：源码全部取 dev 单侧，无真融合），按提交回溯定位单侧引入。
- 回退：合并未提交前 `git merge --abort`；已提交则 `git reset --hard ORIG_HEAD`。

## 八、执行偏差留档

- 计划文档先行落盘于主工作区（master worktree 未跟踪文件），交付提交时迁入 r2 分支——与既往合并任务 Wave 0 惯例一致，无实质偏差。
- 台账融合实测较预演多一处去重点：docs/变更记录.md 与 docs/MEMORY.md 的 T-PerfBatch 交付行两侧同文（dev 经 master 吸收），并集时各去重一次，其余与预演零偏差。

## 九、实施记录

- 2026-09-12 10:20 交付完毕。merge commit `73eaa90`：21 文件 40 冲突块按 §四 定案全部解决，实际冲突与 merge-tree 预演零偏差。
- 合并后核查全过：全树零冲突标记残留；r2 独有 11 文件（ci-verify.ps1、5 计划/调研文档、弹窗克制红线、2 测试类）完整在场；调研文档与 docs/调研报告/ 目录零重复（git 重命名检测正确消化 f949d8c 迁移与 r2 旧路径改动的叠加）。
- 全量测试：清 build/classes+build/kotlin 陈旧产物后 `./gradlew test --no-daemon` 一次通过 **298 类 / 3815 用例 / 0 失败 / 0 错误 / 6 跳过 / 0 陈旧结果文件**（dev 基线 3788 + r2 独有用例 27，账目吻合），零修复。
- 构建校验：`./gradlew buildPlugin verifyPlugin --no-daemon` BUILD SUCCESSFUL（9m8s），三目标 **243（deprecated 3）/ 253（deprecated 6）/ 262（deprecated 47）均 Compatible**（scheduled-for-removal 1、experimental 12，均 warning 级），产物 `mybatis-plus-sql-visualizer-2026.9.11.zip`（6.59 MB）。
- 版本号随 dev 定案 2026.9.11；变更记录总册与 docs/MEMORY.md 变更记录行同步交付行。
