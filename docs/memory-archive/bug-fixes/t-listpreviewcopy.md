---
name: t-listpreviewcopy
description: T-ListPreviewCopy 列表预览默认格式化＋多选防误弹＋多选批量复制已合入 master（merge 280eddc，合并后 4039/0/0/6 绿，20260914）：预览统一跟随 logFormatSql、selectedIndices>1 守卫、batchCopySql 与内置 Ctrl+C 逐字节同构；坑=测试挂起定时器 EDT 真弹浮窗致编辑器泄漏
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f358ee8d-d448-4e51-86db-39b71496bd99
---

用户真机三件套：点击预览的 SQL 应默认格式化显示；同时选择多个 SQL 不要再出现单击预览；多选需支持批量复制。计划 `docs/开发计划/20260914-1602-T-ListPreviewCopy-预览默认格式化与多选批量复制-开发计划.md`。

- ①预览格式化**不新造开关**：统一跟随设置页既有 `logFormatSql`「预览时格式化还原 SQL」（默认开）——顶层 `clickPreviewSql(sql, formatSql)` = 先 `withTerminator` 补分号再 `SqlFormatter.format`（与双击弹窗 `formatIfNeeded` 同序）；日志/历史两页单击浮层 `sqlAt` 换装；历史 tab 双击 `buildPreviewResult` 弃 `formatSql=false` 硬编码改读设置；死代码 SqlHistoryPanel 同构接线防复活分叉。T-HistParam 时「历史页保持现状不格式化」的语义已被本任务推翻。
- ②多选守卫：`SqlClickPreview.onClick` 检查 `list.selectedIndices.size > 1` → 停表+关旧（多选操作不被浮层干扰，已开的预览一并关）；`show()` 到点兜底复查封 300ms 延迟窗补选竞态。Swing 选中更新发生在 mousePressed（先于 mouseClicked），故 mouseClicked 时 selectedIndices 已含被点行——守卫天然成立。
- ③批量复制：右键命中行在多选集合内**保留多选**、不在则收敛该行（`resolveContextMenuSelection` 纯函数；删除/发送/跳转恒作用于被右键行）；`batchCopySql` 选中条目逐条分号收尾换行拼接 = 与 JBList 内置 Ctrl+C（itemToText + join("\n")）逐字节同构；`copyMenuText` 多选文案带条数。i18n 双语 4 键：log.panel.copySelected / log.history.copySelected。
- 测试 +9；**新坑（已入 [[pitfall-regression-false-failure-flakes]]）**：多选恢复用例断言后挂起定时器 300ms 到点在 EDT 真弹带编辑器浮窗 → fixture teardown 报 Editor hasn't been released（TraceableDisposable$DisposalException）且失败记在**无关用例**头上，finally `cp.dispose()` 停表修复。
- 交付链：worktree wt-t-list-preview-copy（并行 T-ExecSlowOps-Merge 活跃期，register T-ListPreviewCopy + build.gradle.kts 临时 `sandboxContainer = mpsv-lpc-sb` 跑完还原不随提交——该属性必须放**顶层 intellijPlatform 扩展块**，本次又误放 dependencies 的 intellijPlatform 块报 Unresolved reference，见 [[project-test-conventions]]）→ 分支全量 **4033/0/0/6 绿**（基线 8aa5261 4024+9）→ no-ff merge 280eddc（跨 T-ExecSlowOps/T-ReportL10n2/T-FeedbackDlg3 合流零冲突；主仓清 build/classes+build/kotlin 后复跑 **4039/0/0/6 绿** 5m37s）→ docs 090e4db（变更记录总册 + MEMORY 台账 + 计划实施记录回填）。
- worktree 删除长路径第三条路：`git worktree remove` 报 Filename too long（沙盒深目录），git-bash 下 `robocopy 空目录 目标 /MIR` + `rmdir /s /q` + `git worktree prune` 一次成功（比 [[t-explain-dbl]] 记的子目录逐块 rm -rf 更省事）；分支 t-list-preview-copy 用后即删。
- 真机走查四步留人工：格式化开/关两态×四预览面；Ctrl 多选无浮层闪现且恢复单选正常；多选右键复制粘贴出多条带分号 SQL；单行/空白处右键不变。
