# T-StatementWeave：agent 语句级织入——吸收 DebugTools「attach 即生效」精华——开发计划

> 2026-10-05 用户点名转正：原 [T-FirstUsePremainConsent 计划](20261005-1026-T-FirstUsePremainConsent-首次启用增强捕获首轮运行零捕获修复-开发计划.md) §三 S6「agent 语句级织入」从登记候选升级为本任务。用户质询「DebugTools 插件支持项目启动后贴附上，这个精华没有吸收吗」——结论：只吸收了 retransform 教训（`AgentMain` javadoc「DebugTools 半成品陷阱的硬验收项」），**织入点模型这个真正的精华没吸收**。本计划写完停等确认，未获确认不实施。

## 一、缺口归因（现状架构事实）

- 当前捕获模型：`SqlHookTransformer` 只织驱动 `connect(String,Properties)`，连接**创建时**包装代理；`StatementHandler`（采集核心：参数捕获/耗时/栈定位/批处理）是 `InvocationHandler`，活在包装代理后面。
- attach（agentmain）虽对已加载驱动类 `retransformClasses`，但改的只是 connect 方法体——只影响**之后新建**的连接；池化场景（Hikari）attach 前建好的老连接永远裸奔 → attach 兜底对存量连接结构性零捕获。
- DebugTools 类工具 attach 即生效的原因：织入点在**语句执行方法**（execute 族方法体）——钩子随方法调用触发，与连接/Statement 对象的创建时间无关，retransform 后下一次 execute 即带钩子。

## 二、已实证事实（2026-10-05 javap + zip 扫描，实施者可直接引用）

| 项 | mysql-connector-j 8.0.33 | h2 2.3.232 |
|---|---|---|
| 语句实现类 | `com.mysql.cj.jdbc.StatementImpl` / `ClientPreparedStatement` / `ServerPreparedStatement`（另有 pooled Wrapper 族见 §四.5） | `org.h2.jdbc.JdbcStatement` / `JdbcPreparedStatement` / `JdbcCallableStatement` |
| 带参形态 | `StatementImpl.execute(String)` / `executeQuery(String)` / `executeUpdate(String)`（含 int[]/String[] 重载） | `JdbcStatement.execute(String)`（final）/ `executeQuery(String)` / `executeUpdate(String)` |
| PS 无参形态 | `ClientPreparedStatement.execute()` / `executeQuery()` / `executeUpdate()` | `JdbcPreparedStatement.execute()` / `executeQuery()` / `executeUpdate()` |
| 连接归属 | `ConnectionImpl.getURL()` **public** 直调 | `JdbcConnection.getURL()` **包私有**（反射 + setAccessible，classpath 应用为未命名模块可行） |
| 内部字段线索 | StatementImpl 无裸 sql 字段（SQL 在 Query 对象链，`getQueryAsString()` 待 javap） | `JdbcStatement.batchCommands`（ArrayList\<String\>，批处理现成）/ `executingCommand`（CommandInterface，toString 是否含 sql 待验） |
| 批处理入口 | `addBatch(String)`（带参，可织入累积） | 同左 |

IDE 侧既有地基：`AgentEventAdapter` 事件消费、`AgentUrlRegistry`/`JdbcUrlDialects` URL 推断链、`CONFIG` 热下发、脱敏/跳过前缀/业务帧定位全部在 `EventBuilder` 下游——语句级钩子只须产出一个同构事件。

## 三、方案设计

### S1：DriverRegistry 语句面登记

- `isWeavable` 拆两面：驱动面（现 connect 织入，不动）+ **语句面**（新增：上述两驱动的语句实现类名单；按具体类名登记，织不上安全降级——沿用现 classloader 契约「织入期判定可见性、回调自吞一切 Throwable」红线）。
- 织入面匹配走精确名 + 前缀双轨（防驱动小版本类名漂移静默漏织；漂移即降级不炸宿主）。

### S2：StatementWeaver（新织入器，与 ConnectWeaver 并列）

