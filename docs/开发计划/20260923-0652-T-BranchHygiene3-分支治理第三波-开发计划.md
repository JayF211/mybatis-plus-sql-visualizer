# T-BranchHygiene3 分支治理第三波 — 开发计划

- 日期：2026-09-23 06:52
- 任务编号：**O-62**（第三轮全景审计 `docs/调研报告/20260923-0650-全分支功能全景审计与长期演进-第三轮调研报告.md` §8.1；系 O-45 / T-BranchHygiene2 的第三波——上波清了 stash、tag 与五条残留分支，本波清四条新任务车道与两处未推敞口）
- 来源：第三轮全景审计 §2 分支拓扑实测（快照 2026-09-23 06:2x，origin/master=`133b4a2`，本地 master=`cee5f63`）
- 性质：治理（无生产代码改动，全部动作可 git 实测验证；不触发门禁复跑——除非 §三.2 合并带进任何 src 文件）
- 实施分支：t-round10（文档车道自身即对象之一）
- 验证基线：origin/master `133b4a2`

---

## 一、背景与实测依据（2026-09-23 06:2x 快照）

治理对象六项：

| # | 对象 | 实测状态 |
|---|------|---------|
| 1 | 本地 master | `cee5f63`，落后 origin/master 3 提交（P20/P21 真缺陷修复 + 两笔验收 docs 在远端），merge-base=自身、可纯快进；master 的检出树在 worktree `mpsv-agent-config-locate` |
| 2 | t-round10 | 7 独有提交（6 docs + 1 merge）未进 master，`git diff --stat origin/master...t-round10 -- src/ sub-projects/ build.gradle.kts gradle.properties` 实测为空；**领先 origin/t-round10 39 提交未推** |
| 3 | feature/kotlin-to-java | 锚点 c07b8e3（T-SyncK2JMaster184，2026-09-23）；**Master184 波次 11 提交未推**（origin 停在 `1c3dce0`，Master115 订正）——19 万行级移植形态的最大未备份资产 |
| 4 | dev / t-hql-colmap / t-prewarm-late-recover | 三条零独有分支：双向 rev-list 独有=0 且 `git diff --stat origin/master...B` 均为空；后两条**无远端备份**（tip 提交均可从 origin/master 到达，内容无损）；各挂 worktree（mpsv-dev / mpsv-hql-colmap / mpsv-prewarm-late-recover） |
| 5 | T-BuildConcurrency 计划文件 | `docs/开发计划/20260922-2038-T-BuildConcurrency-多工作树并行构建并发治理-开发计划.md` **untracked 未入库**，任务编号自称占用 O-59——该编号已被 T-IdentityInsertRollback 占用并交付，应改占 **O-65** |
| 6 | 台账欠账 | docs/MEMORY.md 开发计划表滞后 9 行（2026-09-22 九份计划未登记）；T-AgentResiduals 无 ✅ 行与验收文档；**beta.4 转正三选一口径只在本地会话记忆、仓库 docs 零条目**；env/cloud 与 env/no-proxy 无「禁合回」登记；testDatabase 门禁「10 绿的含义」无显式说明（t-testdb-driver 裁决的替代收口形态） |

另登记（不属本计划动作，D3 请示）：根目录 `ht.txt` / `wt.txt` / `nul` 三个未跟踪垃圾文件。

## 二、待验证前提（实施前先证伪，任何一条不成立即中止并回报）

