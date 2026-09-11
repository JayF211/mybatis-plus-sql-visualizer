# r2-backup-pre-dev-merge-20260910 合入 feature/r2-competition-response 与 dev 增量合并开发计划

- 日期：2026-09-11 13:32
- 任务号：`T-MergeR2DevIntoBackup`
- 目标分支：`r2-backup-pre-dev-merge-20260910`（= `b2fc4ea`）
  - 本次执行 worktree：`C:\Users\USER\WorkBuddy\Worktrees\mybatis-plus-sql-visualizer\r2-backup-pre-dev-merge-20260910-346f4bf8`
  - 该 worktree 当前检出分支 `workbuddy/r2-backup-pre-dev-merge-20260910-346f4bf8`，与目标分支同指 `b2fc4ea`
- 待合入①：`feature/r2-competition-response` = `4ca52e0`（worktree `F:/code/Java/Gitee/SongRenShuo/mpsv-r2`）
- 待合入②：`dev` = `9173e3c`（worktree `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev`）
- 合并顺序：**两段式——先 r2，后 dev**
- 合并方向：两个来源分支均**合入**目标分支（目标分支为集成分支）
- 预演方式：`git merge-tree --write-tree` 预扫 + 临时 worktree 真实 `git merge --no-commit` 双重复核（已执行，临时环境已清理，主 worktree 保持干净）
- 关联上一轮同型合并：
  - `docs/开发计划/20260910-1241-T-MergeDevR2Round2-dev合入r2第二轮增量合并-开发计划.md`（merge `e72dd57`）
  - `docs/开发计划/20260910-1211-T-MergeMasterIntoDev-master合入dev增量合并-开发计划.md`（merge `d5130ab`）

## 一、分叉事实（实测）

| 关系 | merge-base | 独有提交 | 结论 |
|---|---|---|---|
| base ↔ r2 | `b2fc4ea`（即 base 自身） | base 0 / r2 30 | **base 是 r2 的祖先 → 第一段为纯快进** |
| base ↔ dev | `31ecb5f` | base 28 / dev 58 | base 与 dev 分叉较深，dev 侧含量大 |
| r2 ↔ dev | `aebb0ce` | r2 31 / dev 31 | **r2 已含上一轮 dev 合入**，第二段实为增量 |

三条关键结论：

1. **第一段（base + r2）是纯快进**。`git merge-tree --write-tree r2-backup-pre-dev-merge-20260910 feature/r2-competition-response` 干净返回树 `dd3dcdd5`，无冲突；`git merge` 将直接 `Fast-forward`，不产生合并提交。校验锚点：合并后 `HEAD` 应等于 `4ca52e0`。
2. **r2 已包含上一轮 dev 合入**（merge-base = `aebb0ce`，即 T-MergeMasterIntoDev 的交付同步提交；r2 侧 `e72dd57` 那次合并的产物）。因此第二段真正要比对的是 dev 在 `aebb0ce` 之后的 31 个提交，而非全量。
3. **base 线不含 dev 侧资产**：实测 `record/` 在 `b2fc4ea` 为 0 文件、`aebb0ce` 为 11 文件、`dev` 为 12 文件——第一段快进会把 dev 侧录制管线等资产一次性纯新增带入，无覆盖风险。

## 二、冲突预判（merge-tree + 真实试合并双重实测）

内容冲突 **2 文件 2 块**，全部是「最新在上」表格的**顶部插行**同型冲突：

| 文件 | 冲突块位置 | r2 侧（HEAD/ours） | dev 侧（theirs） | 性质 |
|---|---|---|---|---|
| `docs/变更记录.md` | 第 12–17 行 | 2 行：`2026-09-10 dev 合入 r2 第二轮（T-MergeDevR2Round2，merge e72dd57）`、`2026-09-10 生成测试功能 r2 分支跟随 dev 双线一致下线` | 1 行：`2026-09-11 T-PerfBatch 第二轮交付 + T-PerfSweep 性能分析遗留热点清扫` | 同表顶部并集 |
| `docs/MEMORY.md` | 第 221–226 行 | 2 行（同上述两条的 MEMORY 版措辞） | 1 行：`2026-09-11 T-PerfBatch 第二轮交付 + T-PerfSweep 四项遗留热点清扫` | 同表顶部并集 |

