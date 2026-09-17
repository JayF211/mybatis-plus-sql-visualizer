# T-ExecTimeoutGuard 执行链超时护栏补齐 — 开发计划

- 日期：2026-09-17 01:48
- 任务编号：T-ExecTimeoutGuard（第六轮新建议 **O-25**，P2；立项口径据实修正：原表述「超时护栏改线性公式」——dev 实测主执行链**从零**无 `setQueryTimeout`，落点修正为「补齐护栏并采用 DBX 线性公式」）
- 来源：第六轮报告 §7 DBX 吸收清单 **#28**——DBX `1ed06f2`：前端超时=每语句超时×语句数（去掉 ×2 基准放大与 60s 下限），超时回调**先取消/收尾再 reject**（queryTimeout.ts:60、queryStore.ts:596）
- 性质：执行链健壮性——长查询/挂死语句当前无兜底，控制台与 MCP 链都会无限等待
- 实施分支：合流后自 master 拉任务分支 t-exec-timeout-guard（前置项 T-MergeDev2Master）
- 验证基线：合流后 master 全量 testShards 绿

---

## 一、背景与依据

执行链是七环的「原地执行验证」环：用户在预览面板/日志转 SQL 面点执行，SQL 经共享控制台或直连通道跑真库。当前全链无任何超时护栏——`setQueryTimeout` 在插件主链零命中（唯一使用者是 agent 子工程的采集连接自身），一条挂死的查询（锁等待、笛卡尔积、远端库网络黑洞）会让结果面板永远停在 loading，用户没有任何自救入口。DBX 的实践提供了两个可照抄的决策：超时预算随语句数**线性**增长（不搞 ×2 放大和武断下限），以及超时触发时**先做取消与收尾再报错**（不留半截连接与悬挂 UI 态）。

边界先钉死：JDBC `setQueryTimeout` 是驱动级建议值，各驱动支持度不一（MySQL/PG 成熟，部分信创驱动可能忽略）——护栏按「尽力而为+诚实降级」设计，超时异常与驱动不支持的形态都要有可读反馈，绝不虚标「已超时取消」。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| 语句超时 | 主链 setQueryTimeout 零命中；agent 子工程 ParamCapture 自用（与本链无关） | `sub-projects/sql-visualizer-agent/.../ParamCapture.java`（仅参照） |
| 执行入口 | 面板执行三处（executeSql/executeAdhocSql/LogToSqlPanel.executeStatement）汇入 SqlExecutor/DataSourceSelector 反射链 | `execute/SqlExecutor.kt`、`execute/DataSourceSelector.kt` |
| MCP 执行链 | McpDbExecutor 直连执行（explain/check 链同样无超时） | `mcp/McpDbExecutor.kt` |
| 多语句 | SqlStatementSplitter 拆句能力已在位（语句数可得） | `util/SqlStatementSplitter` |
| 取消能力 | 未知——Statement.cancel() 是否有调用需实施时首查 | 实施时核实 |

## 三、方案

**T-ET-01（现状核实与落点圈定）**：核实 SqlExecutor/DataSourceSelector/McpDbExecutor 三链的 Statement 生成点与线程形态，圈定 setQueryTimeout 接入点清单；核实既有 cancel 能力。产出接入点清单后再动码。

**T-ET-02（超时预算公式）**：新增设置项「执行超时」（秒，默认 30，0=不限，追认进既有执行设置分组）+ 纯函数 `timeoutBudget(baseSecs, statementCount)`：单语句=基础值；多语句=基础值×语句数、**上限封顶**（如 ×8，与 DBX 线性精神一致且防滥用）；逐语句 setQueryTimeout(每语句基础值)，脚本整体预算作看门狗上限。

**T-ET-03（超时先收尾再报错）**：超时触发路径：优先 `Statement.cancel()` → 回收结果面板 loading 态 → 错误反馈「执行超时（N 秒），已请求取消；若驱动不支持取消请手动关闭控制台」；cancel 不可用/抛异常的降级路径如实措辞。错误形态走既有 onErrorRich 通道（T-ErrorLocate 地基）。

**T-ET-04（MCP 链同规）**：McpDbExecutor 同公式接入；超时输出按既有纯文本错误形态附「timed out after Ns」（不引 JSON）。

**T-ET-05（i18n + 测试 + 文档）**：双语键 2~3 个；表驱动——预算公式（单语句/多语句/封顶/0=不限）、超时收尾顺序（cancel→UI 复位→报错）、降级路径；H2/目标库真机超时用例以「环境受限未验证」口径处理信创驱动。CHANGELOG/变更记录/第六轮报告 O-25 回填。

## 四、验收标准