- 织 execute 族方法体，**入口+出口双插桩**：入口解析 sql 并记 nanoTime 起点，出口组装事件（耗时/异常/影响行数对齐代理层 `StatementHandler` 语义）；异常路径记录类名+消息后**原样重抛**（宿主语义不变，代理层同红线）。
- 带参形态 sql 从方法参数 aload 直取；无参 PS 形态从 S3 弱映射取，取不到按 S3 盲区策略处置。
- `executeBatch` 从 `addBatch(String)` 织入累积的批串组装（弱映射/字段，同 PS 口径）。
- 回调入口 `SqlHookCallbacks.onStatement(...)` 新增静态方法（签名被织入字节码硬编码，与 ConnectWeaver↔onConnection 同步约束相同，javadoc 互指）。
- retransform 合规：只改方法体、不增删成员（与 ConnectWeaver 同约束，agentmain 可重转换前提）。

### S3：SQL 解析链与盲区处置

- **PreparedStatement sql 登记**：织驱动的 `prepareStatement(String)`（及 `nativeSQL` 不需要）出口，把「PS 实例 → sql」写入**弱引用映射**（WeakHashMap/Identity 弱键，PS 被 GC 或 `close()` 织入移除——内存边界见 §四.1）。attach 后新建的 PS 全覆盖。
- **attach 前已存在的 PS**（映射未登记）：一期**跳过不上报 + 计数器**（`unresolvedPreparedStatements`，宿主侧可观测）；驱动字段反射兜底（mysql Query 链 / h2 executingCommand）列二期，先证伪 §四.2/3 再做。
- Statement 带参形态零盲区（sql 就在参数里）。

### S4：连接归属链

- 语句钩子从 `statement.getConnection()` 拿物理连接，URL 解析顺序：包装代理标记（attach 后新连接已带）→ 驱动内部 `getURL()` 反射（mysql public 直调 / h2 setAccessible 包私有）→ 最近 connect 钩子 URL 兜底 → 空（IDE 侧推断链接管，事件 url 允许空）。
- 方言识别复用 `JdbcUrlDialects`，空 url 事件面板按「来源未知」既有降级显示。

### S5：attach / premain 接线

- `AgentMain.retransformLoadedDrivers` 扩展为双面（驱动面 + 语句面）：attach 时对**已加载的语句实现类** retransform——这是「attach 即生效」的直接落点。
- premain 路径零改动（transformer 挂上后新加载类自动织入）。

### S6：IDE 侧配套

- 无 url 事件消费兜底（推断链）。
- **文案口径修订**：R2 S2 能力边界通知与状态行从「挂载前的连接不在捕获范围，重新运行后完整生效」改为「已挂载，此后执行的 SQL 即时可见；挂载前已 prepare 且未再执行的语句不在范围内」——attach 兜底价值从「下轮才完整」升级为「立即可用」。
- attach 兜底挂载成功后可同步触发一次回执性提示计数（登录 idea.log，不新增弹窗）。

### S7：测试与反向验证

- agent 单测：`FakeDriver` 扩展语句实现类 + 织入端到端（对齐 `ConnectWeaveEndToEndTest` 形态）：带参 execute / PS 映射形态 / 盲区跳过计数 / 批处理 / 异常原样重抛 / url 反射失败降级。
- 真驱动端到端：mysql 8.0.33 + h2 2.3.232 双 jar 驱动级测试（现有真驱动测试同形态），**attach 时序模拟**：先建连接池 → 再 install（retransform 语句面）→ 断言老连接上的后续 execute 产出事件（本任务的核心判别用例）。
- 反向验证：摘 S5 的语句面 retransform → 上述 attach 时序用例必红（两次读数留档）。
- IDE 侧：`AgentPremainConsentTest` 文案断言随 S6 修订；agent+config 全包回归 + `testFast`。
- 宿主零影响红线验证：钩子内异常注入 → 宿主 SQL 执行结果不变（现有采集红线测试同款）。
- 沙盒走查（留下轮批可并）：长跑 demo 中途 attach 场景面板可见性 + 老连接盲区计数展示。