**零 delete/modify 冲突、零 add/add 冲突、零 rename 冲突。源码（`src/main`、`src/test`）零冲突。**

试合并输出摘录：

```
Auto-merging docs/MEMORY.md
CONFLICT (content): Merge conflict in docs/MEMORY.md
Auto-merging docs/变更记录.md
CONFLICT (content): Merge conflict in docs/变更记录.md
Auto-merging src/main/resources/messages/MpSqlVisualizerBundle.properties
Auto-merging src/main/resources/messages/MpSqlVisualizerBundle_zh.properties
Automatic merge failed; fix conflicts and then commit the result.
```

## 三、自动合并区关键结论（预演已核实，无需人工干预）

1. **两侧都改的文件只有 4 个**。r2 侧自 merge-base 改动 43 文件、dev 侧改动 73 文件，**交集仅 4 个**：`docs/变更记录.md`、`docs/MEMORY.md`、`MpSqlVisualizerBundle.properties`、`MpSqlVisualizerBundle_zh.properties`。前两个冲突，后两个**自动合并成功**——源码文件两侧零交集，这是本次合并「几乎无痛」的根本原因。
2. **bundle 中英键完全对称**：合并结果 en 与 zh 均为 **553 键**，双向差集为空（无单边键、无重复键）。较上一轮 548 键 +5，为 dev 侧新增键。
3. **`CHANGELOG.md` 自动全取 dev 版**：合并结果含 `## [2026.9.10] - 2026-09-10`、`## [2026.9.9] - 2026-09-09` 两段（r2 侧本轮未动该文件，dev 侧为超集）。
4. **版本号走向 `2026.9.8` → `2026.9.9` → `2026.9.10`**：base 为 `2026.9.8`、r2 为 `2026.9.9`、dev 为 `2026.9.10`，合并结果取 **`2026.9.10`**（`gradle.properties`），与 CHANGELOG 版本段自洽。
5. **`plugin.xml` 保留 r2 版**：r2 侧独有改动、dev 侧自 merge-base 未动该文件，自动合并即 r2 内容 → r2 竞品应答描述文案（冷启动组合拳 / 承接 MyBatis Log Free 老用户 / 弹窗克制）一字不丢。
6. **r2 竞品应答代码资产逐项计数保留**：合并结果 `supportsDeleteLimit` 8 行、`entityResolutionFailed` 4 行、`isWrapperVariable` 13 行，与 r2 基线（`git grep 4ca52e0`）**逐项相等**。
7. **dev 侧删除仅 1 文件**：`docs/images/t-vstack-requirement.png`（T-VStack 需求截图移出 docs，r2 侧无引用面，安全）。r2 侧零删除。
8. **`testgen` 双线下线一致**：合并结果 `src/` 内 `testgen` / `TestAssetGenerator` 命中 **0**，无残留引用。
9. **单侧新增互不影响**：dev 侧新增 17 文件（11 份开发计划/性能分析文档 + 4 个测试类 + `record/pos_frame.py` + 1 份发行说明）；r2 侧新增 9 文件（`dev-support/ci-verify.ps1`、`docs/设计规范-弹窗克制红线.md`、6 份调研/计划/测试计划文档、`DatabaseSchemaResolverFallbackTest.kt`、`XmlMapParamSpecMatrixTest.kt`）。均为单侧新增，无覆盖风险。
10. **`record/` 录制管线**：dev 相对 merge-base 改 10 文件（+332/−57，含新增 `pos_frame.py`），与 r2 零交集。
11. **dev 上游能力在位**（合并结果 `src/main` 计数）：`sequenceNumber` 4、`isModuleBasedLaunch` 3、`awaitPrewarmedTempFile` 3、`lineFragments` 6。

