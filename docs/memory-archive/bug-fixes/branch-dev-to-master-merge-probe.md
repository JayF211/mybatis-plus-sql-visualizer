---
name: branch-dev-to-master-merge-probe
description: 2026-09-09 实测 dev→master 合并冲突面：仅 4 文件 6 冲突块（D1 假开关簇 + gutter
  结构差异），其余 193 文件全自动合并；附逐块解决配方
metadata:
  node_type: memory
  type: project
  originSessionId: sess_5361d041-6f37-4c2c-9d97-515b95c961df
---

dev→master 合并冲突面实测（2026-09-09，探针 worktree `git merge --no-commit --no-ff dev`，事后已清理）。

**基线**：merge base bef7c152；dev 增量 193 文件 / +28812 行；master 增量 25 文件 / +874 行；双方共同触碰 20 文件。**冲突仅 4 文件 / 6 块**，其余（含 PluginSettingsPanel.kt、双 bundle、SqlLogManager.kt、转换器三件套、多个测试）全部自动合并——因为 T-PortLossBatch1 逐字移植使 git 对相同内容直接去重，master 现状是**合并友好**而非合并 hostile。

**冲突簇 1（D1 假开关，4 块/3 文件）**：dev 侧回加 logStopOnToolWindowClose 真功能（字段 + Java 监听器 + 面板复选框），master 07b7d32 刚删。

**用户裁定（2026-09-09）：开关要转正，不要删除**——D1 方向性错误，正确目标是把假开关做成真开关。关键考据：该开关在 master **曾经是真功能**（T-M51 31b5b437 自 base bef7c152 起就在 master 历史中，实现完整且与 dev 逐字节一致——插件级 `plugin-level` messageBus 订阅；dev 侧 listener 文件自 base 后零改动）；出假点在面板复选框曾被漏移植，后端盘活时复选框无独立历史致三方合并取「无」——外观假但后端真。故 07b7d32 的删除**用力过猛**：census 21、Factory 净化、监听器删除、字段删除全部建立在「无读者」错误前提上。

**后续合并（推荐方向）解冲突配方（全保功能）**：
- `PluginSettings.kt` 块1 字段取 **dev**；块2 取 **dev**（reportSubmitterName/Email 有真读者 ErrorReportDialog.kt 随合并进来；collapsedGroups 两侧均无读者，留 dev 侧无害）。
- `SqlLogToolWindowFactory.java` 取 **dev**（恢复订阅块 + 顺带收获 T-Perf-15 contentListener Disposer 泄漏修复；imports 须对照 dev 版补 Logger/ContentManagerEvent/ContentManagerListener/ToolWindowManagerListener(ex)/PluginSettings）。
- `ToolWindowVisibilityListener.java` 须 `git checkout dev -- <path>` 显式恢复（base 有、master 删、dev 零改动 → 三方合并自动保持删除）。
- `PluginSettingsPersistenceTest.kt` census 取 **dev**（22）。
- 面板 5 处复选框引用（base 就有、双方都未动）：master D1 若已在面板上删行则须恢复；本探针实测 master 的 D1 未动面板（零引用由后端删除传导），合并后须跑 census 用例校准 22。
- 全量测试 + M38.1 gutter 负例回归集验收。

（历史记录：探针当时曾给出「保 master 侧」推荐，用户裁定后作废。）

**提前单独移植到 master 的影响评估（2026-09-09）→ 已实施（T-AutoShowRecover，master b3c2ba3，全量 3153/0/6 绿）**：B 项 stopOnToolWindowClose 全链按基点 bef7c152 原文选择性恢复 D1 足迹 7 文件（字段/工厂订阅区/监听器整文件/面板 5 行/FolderConfigurable 搜索字段/双 bundle key/census 22）；A 项同步落地——onProjectProcessStarted 清 autoShowSuppressedByUserClose（项目启动=对自动展开的再次确认，真机复测实证；autoStart 关闭时短路不动抑制，+2 用例）。恢复 fidelity 校验法：`git diff bef7c152 -- <file>` 逐文件核对，工厂/监听器/Folder/双 bundle/census 对基点**零内容差异**（工厂首次写入后曾出现 import 顺序 16 行差异，改回基点顺序归零）。collapsedGroups 维持 D1 处置（两侧零读者）。**合并面更新**：本簇恢复后 master 对基点增量归零（除 PluginSettings.kt 的 M37.3 默认值 true + collapsedGroups 删除、双 bundle 的 D4 tooltip、A 项 manager 新增），未来 dev→master 合并冲突从 6 块缩到仅 gutter 2 块（JpaGutterLineMarkerProvider.kt——master M38.1 前置守卫 vs dev M39 Wave3 重构结构，取 dev 可编译但须跑 master M38.1 负例回归集验收，启发式差异：master 查 extends 含 Repository / dev 查基类 FQN 前缀+接口名后缀）。dev 侧的 T-Perf-15 contentListener Disposer 修复将随合并自动叠加进工厂。测试流程实录：活跃任务下临时注入 `sandboxContainer.set(buildDir/"sandbox-autoshow-recover")` 跑全量，完毕还原+`done --name` 注销。

**冲突簇 2（gutter，2 块/1 文件）**：master M38.1 前置双守卫（isInRepositoryInterface + isRepositoryMethod）vs dev M39 Wave3 重构（@Procedure/T-J5/T-J20 锚点，守卫移入 DerivedMethodLocator.isRepositoryMethod 内部的 isRepositoryInterface）。取 dev 结构可编译（master 的 helper 无剩余调用点），但两守卫启发式不同：master 查 extends 名**含** Repository，dev 查已知基类 FQN 前缀 + 接口名以 Repository 结尾——`interface Foo extends BarRepository`（自定义基类）两侧判定相反。**验收门：master M38.1 负例回归集须全绿**，红了就在 dev 结构上 OR 回 master 守卫。

预估工作量：解冲突 + 全量测试校准约半小时~1 小时。关联 [[branch-port-loss-audit]]、[[t-portlossbatch1-delivery]]。
