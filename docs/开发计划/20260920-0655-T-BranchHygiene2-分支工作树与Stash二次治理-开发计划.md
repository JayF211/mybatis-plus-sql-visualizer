# T-BranchHygiene2 分支工作树与 Stash 二次治理 — 开发计划

- 日期：2026-09-20 06:55
- 任务编号：T-BranchHygiene2（全分支功能全景审计第二轮调研报告 §6 劣势 4 / §8 O-45）
- 来源：O-22（T-BranchHygiene）2026-09-17 宣布闭环后两天内，分支拓扑再次积累：3 条新分支（其一无远端备份）、5 条旧 stash、1 个悬空 stash 引用、dev 分支唯一独占增量为 3 篇架构文档未回master
- 性质：工程治理（git 引用 / worktree / stash 清理 + 3 篇文档重放，源码零改动）
- 实施方式：主工作树直接操作，不建任务分支
- 前置：①并行会话核对——8 个 worktree 全部处于检出状态，删除/重置任何 worktree 前先确认对应会话已收工（并行登记表/进程核对），`t-gif-rec-mcp` 为活动录制工作台尤其如此；②本计划全部 git 实测快照点为 master `b00ab87`（2026-09-20 06:15），实施时若 HEAD 已大幅推进，**先重跑 §二 的核实命令再动手**（并行会话高频推进，快照易过时）

---

## 一、背景与依据

首轮全景审计（20260916-0635）把分支治理立项为 O-22，两天后闭环；但 09-18 起的三天里拓扑再次膨胀：T-GifRec-MCP 录制工作台（13 个独有提交、无远端）、t-settings-truth / t-testdb-driver 两条任务分支滞留、5 条 M30/M31 时代的 stash 未清（其中 1 条挂在已删除分支上成悬空引用）、dev 分支带着唯一一条架构文档提交与 master 分叉。首轮报告的教训原话仍适用：「治理不是一次性动作」——因此本计划不追求一次性清零，而是把**当前**可判定的对象全部处置掉，并为「活动工作台」类资产（t-gif-rec-mcp）建立留底与收口条件，避免治理债以三天一轮的速度再生。

本计划的每一项处置都以 blob 级 / merge-base 级实测为依据（§二），不做「看起来合了就删」的推断式清理。

## 二、现状核实（实测，2026-09-20 06:15，HEAD=b00ab87）

| 对象 | 实测状态 | 依据 |
|---|---|---|
| t-perfsweep4 `47c1209` | master 祖先，完全合入 | `git merge-base --is-ancestor` 通过 + 三点 diff 0 行 |
| t-settings-truth `89a4651` | master 祖先，完全合入（`6b3e31e` 显式 merge） | 同上；无远端但内容在 master，无损 |
| feature/dialect-auto-infer `a481a4f` | 6 独有提交 = 5 个 T-DAI 实提交 + 1 merge；已被 master **同 subject 异 SHA** 重放提交（`29dd055`/`4f2de15`/`c9b93dc`/`effc587`/`be23010`）收编；`DialectInference.kt`/`DialectInferenceTest.kt` blob 一致，其余 3 文件 master 侧多 +408/−42 行（更完整形态） | subject 逐字比对 + blob 比对 |
| dev `3b20a93` | 唯一独占提交 = `docs: 架构设计三篇同步近期演进`（`docs/架构设计/01/02/03` 三篇，+33/−27）；代码零独占；基于旧文档底稿，落后 master 155 提交——**回收须重放（cherry-pick），merge 必冲突** | `git log master..dev` + 关键词实测（master 三篇「智能推断」0 命中） |
| feature/kotlin-to-java `056032c` | 活跃移植主线（src/main 557 java / 0 kt，锚点 b9aeeba），落后 1003、本地领先远端 15（锚点文件自述「未 push」） | `.port-sync` blob 6449484 + `git branch -vv` |
| t-gif-rec-mcp `0ec5ef9` | 13 独有提交 / 落后 2 / **无远端**；新增 `record/scene_m1_mcp.py`（406 行，M1 MCP 演示 7 分镜）；`build.gradle.kts` gifRecord 默认值 false→true（**分支自述「合回 master 前必须还原」**）；`AcpAgentId.kt` 桩从 `ml/llm/agents/acp/api` 迁至平台包 `com.intellij.platform.acp`（master 仍留旧位置）；另有 5 个 mcpStub/ACP 测试文件修改 | `git diff --name-status master...t-gif-rec-mcp` + 脚本头注释 |
| t-testdb-driver `741ef6c` | 核心内容（`DbDriverPipelineTest.kt` / `DbIntegrationSeedSupport.kt`）blob 一致已进 master；残余 = 4 个 DB 集成类 tearDown 补 `clearCachedTempPsiFileForTest()` + 记录文档原版；**7 项断言失败为有意挂起的裁决点**（记录文档 §四明写「不回合、不做掩盖」） | blob 比对 + `docs/调研报告/20260918-testDatabase驱动管线打通-记录.md` |
| stash ×5 | `stash@{0}`~`@{3}` On master（sandbox-execcopyfix / Kotlin→Java 转换波备份 / diagram-refactor-wip / T-M31 log 包中间态）；`stash@{4}` 挂在已不存在的 `T-M30-idea243-compat` 分支上（悬空） | `git stash list` |
| worktree ×8 | 全部检出中（主仓 + mpsv-gif-rec / mpsv-settings-truth / mpsv-tdd / mpsv-tps4 / mpv-dialect-auto-infer / -dev / -kotlin-to-java） | `git worktree list` |

