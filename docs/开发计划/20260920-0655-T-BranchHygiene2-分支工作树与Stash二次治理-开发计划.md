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

## 七、实施记录（2026-09-22 回填，T-Round10-08）

> **§二 现状核实快照点 `b00ab87`（2026-09-20 06:15）整体作废**——实施时 HEAD 已是 `t-round10` `3f7853b`（第十轮批次第 3 波提交），§二的 8 个 worktree / 5 个待删分支 / 3 条新分支全部过时。以下全部为 **2026-09-22 本会话实测**，命令与读数逐条可复核。
>
> **本任务性质**：用户未逐条授权破坏性 git 操作（批次需求「可逆性红线」段），故**只执行只读登记**——`git branch -a` / `git worktree list` / `git stash list` 三类清单 + stash 逐条 show 留档。**零 drop、零删除、零 push、零重置**。

### 7.1 （**本节所有计数均为实施时点读数，`HEAD=3f7853b`；独立复核 ㉛ 实测复核时已漂移为分支 7 / worktree 7 —— 引用前请重跑 git 实测**）三类清单实测（2026-09-22）

| # | 对象 | 结果（实测值） | 提交/SHA | 偏离计划之处 |
|---|------|----------------|----------|--------------|
| 1 | 只读登记 · 分支 | 本地 **5** 条：`dev` `feature/kotlin-to-java` `master` `t-prewarm-late-recover` `t-round10`；远端 **3** 条：`origin/dev` `origin/feature/kotlin-to-java` `origin/master` | 未提交（只读） | §二 记「3 条新分支」、任务说明记 4 条——实测 **5** 条（多出 `t-prewarm-late-recover`，并行会话在途分支） |
| 2 | 只读登记 · worktree | **5** 个：主仓 `[t-round10]` / `mpsv-agent-config-locate` `[master]` / `mpsv-prewarm-late-recover` `[t-prewarm-late-recover]` / `mybatis-plus-sql-visualizer-dev` `[dev]` / `-kotlin-to-java` `[feature/kotlin-to-java]` | 未提交（只读） | §二 记 8 个、任务说明记 3 个——实测 **5** 个 |
| 3 | 只读登记 · stash | **5** 条仍在（`stash@{0}`~`@{4}`），逐条 show 留档见 7.2 | 未提交（只读） | 与 §二/任务说明的「5 条」一致 |
| 4 | 推送债 | `origin/master..master` = **4**（`origin/master` = `8118a7b`，本地 `master` = `e8ee771`）；`origin/dev..dev` = **0**（两侧均 `d3ef121`）；`origin/feature/kotlin-to-java` = `1c3dce0` = 本地同名分支 | 未推送任何东西 | 任务说明记 0/0、盘点 :3/:17/:103 记「master 9」——实测 **4/0**。master 的 4 条来自并行会话 `mpsv-agent-config-locate` 工作树（`[origin/master: ahead 4]`），**非本任务所为**；本任务 push 次数 = 0 |
| 5 | 对象 4（dev 三篇架构文档重放） | **已完成**：`dev..master` = 0，三篇已在 master（`grep -c 智能推断 docs/架构设计/01-*.md` = 5，非零） | 未提交（只读确认） | §三 对象 4 的①cherry-pick 无需执行——随前序合流带入，本任务**只读确认零改动** |
| 6 | 对象 1/2/3/5/6 目标分支与 worktree | **全部已不存在**：`t-perfsweep4` / `t-settings-truth` / `feature/dialect-auto-infer` / `t-gif-rec-mcp` / `t-testdb-driver` 在 `git branch -a` 与 `git ls-remote origin` 中均零命中 | 未提交（本任务零操作） | §三 处置表 1/2/3/5/6 整体作废——已由其他会话完成，本任务**零删除** |
| 7 | n6-join-fk-nullref | 分支与工作树均**不存在**（`git branch -a --list '*n6*'` 空、`git worktree list` 无该树）；头提交 `03f6d25` 已是 master 祖先（`git merge-base --is-ancestor` 退出码 0） | 未提交（本任务零清理） | §一 记「已删除分支」、盘点 :73 记「分支与工作树未删」——实测**已不存在、非本任务所为**。登记为「0 次清理」而非幽灵待办 |
| 8 | 对象 5 的 `t-gif-rec-mcp` 头提交 | `0ec5ef9` 已是 master 祖先（`git merge-base --is-ancestor` 退出码 0）→ 其分支删除未丢历史 | 未提交（只读） | 计划 §三 对象 5①「push 留底」已无对象 |
| 9 | 本任务实际落点分支 | HEAD = `t-round10` = `3f7853b`（`git rev-parse --abbrev-ref HEAD`）；`t-round10` 内容与 `dev`/`master` 均已分叉（master 已被并行会话推进到 `e8ee771`） | 本任务文档改动提交在 `t-round10` | 核验报告记「HEAD=master d3ef121」**已过时**；批次需求「当前分支 t-round10」属实。登记以免后续会话按错误分支核对 |
| 10 | 共享文档回填（本任务兼任唯一写者） | `CHANGELOG.md` `[Unreleased]` + `docs/变更记录.md` + `docs/MEMORY.md` + 两份调研报告回填，条目与提交号见 §八 | 本任务提交 | 见 7.4 与 §八 |

