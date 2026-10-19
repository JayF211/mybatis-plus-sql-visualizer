---
name: t-feedbackdlg
description: T-FeedbackDlg 报告对话框缺陷三波已交付——一波三缺陷（96bcb62）；二波 T-FeedbackDlg2 堆栈勾选跳动（943494c）；三波 T-FeedbackDlg3 手动形态堆栈自主输入+描述框收敛（23a8581）；真机走查留人工
metadata:
  node_type: memory
  type: project
  originSessionId: sess_a8b98cd1-fd3e-473e-96ff-17caec5cb6ac
---

T-FeedbackDlg 一波三缺陷已实施交付（2026-09-14，提交 96bcb62 + 62f0079；计划 `docs/开发计划/20260914-1010-T-FeedbackDlg-...md`）。3974/0/0/6 绿。

T-FeedbackDlg2 二波真机两缺陷已交付（943494c；计划 20260914-1320）。3982/0/0/6 绿。
- 跳动根因：堆栈行与描述行都 `weighty=1 + fill=BOTH`，GridBagLayout 排除不可见组件——勾选后堆栈行进入纵向分配但窗口高度不变，两行对半分富余，描述被压扁「跳上去」。修复：`toggleStackPreview` 在翻可见性前后各测一次 `contentPane.preferredSize`（不依赖行高假设），`toggleResizeDelta` 纯函数算补偿量（show=富余+preferred增长；hide=-实际行高与收缩量取大），窗口等高撑/缩，描述行像素级不变；MIN_WINDOW_HEIGHT=400 下限。
- 手动形态堆栈来源：`latestPluginErrorStack()` 走 `MessagePool.getInstance().getFatalErrors(true,true)`（243/262 javap 双版本实证同签名；ErrorsHolder/Suppressable* 全树扫描证伪不存在），asReversed 取最近一条按插件包名文本匹配归属（243 无 belongsToPlugin）。

T-FeedbackDlg3 三波真机两缺陷已交付（23a8581；计划 20260914-1617）。4025/0/0/6 绿。
- 二波的「自动预填最近一条」形态被真机再否决（替用户做决定、无换/清空入口）→ 三波改**自主输入**：手动形态勾选行恒显示、堆栈区改可编辑（emptyText 引导粘贴）、勾选行右侧「从 IDE 错误池导入…」按钮点击时经 `stackPoolProvider` 实时拉候选。`latestPluginErrorStack` 重构为 `pluginErrorPoolStacks()`（同数据源同包名过滤，最近在前/去重/至多 10 条）；JPopupMenu 每项首行截断为文本、点击覆盖填入；空池禁用说明项；`collectStackAttachment` 手动形态取文本区实际内容（空视同不附带）、错误形态仍用 event 全文。i18n 双语各 +4 键。
- 描述框默认高 140→90 / 文本区 6→4 行（T-FeedbackDlg 只收过宽度没收纵向）。

T-FeedbackErr 四波（2026-09-14，真机「上报失败、无对话框、只有 notify」）——详情见 [[t-feedbackerr]]：
- `doOKAction()` 不再 `super.doOKAction()`（旧实现先关窗再提交，失败只剩 toast）；改为 `beginSubmitting()`（按钮转「提交中…」禁用 + 状态行）/`finishSubmitting(outcome)`（成功才 `close(OK_EXIT_CODE)`，失败留窗、状态行红字给服务端原因、可原地改内容重试），`ReportFormFields` 新增常驻 `statusLabel`（`fullRow` 末行）。
- 回调签名升级为 `onSubmit: (Submission, (SubmissionOutcome) -> Unit) -> Unit`；结果提前跑一次 `super.doOKAction()` 会关窗，别再退回旧写法。

交付形态（后续改此对话框先读）：
- `ReportFormFields`（internal 控件载体，构造即带占位/换行）+ `buildCenterPanel`/`validateForm` companion 纯函数——headless 零 peer；`ErrorReportDialogLayoutTest` 8 例（含宽度 ≤800、堆栈行 stretch、窗口补偿量五分支、手动控件默认态 + 新键 bundle）。勾选行是「勾选框+导入按钮」FlowLayout 同行容器。
- 校验常量单一事实源=`ErrorReportComposer.TITLE_MIN/TITLE_MAX/DESCRIPTION_MIN`；尺寸记忆 key=`"MpSqlVisualizer.ErrorReportDialog"`；勾选监听在 init() 初始态落定后注册；`contentPane` peer 前抛 ISE 须 runCatching。

**Why:** 同一对话框三连真机反馈的共同信号——**「替用户自动决定」的形态反复被否**（手动堆栈先自动隐藏→再自动预填→终态自主粘贴/导入）：反馈入口类 UI 默认给选择和出口，少替用户拍板。weighty 行显隐须配窗口高度补偿是所有 DialogWrapper 动态行的通用坑；MessagePool.getFatalErrors 是跨版本错误池唯一可靠入口。
**How to apply:** 改布局先动 `buildCenterPanel` 跑 LayoutTest；主仓工作树是并行共享面，properties 高频文件改完尽快单独提交（本轮 4 键被并行 T-ModPick 提交 f40cde7 顺带入册）；API 核实见 [[idea-262-platform-jar-verify]]；verify 口径见 [[feedback-verify-scope]]。
