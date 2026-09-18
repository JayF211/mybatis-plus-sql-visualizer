# T-SourceJumpFunnel 自研框架汇聚入口跳源码失准（堆栈上溯与候选纠偏）— 开发计划

- 日期：2026-09-17 17:26
- 任务编号：T-SourceJumpFunnel
- 来源：用户报告（公司内自研数据访问框架场景）——跳转源码全部落在 `DataUpdateTool.findByFilter`，落不到真实业务调用行
- 定性：**架构级适配缺口**（读码可证判定链在该场景失效的具体机制，主位置锁定路径需真机日志定谳）

---

## 一、背景与症状

用户业务代码形如（参数名提示为 `schemaCode` / `filterExpression`）：

```java
List<? extends Map<String, Object>> projectMaps = DataUpdateTool.findByFilter(
        SCHEMA_CODE_XXX,
        Q.it(field: "plan_id", op: FilterExpression.Op.Eq, value: planId));
```

该框架不经 MyBatis/MP：`DataUpdateTool.findByFilter` 是「元数据 → 动态 SQL（SQL Visitor）→
Spring NamedParameterJdbcTemplate → JDBC」自研链路的唯一门面，业务 → 引擎内部
（Facade → Service → LocalDbService → Repository(dml)）层层向下，全部 SQL 汇聚于同一个入口方法。

对本插件的影响：MyBatis 拦截器链路（SqlSourceInterceptor / CallerSourceLocator）在该框架下没有拦截点，
源码定位**完全依赖 M56 agent 链路**；而 agent 捕获的源码位置恒为 `DataUpdateTool.findByFilter`，
所有语句的跳转都堆到这一个框架方法上。

## 二、现状链路（读码实测）

捕获链（T-M56 / T-AgentSourceJump 交付形态）：

1. agent `StatementHandler.java:123` 在 execute 时调 `StackLocator.locate(hint)` 取栈；
2. `StackLocator`（`sub-projects/.../agent/util/StackLocator.java`）三层判定：
   - **L2 前缀名单**（`:30-52`）：跳过 JDK/驱动/池/ORM/Spring 等开源家族前缀，刻意不收通用工具库；
   - **L1 来源过滤**（`:114-121`）：优先取「非 JAR 来源」帧（开发态业务类在目录、第三方在 JAR，
     按 `ProtectionDomain.getCodeSource` 判定）；
   - **安全网**（`:122-126`）：L1 无命中（fat jar 等）回退首个非框架帧 pool[0]；
   - 主位置无调试信息时从候选逐级上顶（`:139-149`）；
   - **调用方缓存**（`:88-104` + `:151-159`）：缓存 key = **首个非框架帧**（即最深的非框架帧）`class#method`，
     命中直接返回上次结果；
3. 事件 `SqlEvent` 携带 `sf/sl/cands/mk`，其中 `cands` 最多 **3 条**且只有 `[file, line]` 两列（类名/方法名不上送）；
4. IDE 侧 `AgentEvent.parse` → `AgentEventAdapter` → `LogSqlEntry.sourceCandidates`；
5. 跳转解析分两处、行为不一致：
   - 实时面板 `SqlLogPanel.resolveEntryJump`（`SqlLogPanel.kt:1341`）：主位置工程内解析失败 → 候选回退
     `resolveCandidateJump`（栈序取首个工程内可解析项）→ 全落空 NotFound；
   - 历史面板 `HistorySqlLogPanel`（`:554-562`）：主位置为 null 直接 NoSource，解析失败直接 NotFound，**没有候选回退**。

## 三、根因分析

自研框架击穿现有判定链，是多机制叠加：

