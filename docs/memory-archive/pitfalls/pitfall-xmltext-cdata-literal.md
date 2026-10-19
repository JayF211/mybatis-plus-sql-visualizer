---
name: pitfall-xmltext-cdata-literal
description: 本平台 PSI 的 XmlText.text 对 CDATA 返回带 <![CDATA[ ]]>
  包裹的字面全文、并非展开内容——按「CDATA 已展开」口径写的提取链（hbm 命名查询）带着既有缺口且断言非判别性恒绿；O-72 实测与修法
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e7ef117e-74b4-4cfa-95bf-4150d63d2ef4
---

> **归档注记（2026-09-24 第十六次精简）**：连带发现的 hbm 命名查询链 CDATA 缺口已修（dev `1406754`「O-72 延伸」：抽共享 `xml/XmlDirectText.kt`，两处提取改共享口径、断言判别化）；本档保留的核心事实＝平台 `XmlText.text` 对 CDATA 返回字面全文，新增 XML 文本提取代码前仍须先读。

**2026-09-23 O-72 T-XmlInlineSql 实测（ideaIU 2024.3.7.1 测试环境）：PSI 的 `XmlText.text` 对 CDATA 返回的是带 `<![CDATA[ ... ]]>` 包裹的『字面全文』，不是展开后的内容。**（KDoc/注释里流传的「CDATA 已被 PSI 展开为文本节点」说法被 ComparisonFailure 判别性读数证伪：`expected:<[DELETE FROM t]> but was:<[<![CDATA[DELETE FROM t]]>]>`，首跑 11 例红。）

**修法**（`XmlInlineSqlDetector`）：对每个直属 `XmlText` 统一 `text.replace(Regex("(?s)<!\\[CDATA\\[(.*?)]]>"), "$1")` 剥包裹后再做首词判定/文本拼接；混排文本与多 CDATA 段均安全。首词判定前不剥包裹会把 `<![CDATA[` 当首词。

**连带发现（既有缺口，登记待议未修）**：hbm 命名查询链 `HbmXmlEntityResolver.extractNamedQueryText` 与 `SqlPreviewService.previewHbmNamedQueryTag` 用的是同口径裸 `XmlText.text` 拼接——CDATA 形态的 `<query>` 会把包裹原样带进 HQL 转换。该缺口此前不可见的原因＝`HbmNamedQueryResolverTest` 的断言「CDATA 文本应展开提取」是**非判别性 contains**（`<![CDATA[FROM LegacyItem li …]]>.contains("FROM LegacyItem li")` 展开与否同为真，见 [[pitfall-fake-green-test-assertions]] ④）。修法建议：两处提取走 unwrap 口径 + 断言改 `assertFalse(contains("<![CDATA["))`。

**How to apply**：任何从 XML 标签提取文本喂给 SQL/代码管道的新代码，先在夹具里对 CDATA 形态写**判别性断言**（断「不含包裹标记」而不是「包含内容」）；引用既有「CDATA 已展开」注释前先实测。

相关：[[pitfall-fake-green-test-assertions]]、[[project-pending-plans]]（hbm CDATA 缺口台账）。
