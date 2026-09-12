---
name: t-hibdualcapture-plan
description: Hibernate 日志同语句双条目参数分裂已交付（master 90f082a，2026-09-10）——零参影子隔离槽 +
  string-aware 占位符口径；F2 参数框随在途会话一并提交多笔拆分，XmlPreviewPanelTest 失败已修复，全量 3252/0/0
metadata:
  node_type: memory
  type: project
  originSessionId: sess_393e0998-29f6-49fb-bb06-6774dfcb7fbc
---

T-HibDualCapture（docs/开发计划/20260910-1547，2026-09-10 交付 master 90f082a）：`show-sql`+`org.hibernate.SQL: debug` 双路打印时同语句两份 SQL 块，logger 副本被 `Hibernate: ` 独占行提前闭合产出零参条目（`?` 残留+误报数量不匹配），绑定行全挂 stdout 副本，每查询裂成两条（面板去重拦不住，restoredSql 指纹不同）。

**已提交（master 90f082a）**：F1 `HibernateLogParser.quarantined` 隔离槽（同 rawSql 后继条目取代零参影子；**takeEntries 仅在 `!collecting` 时冲刷**——桥接逐行取件若无条件冲刷会让影子在 stdout 副本前泄出，合并失效，这是实现关键）；F3 `placeholderPositions`（字面量含 `''` 转义/行注释/块注释内 `?` 不计）落 `LogSqlRestorer.kt` 单点，**`restore` 从裸 indexOf 改按位替换同步换口径**（否则计数与回填错位），`toRecognizedPreview`/`LogToSqlPanel`/`SqlLogPanel` 三处接入；警示分流新键 `log.preview.unresolvedNoBinding`。

**F2 参数框（后续一并提交）**：16 列 + caret 钉首 + tooltip `#source = 值`（buildParamRow/onParamFieldText/paramTooltip + 两个 ForTest 缝 + SqlPreviewPanelParamFieldTest）。**连带修复**：工具框 tooltip 从 `#source` 变 `#source = 值` 击穿了 `XmlPreviewPanelTest.testXmlBindValuesOnlyInParamSection` 的 `toolTipText == "#likeName"` 精确断言——bind 参数回填值后 tooltip 恒带 ` = 值` 后缀，改成 `startsWith("#likeName")` 前缀匹配（T-HibDualCapture 记为已修复；此测试改动与 XmlPreviewPanelTest.kt 一起在后续多笔提交）。

**测试坑三则**：KDoc 内嵌字面 `/*` 会因 Kotlin 嵌套注释编译炸（须改 `*&#47;`）；JUnit3 风格 BasePlatformTestCase 测试方法必须 `test` 前缀（反引号方法名不执行）；SqlPreviewPanel 测试须 `panel.clear()+dispose()` 否则 EditorImpl 泄漏。

**最终全量 3252/0/0 绿**（此前 3252/0/1 的唯一失败即上记 XmlPreviewPanelTest）。

**未提交内容多笔拆分（用户 2026-09-10 要求按内容分多次全部提交）**：工作树未提交的四簇——①T-PreviewTopOverlap（SqlPreviewPanel.kt 顶部行 WEST→CENTER + 更多折叠菜单 + 宽度预算截断 + SqlPreviewTopRowLayoutTest）②T-ExecSilentNoop（DataSourceSelector.kt 探针 + 提示分流 + ExecSilentNoopHintTest）③T-HibDualCapture F2 参数框（SqlPreviewPanel.kt buildParamRow + SqlPreviewPanelParamFieldTest + XmlPreviewPanelTest 修复）④四份开发计划文档——① ~ ③ 的代码都叠在共享文件 SqlPreviewPanel.kt，须按 hunk/索引级拆开分次提交。相关：[[t-hibdualcapture-repro-method]]、[[t-execsilentnoop-plan]]、[[t-previewtopoverlap-plan]]。