## 四、待验证前提（实施者先证伪再动手）

1. **PS 弱映射内存边界**：键必须弱引用（PS 池长持有不泄漏）；规模上限与驱逐策略；`close()` 织入移除的时机准确性（连接池复用 close 是归还不是关闭——归还后映射应保留，判据=GC 而非 close，倾向只按 GC 清理不织 close）。
2. **mysql sql 字段反射链**：`StatementImpl` 的 Query 对象链（`query` 字段 → `getQueryAsString()`）逐环 javap 实证后才可做字段兜底；一期不做则盲区跳过策略即终态。
3. **h2 字段兜底可行性**：`executingCommand`（CommandInterface）的 toString/内部字段是否含原始 sql；`batchCommands` 能否直接服务批处理事件。
4. **插桩复杂度**：入口+出口双插桩在 ASM 层对多返回点方法（catch/finally/异常路径）的覆盖——若复杂超预算，降级方案=出口单点（duration 缺省，sql 仍可得），先保功能后保耗时精度。
5. **Wrapper 族是否登记**：mysql `StatementWrapper`/`PreparedStatementWrapper` 是 pooled/connection-wrapper 形态，Hikari 默认透传原生句柄大概率不需要；误登记会撞 `ProxySupport.isOurProxy` 幂等（无害但浪费）。先只登记原生实现类，实测漏了再补。
6. **性能红线**：每条 execute 过钩子的 volatile 短路成本须与现代理层同级（现有 perf 测试口径跑对照）；入口解析 sql（带参形态）是字符串引用传递非拷贝，预期零成本。
7. **驱动版本面**：实证面为 demo 工程在用版本（mysql 8.0.33 / h2 2.3.232）；更老/更新版本类名漂移按 S1 前缀+降级契约兜住，不追全版本兼容。
8. **`executeBatch` 空批/混合批**语义与代理层 `batchSqls` 组装口径对齐（防止同一批两种通道产出两种格式）。

## 五、验收标准

1. **核心**：应用已运行、连接池已建好的进程，中途 attach 后，**老连接上执行的新 SQL**（Statement 带参 + attach 后新建 PS）在面板即时可见——这是与现状的判别性差异。
2. premain 场景全链路回归绿（现行为零变化）。
3. 盲区如实：attach 前 prepare 的 PS 不产生错误/伪 sql 事件（跳过 + 计数可见）。
4. 宿主零影响：钩子异常自吞、SQL 执行语义与结果不变（红线测试）。
5. 反向验证「摘语句面 retransform 必红」两次读数留档；agent 定向 + `testFast` 绿。

## 六、范围外（登记不做）

- 更多驱动（PostgreSQL/Oracle/达梦等）语句类登记：随真库验证轮扩面（本任务架构留好登记位即可）。
- 驱动字段反射兜底（mysql Query 链 / h2 Command）：二期，前提 §四.2/3 证伪后立项。
- redefine（非 retransform）路径：不碰（增删成员能力带卸载屏障语义，风险面大收益小）。
- connect 织入与包装代理层不拆：语句级织入是**补位**通道，代理层对新连接的参数捕获/耗时精度更优，双通道并存按事件幂等去重（同语句同时间窗一条）——去重判据随 S7 定。


## 七、实施记录（2026-10-05 当轮回填，工作树 mybatis-plus-sql-visualizer-tsw @ t-statementweave）

### 落地内容

