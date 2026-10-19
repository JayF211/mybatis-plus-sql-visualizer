---
name: t-logrownumber-plan
description: SQL日志列表行号已交付（master c83fb56，3173 绿）——捕获序号语义、buffer 入队分配、processStarted 无条件清零、removeEntry 同一性删除修复
metadata:
  node_type: memory
  type: project
  originSessionId: sess_144fcf04-5442-4147-8a7d-9707c2e9de7f
---

**T-LogRowNumber（2026-09-09 立项并交付，master c83fb56，3173/0/0/6 绿）**：用户需求「sql日志的sql列表需要增加行号，每次启动项目时清0」。计划文件 `docs/开发计划/20260909-1339-T-LogRowNumber-SQL日志列表行号-开发计划.md`（含 §实施记录）。

**核心决策**：
- 行号 = **会话内捕获序号**（非显示位置序号）——排序/过滤/头部淘汰下保持稳定可引用；`LogSqlEntry` 加 `sequenceNumber: Long = 0`（0=历史文件/round-trip 无行号）。
- 分配点：`SqlLogEntryBuffer.addEntry` 成功入队临界区内 `AtomicLong.incrementAndGet()` + `entry.copy`——去重丢弃/暂停/过滤关键字不占号。
- 清零点：`RunConfigurationProcessListener` processStarted 分支**首行无条件**调 `manager.resetEntrySequence()`——不放 `onProjectProcessStarted`（其 `!autoStart` 守卫会跳过手动捕捉模式的清零，违背无条件语义）。同坑先例：T-AutoShowRecover 项目启动清自动展开抑制走同一点位。
- `clear()` 一并清零（行号与列表内容同生命周期惯例）；列表内容不随启动自动清空（需求只说行号清 0）。
- 渲染：元信息行最前 `#N ` 灰色片段（LIST_META_GRAY），片段管线零结构变更，不动固定行高；不加设置开关（防假开关回归）。

**实施中新学（防复踩）**：
- **buffer 入库带号 copy 后，data class equals 断层**——`addEntry` 内 `entry.copy(sequenceNumber=…)` 入库，外部原型与库存实例 equals 不再相等（新字段参与相等性），`removeEntry` 按 equals 删外部原型永不命中 → 右键删除失效。修复=同一性删除（`entries.removeAll { it === entry }`，面板引用本就来自库存实例）。教训：给 data class 加「分配型字段」（copy 注入）必须全仓审计 equals 依赖点。
- **并行会话 sandboxContainer 行互写**——build.gradle.kts 该行会被对方会话随时改写，每次跑测试前必须重读核对；全量回归被并行 worker 竞争打断（ServiceNotReadyException/MessageIOException）时按登记表 `--no-daemon` + 独立沙盒重跑。
- **顺手修并行会话编译错须留工作树**——T-ExecCopyFix 在 `SqlPreviewPanel.kt` 的 `flashCopied(this)`（apply 块内 this 类型错）阻塞全员测试，一行改 `errorCopyButton?.let { flashCopied(it) }` 解锁，但不提交进己方 commit（文件归属对方会话）。

**已知边界**：processStarted 对所有 RunProfile 触发（.http 会提前清零行号，可接受）；历史 tab 不动。

相关：[[t-previewlistreload-plan]]、[[t-autoshowmaster-delivery]]、[[project-test-conventions]]

**2026-09-10 追加（dab41e3）**：`processStarted` 无条件清零是错的——EXECUTION_TOPIC 的 processStarted 对**任意**运行配置进程触发（HTTP 请求脚本/profiler/远程调试/重复 Run 的测试），用户反馈「项目启动中多次发请求都从头计数」。修复=主配置 gating：`env.getRunProfile()` 返回的 RunProfile 仅当 instanceof `ModuleBasedConfiguration`（Application/JUnit/SpringBoot 共同父类）才清行号。教训：**execution 域 processStarted ≠ 主应用启动**，挂「会话级清零」这类状态副作用时必须按 RunProfile 类型过滤非主进程。
**2026-09-10 再追加（6876689）**：点击「移除重复的sql」漏裁跨窗口重复——manual 全量裁剪 `dedupeExisting` 曾复用运行时去重窗口 `dedupeWindowMs`（60s），把「短时间重复打印」的运行时语义误带进「手动一次性全量去重」，跨窗口累积的同文本 SQL 点击去重后仍在列表。修复=去时间窗口条件纯按 `dedupeKey` 判重；运行时 `isDuplicate` 窗口语义不变。教训：**运行时去重的窗口语义 ≠ 手动全量裁剪语义**，两个入口共享 `dedupeKey` 但不应共享时间窗口条件。

**历史 tab 与行号/去重物理隔离（2026-09-10 用户确认核实）**：历史面板 `HistorySqlLogPanel` 二级 SQL 列表用独立渲染器 `HistoryCellRenderer`（元信息行仅 [耗时][时间戳][来源]，无 #N 行号片段），数据源是 `SqlLogFileReader.readFromFile` 直读磁盘文件（`LogSqlEntry.sequenceNumber` 默认 0，`>0` 守卫下即使加行号也不显示），无去重勾选框、不经过 `dedupeExisting`。故「行号」与「移除重复」的两个 bug 均只存在于主 SQL 日志面板 `SqlLogPanel`，历史面板不受影响——两个列表三层隔离：渲染器不同、数据源不同（磁盘文件 vs 内存缓冲）、控制按钮不同。