## 四、解决原则

1. 两处表格冲突**一律并集去重 + 日期倒序**：dev 的 `2026-09-11` 行置顶 → r2 的 `2026-09-10` 两行 → 向下接共有区。**不取边、不丢行**。
2. **严禁按文件整取一边**（`git checkout --theirs / --ours`）。`docs/MEMORY.md` 的**非表格索引区两侧各自插过行**（实测：r2 在旧行 35 后插 1 行、dev 在旧行 81 后插 1 行），整取会直接抹掉另一侧改动——**上一轮 T-MergeMasterIntoDev 已因此翻车过一次**（`releaseForPanel` 悬空致编译失败）。
3. 正确的处置姿势：`git checkout -m <file>` 恢复三方**自动合并结果**，然后**只替换冲突块**；或用 `git merge-file` 复现自动合并输出后定点替换。
4. 自动合并区（bundle / CHANGELOG / gradle.properties / plugin.xml / 源码）只做**语义核验**，不重写。

## 五、逐块解决方案

### 5.1 `docs/变更记录.md`（冲突块第 12–17 行）

- r2 侧（HEAD）2 行：
  - `| 2026-09-10 | **dev 合入 feature/r2-competition-response 第二轮（T-MergeDevR2Round2，merge e72dd57）**…|`
  - `| 2026-09-10 | **生成测试功能 r2 分支跟随 dev 双线一致下线（T-MergeDevR2 后续，用户决策）**…|`
- dev 侧 1 行：
  - `| 2026-09-11 | **T-PerfBatch 第二轮交付 + T-PerfSweep 性能分析遗留热点清扫…**|`
- **定案**：删除 `<<<<<<< HEAD` / `=======` / `>>>>>>> dev` 三行，按 **2026-09-11（dev 1 行）→ 2026-09-10（r2 2 行）** 顺序落位。紧随其后的 `| 2026-09-10 | **master 合入 dev 增量合并（T-MergeMasterIntoDev，merge d5130ab）**…|` 属共有行，保持原样不动。
- 表头行与分隔行沿用 r2 侧（避免无谓的内容宽度改写）。

### 5.2 `docs/MEMORY.md`（冲突块第 221–226 行）

- 同型：r2 侧 2 行 vs dev 侧 1 行。
- **定案**：与 5.1 完全一致的并集顺序；**但禁止「表头取一侧 + 表行并集重写整文件」的脚本做法**，必须先 `git checkout -m docs/MEMORY.md` 拿到真实自动合并结果，再只替换那一个冲突块（理由见第四节第 2 条）。

### 5.3 自动合并区核验（只核对，不施工）

| 核验项 | 期望值 |
|---|---|
| `gradle.properties` | `version=2026.9.10` |
| `CHANGELOG.md` | 含 `[2026.9.10]`、`[2026.9.9]` 两段 |
| bundle en / zh | 各 553 键、双向差集为空 |
| `plugin.xml` | 与 r2 版逐字节一致 |
| r2 资产计数 | `supportsDeleteLimit` 8、`entityResolutionFailed` 4、`isWrapperVariable` 13 |
| dev 资产计数 | `sequenceNumber` 4、`isModuleBasedLaunch` 3、`awaitPrewarmedTempFile` 3、`lineFragments` 6 |
| r2 文档资产 | `docs/设计规范-弹窗克制红线.md` 在位、`SqlPreviewSpecMatrixTest` / `XmlMapParamSpecMatrixTest` 在位 |
| `testgen` 残留 | 0 |

## 六、实施步骤

### Wave 0 前置（本计划）

- 生成并提交本计划文档。
- **打回滚锚点 tag**：`git tag backup/pre-r2dev-merge-20260911 b2fc4ea`
  - 必要性：第一段是快进，分支指针会**离开** `b2fc4ea`，此后回滚无 SHA 可依；同时该 tag 与 worktree 检出分支 `workbuddy/r2-backup-pre-dev-merge-20260910-346f4bf8`（同指 `b2fc4ea`）互为双锚点。
