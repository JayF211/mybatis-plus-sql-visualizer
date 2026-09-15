---
name: t-explain-dbl
description: T-ExplainDbl 重复点击「执行计划」EXPLAIN 前缀双加致 MySQL 1064——已实施并合入
  master（merge 18b3f03，合并后 3980/0/0/6 绿，20260914）：Dialect 幂等包装
  renderExplainStatementIdempotent；根因=执行链把包装语句写回控制台文档后二次点击再包装
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f473d622-e5b1-4d87-a226-d28e0223a23c
---

用户真机反馈：预览窗口首次点击「执行计划」后再点一次，提交 SQL 变 `EXPLAIN FORMAT=JSON EXPLAIN FORMAT=JSON SELECT ...`，MySQL [42000][1064] + 「等待结果超时」提示。计划 `docs/开发计划/20260914-1242-T-ExplainDbl-执行计划重复EXPLAIN前缀-开发计划.md`。

- 根因链：`SqlPreviewPanel.explainSql()` 每次无条件 `renderExplainStatement` 加前缀；首次执行后 `DataSourceSelector.reuseSharedConsole` 的 `editor.document.setText(sql)` 已把包装好的 EXPLAIN 语句写回原生控制台文档（`nativeEditorDoc` 即该文档），二次点击在已包装文本上二次加前缀。
- **可泛化教训：凡「包装 SQL 再执行」的按钮（EXPLAIN/limit/upsert 类），输入源是会被执行链回写的控制台文档，包装必须幂等，否则重复点击必双加前缀。**
- 修复：`Dialect` 接口新增默认方法 `renderExplainStatementIdempotent`——已以 EXPLAIN 关键字开头（忽略大小写 + 第 8 字符词边界，防 `EXPLAINING_TABLE` 类标识符误判）时去首尾空白/结尾分号原样返回，否则委托 `renderExplainStatement`；explainSql 改调幂等入口（trim 收进默认方法）。
- 测试：DialectExplainTest +5 例（幂等/大小写分号/词边界/正常加前缀/Oracle 不支持降级 null 已包装原样），合入 master 后全量 **3980/0/0/6 绿**（基线 3975+5）。
- 交付链：worktree `wt-t-explaintdbl`（分支 t-explaintdbl，94c3128 分支全量亦 3980 绿）→ no-ff merge 18b3f03 → 合并提交上 detach 再跑全量绿 → docs 58a2ea5（变更记录总册 + docs/MEMORY 索引）；分支与 worktree 用后即删（`git worktree remove` 又因 build 产物 Filename too long 失败，改子目录逐块 `rm -rf build .git gradle src docs` 成功——比 [[t-jumpcapture]] 记的 `cmd rd "\\?\..."` 长路径前缀更省事的 git-bash 路线）。真机连点走查留人工。
- 并行铁律执行实录：check 见 T-FeedbackDlg2 占默认沙盒 → register `--sandbox` 指向 worktree 路径，全量绿后 `done --name`（勿裸 done）。
- 真机复验已过（2026-09-14 13:04）：重复点击提交 SQL 为单前缀 `EXPLAIN FORMAT=JSON SELECT ...`，1064 不再出现。
- 后续真机新报错甄别实录：复验日志中 MySQL `[42S22][1054] Unknown column` 属**真实 SQL/库表不匹配**非拼接缺陷——EXPLAIN 先做完整语义解析，列错误与去前缀裸 SELECT 行为一致；归因法=同库跑裸 SELECT 对照（同报 1054 即坐实 SQL 本身问题：列归属别名写错 或 IDEA 所连库 ≠ 应用运行时库）。仅当同库裸 SELECT 能跑通而插件生成的 SQL 报错，才往生成链（MPJ 联表列→别名映射）排查。