| # | 机制 | 位置 | 说明 |
|---|------|------|------|
| M1 | L2 名单盲区 | `StackLocator:30-52` | 公司自研框架包名不在开源家族名单，引擎全链帧（Facade/Service/LocalDbService/Repository/SQL Visitor + `DataUpdateTool`）全部进入「非框架帧池」 |
| M2 | L1 目录/JAR 假设失效 | `:114-126` | 该假设在三种常见形态下失效：①引擎与业务同工程多模块（引擎 class 也在目录 → L1 首个「目录帧」= 最深引擎帧）；②类加载隔离致 `Class.forName` 失败返回 null（既非目录也非 JAR，被 L1 跳过）；③业务侧也全 JAR 运行 → 安全网取 pool[0] = 最深引擎帧 |
| M3 | 缓存合流放大 | `:88-104` | 缓存 key = 最深非框架帧；自研框架下该帧对所有业务调用是**同一个**（引擎汇聚帧）→ 第一次解析结果服务此后一切语句，一次错 = 处处错；即便某次 L1 碰对，也会被「第一个调用者」锁死 |
| M4 | 上顶路径可达门面 | `:139-149` | 引擎类若未带调试信息（file=null），主位置从候选逐级上顶，`DataUpdateTool.findByFilter` 是带调试信息的浅层帧，可被顶为主位置 |
| M5 | IDE 无纠偏面 | `SqlLogPanel.kt:1341` / `HistorySqlLogPanel.kt:554` | 候选仅 3 条、无类名方法名、历史面板根本不消费候选；用户看不到栈、无法选帧、无法登记过滤规则 |

其中「恒指向 `DataUpdateTool.findByFilter`」由 M2/M3/M4 哪条路径命中，**待真机日志定谳**
（遵循「真机日志优先于代码推演」）——但修复方向对三条路径均有效：让主位置/候选携带足够信息、
IDE 侧以工程内解析为准绳、给用户显式纠偏入口。

## 四、方案（分期）

### P0 诊断定谳 + 判定保守化 + IDE 收口（根治面最大）

| # | 项 | 改动 |
|---|----|------|
| P0-1 | 诊断打点 | `StackLocator` 增 info 级判定路径日志（`logLevel=info` 才输出）：primaryKey、主位置来源路径（L1 / SAFETY / PROMOTE）、pool 前 N 帧类名。用户公司项目跑一次即锁定 M2/M3/M4 具体路径；打点在 info 级，warn 默认档零开销 |
| P0-2 | 候选增厚 | `SqlEvent.cands` 列尾追加两列 → `[file, line, className, method]`；`MAX_CANDIDATES` 3→8。`AgentEvent.parseCandidates` 本就按 `getOrNull` 逐列取值（`:69-84`），旧 IDE 读新事件只取前两列天然兼容；新 IDE 读旧事件需 `getOrNull` 守卫新增列——「列尾追加」双向兼容成立 |
| P0-3 | 缓存防合流 | 缓存 key 改为「最终选中帧」的 `class#method`，且**仅 L1 目录帧命中时写缓存**；安全网/上顶路径结果不缓存（每次重算）。热点兜底：主流路径（L1 命中）仍走缓存，安全网重算频率上界=调用方种类数（有界），不触 per-execute 全栈解析红线 |
| P0-4 | 上顶路径收敛 | 安全网与主位置选择优先取 pool 中**首个带调试信息**的帧，`:139-149` 的候选上顶逻辑退役（消除 M4 可达门面的路径） |
| P0-5 | IDE 收口 | 实时/历史两处跳转解析统一为一个共享解析器：主位置 → 候选（栈序、工程内解析优先）→ NotFound/引导；`HistorySqlLogPanel` 补齐候选回退，与实时面板行为一致。候选带类名后，NotFound 轻提示可展示「候选均不在工程内」明细 |

### P1 用户纠偏面

| # | 项 | 改动 |
|---|----|------|
| P1-1 | 选帧弹层 | 主位置非工程帧/候选多义时提供「选择调用栈帧」弹层：列 `SimpleClass#method:line`（栈序），回车即跳 |
| P1-2 | 选择记忆 | 用户选定帧按 `methodKey → sourceFile` 记忆，下次同汇聚入口直接首选该帧（用户确认过的缓存，与 P0-3 机器缓存分账） |
| P1-3 | 自定义帧过滤前缀 | 设置页「源码捕获」分组新增多行文本「帧过滤前缀」；IDE 侧候选解析即滤（即时生效）；经既有 `CMD_CONFIG` 通道（`EventBus.java:19`）下发 agent，`AgentArgs`/`AgentState` 接收动态前缀集并入 `isFrameworkFrame`；旧 agent 忽略未知配置键，双向兼容 |

