---
name: t-crudsql-plan
description: T-CrudSql 实体类与数据库表右键生成 CRUD SQL（2026-09-12 已交付，feature/t-crudsql 待合并 master）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c9509403-ca23-431c-967b-e70c46ac39a4
---

T-CrudSql 已交付并合入 master（bf303aa → 真机反馈修复 50dd0ae → 弹窗原生化 bcc7322 → 候选选择 JBPopup 浮层化 dd63466 / docs c08334e，2026-09-12；worktree mpsv-crudsql 分支 feature/t-crudsql）：双入口生成 CRUD SQL——实体类右键（GenerateCrudSqlFromEntityAction，复用 [[project-overview]] 的 EntityResolver）+ Database 工具窗口表节点右键（GenerateCrudSqlFromDbTableAction）；选中语句走 SqlPreviewService.showResult，INSERT/UPDATE 复用 BatchModel 列勾选重建。计划文档 docs/开发计划/20260912-1003。测试 23 例新增，clean 全量 3346/0/6 绿；Community 兼容已核实；runIde 真机走查与 verifyPlugin 三版本留待发行前。

UI 样式两轮反馈教训（用户以主分支沙盒实测截图为准）：① 只换列表渲染不动弹窗壳=无效感——模态对话框（OK/Cancel 按钮+固定高度留白+标题栏）本身即不原生，最终定案 CRUD 候选选择用 `JBPopupFactory.createPopupChooserBuilder` 浮层（无按钮/标题条/Enter双击/ESC/setNamerForFiltering 打字过滤/showInFocusCenter）；② BasicHTML.propertyKey 是 Swing View 缓存键，禁 HTML 解析须用 "html.disable" 字符串键（放 Boolean 进 propertyKey 会 CCE）；③ 模态弹窗 doOKAction 栈内同步 show modeless 预览会被吞（真机 262 零异常零日志），选择回调一律 invokeLater 出模态。

真机反馈修复（33e490c）：选择弹窗 OK/双击不进预览——根因=onSelect 回调在**模态弹窗 doOKAction 栈内同步 show modeless 预览弹窗被模态上下文吞掉**（真机 262 零异常零日志、面板渲染单测全绿为判据）；修复=回调包 invokeLater 出模态，对齐既有 ConvertToSqlAction 经 previewAsync 异步跳板从不在此栈内同步 show 的范式（[[pitfall-wira-not-in-readaction]] 同款「runCatching 吞异常须 grep 日志」排查法：本次日志零痕迹靠行为差分定位）。顺带 SqlSelectionDialog 补双击直开 + displayText 样式分层（加粗摘要/灰色等宽）。

过程坑：worktree 分支 ref 一度异常（commit 被记为无父 initial、git status 全量 untracked），`git reset --mixed <原merge提交>` 恢复后正常重提；并行会话共用仓库时操作 ref 前先 `git reflog` 核对 HEAD 状态。

关键实证（本地 ideaIU-2024.3.7.1 jar）：DatabaseTools 无视图 action EP，第三方注册走 `<add-to-group group-id="DatabaseViewPopupMenu" anchor="last"/>`（database-plugin.jar plugin.xml:713）；`PSI_ELEMENT_ARRAY` 在 **PlatformCoreDataKeys** 非 CommonDataKeys（计划曾写错）；DbTable→getDasObject→getDbChildren(DasColumn, ObjectKind.COLUMN)，主键 DasTableKey.isPrimary()+getColumnsRef()（无 DasKey 类）；自增判 DasColumn.Attribute.AUTO_GENERATED。@Version 语义对齐 BatchOperationConverter.buildUpdateBatch（SET 尾 `= version + 1`、WHERE 追加）。DbTypeQuoteHint 数值族返回 false（原样）其余 true（首版写反已修）。