- **S1 登记单源化**：语句面/prepare 面名单与织入形态标注统一在 `StatementWeaver.SPECS`（`Kind[]` 按类标注），`DriverRegistry.isStatementWeavable/isPrepareWeavable` 委托 `specOf` 判定——防两份名单漂移（守卫钉住）。登记面：mysql 8.x（StatementImpl/ClientPreparedStatement/ServerPreparedStatement）+ 5.x 遗留路径 + h2 2.x（JdbcStatement/JdbcPreparedStatement/JdbcCallableStatement）；prepare 面：ConnectionImpl（mysql 双包路径）/JdbcConnection。
- **S2 织入器**：`StatementWeaver`（ASM Tree API，入口+出口双插桩）。出口覆盖 **ARETURN/ATHROW/IRETURN/LRETURN** 四类返回点——executeUpdate→int、execute→boolean 走 IRETURN，executeLargeUpdate→long 走 LRETURN，结果装箱（Integer/Long.valueOf）后恢复原值返回；PREPARE 面只插 ARETURN，ADD_BATCH 只插 RETURN。
- **回调四件套**（`SqlHookCallbacks`）：`onStatementEnter`（token=nanoTime，停采 volatile 短路 + 代理层门禁双闸，0=短路）／`onExecuteExit`（EXEC 与 BATCH 统一签名，批 SQL 按 op 名从登记表取）／`onAddBatchExit`（批串累积）／`onStatementPrepare`（PS→SQL+URL 登记）。`WovenEvents` 组装事件（字段语义对齐代理层；params 置空与 timestamp 出口回推两处结构性差异如实注释）。
- **S3 归属登记**：`StatementRegistry`（WeakHashMap 弱键，PS GC 即清账；盲区跳过 + `unresolvedCount` 计数可观测；`clearForTest` 测试缝）。`UrlResolver`（getURL 按类缓存 Method，h2 包私有 setAccessible；`noteConnectUrl` 在 onConnection 记最近 URL 兜底）。
- **S4 归属链**：ctx.url（prepare 时连接）→ `stmt.getConnection()` 反射 → 驱动 getURL → 最近 connect URL → 空（IDE 推断链接管）。
- **双通道去重门**：`WeaveGate`（ThreadLocal）——`StatementHandler.executeWithCapture` 在 `method.invoke(raw)` 期间置位，织入入口读到即短路。不设门：包装连接的语句 invoke 到的 raw 方法体已被织入，同一条 SQL 双通道双事件。
- **S5 attach 即生效落点**：`AgentMain.retransformLoadedDrivers` 扩展三面（驱动 connect + 语句 + prepare），attach 时对已加载语句实现类 retransform——存量对象的下一次 execute 即带钩子。
- **S6 IDE 侧**：能力边界通知双语口径升级（「此后执行的 SQL 即时可见（含挂载前已存在的连接）；挂载前已 prepare 且未再执行的语句不在捕获范围内」）。
- **S7 测试**：`StatementWeaveEndToEndTest` 8 例（带参/登记/盲区计数/门禁短路/停采短路/批处理/异常原样重抛/loader 契约）+ 主仓 `StatementWeaveWiringGuardTest` 6 例（源码扫描接线守卫 + 登记单一真相 + 通知口径）。

### 实施过程三坑（留档）

1. **出口只认 ARETURN 的盲织**（探针实证）：首版出口只插 ARETURN/ATHROW——executeUpdate→int、execute→boolean 走 IRETURN，整个出口钩子没插上，织入体执行但零事件。修正为四类返回点分派 + 基本类型装箱。
2. **IRETURN 装箱参数顺序反**：`Integer.valueOf` 先于 `ILOAD` 压栈 → VerifyError（Bad type on operand stack）。先压参再调 valueOf。
3. **BATCH 出口栈错位**（红探针钓出）：BATCH 出口块按 6 参压栈（多压一个 sql 槽）但 `onBatchExit` 描述符只收 5 参——token 读到垃圾值、failure 槽套上 int[]，回调链静默错乱且零事件零计数。定案=撤掉独立 onBatchExit，EXEC/BATCH 统一 `onExecuteExit` 签名（sql 槽 BATCH 传 null，op 名分支在回调内）。教训：**织入块的压栈序必须与回调描述符逐槽对账，多一个 ACONST_NULL 都是无声灾难**；这类错误 VerifyError 都未必报（栈形态经 COMPUTE_FRAMES 后可能恰好可验），只有运行期行为探针能钉住。

### 待验证前提的实测落账（对照 §四）