1. 预算函数表驱动全绿；0=不限与封顶路径覆盖。
2. 三执行链接入点全部生效或明确登记「该点不支持」（诚实降级，无静默漏接）。
3. 超时后无悬挂 loading、无半开连接泄漏（面板态复位断言）。
4. 默认值 30s 下既有测试与正常执行路径零行为变化（超时只在异常态触发）。
5. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. 预览面板对锁表场景执行 UPDATE：默认 30s 超时触发，面板可恢复，提示可读。
2. 多语句脚本（5 条慢查询）：预算按线性放大且封顶生效。
3. MCP explain 传必然慢查询：输出含 timed out 文本行。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 驱动不支持 setQueryTimeout | 部分信创驱动静默忽略 | 接入点逐链登记生效性；不支持则看门狗层（线程 interrupt+连接回收）兜底或如实标注 |
| 共享控制台链路取消语义 | 嵌入式控制台执行走控制台文档而非直连 Statement | T-ET-01 首查该链；控制台链若无法语句级取消，v1 只覆盖直连链并如实缩小承诺 |
| 超时误伤长跑合法查询 | 用户故意跑大查询 | 默认 30s + 设置项可调 + 0=不限；超时提示给出调大入口 |

## 七、实施顺序

```
T-ET-01（现状核实圈定接入点）→ T-ET-02（预算公式+设置项）→ T-ET-03（先收尾再报错）
→ T-ET-04（MCP 链同规）→ T-ET-05（i18n+测试+文档）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

**已实施（2026-09-17，dev 分支随「2.2 五计划 + T-PerfSweep4 + 尾巴项」批次）**：

- **T-ET-01（现状核实）**：主链 `setQueryTimeout` 零命中证实；`RemoteStatement`（DBN remote JDBC 镜像）javap 实证 243（jdbc-console.jar）与 262（intellij.database.jdbcConsole.jar）同形声明 `setQueryTimeout(int)/getQueryTimeout()/cancel()`，`RemoteConnection.cancelAll()` 在位；共享控制台链执行走 `console.executeQueries(editor, scriptModel, execOption)`（DBN 托管语句生命周期）且 `DatabaseSettings.ExecOption` 无超时面——**该点 v1 不支持，如实登记**（计划 §三允许的诚实降级）。
- **T-ET-02**：`execute/ExecutionTimeoutGuard.kt` 纯函数——`timeoutBudgetSeconds(base, count)`（单语句=基础值；多语句=基础值×语句数封顶 ×8；0=不限）+ `perStatementTimeoutSeconds`；设置项「执行超时」（秒，默认 30、0=不限）落 PluginSettings.State `executionTimeoutSeconds` + SQL 预览分组面板行 + 双语键 `settings.preview.executionTimeout(.tip)` + isModified/apply/reset 四接线。
- **T-ET-03**：`McpDbExecutor.executeQuery` 增 `timeoutSeconds` 参数（默认 0=不限，ExplainTool 从设置项透传）——执行前 setQueryTimeout（设置失败不拦执行，护栏尽力而为）；`InvocationTargetException.targetException` 经 `isTimeoutLike` 判中超时形态先 `Statement.cancel()` 收尾（RMI 尽力而为，失败只记日志不吞超时事实）再冒泡 `SQLException("query timed out after Ns (cancel requested); original: …")`；语句/连接 close 既有 finally 收口不变（无悬挂半开连接）。
- **T-ET-04**：MCP 链同公式接入（explain 单语句取每语句基础值）；超时输出附 `timed out after Ns` 英文文本行（不引 JSON，agent 可读）。
- **T-ET-05**：`ExecutionTimeoutGuardTest` 8 例（预算公式单语句/线性/封顶 8 倍与 9 条起裁/0=不限、超时判定 SQLTimeoutException/57014/消息签名、非超时反例语法错·连接拒绝·死锁、InvocationTargetException 解包、循环 cause 链深度 8 有界不挂死）；`McpDbSignatureChecker` 登记两条新反射入口（RemoteStatement.setQueryTimeout(int)/cancel()）跨版本核验；H2/信创驱动真机超时用例按「环境受限未验证」口径留真机走查。
- **共享控制台链接入点登记（v1 不覆盖）**：DBN 托管无语句句柄；预算函数作为该链接入时的规格先行交付（消费方接线时按 timeoutBudgetSeconds 取看门狗上限，类注释已载明）。
- T-ET-04 文档回填：CHANGELOG [Unreleased] Added、变更记录行、第六轮报告 §8 状态回填（随批次统一落）。
- 门禁：ExecutionTimeoutGuardTest 8 例定向绿；全量 testShards 随批次终门禁。真机走查 §五三条留人工。