- 确认三处 worktree（目标 / r2 / dev）均无未提交改动，避免带入脏工作树。
- 确认并行任务登记表无占用（沿用前两轮：`T-Merge*` 任务名 + 独立沙盒）。

### Wave 1 第一段——合入 r2（快进）

- 在目标 worktree 执行：`git merge feature/r2-competition-response`
- 预期：`Fast-forward`，无冲突、无合并提交。
- 校验：`git rev-parse HEAD` == `4ca52e0`。
- 决策点：若希望保留一条显式合并提交（体现「两源一次性整合」的语义），改 `git merge --no-ff`。**本计划默认 FF**（无信息量的合并提交不入库），如需 `--no-ff` 请在执行前确认。

### Wave 2 第二段——合入 dev（真实冲突在此）

- 执行：`git merge dev`
- 预期：复现第二节的 **2 文件 2 冲突块**，与预判一致。
- 按第五节处置：
  - `docs/变更记录.md`：并集 3 行按日期倒序落位。
  - `docs/MEMORY.md`：`git checkout -m` 恢复自动合并结果 → 只替换冲突块。
- 校验：`git diff --name-only --diff-filter=U` 为空；两文件 `grep -c '^<<<<<<<'` 为 0。

### Wave 3 语义清扫

- bundle zh/en 键对称（553/553，双向差集空）。
- r2 资产计数（8/4/13）与 dev 资产计数（4/3/3/6）复跑，与预演值比对。
- `testgen` 全树零残留；`plugin.xml` 与 r2 逐字节比对。
- `CHANGELOG.md` 段落顺序与 `gradle.properties` 版本号复核。
- **语义风险点**（源码零交集，重点在时序与共享设置）：
  1. dev 侧 `SqlLogEntryBuffer` 三出口拆分（T-PerfBatch 第二轮）改变了列表通知时序，需确认 r2 侧矩阵测试（`SqlPreviewSpecMatrixTest` / `XmlMapParamSpecMatrixTest`）与预览链路测试不受影响。
  2. 两侧各自新增的测试共存后是否互相污染共享设置（`PluginSettings` 显示模式 / 行号 / 开关态）——bundle +5 键在 zh 侧无遗漏。

### Wave 4 全量测试与构建

- `./gradlew test --no-daemon`（独立沙盒）→ **红线 0 失败**。
  - 预期规模：r2 基线 3720（合并前）+ dev 侧新增（dev 基线 3696）去重 ≈ **3700–3760** 量级，以实跑为准（测试类数同步增长）。
  - 已知假失败两类预案（沿用前两轮）：① K2J 陈旧类劫持——`build/classes/java/main` 残留旧类导致字节码级假红，按包清除后复跑；② clean 首跑 worker 崩溃——原样重跑一次。
- `./gradlew verifyPlugin --no-daemon` → 三目标（IU-243 / IU-253 / IU-262）**Compatible**；`buildPlugin` 出产物 `build/distributions/mybatis-plus-sql-visualizer-2026.9.10.zip`。

### Wave 5 定稿与同步

- 合并提交信息（第二段）建议：
  `merge: feature/r2-competition-response + dev into r2-backup-pre-dev-merge-20260910——r2 快进 30 提交（含上轮 dev 合入整簇与竞品应答资产）+ dev 31 提交（T-PerfBatch 第二轮/T-PerfSweep/T-PreviewSlow/T-HibDualCapture/T-VersionUtil/GIF 录制环境）；2 文档冲突并集，源码零冲突`
- 文档同步：`docs/变更记录.md` 顶插本次交付行、`docs/MEMORY.md` 变更记录行、本计划 §九 实施记录回填。
- **不主动 push**（沿用前两轮约定）。
- runIde 真机抽验留人工：dev 面（日志行号 / 竖向排列 VALUES 列名标注 / 预览加载速度）+ r2 面（冷启动首屏 / 承接老用户文案 / 弹窗克制）。

