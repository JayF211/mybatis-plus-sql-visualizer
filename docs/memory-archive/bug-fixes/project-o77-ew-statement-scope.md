---
name: project-o77-ew-statement-scope
description: O-77 T-EwStatementScope 已立项待实施（20260924，计划 `c3cb052`）——XML 预览 ew
  占位全文扫描误报「无 Wrapper 参数」的根因锚点与修法
metadata:
  node_type: memory
  type: project
  originSessionId: sess_84ed25cb-c9f5-46a3-9839-8f57a4c68127
---

# O-77 T-EwStatementScope：XML 预览 ew 占位按语句域收敛（已立项待用户定案，未动码）

用户反馈：demo 仓 `OrderMapper.selectOrdersByCondition`（无 ew 参数的普通 XML select）预览误报「方法 … 无 Wrapper 参数（ew），无法展开 MP 占位」红横幅。

**根因（读码实锤）**：MP 占位识别域与解析域不一致——`XmlPreviewService.kt:79` 用 `XmlEwExpander.placeholders(fileText)` 扫**整个 mapper 文件**，同文件他语句（demo OrderMapper.xml 第 8/16 行 `${ew.customSqlSegment}`）去重后剩 `ew` 占位 → `XmlEwResolver.kt:65` 按当前语句 id 关联方法无 Wrapper 参数即误报（警示文本=当前语句 id＋他语句变量名）。引入 `0f42a80b`（M26-B），`git tag --contains` 实测 2026.8.15 起全 tag 含＝**发布版 2026.9.14 受影响，非新回归**。

**同族缺陷两形态**（随本任务一并收敛）：①预览带 ew 语句时他语句不同变量占位（ew2）错配/误报；②跨命名空间 include 注入的占位全文扫描漏检（语句域扫描反而覆盖）。

**修法（方案 A 语句域扫描）**：`XmlStatement` 尾置默认字段 `rawText`（`XmlMapperParser.parse` 在 include 展开后取 `element.textContent` 填充——textContent 含 CDATA、实体已解码、片段带入占位一并计入），`resolveEwPlaceholders` 改传语句文本、null 回落 fileText；`XmlEwExpander`/`XmlEwResolver` 零改动。测试先行三用例（双语句隔离/不同变量名/include 带入）＋反向验证摘掉修复必红。

计划：`docs/开发计划/20260924-0848-O77-T-EwStatementScope-XML预览ew占位按语句域收敛-开发计划.md`（待验证前提四条在 §五：textContent 含 include 展开文本待单测坐实 / AnnotationSqlParser·ConvertToSqlAction 构造路径回落不回归 / 跨 ns 检出改善可留真机走查 / SqlPreviewPanel:4311 无隐含分支依赖）。波及入口：预览弹窗横幅（`panel.banner.renderFailed`）、两 gutter marker、MCP render 工具、ConvertToSqlAction。