1. **t-round10 纯 docs 差异**：已实测 diff 为空（§一.2）；实施时复测。若复测出现 src/sub-projects/build 文件，**中止**，不许按「纯文档」假设合并。
2. **三条待删分支零独有内容**：已实测（§一.4）；删除前各复测一遍 `git rev-list --count origin/master..B`（非 0 即中止）。
3. **k2j push 不与并行会话冲突**：k2j 是「并行勿动」车道。push 前跑 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`，并确认 `mpsv-kotlin-to-java` worktree 无在写未提交产物；若对方正在波次中途（锚点未收官），登记等待，不催不改。
4. **T-BuildConcurrency 文件无会话在写**：该文件创建于 20260922-2038 后一直 untracked。动手前 `git status` 复查 + 文件 mtime 无当日更新才改；有在写迹象则只登记冲突、不改内容。
5. **mpsv-t05-reverse（detached `3f7853b`）无未提交产物**：`3f7853b` 已在 master 祖先链（O-52 交付提交），确认该树 `git status` 干净后才可清理。

## 三、实施步骤

1. **本地 master 快进**：在 `mpsv-agent-config-locate` 工作树执行 `git merge --ff-only origin/master`。三重守卫（既有定例）：该树干净 / 确认纯快进 / 记回滚锚点 `cee5f63`。
2. **t-round10 并回 master**：先 `git push origin t-round10`（39 提交留底），再在 master 树 `git merge --no-ff t-round10`。预期冲突仅在 `docs/变更记录.md` 顶行——按「台账顶行冲突双方行全保留」惯例处理。合并后 `git diff --stat 133b4a2..master` 必须只含 docs 文件，出现 src/ 即回滚重查。随后 `git push origin master`。
3. **k2j push 留底**：`git push origin feature/kotlin-to-java`（11 提交）。纯 push：不动工作树、不合并、不改锚点，符合「同步不合并」纪律。完成后 `git ls-remote origin feature/kotlin-to-java` 对照。
4. **删除三零独有分支 + worktree（全部待用户授权，见 D4）**：每条先移除 worktree 再删分支。长路径坑既有口径：`git worktree remove` 报 Filename too long 时 deregister 已生效、勿重试，`rm -rf` + `git worktree prune` 收尾。顺序：dev（本地删，origin/dev 保留不动）→ t-hql-colmap → t-prewarm-late-recover。删除后 `git branch -a` 复盘。
5. **T-BuildConcurrency 订正入库**：编号行 O-59 改 O-65，任务编号行补一句「（20260923 第三轮全景审计登记订正，O-59 已被 T-IdentityInsertRollback 占用）」；`git add` 显式路径入库提交。前提 §二.4 不成立则跳过本步只登记。
6. **台账补齐**：docs/MEMORY.md 开发计划表补 2026-09-22 九行（AgentResiduals / HqlAliasColumnScope / DbColumnCalibration / DynUpdateWriteSafe / IdentityInsertRollback / PrewarmLateRecover / ConsoleErrorCoverage / AgentConfigChannel / AgentSourceLocateStale）+ 本轮三行（O-62~O-64）+ O-65；T-AgentResiduals 行补 ✅（主体合入 `0abc1bb`）与两条真机残留注记。
7. **beta.4 转正口径落仓库 docs**：在 `docs/开发计划/20260920-1140-T-ReleaseArtifactHygiene-*.md` 决策定案表后追加「2026-09-22 用户转向 beta.4 转正式版路线」小节——三段：技术口径（版本号焊 6 处：外层 zip 名 / 内层 jar 名 / plugin.xml `<version>` / MANIFEST Version / change-notes 标题块 / update.json；自报 beta 更新提示永不收敛；转正=用 beta 树重打，`2026.9.14` 先例）/ 三选项与各自代价（A 原样转正带着 SenderLoop P0 / B 摘取重打 / C 用 master 重打）/ 复核倾向（B 或 C，A 不建议）。
8. **env 车道禁合登记**：docs/MEMORY.md 分支治理相关行登记 origin/env/cloud、origin/env/no-proxy 为构建环境车道、禁合回 master、本地无需建分支。
9. **testDatabase 门禁含义说明落档**：一段话写明该门禁 10 绿的覆盖范围与被绕开面（t-testdb-driver 七失败项的「不掩盖」挂起转为显式口径），落点 docs/MEMORY.md 测试约定行或验收文档，一次性销掉该裁决。

## 四、验证与反向验证

- 每步留 git 实测读数：rev-list 双向计数、`diff --stat`、`git ls-remote origin` 前后对照（裸 reflog 证不了 push——T-BH2 订正口径）。
- §三.2 合并后 `git diff --stat 133b4a2..master` 只含 docs 文件（反向验证：出现 src/ 即中止）——本计划的「无生产代码改动」承诺由它守门。
- 删除类动作前逐条复测零独有（反向：rev-list 非 0 即停）。
- 本计划不触发门禁复跑；若 §三.2 带进任何 src 文件则视为大改动、门禁必须复跑并登记。

## 五、决策点（待用户）

| # | 决策 | 选项 | 倾向 |
|---|------|------|------|
| D1 | t-round10-followup 处置 | A 本地分支 + mpsv-round10-followup worktree 都删（远端保留，内容=origin/master 已证）/ B 全保留 | A |
| D2 | mpsv-t05-reverse detached worktree | A 清（`3f7853b` 在 master 链上，前提 §二.5 核过）/ B 保留 | A |
| D3 | 根目录 ht.txt / wt.txt / nul | A 删 / B 留 | 请用户辨认归属后删（nul 是 Windows 重定向事故产物） |
| D4 | 三条零独有分支删除授权 | A 授权删（内容无损已证，无远端两条先 push tip 备注留档）/ B 只登记不删 | A |
| D5 | beta.4 转正内容口径（本计划只落档不拍板） | A 原样转正 / B 摘取 P0 修复重打 / C 用 master 重打 | B 或 C；A 不建议 |

## 六、风险

- `docs/变更记录.md` 顶行冲突：预期内，双方行全保留惯例。
- 并行会话同时写 docs/MEMORY.md / 变更记录.md：提交前 `git status` 复查，显式路径清单暂存，不用 `git add -A`。
- k2j push 撞上对方波次中途：登记等待，不催不改。
- 删除类操作不可逆：虽有零独有内容实证，仍全部走用户授权门（D1~D4）。
- commit message 一律 `-F` 文件（Git Bash 反引号陷阱）。

## 七、实施记录

（待实施后回填）
