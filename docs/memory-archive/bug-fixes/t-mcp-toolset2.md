---
name: t-mcp-toolset2
description: T-McpToolset2 MCP 二期已交付并合入 dev（2026-09-15，未合 master）——工具 3→10 + 设置页门控（总开关+工具级），一工具一 toolset 类
metadata:
  node_type: memory
  type: project
  originSessionId: sess_ebb2f76f-0aba-4243-b361-f39b47100472
---

T-McpToolset2（计划 `docs/开发计划/20260915-1430-T-McpToolset2-MCP能力二期工具扩容-开发计划.md`）2026-09-15 实施完毕并**已合入 dev**（t-mcp-toolset2 → dev 快进合并 f6533f7，其后 6a93bac 回填文档；dev 未 push、未合 master）。合并后全量 testShards 367类/4421/0/0/6 绿（基线 350/4267 → +17 类 +154 例）；verifyGate 三目标 exit 0；独立子代理复核 9/10。**合并踩坑**：dev worktree 旧 build 目录直接跑出成片 NoSuchMethodError（陈旧产物劫持），清 build/classes+build/kotlin 即绿——[[pitfall-regression-false-failure-flakes]] 同型。

**核心决策与沉淀（跨任务可复用）**：
- **平台 per-tool 门控只有 toolset 粒度 `isEnabled()`**——262 mcpserver.jar 字节码实证 `McpToolset.Companion.getEnabledToolsets` = EP getExtensionList() 过滤 isEnabled，每次 getTools 重新求值 → 设置开关**即时生效无需重启**。工具级开关 = 一工具一 toolset 类 + McpToolsetSupport 抽象基类（三钩子 final 字面量覆写保 253 兼容）。
- **243 测试 JVM 缺 `SpillingKt`**（一期已知，audit 高阶 suspend 结构性发射）：测试进程直接实例化 Tool 类调用挂起方法 → NoClassDefFoundError。解法=工具核心抽 internal 非 suspend 纯函数，挂起方法只做 audit 一行委托，测试走纯函数层。
- **mcpStub 桩源集要挂 testShardN 分片任务 classpath**（IPGP 分片默认不含桩输出）——定向 `test` 绿、分片红成片的实证；compileTestKotlin 也要挂。
- **fe11c97 合并遗留三处回归被本次顺手收口**：PluginXmlStructureGuardTest 子设置页 count 5→7、PluginSettingsFolderConfigurable.SUB_PAGES 补 shortcuts、McpScopeSourceScanTest 增 stripStrings 字符串字面量豁免（`Class.forName("com.intellij.mcpserver.settings...")` 反射字符串非类型引用）。
- 英文文案 helper：`MpSqlVisualizerBundle.messageEn`（Locale.ROOT + utf8Control 读 base 英文，零新键）——agent 面警告渲染用。
- 设置页工具复选框与总开关**正交**（复选框只表达「不在禁用清单」，否则 master off 会把全工具误写禁用清单）。
- 七新工具：render_logs/check_sql/rollback_plan/perf_advice（R1 纯逻辑）+ crud_sql/reverse_sql/mapper_check（R2）；mapper_check simulate 对渲染产物过 UnboundedWriteDetector/SqlPerfAnalyzer=「校验渲染产物」位差落点。
- 相关：[[t-competitive-round4-plans]]（N-16 一期）、[[pitfall-regression-false-failure-flakes]]（SpillingKt 类似假失败形态）。
