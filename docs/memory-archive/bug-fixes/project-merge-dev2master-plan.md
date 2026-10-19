---
name: project-merge-dev2master-plan
description: T-MergeDev2Master 合流方案与实战结果（20260918 用户定案「除 K2J 外全合」→ 同日执行完成：master→dev 解冲突 → master 快进；11 文件冲突面、三条风险的应验与处置）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bd923173-5e98-4d8b-ac6b-b59251d2d90d
---

2026-09-18 用户定案「除了 K2J O-24 不合并，其他的都合并到主分支，并修复所有问题」。方案交付后**同日执行完成**（T-DevMergeFix，master 已含 dev 全部 163 提交、双树归一）。相关状态见 [[project-pending-plans]]。

## 关键实测（决定了方案可行性，全部应验）

- **真实冲突面只有 11 个文件**，不是 163 个提交。算法：以合流基点 `087494e` 为准取「两侧都改过的文件」（`comm -12 <(git diff --name-only BASE master|sort) <(git diff --name-only BASE dev|sort)`）。命中：`CHANGELOG.md`、`docs/变更记录.md`、全景盘点、`buildSrc/src/main/kotlin/mpv-testing.gradle.kts`、`SqlLogConsoleBridge.kt`、`SqlLogPanel.kt`、`agent/AgentManager.kt`、`SqlPreviewPanel.kt`、`SqlResultPanel.kt` + 两个 bundle。dev 侧共动 313 文件。**执行实测：只有 2 个文件真冲突**（`变更记录.md` 同点插入、全景盘点 3 处），其余 9 个 hunk 坐标不相交、git 自动并集——「交叠文件数」是上界，真冲突面还要小。
- **dev 未动发行元数据**：`gradle.properties` 版本号、`update/preview.update.json`、`update/update.json`、`plugin.xml` 的 since-build/description 全部未变 → 合并只带功能与文档，不改发行身份。

## 执行结果（Step 0~6 全部走完）

0. master 打退路 tag `pre-dev-merge-20260918`（在 `fbf65e6`）——**留 tag 是本批最省事的一步，大合流必做**。
1. **master → dev** 合流（`6cce631`）：2 文件真冲突取并集（变更记录 10 行一条不丢、master 两行置顶；全景盘点三处人工合成到第 10 版）。**关键人工补齐**：dev 侧 premain 建会话点未传 `clockMs`，自动合并抓不到——心跳窗口判定跨「会话记 lastSeen」与「管理器算过期」两侧，缺它则 T-RA-01 修的「半死会话永久封口桥」在 premain 通道原样复活。
2. dev 合并树门禁绿（5m10s）。
3. **master 快进到 dev**（零冲突）→ 双树归一，策略决策顺带落地。
4. 代码侧四路修复（sam 六路并行、写集不相交）：testDatabase 六失败 / F8 JOIN 图加载态 / 设置导入 base-merge + 项目级取值 + 守卫收紧 / WriteSafeCtx 两处投递过闸。
5. 文档收口 + **K2J 冻结建议**（不合并就该冻结 k2j 增量同步并落 O-24 续/弃结论，否则锚点同步成本继续发生）。
6. 真机走查八项留人工。

## 三条风险的实际应验

- **PS4 域双实现**：本轮未成为冲突源（PS4 域差异已在更早的 merge 里消化），但「逐文件判、别一刀切」的原则仍适用于任何后续跨树合流。
- **近三批改动必须「并集」活着过合并**：**应验**——`AgentManager.kt` 是三方差集（方言 `lastConnectionUrl` + dev premain 通道 + T-RA-01 心跳窗口 + 两处建会话点的 `clockMs`），`SettingsIO.kt` 是「方言白名单加键」与「base-merge 机制」并存，设置页是「哨兵项」与「已删 modified 标志位不复活」。取任何一边都会丢功能或复活已清理的假状态。
- **合并 ≠ 发行**：**仍未解**——本批是六批功能一次进，真机走查大面积未做，谈版本前必须走查。

## 「修复所有问题」的边界（实践后确认）

- **代码侧可修**：11 文件冲突解、testDatabase 6 项、F8、T-SettingsIoScope、T-SettingsTruth 两项 P3、T-WriteSafeCtx 留档——本轮已全部做掉（并派生 T-TailSweep 批次继续收口）。
- **只能用户做**：全部真机走查（T-CaptureTruth/T-ConsentTruth/T-ReachAudit 走查项、T-PerfSweep4 五项含 fat jar 现场、T-AgentCudSource 达梦、T-BrowserLeak2 窗口、P-立项-05 剩 MariaDB/SQL Server、7 份验收文档走查表）。

## 归一后 dev 分支运维现状（20260919 实测）

- 双树归一后 **dev 只是 master 的落后快照**（无独有提交），但仍被用户要求「切到 dev 干活」——dev 检出在独立 worktree `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev`（主工作区在 master，`git checkout dev` 切不过去）。**正确流程=先在 dev worktree `git merge --ff-only master` 拿最新，再干活**。
- dev worktree 可能有 **dev 专属在途残留**（未提交改动挡 ff）——先 `git diff` 核对内容：若 master 已以更完整形式包含同一改动（如 premain `clockMs` 残留 vs master 带注释的 T-RA-01 版），`git checkout --` 丢弃是安全的；判断依据是逐行比对而非假设。
- 20260919 本会话按此流程完成架构设计三篇文档更新（dev `e2700bb`，未回合 master，见 [[project-pending-plans]] 合流债）。



- **合流方向选「先 master→dev，再 master 快进」**：落后少的一侧先收先进的一侧，冲突面只解一次、快进零冲突，顺带把双树形态拍成单线（不必单独做策略决策）。
- **交叠文件数 ≠ 冲突文件数**：先按基点算交叠面做风险预估，实际解冲突时以 git 报的 CONFLICT 为准。
- **自动合并抓不到「跨两侧语义耦合」**：同一函数在 A 侧加了参数、B 侧新增了调用点时，git 会合并成功但语义断裂——合流后必须逐文件核「A 侧贡献了什么、B 侧贡献了什么、并集后两者是否都在」，独立验证者用「相对基线删除行清单」双向机械判定最有效。