### 7.2 stash 逐条留档（`git stash show --stat` / `-u --stat` / `-p` 实测，2026-09-22）

| stash | 自述 | 内容读数 | SHA（**stash 自身 / base / 未跟踪父**——独立复核 ㉛ 订正：原列名「父提交」把 stash 提交自身当首项列出，照列取值会得到相反读数） | 处置 |
|---|---|---|---|---|
| `stash@{0}` | `On master: sandbox-execcopyfix line (other session in-flight)` | 1 file changed, **2 insertions(+)**, 0 deletions（`build.gradle.kts`） | `32c3a62` / `707e76a` / `9fc9e9a` | **不 drop**（两条理由叠加：自述即「other session in-flight」，批次红线明禁「删除任何被并行会话占用的资源」；且 `build.gradle.kts` +2 行不在 master，按计划「show 出 master 不存在的独占改动即保留」判据亦保留） |
| `stash@{1}` | `On master: wip: Kotlin->Java 转换波(actions/converter/execute/reverse/share) — 已迁移至 feature/kotlin-to-java 工作树，此处仅作备份` | 27 files changed, **3 insertions(+), 9810 deletions(-)**；加 `-u` 为 65 files changed, 10060 insertions(+), 9810 deletions(-)。被删的 `.kt` 含 `reverse/SqlToJavaConverter.kt` -347、`reverse/SqlTokenizer.kt` -116、`reverse/WrapperFormat.kt` -50、`share/SqlShareManager.kt` -130，**master 侧这些 .kt 仍在** → 形态不一致 | `37502e2` / `2521d33` / `a01f5d4` / `a8d6ff7` | **不 drop**（计划 §三 对象 7「宁留勿删」判据：show 出 master 不存在的独占改动） |
| `stash@{2}` | `On master: diagram-refactor-wip: diagram renderer restructuring` | 10 files changed, **289 insertions(+), 600 deletions(-)**；含 `CHANGELOG.md` 17 行、`build.gradle.kts` 38 行、`ui/diagram/DiagramPanel.kt` 206 行，并**删除** `DiagramRenderTask.kt`/`SvgRasterizer.kt` 及其测试（**独立复核 ㉛ 订正：原写「master 侧仍在」经实测证伪**——`git cat-file -e master:<这三个路径>` 全部报 `does not exist`，删除发生在 `c58fc2b`（2026-08-26「JOIN图渲染 — PlantUML + Batik 替换为自绘Swing引擎」），且在实施时点的 HEAD 上就已不存在） | `92e35d6` / `44459e9` / `73eb293` | **不 drop**（**订正后的判据**：该 stash 的 `JoinDiagramGenerator.kt`/`DiagramPanel.kt`/`build.gradle.kts`/`CHANGELOG.md` 等 blob 与 master 侧**均不同**，确有 master 不存在的独占内容——理由成立，但原文引的「删除面 master 侧仍在」不成立） |
| `stash@{3}` | `On master: T-M31 并行会话 log 包中间态（LogSqlRestorer 未创建，阻塞编译）` | **`git stash show --stat` 输出为空**（该 stash 只含未跟踪文件，内容在其第三父 `9d8b3ee`）；须用 `git stash show -u --stat` = 2 files changed, **144 insertions(+)**（`log/LogSqlModels.kt` +35、`log/MyBatisLogParser.kt` +109）。**blob 比对**：两文件 master 侧均存在但 **blob 不同**（stash `f6220e6`/`a2ee4ae` vs master `4ba3d85`/`ad13d01`）→ 有独占形态 | `1c4e2a4` / `ad537dc` / `f9137cc` / `9d8b3ee` | **不 drop**（独占改动；且按 `--stat` 字面判据会误判为空——本行即该坑的留档） |
| `stash@{4}` | `On T-M30-idea243-compat: T-M31~T-M35 并行会话文档（docs/MEMORY.md + 开发计划）` | 1 file changed, **1 insertion(+)**（`docs/MEMORY.md` 索引行「20260815-1900-日志SQL还原与Wrapper实时提示及P2深化-开发计划.md」）。该索引行 **master 侧已存在**（`grep -c` = 1），故无独占内容；stash 挂在**已不存在的分支** `T-M30-idea243-compat` 上（悬空引用） | `4e42129` / `fac8496` / `e28f056` | **不 drop**（内容无独占，按判据本属可 drop 项；但 drop 是本任务唯一不可逆动作——`drop` 后 `refs/stash` reflog 条目消失、只能靠 `git fsck --unreachable` 捞回，故本波**只留档不 drop**，留给用户授权后的一轮） |

