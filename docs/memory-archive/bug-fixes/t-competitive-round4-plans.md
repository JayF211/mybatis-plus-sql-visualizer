---
name: t-competitive-round4-plans
description: 第四轮竞品应答七任务已全部交付 dev（2026-09-15，4410 绿）——T-McpGuard/T-UnboundedWriteGuard/T-DialectLabMatrix/T-DollarInjectMark/T-DdlCacheInvalidate/T-SemicolonGuard/T-BatchInMerge；未合 master 未 push
metadata:
  node_type: memory
  type: project
  originSessionId: sess_2a8b19f2-ad6a-45ac-8278-7b36ee29ed55
---

第四轮竞品对比（报告 `docs/调研报告/20260915-0123-竞品横向对比与功能建议-第四轮调研报告.md`）的 N-16 与 O-9~O-14 七任务 **2026-09-15 已全部在 dev 实施交付**（master 合入 dev 的 merge 74f7842 基线上：ebb6667 / cd9f490 / 91b6810 / e87a377 / d5f9c44 / 82b70c3 / 70d958e / 文档收尾 8ad316b；全量 testShards 360类/4410/0/0/6 绿，基线 350/4267 → +10 类 +143 例）。**未合 master、未 push**——dev 领先 origin/dev 九提交，见 [[t-fourplans-20260914]] 合流模式。

- **T-McpGuard**：structuredContent 判定分支 a（262 jar 字节码 String→content 文本块，零改码）+ 位差叙事四处 + 哨兵 check_competitor_watch.ps1（计划任务 MPSqlVisualizer-CompetitorWatch 每日 09:30）
- **T-UnboundedWriteGuard**：UnboundedWriteDetector 七规则 + 三处确认流共用 ExecuteConfirmMessages；**偏差定案**=「PARTIAL 触发面」落在回滚 warnings（UPDATE/DELETE 回滚本就 UNABLE 不虚标）
- **T-DialectLabMatrix**：路径 A 半自动（两库官方均 tar 分发无公开 pull）+ database-lab/ 资产 + 九例配方守卫（snakeyaml 2.2 test 依赖新增）
- **T-DollarInjectMark**：引擎直替计数 + DegradationBanner.DOLLAR_INJECT（✕ 可关会话记忆）；**偏差定案**=计划「字符串内 ${} 不计」与引擎语义不符，按引擎忠实实现（字符串内同样直替同样计数）
- **T-DdlCacheInvalidate**：分支 A——盘点唯一自持 schema 面 = DatabaseSchemaResolver.metaCache，invalidateAll 挂 executeOnConsole 唯一提交收口；MetadataInvalidator 不透明类型（UnsupportedStatement/Block）词边界关键字兜底
- **T-SemicolonGuard**：util/SqlStatementSplitter 单实现收敛三处+SqlToJavaConverter 第四处同款；B 三缺陷修复（字符串内分号/注释内分号/字符串内 --）
- **T-BatchInMerge**：BatchPredicateMerge 纯函数 + 浮层 setAdText 原生单行参考行；**偏差定案**=平台 adText 替代自建提示行（弹窗克制）

实施沉淀（跨任务通用）：JSqlParser 5.3 三个解析形状实证——括号组解析为 `ParenthesedExpressionList`（非 Parenthesis）、残缺语句宽松包成 `UnsupportedStatement`、`BEGIN…END` 包成 `Block`（首词关键字路由会漏判）；Kotlin 普通字符串里写 `${}` 字面量必须 `\${}`（KDoc 注释里 `*/` 序列会提前终结注释）；bundle 值内 MessageFormat 单引号转义 `${}` 会被 parity 占位符守卫拦截——token 作 {0} 参数传入；headless 沙盒预览降级链恒亮 DEGRADED 横幅（内存 PSI 层也亮），面板级低优先横幅断言须容错。

**2026-09-15 T-ShortcutSettings 收尾任务**（aed3b07，基于七任务交付后的 dev）：用户反馈「快捷键冲突提示找不到修改入口」+「MCP 无设置入口」→ 新增独立「快捷键」设置节（SECTION_SHORTCUTS + ShortcutsConfigurable + plugin.xml groupWeight=45），5 个插件快捷键统一管理（既有 2 个从 UI 节迁入，3 个新增 Explain/Count/Sample 复用现成 ShortcutEditor 内部类）；「关于」节新增「管理 MCP 工具」跳转（ShowSettingsUtil→`Class.forName("com.intellij.mcpServer.McpServerConfigurable") as? Class<out Configurable>`）；启动冲突检测缩减为仅处理「当前键位==plugin.xml 默认值」的动作（新增 DEFAULT_KEYSTROKES 常量）。编译踩坑：`Class.forName` 返回 `Class<*>` 需强转 `Class<out Configurable>`；`runCatching {}` 在 `showSettingsDialog` 泛型方法前推断失败需显式 `runCatching<Unit>`。
