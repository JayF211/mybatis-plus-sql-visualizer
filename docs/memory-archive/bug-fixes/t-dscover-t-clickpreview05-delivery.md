---
name: t-dscover-t-clickpreview05-delivery
description: T-DsCover 双击预览 Database 工具窗抢占侧边栏修复 + T-ClickPreview-05 复制按钮对号反馈均已交付（2026-09-08，master 5e75c12/be232ce + docs 15ec597/06d411d，3131 绿）——executeAfterPrepare 门控、runRunner silent 参数、AllIcons.Actions.Checked 闪变、四处文档同步含表格行误合并修复
metadata:
  node_type: memory
  type: project
  originSessionId: sess_continued_tdscover_clickpreview05
---

2026-09-08 同会话连修两个用户反馈问题，均已交付 master（全量回归 3131/0/0/6 绿，基线 3130+1）：

**T-DsCover（fix 5e75c12，docs 15ec597，计划 3de4e24）**：用户反馈「双击预览时若 idea 未配数据源或驱动未装，插件侧边栏每次被 Database 侧边栏覆盖」。根因链：`SqlPreviewPanel.showResult:699` 末尾无条件 `prepareDefaultConsole()` → `chooseAndPrepare` runners.isEmpty() 分支（DataSourceSelector.kt:268）每次调 `openDatabaseToolWindow`（`ToolWindowManager.getToolWindow("Database")?.show()`，等价手点条带按钮顶掉插件工具窗）——全库 grep 确认这是插件侧唯一工具窗激活面。矛盾点：`prepareDefaultConsole` 本传 `onError=null`（设计意图预览静默），但 `show()` 在 onError 判定之前执行。修复全落失败分支、成功路径零改动（不触 M36.5.25g「预览阶段显示 DBN 工具栏」红线）：① `show()` 门控 `if (executeAfterPrepare)`——仅显式执行 selectAndExecute 引导开窗，预览默认准备彻底静默；② `runRunner` 加 `silent` 参数——预览路径（last-used 静默复用 / 单数据源直选 `silent = !executeAfterPrepare`）失败仅 LOG.warn 不弹模态错误框，并清损坏数据源的 `LAST_RUNNER_KEY` 记忆（`PropertiesComponent.unsetValue` 在 **util-8.jar** 不在 app-client.jar，javap 核验）；弹窗主动选中保持 silent=false（用户刚做了主动选择）。runIde 真机 5 项验收清单留人工：无数据源双击×3 不抢侧边栏 / 有数据源 DBN 工具栏照旧 / 执行引导保留 / 驱动缺失静默+执行仍弹框 / 多数据源预览弹选择框。

**T-ClickPreview-05（fix be232ce，docs 06d411d）**：用户反馈「单击预览的 SQL 点击复制没反馈，只需要改按钮样式，复制图标变对号代表成功」。修复刻意收窄只动视觉：`SqlClickPreview.buildContent` 复制按钮监听在 `copyText(editor.document.text)` 成功后调私有 `flashCopied(button)`——图标即时换 `AllIcons.Actions.Checked`（243 基线 AllIcons$Actions javap 核验存在，expui/actions/checked.svg），1500ms 后一次性 `javax.swing.Timer`（`isRepeats=false`，无 listener 构造+后置 addActionListener）恢复原复制图标；复制语义/关闭/焦点零改动。SqlClickPreviewTest 增至 11 用例（断言点击后 icon===Checked 且异于初始图标）。插曲：首跑假失败（icon 仍为 copy.svg）为陈旧编译产物——javap 反汇编证 flashCopied 字节码正确，重跑即绿（[[pitfall-regression-false-failure-flakes]] 又一例）。

**文档同步教训（变更记录.md 表格行误合并）**：给 docs/变更记录.md 顶插行用 Edit 且 old_string 取了既有行的前缀、替换文本恰以该前缀结尾时，新行文本与既有行的剩余部分会**合并成一条 5 字段表格行**（正常 4 字段）——按 awk -F'|' 数字段才能发现。修复用 python 脚本在拼接点 split 回两行，再以「new 行是 old 行前缀、剩余部分与新行尾完全一致」校验内容零丢失。这与 [[pitfall-toolcall-newline-escaping]] 的教训同源：脚本化/前缀式编辑文档后必校验 diff 增量（行数+每行字段数）。

**Why:** executeAfterPrepare 门控与 silent 参数是预览路径与显式执行路径分流的通用模式（被动展示不弹模态不打断，用户主动操作才引导），后续凡「预览连带副作用」类问题先查这个分流信号；Checked 对号闪变是平台内最小侵入的成功反馈形态，可复用于其他按钮。
**How to apply:** 动 DataSourceSelector 时守住三条：prepareConsole 调用本身不可动（M36.5.25g 红线）、失败分支才允许加行为、LAST_RUNNER_KEY 损坏数据源须清记忆；做按钮成功反馈直接抄 flashCopied 模式（一次性 Timer 复位，无常驻定时器）；编辑变更记录.md 表格后必跑字段数校验。runIde 真机两项（T-DsCover 5 项清单 + 对号闪变目检）留人工。
