# T-AgentCudSource 开发计划：增删改语句源码位置缺失修复 + 方言增强捕获能力矩阵

> 立项日期：2026-09-16｜分支：master
> 需求来源：用户反馈「增强捕获下只有查询语句正常捕获到源码位置，添加/修改/删除未成功捕获」；并顺带要求盘点插件全部方言是否都可用增强捕获（达梦 DM 用户反馈捕获不到）。

## 一、问题分析与根因（两轮子代理深挖 + 主会话复核）

### 1.1 现象

- 增强（agent 字节码注入）模式下，SELECT 查询语句能捕获到源码位置（面板可跳转），INSERT/UPDATE/DELETE 增删改语句捕获不到。
- （背景）某 DM 达梦数据库项目反馈：增强捕获没捕获到 idea 控制台的 SQL，也没捕获到名字，疑似「在线但零捕获」。

### 1.2 采集链路对语句类型无分流（子代理结论，复核通过）

agent 采集对 SELECT 与 CUD **完全同构、无按 SQL 类型分支**：

- `StatementHandler.invoke` → `EventBuilder.isExecuteFamily(name)`（`EventBuilder.java:13-17`）把 `execute/executeQuery/executeUpdate/executeLargeUpdate/executeBatch` 一并纳入，SELECT 与 CUD 均命中同一 `executeWithCapture`。
- `emitEvent`（`StatementHandler.java:110-141`）无条件先 `StackLocator.locate(hint)`（123 行），在判定操作类型/updateCount **之前**填 `sourceFile/sourceLine/methodKey/candidates`——无「只有查询才采集栈」的分支。
- IDE 侧 `AgentEventAdapter.toEntry`（`AgentEventAdapter.kt:27-33`）纯直通，无 CUD 置 null；面板 `resolveEntryJump/formatSourceFragment` 只依赖 `sourceFile == null` 判断，不区分语句类型。

→ 当前 master 代码里**不存在**「executeUpdate 漏调 StackLocator / IDE 对 CUD 置 null」这类结构性缺陷。缺口的真正根源在运行期调用栈构成。

### 1.3 根因：`StackLocator` 调用方缓存把「null 位置」永久锁进缓存（本计划修复主体）

`StackLocator.locate()`（`StackLocator.java`）缓存逻辑存在一个**可自我毒化**的缺陷：

- 缓存键 = **首个非框架帧**的 `class#method`（98-104、154 行）。
- 主位置无调试信息（`primaryFile == null`）时（139-147 行）：若候选首位也是 null-file，会把 `candidates.remove(0)` 的 null 直接顶上 → `primaryFile` 仍为 null → 构造出 `sourceFile == null` 的 `Frame` → **`CACHE.putIfAbsent(primaryKey, frame)` 把 null 缓入**（154 行）。

后果：任一用户方法某次调用恰好解析出 null 主位置（CUD 常走的 MyBatis-Plus `BaseMapper` 默认方法 / `$Proxy`/`java.lang.invoke` 派发中间帧无调试信息时即会命中——这些帧不落在框架前缀名单里、又没有有效 file），该 `class#method` 键即被**永久缓存为 null**，该方法名下**所有后续语句（含本应正常的 CUD）都失去源码位置且不再自愈**（每来一次命中缓存直接 return null）。SELECT 方法若恰好先缓存了有效帧则正常——正对应「查询有、增删改无」的观感。

> 佐证：agent 侧对「真实 CUD 执行 → 断言事件 sourceFile 非空」**没有任何端到端用例**（`ConnectWeaveEndToEndTest` 只断言 executeUpdate 原语义透传/返回行数，未断言 sourceFile；`CaptureRulesTest` 的 StackLocator 用例都从测试线程自身栈取帧）。缺陷长期未被绿灯拦下。

### 1.4 方言与增强捕获能力矩阵（副任务结论，供全方言盘点）

- **增强采集面是 JDBC 接口级、与数据库类型无关**：对插件全部 9 种方言（MySQL/MariaDB/PostgreSQL/Oracle/SQLServer/H2/SQLite/DM/KingBase）+ GBASE/HSQL/openGauss 一视同仁，不走「按方言分支」。
- **真正的耦合点是 `DriverRegistry` 驱动类名单**（`.java:14-42`，16 个精确类名）：DM 仅登记了 `dm.jdbc.driver.DmDriver` 一个精确类名，无模糊/变体匹配。若目标应用达梦驱动类名不同、加载晚于 `agentmain` 的 `retransformLoadedDrivers`（`AgentMain.java:66-77`）、或驱动 loader 隐藏 agent 类（`ConnectWeaver.java:48-49` 安全降级）→ 该类驱动不织入。
- **「在线但零捕获」是静默的**：`AgentManager.updateOnline` → `SqlCaptureGate.paused=true`（`AgentManager.kt:266` + `SqlLogConsoleBridge.kt:676`），在线时连控制台解析也短路——若驱动没织入，两边都无 SQL，且无「在线零捕获」告警（增强角标已按用户要求移除）。