## 三、方案（处置表）

| # | 对象 | 动作 | 条件 |
|---|------|------|------|
| 1 | t-perfsweep4 | 删本地分支 + 删 origin 远端分支 + remove worktree `mpsv-tps4` | 无（完全合入） |
| 2 | t-settings-truth | 删本地分支 + remove worktree `mpsv-settings-truth` | 无（完全合入，无远端） |
| 3 | feature/dialect-auto-infer | 删本地分支 + 删 origin 远端分支 + remove worktree `mpv-dialect-auto-infer` | **须用户明示授权**（既有登记「待用户授权清理」；授权前本项不动） |
| 4 | dev | ① 主仓 `git cherry-pick 3b20a93` 重放三篇架构文档（冲突预期低——纯 docs/架构设计 路径；若冲突按 master 现状改写合并双方内容）；② dev worktree 内 `git merge --ff-only master` 后确认与 master 一致；③ `git push origin dev` 消掉 52 滞后 | 步骤① 完成并核实三篇内容落 master 后才做②③ |
| 5 | t-gif-rec-mcp | ① `git push -u origin t-gif-rec-mcp` 留底（13 提交当前仅存本地）；② 录制收口后按脚本自述纪律执行：还原 `build.gradle.kts` gifRecord 默认值 → 评估 cherry-pick `AcpAgentId` 迁包提交回 master（master 仍留旧位置 `ml/llm/agents/acp/api`，迁移属平台包归属修正，是否回流由实施时核对其与 mcpStub 源集的兼容性再定）→ 分支并入 master 后删除。**收口动作不在本计划内实施**，本计划只负责留底与登记收口条件 | ① 立即执行；② 挂收口条件 |
| 6 | t-testdb-driver | ① `clearCachedTempPsiFileForTest()` tearDown 修复（4 集成测试文件）cherry-pick 回 master——属非掩盖性改进，记录文档明写可回流；② 7 项断言失败的处置（修复 / skip / 撤管线三选一）**移交主调度与用户裁决**，本计划不改断言、不加 skip；③ 裁决前分支与 worktree `mpsv-tdd` 保留 | ① 实施时先确认 master 上对应文件形态未漂移（快照点 blob 一致，但并行推进需重验） |
| 7 | stash ×5 | 逐条 `git stash show -p` 核对内容 → 对应分支/提交均在 master 历史（M30/M31 时代交付物）且无独占形态 → `git stash drop` 逐条删除（悬空引用 `stash@{4}` 一并）；**删除前把 5 条的 show 摘要贴进 §七 实施记录留档** | 任一条 show 出「master 不存在的独占改动」即保留并登记，宁留勿删 |
| 8 | 全局终态核对 | `git worktree list` 收敛至 6 个（主仓 + gif-rec + tdd + dev + k2j + 其余活动树）；`git branch -v` 收敛至 5 条（master/dev/k2j/t-gif-rec-mcp/t-testdb-driver + 用户授权项视情况）；`git stash list` 为空 | §六 实施顺序末步 |