**终态：`git stash list` 仍为 5 条，drop 次数 = 0。** 5 条 show 摘要已全部留档于上表（含 `-p` 已逐条核读）。

### 7.3 禁止项登记（逐条零执行，附现状实测值）

| 禁止项 | 执行次数 | 现状实测值 |
|---|---|---|
| 删除任何远端分支（`origin/*`） | **0** | `git ls-remote origin` 的 `refs/heads/*` = `dev`/`feature/kotlin-to-java`/`master` 三条，与实施前一致 |
| 任何 push | **0** | `origin/master` = `8118a7b`、`origin/dev` = `d3ef121`、`origin/feature/kotlin-to-java` = `1c3dce0`——与本地对应分支的关系未因本任务改变（master 的 ahead 4 系并行会话产生） |
| 删除或移动其他会话占用的 worktree | **0** | `git worktree list` 仍 5 个，`mpsv-agent-config-locate` / `mpsv-prewarm-late-recover` / `-dev` / `-kotlin-to-java` 四处原样未动 |
| 强制重置任何分支 | **0** | 未执行任何 `reset --hard` / `checkout -f` / `branch -f` |
| 删除被并行会话占用的资源 | **0** | `stash@{0}`（自述 other session in-flight）保留；工作树里并行会话在途的 2 个已跟踪文档改动（`docs/开发计划/20260916-1730-待完成开发计划全景盘点.md`、`docs/验收文档/20260920-待分析问题清单.md`）与 3 个未跟踪项（`.zcodeignore`、两份 20260922 计划文档）一律未动 |
| n6-join-fk-nullref 相关清理 | **0** | 对象已不存在（见 7.1 #7），**非本任务所为**；头提交 `03f6d25` 已是 master 祖先，无历史丢失 |
| dev 三篇架构文档的任何改动 | **0** | 只读确认 `dev..master` = 0、三篇在 master（见 7.1 #5），未做任何 cherry-pick / 改写 |