1. 弱映射：WeakHashMap 弱键 + 不织 close（连接池归还≠废弃）——按定案落地，泄漏面=弱键本身。
2/3. mysql Query 链/h2 Command 字段兜底：一期未做（盲区跳过+计数），维持二期登记。
4. 插桩复杂度：入口+出口双插桩落地（含基本类型返回分派），未走「出口单点」降级案。
5. Wrapper 族：未登记（原生实现类先行），守卫登记名单可随时扩。
6. 性能红线：双闸均为 volatile 读，与现代理层同级；对照性能轮（F 系列）未跑，登记下轮性能报告批。
7. 驱动版本面：demo 在用版本已 javap 实证；漂移按织不上降级契约。
8. batch 口径：织入通道批串格式 `;
` 连接与代理层 batchSqls 一致；双通道并存去重由 WeaveGate 保证（代理层优先，织入通道只服务无代理的老连接——天然互斥，无需时间窗去重）。

### 反向验证与读数

- **红**：摘 `AgentMain` 语句面/prepare 面 retransform → `test接线守卫_retransform三面_语句面摘除必红` 红（断言「retransformLoadedDrivers 必须对语句面 retransform（attach 即生效落点，摘掉必红）」）。
- **绿**：恢复后 agent 子项目 **101/0**（含 8 新例）+ 主仓定向 **31/0**（WiringGuard 6 + AgentPremainConsent + AgentConsentFlow）。
- **真机时序判别用例留下轮 runIde 批**（单测 JVM 无 Instrumentation，retransform 语义无法纯单测复现——本类只证织入通道自身正确；「先建池再 attach 存量连接可见」的端到端判别随下轮沙盒走查）。

### 遗留

- 全量门禁（testShards/agent 子项目全量）按三档分层另派只读收口代理。
- 性能对照（织入钩子每条 execute 的固定开销）随下轮性能报告批（§四.6）。
- 更多驱动语句类登记随真库验证轮扩面（§六）。

## 七A、F74 合并前置修复（20261006 合并评审轮）

性能二十三轮审计 F74 定案「合并前必改」，随本轮合并命令一并实施：登记批串 `executeBatch` 消费后不复位 + `clearBatch()` 未织入——同一条 Statement 批串跨批次只增不清（内存/组装耗时双无界 + 下一批事件携带上一批残串）。修复三件：

1. **消费即复位**：`onExecuteExit` 批串读后 `setLength(0)`（成功与异常出口同口径，对齐 JDBC 驱动批缓冲语义）。
2. **clearBatch 面登记**：`StatementWeaver.Kind.CLEAR_BATCH`（`clearBatch()V`，RETURN 单点插桩）+ 回调 `onClearBatchExit`（停采/代理门禁同款双闸；有登记才清不建账，清账语义 `StatementRegistry.clearBatchOf`）。
3. **规模边界定案**：复位+清账后批串天然有界＝宿主单个批周期内 addBatch 总量，与驱动自身批缓冲同寿，**不另设截断上限**（截断会静默篡改事件内容，比无界更糟）。

反向验证两读数（摘修复三刀＝删复位行 / 摘 statementFace 的 CLEAR_BATCH / 摘 `matches()` case；注意 e2e 走测试缝自传形态面，摘 SPECS 登记杀不到 e2e——行为红必须摘 `matches()`）：

- **红**：e2e 10 例 2 红（`批处理_executeBatch消费即复位_跨批次残串不得串包` + `clearBatch织入_清账后executeBatch零残串`）+ 主仓守卫 `test接线守卫_F74批串消费复位与clearBatch清账` 红。
- **绿**：恢复后 agent e2e **10/0** + 主仓守卫 **7/0**（守卫四件套升级五件套）。

过程坑留档：agent 子项目被根项目 `test` 任务依赖且 `failOnNoMatchingTests=false`——根项目带 `--tests` 过滤器跑守卫时 `:sql-visualizer-agent:test` 会以空匹配重跑并清空结果目录，e2e XML 随后跑被清非假绿；绿读数须在 e2e 单跑后立即取 XML。
