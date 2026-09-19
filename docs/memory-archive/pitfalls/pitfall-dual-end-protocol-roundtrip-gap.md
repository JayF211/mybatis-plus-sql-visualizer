---
name: pitfall-dual-end-protocol-roundtrip-gap
description: 自研双端协议（手写序列化↔对端解析）两端各自单测皆绿、合流即断——手写序列化器必须配格式守卫断言（无 {, / ,, / ,}）与往返用例，静默丢弃点必打日志
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1818de3a-dbb9-4d6a-a3e7-39842ab1b0df
---

**坑（2026-09-16 M56 agent 实录，「agent 在线但 SQL 日志零捕获」真凶）**：agent 侧手写 JSON 序列化（`Jsons.writeEvent`，agent 禁外部依赖故自拼 StringBuilder），`str()` 辅助方法给**每个成员**都补前导逗号——含首成员 → 产出 `{,"sql":...}` 非法 JSON；IDE 侧 Gson `JsonParser` 解析全量失败 → `AgentEvent.parse` 返回 null → 事件**静默全量丢弃**。此前两端各自测试全绿：agent 侧测采集规则、IDE 侧测解析适配，唯独没有「写侧产出喂读侧解析」的往返用例——格式约定（首成员/分隔/尾逗号/转义）无契约钉死，任何字段增删都可能打破。

**Why**：三层掩盖导致排查绕大圈——①两端分开测各自 mock 了对方，合流缺口只有端到端才暴露；②端到端失败的表象是「在线态正常、无条目」而非报错；③parse 失败的丢弃点无日志（仅 eventDropped++ 计数），失败原因彻底静默。同理场景：标记行协议、免注入文件通道等所有自研双端格式。

**How to apply**：
- 自写序列化器（agent 禁依赖场景）必须配**格式守卫测试**：断言无 `{,`、`,,`、`,}`、首成员形态、转义生效——`JsonsEventFormatTest`（agent 子项目）为样板；
- 优先加**往返用例**（写侧产出喂真读侧解析断言关键字段 round-trip）；模块隔离做不到时格式守卫兜底；
- 对端解析失败的丢弃点**必打日志**（含失败体头部片段）——本次全靠 `EVENT 帧解析失败丢弃（... head={,"sql":...}）` 这条 WARN 一次定位真凶。
- **协议扩容同样走守卫（2026-09-16 T-AgentSourceJump 实证）**：事件新增 `cands` 候选帧数组时，写侧先在格式守卫断言「有候选才写键 + 数组分隔合法」、读侧补「缺键=空列表」「畸形项跳过」容错用例——扩容方与解析方各补一侧，避免重演单向假设（旧版 agent 无此键必须零报错）。
