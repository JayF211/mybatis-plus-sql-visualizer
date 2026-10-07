---
name: t-jdbcmultiline-plan
description: T-JdbcMultiline 已交付（master 0b178ff，3121 绿）——多行 JdbcTemplate 日志漏捕获两态机修复 + MyBatisCodeHelper-Pro 能力对照与精华取舍（ignoreSqlContains 核实=既有 logFilterKeywords 零代码）；遗留 T-PerfBatch/consoleFilterProvider EP 见独立计划
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c6208800-e78f-4681-b26b-6aada01cfb87
---

用户反馈 TLog 前缀的多行 JdbcTemplate 日志（`Executing prepared SQL statement [` 后 SQL 跨 8 行、`]` 独占一行）在 SQL 日志面板零捕获。根因：`JdbcTemplateLogParser` 的 SQL 正则要求 `[...]` 同行闭合（`\.\(.*\)\]`），逐行喂入架构下多行语句整体落空；`LogToSqlPanel` 粘贴路径同源同漏。`[TID: ...]`/`Line:711` 自定义 pattern 前缀无害（正则无行首锚定）。

**已交付**（2026-09-08，master 两笔提交 `0b178ff` fix + `b29e18d` docs 同步，全量 3121/0/0/6 绿，基线 3112+9）。核心：JdbcTemplateLogParser 加 HibernateLogParser 同款 IDLE/COLLECTING 两态机——`SQL_ACTION_PATTERN` 动作短语 + `scanBrackets` string-aware 净括号计数（单引号字符串内 `[`/`]` 不计数、`''` 转义、字符串态跨行延续）闭合；边界行（新语句/NamedParameter/参数行）与超 maxParseLength、closePending 强制闭合残句不误挂参数。附带修一个旧隐性 bug：快路径闭合点由「贪婪最后一个 `]`」改「首个平衡 `]`」，`[SELECT arr[1] FROM t` 旧式误提取为单行、现正确进入多行收集；标准单行用例零改动通过。

MyBatisCodeHelper-Pro（在售版 3.5.9+2321）能力对照结论：其 SQL 日志源自开源 BatsLog，只支持 MyBatis `Preparing:/Parameters:` 成对格式，无 JdbcTemplate/Hibernate 处理——用户样本它同样捕获不到，精华在工程化（批量队列 1s 排空、200k/50k 文档自清理、可配置前缀、consoleFilterProvider 稳定 EP），去重键为原文拼接弱于本插件指纹键勿回退。`ignoreSqlContains` 黑名单实施时核实=既有 `logFilterKeywords` 等价（SqlLogEntryBuffer.kt isFiltered + 设置 UI 齐备），零代码。

遗留（另立计划，见下方相关）：
- [T-PerfBatch] 吸收 MBCHP-Pro 批量异步 UI 精华——见 [[t-perfbatch-plan]]；
- consoleFilterProvider EP 双轨捕获调研——独立计划文档，只立项不实施。

**Why:** 用户项目跨行日志整条漏捕获是核心能力缺口；竞品反编译避免了"抄它就能修"的误判，并把平台脆弱点（反射桥 vs 稳定 EP）记入后续方向。
**How to apply:** 后续在 dev 上推进 T-PerfBatch / EP 双轨时，复用 JdbcTemplateLogParser 两态机与 batch 节流的教训（批量通知不能直接叠加在 `notifyChanged` 单通道上，须先做监听器分层，见 [[t-perfbatch-plan]]）。