## 四、验收标准

1. t-perfsweep4 / t-settings-truth 本地分支、远端分支（如有）、worktree 三者零残留；`git log --all --oneline | grep <分支头 SHA>` 确认提交仍经 master 可达（删分支不丢历史）。
2. feature/dialect-auto-infer 仅在用户授权后删除，授权记录（谁、何时）写入 §七。
3. master 上三篇架构文档含「智能推断」「T-TS-13」「452 个测试类」关键词（`grep -c` 非零）；origin/dev 与 dev 本地一致且 dev 与 master 内容等价（`git diff master dev` 为空或仅差 dev 不再需要的中间历史）。
4. t-gif-rec-mcp 有远端对应分支（`git branch -r | grep gif-rec` 非零）；其收口条件（gifRecord 还原 + AcpAgentId 评估）登记在 §七。
5. t-testdb-driver 的 tearDown 修复在 master（4 个集成测试文件含 `clearCachedTempPsiFileForTest()` 调用），7 失败项裁决状态登记在 §七（裁决可后置，不阻塞本计划其余项）。
6. stash 清零，5 条 show 摘要留档 §七。
7. 全程零源码改动（`git diff --stat` 仅 docs/架构设计 三篇 + 本计划文档 + 变更记录台账）。

## 五、风险

1. **并行会话争用**：8 个 worktree 全检出，任何删除/重置前先核对并行登记表与对应会话状态；发现某树有未提交在途改动（`git -C <worktree> status --porcelain` 非空）即跳过该对象并登记，不代清。dev worktree 的在途残留按既有定例先 `git diff` 逐行比对再定去留（master 已含更完整形态时丢弃是安全的）。
2. **worktree 删除长路径坑**（Windows 实证）：`git worktree remove` 可能报 Filename too long，兜底 PowerShell `Remove-Item -Recurse -Force` 后再 `git worktree prune`。
3. **cherry-pick 重放冲突**：dev 三篇文档基于旧底稿，若与 master 侧 155 提交内的文档演进冲突，以 master 现状为基线手工合并双方内容（重放的价值在叙述性增量，不在逐字保留）；冲突超过三处时改为按 dev 提交内容重写三篇对应小节。
4. **stash 误删**：先 show 留档再 drop；判定标准从严（有独占改动即留）。
5. **t-gif-rec-mcp 留底后仍是被检出活动树**：push 不影响录制工作继续；不要在录制会话活动期间动它的 worktree。

## 六、实施顺序

1. 核对并行登记表 → 逐 worktree `status --porcelain` 摸底（风险 1）。
2. 对象 1/2（无损删除两条完全合入分支）。
3. 对象 7（stash show 留档 → 清理）。
4. 对象 4（dev 三篇重放 → ff 归一 → push）。
5. 对象 5①（t-gif-rec-mcp push 留底）+ 对象 6①（tearDown 修复 cherry-pick，先重验 blob）。
6. 对象 3（**待用户授权**，授权后执行；未授权则保留并登记）。
7. 对象 8 终态核对 + §七 回填 + `docs/变更记录.md` 台账行。

依赖关系：3 独立于其余全部；4 与 5/6 可并行但共用主工作树，串行执行避免 index 争用。

## 七、实施记录（实施后回填）

| # | 对象 | 结果 | 提交/SHA | 偏离计划之处 |
|---|------|------|----------|--------------|
| | | | | |

## 八、变更记录

- 2026-09-20 06:55 立项（第二轮全景审计报告 §8 O-45 承接；现状核实 §二 全部为 git 实测，快照点 b00ab87）。