## 七、风险与回滚

| 风险 | 等级 | 处置 |
|---|---|---|
| 第一段快进使分支指针离开 `b2fc4ea`，丢失原位 | 中 | Wave 0 先打 tag `backup/pre-r2dev-merge-20260911`；worktree 检出分支 `workbuddy/…-346f4bf8` 同指 `b2fc4ea` 为第二锚点 |
| `docs/MEMORY.md` 非表格区被「整表重写」抹掉 | **高** | 强制 `git checkout -m` + 只替冲突块；第四节第 2 条为硬约束 |
| 版本号语义：目标分支变为 `2026.9.10` | 低 | 与 dev 一致是最省事口径（避免又一处内容/EOL 分叉）；若该分支另有独立发版计划，发版时重新定号，本次不做额外处理 |
| bundle 出现单边键 | 中 | Wave 3 双向差集检查；本预演已实测为空（553/553） |
| 全量测试规模膨胀至 3700+ | 低 | 独立沙盒 + 两类已知假失败预案 |
| 中途回滚 | — | 第二段：`git merge --abort`；第一段快进后：`git reset --hard b2fc4ea`（或 `backup/pre-r2dev-merge-20260911`） |

## 八、验收标准

- [ ] 第一段快进成功，`HEAD` == `4ca52e0`
- [ ] 第二段 2 处文档冲突并集完成：无丢行、无重复行、日期倒序、无冲突标记残留
- [ ] r2 竞品应答资产在位：`plugin.xml` 文案与 r2 逐字节一致、`docs/设计规范-弹窗克制红线.md`、金样矩阵测试、`supportsDeleteLimit`/`entityResolutionFailed`/`isWrapperVariable` 计数 8/4/13
- [ ] dev 上游能力在位：`sequenceNumber`/`isModuleBasedLaunch`/`awaitPrewarmedTempFile`/`lineFragments`、`record/` 12 文件、bundle 553×2 键对称
- [ ] `testgen` 零残留；版本号 `2026.9.10`；CHANGELOG 含 `[2026.9.10]` 与 `[2026.9.9]`
- [ ] 全量测试 0 失败
- [ ] verifyPlugin 243/253/262 三目标 Compatible，`buildPlugin` 出包
- [ ] 变更记录 / MEMORY / 本计划三处同步；回滚 tag 已打

## 九、实施记录（回填）

- 待实施后回填（Wave 0–5 逐段记录实际数据与与预判的偏差）。

## 附：预演命令与实测输出（可复现）

```bash
# 分叉量（实测）
git rev-list --left-right --count r2-backup-pre-dev-merge-20260910...feature/r2-competition-response  # 0  30
git rev-list --left-right --count r2-backup-pre-dev-merge-20260910...dev                               # 28 58
git rev-list --left-right --count feature/r2-competition-response...dev                                 # 31 31
git merge-base r2-backup-pre-dev-merge-20260910 feature/r2-competition-response  # b2fc4ea
git merge-base r2-backup-pre-dev-merge-20260910 dev                              # 31ecb5f
git merge-base feature/r2-competition-response dev                               # aebb0ce

# 冲突预演（临时 worktree，执行后已清理）
git worktree add --detach /tmp/mpsv-merge-check b2fc4ea
cd /tmp/mpsv-merge-check
git merge --no-commit --no-ff feature/r2-competition-response   # Automatic merge went well（可快进，无冲突）
git commit -m "tmp"
git merge --no-commit --no-ff dev                                # 冲突 2 文件：docs/MEMORY.md、docs/变更记录.md
git diff --name-only --diff-filter=U
git merge --abort
```

- 预演产物核对：bundle 553/553 键对称、版本 `2026.9.10`、r2 资产 8/4/13 与基线相等、`testgen` 残留 0。