### P2 自适应汇聚检测（加分项）

| # | 项 | 改动 |
|---|----|------|
| P2-1 | 汇聚入口识别 | IDE 侧统计：同一**非工程内** sourceFile 在 ≥N 条不同 SQL 指纹上重复出现 → 轻通知引导「疑似框架汇聚入口，加入过滤前缀？」一键登记（学习式纠偏，替代打地鼠） |
| P2-2 | 堆栈快照 | `SqlEvent` 增 `stackDigest`（`类#方法:行` 压缩单串 × ≤16 帧，超长截断，字节预算内）；详情面板「调用栈」节点展开，点帧即跳——「记录堆栈，往上追踪」的完整人工兜底 |

## 五、验收标准

1. agent 纯逻辑单测矩阵（模拟自研框架栈：引擎帧 × 目录/JAR/null 来源 × 汇聚帧 × 无调试信息）全绿；
   缓存不再跨调用者串位（同 key 不同调用者各归各位，或安全网路径不缓存）。
2. 双端协议兼容：旧事件 JSON 新 IDE 可读（增列守卫）；新事件 JSON 旧 IDE 只丢增强列不炸（既有
   `AgentEventAdapterTest` 扩展矩阵）；对齐「双端协议往返缺口」教训逐项过。
3. 实时/历史两面板跳转行为一致：主位置为非工程帧时自动落首个工程内候选。
4. 用户在设置填入自研框架前缀后，实时+历史跳转立即落业务帧，无需重启 agent（CMD_CONFIG 热下发）。
5. 全量 `test` 绿（分片口径）；不新增反射入口，签名护栏无登记项。

## 六、真机走查清单（留人工）

1. 用户公司项目（自研框架）：捕获一条 `findByFilter` SQL → 跳转应落业务调用方法（截图中的 `getXxxId`）。
2. 历史记录面板同一条 SQL → 同样落业务帧（P0-5 前该面板无候选回退，正是走查对照点）。
3. `logLevel=info` 跑一次：日志确认主位置判定路径（P0-1），与修复前对照。
4. 混跑旧版 agent（未升级 IDE 场景反向：新版 agent + 旧 IDE）不劣化。
5. P1/P2：选帧弹层、前缀热下发、汇聚入口引导流逐项过。

## 七、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 热点红线 | 缓存 key/写缓存条件改动不得引入 per-execute 全栈重复解析 | L1 命中主流路径仍走缓存；安全网路径重算上界有界；上线路径 P0-3 单测加频次断言 |
| 事件体体积 | cands 3→8、增两列、stackDigest 都加长事件体 | 候选与快照帧数上限+超长截断；SenderLoop 链路按现有事件体预算校准 |
| 协议兼容 | cands 列扩展、CMD_CONFIG 新键都动双端契约 | 列尾追加+按下标守卫读取（`parseCandidates` 已是此形态）；新旧组合矩阵测试 |
| 双副本对齐 | 帧过滤语义 agent/IDE 两份独立实现（agent 不引 IDE 类） | 两侧同语义实现 + 对齐守卫用例（同输入同判定），防历史「CallerSourceLocator 对齐」漂移重演 |
| 安全网劣化 | P0-4 取「首个带调试信息帧」在极端全无调试信息时可能空 | 空则沿用现状上顶路径兜底，行为不劣化于今 |

## 八、范围边界

- MyBatis/MP 拦截器链路（`CallerSourceLocator`）不受本任务影响——自研框架下该链路本就没有拦截点；
  但 P0-5 的共享解析器落地时允许其后续接入。
- 引擎内部表结构/元数据解析（schemaCode 语义）不在本任务范围——只修「跳转落点」。
