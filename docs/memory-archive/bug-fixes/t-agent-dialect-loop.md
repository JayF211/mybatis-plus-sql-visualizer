---
name: t-agent-dialect-loop
description: T-AgentDialectLoop 已交付（M56 分支 564356b/e3603c1，agent 24 绿 + 主项目
  4223/0/0/6 绿）——url 透传+目标库角标、接管期日志时间嗅探回填、三项计划外修复
metadata:
  node_type: memory
  type: project
  originSessionId: sess_36156c92-1da3-44cb-936c-4a685f1725a7
---

T-AgentDialectLoop（字节码增强全方言兼容与控制台日志时间闭环）2026-09-16 交付于 `feature/t-m56-01-agent-base`（worktree `mpsv-m56`，feat 564356b + docs e3603c1，未合 master 未 push）。计划：docs/开发计划/20260916-0422。

**需求四点落点**：①方言差异=采集面是驱动类清单（补 org.opengauss.Driver），事件 url 全链透传 + `JdbcUrlDialects` 子协议→显示名（独立映射，**不可走 DialectRegistry.byName——未知回退 MySQL 会误标**）+ `LogSqlEntry.databaseName` + 双页角标 + 历史 `| db:` 持久化；②接管期日志时间=`LogTimeCorrelator`（512 FIFO，指纹→logTime），`processConsoleLine` paused 分支嗅探复用三解析器（条目即弃），agent 入队前同构指纹回填，`ParserConfig.sniffLogTime` 快照门控（时间显示关/源=capture 时零成本）；③耗时链 agent 侧本就闭环（nanoTime→dn→executionTimeMs），零改动；④`JdbcTemplateLogParser` 级别正则提常量（五轮复查热点收口）。

**Why**: 三项计划外发现均有复用价值。**How to apply**:
- `URLClassLoader` 父优先：测试 classpath 有同名类时织入字节永远不加载——`ConnectWeaveEndToEndTest` 断言曾长期空真，须用子优先 loader（childFirstLoader）覆盖 FakeDriver*；真织入硬验收仍是 AttachGateTest。
- 测试串染范式坑：保存值若「finally 里现读」（set 后读到的已是改后值）则还原恒失败，同 worker 后续类遭殃；新测试类改变分片内类顺序会暴露潜伏串染——HistoryCellRendererTest 已改 setUp 捕获/tearDown 还原。
- LogSqlEntry 加构造字段 → 首跑必现陈旧产物劫持 NoSuchMethodError（第八犯），清 build/classes+build/kotlin 复绿。

相关：[[project-m56-agent-bytecode]]、[[t-perf-round5-report]]（JdbcTemplateLogParser:230 热点即本次提常量处）。