**证据方法说明（订正任务说明给的 `git reflog` 口径）**：裸 `git reflog` 是 HEAD 的 reflog，push 不动 HEAD，且被删分支的 reflog 文件已不存在（`.git/logs/refs/heads/` 只剩 dev / feature / master / t-round10），故它证不了「0 次 push」。本任务改用**远端权威源前后对照**：`git ls-remote origin`（上表读数）——远端 ref 值可逐条比对。

### 7.4 共享文档回填（本批次唯一写者）

本批次 8 个代码任务的计划文档中 **7 份**要求「CHANGELOG `[Unreleased]` + 变更记录行 + 报告落地备注」（**独立复核 ㉜ 订正：原文写「8 份均要求」，与本段下文「8 份中 7 份点名」自相矛盾**；`20260921-2306-T-PerfSweep6` 对两者均 0 命中，其条目由本任务据该计划 §八 逐项落地表 + 提交 `3f7853b` 补写）；这些文件被多方共同要求同步；集中到本任务统一写入，条目与真实提交号见 §八。**实际落点与任务说明的差异**（逐条实测）：

- CHANGELOG / `docs/变更记录.md`：8 份计划中 **7 份**点名（`20260921-2306-T-PerfSweep6` 对两者均 **0 命中**，其条目由本任务据该计划 §八 逐项落地表 + 提交 `3f7853b` 补写）。
- `docs/MEMORY.md`：9 份计划文档对它的命中数**均为 0**（实测 `grep -c`），「台账同步」由本任务按该文件自身的「开发计划（时间倒序）」索引约定执行。
- 第十轮报告 §8：#50/#51 → T-Round10-02；#52 → T-Round10-01；#54 → T-Round10-03；#55 → T-Round10-05；#61 → T-Round10-06；另 §7.2 Z-30 → T-Round10-04。
- 性能第九轮报告 §5：18 项发现点全表逐项标注（T-PerfSweep6 计划 §八 8.1/8.5 落地表为准），发现 17 定案不改留痕。
- **写集外未落点（如实登记，本任务未越界写）**：T-Round10-09 计划 `20260920-1148-T-MlLlmSignatureGuard-…md:83` 与 `:131` 要求「第九轮报告 §9.4 O-41 落地备注」与「ACP 验收文档 §七-7 标注」——`docs/调研报告/20260920-0128-竞品横向对比与功能建议-第九轮调研报告.md` 与 `docs/验收文档/20260919-2340-ACP传输绕行与泄漏源修复及MCP工具命名收敛-验收文档.md` **两份文件均不在本任务写集**，故未写；该两项落地备注目前处于**无归属**状态，须由主调度补派（详见 §八 偏离登记）。

## 八、变更记录

- 2026-09-20 06:55 立项（第二轮全景审计报告 §8 O-45 承接；现状核实 §二 全部为 git 实测，快照点 b00ab87）。
- 2026-09-22 实施（T-Round10-08，分支 `t-round10`）：**只做可逆部分**——三类清单只读登记（分支 5 / worktree 5 / stash 5）+ stash 逐条 show 留档，**零 drop、零删除、零 push、零重置**（禁止项逐条登记见 7.3）。§二 快照点 `b00ab87` 整体作废，§三 处置表对象 1/2/3/5/6 目标分支全部已不存在（其他会话完成）、对象 4 已完成、对象 7 只留档不 drop。兼任本批次共享文档唯一写者：CHANGELOG `[Unreleased]` 8 条 + `docs/变更记录.md` 9 行 + MEMORY 索引与台账 + 第十轮报告 §8（#50/#51/#52/#54/#55/#61 六项）与 §7.2 Z-30 + 性能第九轮报告 §5 全 18 项。**已知偏离**：任务说明与验收给的清单计数（分支 4 / worktree 3）已被实测推翻（5 / 5）；写集外两处落地备注（第九轮报告 §9.4、ACP 验收 §七-7）无归属，须补派。