## 二、方案设计

### A. 修复 `StackLocator` null 缓存自毒化（本期主体）

`StackLocator.java` `locate()` 收口两处：

1. **候选顶上改挑非 null**：主位置为 null 时，从候选中循环找第一个 `[0] != null` 顶上；候选也全 null 则 `return null`（不缓存，本次调用无有效位置——下次同方法**重试**而非被永久锁死）。
2. **null 位置绝不入缓存**：`CACHE.putIfAbsent` 前加 `primaryFile != null` 守卫（双保险，杜绝方法级常驻缺失）。

同时顺手把候选中无 file 的项在候选构建时过滤（可选，保留无害项避免过度改动——本计划**不**动候选构建，仅加守卫与挑选逻辑，改动最小）。

### B. 补 CUD 源码位置端到端守卫用例（收口子代理点出的测试缺口）

agent 子项目新增用例：真实 `PreparedStatement` 代理执行 `executeUpdate`/`execute`（CUD 形态），断言 `EventBus` 出队事件的 `sourceFile` 非空且指向用户帧（测试自身的调用栈帧）——首次把「CUD 事件必须带源码位置」锁进绿灯，堵住本节缺陷的回归面。

### C. 方言增强捕获能力矩阵文档化（副任务结论落地）

新增/更新说明，明确：
- 增强对 9 方言 + GBASE/HSQL/openGauss 全兼容（采集面与库无关）；
- 「在线但零捕获」的三类已知诱因（驱动类名不符 / 加载时序晚 / loader 隐藏降级）与排查路径（看 IDE 日志 `AgentManager` 在线态切换 + 目标应用实际驱动类名核对）；
- DM 达梦：`dm.jdbc.driver.DmDriver` 已登记，前提是目标应用确用该驱动类名且正常织入；若仍零捕获，属运行期环境问题需真机证据，**非本期代码修复对象**（记录在案，待用户提供驱动类名/日志后续跟进）。

## 三、任务分解

| # | 任务 | 文件 | 验收 |
|---|---|---|---|
| T-ACS-01 | StackLocator null 缓存自毒化修复 | `sub-projects/sql-visualizer-agent/.../util/StackLocator.java` | 主位置为 null 不缓存；候选挑非 null；候选全 null 返回 null 不缓存 |
| T-ACS-02 | CUD 源码位置端到端守卫用例 | `sub-projects/sql-visualizer-agent/src/test/.../StatementSourceLocationTest.java`（新增） | executeUpdate/execute 事件 sourceFile 非空且为用户帧 |
| T-ACS-03 | 方言增强捕获能力矩阵记录 | 计划回填 + CHANGELOG + docs/MEMORY.md | 能力矩阵与 DM 排查路径落位 |
| T-ACS-04 | 文档三处同步 | 本节 + CHANGELOG + docs/变更记录.md | 交付记录/变更条目 |

## 四、测试与门禁

- agent 子项目 `:sql-visualizer-agent:test` + 主项目全量 `test`（日常门禁口径）。
- 并行约束：已 `python parallel_tasks.py check`（无活跃任务，用默认沙盒）。
- 陈旧产物注意：agent 侧改动首跑先清 `sub-projects/sql-visualizer-agent/build/`，防 StaleSkip。

## 五、风险与非目标

- **非目标**：本次不臆造「CUD 分支漏采集」类修复——静态证据不支持存在该类结构性缺陷，真正的修复是缓存自毒化防治 + 用守卫用例把行为锁住。若真机验证发现除缓存外还有运行期调用栈差异，按新增证据另行立项。
- **DM/方言**：「在线但零捕获」若无驱动类名/IDE 日志证据无法定案，本期仅记录矩阵与排查路径，不擅改代理逻辑（禁猜 API、禁无证据改线）。
- **风险**：返回 null（不缓存）语义变化——从「该调用方永久缺失位置」变为「每次重试」；重试成本（per-execute 全栈解析）仅在 null 命中时发生，正常路径仍走缓存，热路径零回归。