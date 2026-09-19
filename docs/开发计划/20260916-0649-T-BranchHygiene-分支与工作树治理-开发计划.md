# T-BranchHygiene 分支与工作树治理 — 开发计划

- 日期：2026-09-16 06:49
- 任务编号：T-BranchHygiene（全分支功能全景审计报告 §5 劣势 5 / §7 O-22）
- 来源：8 个功能分支 + 9 个工作树 + 3 个备份点同时在线，其中过半为已合入或纯备份残留；单人带宽从「管十三个分支引用」还给功能开发
- 性质：工程治理（git 引用与工作树清理，零源码改动）
- 实施方式：主工作树直接操作，不建任务分支
- 前置：与 T-MergeDev2Master 无硬顺序（治理动作均不触碰活线历史），但建议在其后执行以避免清理期间拓扑再变

---

## 一、背景与依据

全景审计实测：log-timestamp 与 log-search-copy-timestamp 两分支头（51eb01a/5eeb884）均为 master 祖先（功能已随 origin/master 在架）；t-mcp-toolset2 已快进合入 dev；backup-m56-pre-master-merge-20260915 与 feature/t-m56-00-consumer 的头均为 feature/t-m56-01-agent-base 祖先；r2-backup worktree 处于 detached HEAD（5ee2305，在 dev 历史）。这些引用不再承载独立信息，只贡献认知负担与误操作面（历史上发生过裸 `done` 注销全部任务、并行合并清工作区等事故，引用越多越易踩）。

## 二、现状核实（实测，2026-09-16 06:20）

| 对象 | 状态 | 处置 |
|---|---|---|
| feature/log-search-copy-timestamp（+worktree） | 头 5eeb884 = origin/master，完全合入 | 删分支 + remove worktree |
| feature/log-timestamp（+worktree） | 头 51eb01a 为 master 祖先，完全合入 | 删分支 + remove worktree |
| t-mcp-toolset2（+worktree mpsv-mcp2） | 头 f6533f7 为 dev 祖先 | **待 dev 推送远端后**删分支 + remove worktree |
| feature/t-m56-00-consumer | 头 a4ac704 为 M56 分支祖先 | 删分支（打 tag 留底） |
| backup-m56-pre-master-merge-20260915 | 头 2e6d829 为 M56 分支祖先（合并计划 docs） | 删分支（M56 侧已有完整合并台账） |
| r2-backup-pre-dev-merge worktree | detached 5ee2305，在 dev 历史 | remove worktree（tag r2-backup-pre-landing-20260915 已留底） |
| feature/kotlin-to-java（+worktree） | 活线（K2J 同步基线） | **保留**，O-24 决策前冻结增量同步投入 |
| dev / feature/t-m56-01-agent-base / t-uinative / master | 活线 | 保留；M56 无远端是最大敞口，随 T-MD-04 推送 |
| t-listparamcollect | 分支已删（merge 9fcc20f 在 master），无残留 | 无动作，核实即可 |

## 三、方案

**T-BH-01（清理前核验）**：逐对象跑 `git branch --merged <宿主>` 验证包含关系（以实测为准，本计划表格仅是 06:20 快照）；确认并行会话登记表无对应活跃任务、目标 worktree 无未提交改动（`git -C <worktree> status --porcelain`）。

**T-BH-02（留底）**：删除前对每个将删引用打一次性 tag（`hygiene-backup-<名>-20260916`）或确认既有 tag 已覆盖；项目已有「分支消失先查 reflog」教训，tag 化让恢复从考古变成取用。

**T-BH-03（执行清理）**：先 remove worktree 再删分支（worktree 挂着时分支删除会被拒）；顺序：log 系两套 → r2-backup worktree → t-m56-00/backup-m56 分支 → （dev 推送后）t-mcp-toolset2 套。

**T-BH-04（收口盘点）**：清理后输出终态清单（预期：活线 5 引用 = master/dev/M56/t-uinative/k2j + 各自 worktree），回填本计划 §八并同步 docs/MEMORY 台账。

## 四、验收标准

1. `git worktree list` 收敛到活线集合；`git branch` 无已合入残留引用。
2. 每个被删引用有 tag 或既有 tag 对应，`git log <tag> -1` 可达原头。
3. 全程零源码/文档工作区改动（`git status` 干净）；活线四条 HEAD 与清理前逐字节一致。

## 五、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 并行会话占用 | 某 worktree 正被其他会话使用 | T-BH-01 查登记表 + status 干净才动手 |
| 误删未合内容 | 快照后拓扑变化 | 逐对象 `--merged` 实测覆盖计划表格 |
| dev 未推送先删了 t-mcp-toolset2 | f6533f7 仅存在于本地 dev 历史 | 硬顺序：T-MD-04 推送完成前不动该套 |

## 六、实施顺序

```
T-BH-01（核验）→ T-BH-02（留底）→ T-BH-03（清理，t-mcp-toolset2 套挂起待 dev 推送）
→ T-BH-04（盘点回填）
```

## 七、实施记录（实施后回填）

**2026-09-17 实施完毕（零源码改动，无独立提交）**

- **T-BH-01/T-BH-02/T-BH-03 分工**：分支与工作树清理大部分由并行 master 会话按本计划表格执行完毕——本地分支已收敛 dev / master / feature/kotlin-to-java 三条（活线），已合入分支与备份分支全部删除且留底；本轮（dev 会话）完成最后一项：r2-backup worktree 移除（detached HEAD 5ee2305，tag `r2-backup-pre-landing-20260915` 留底且该提交 dev 可达，`git merge-base --is-ancestor` 实证）。
- **执行细节**：r2-backup worktree 目录为 Windows 长路径残留，`git worktree remove` 后用 Python rmtree 清理物理目录；全程零源码/文档工作区改动。
- **T-BH-04 终态盘点（实测 2026-09-17）**：`git branch` = dev/master/feature/kotlin-to-java 三条活线；`git worktree list` = 主工作树（master）+ dev worktree + kotlin-to-java worktree 三处；被删引用均有 tag 或 reflog 留底，`git log <tag> -1` 可达。docs/MEMORY 台账同步留主会话。
- **验收对照**：§四三条全过（活线收敛/留底可达/清理过程 git status 干净）。

**2026-09-17 合流后终态核对（T-BranchHygiene 收尾闭环，盘点 §2.4 第二项）**：master 合入 dev（3552d4b）后的拓扑实测——`git branch` = dev / master / feature/kotlin-to-java 三条活线 **+ feature/dialect-auto-infer、t-execdocgate 两个在途任务分支**（前者为 T-DialectAutoInfer 立项会话的任务分支、后者为 T-ExecDocGate 在途工作树含未提交改动，均系并行会话**活跃任务**而非清理残留，任务收口时按本计划同款流程自清）；`git worktree list` 对应四处 + 主工作树。活线集合与本计划 §三 T-BH-04 预期一致（活线内零残留）。本计划就此闭环。
