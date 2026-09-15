# T-DdlCacheInvalidate DDL/事务提交后元数据缓存失效 — 开发计划

- 日期：2026-09-15 09:03
- 任务编号：T-DdlCacheInvalidate（第四轮新建议 **O-12**，P2）
- 来源：第四轮竞品调研报告 §7 DBX 吸收清单 #15——DBX `object_cache.rs:41` sql_may_change_object_metadata 关键字表（DDL + COMMIT/END/ROLLBACK/ABORT）+ `:70` invalidate_connection_object_cache，挂接执行链三处（query.rs:2779,3125,3992）；其注释点破关键语义：「失败的 DDL 可能已经隐式提交（Oracle）」——失效必须在返回 SQL 结果前执行、失败只记日志不改变执行结果状态
- 性质：防御性基建（先盘点后定形——本插件的自持缓存面可能比 DBX 薄，方案分两分支）
- 实施分支：基于 dev 开任务分支 t-ddl-invalidate
- 验证基线：dev 全量 `./gradlew testShards` 4246/0/0/6 绿

---

## 一、背景与依据

预览列补全、EXPLAIN 目标表结构、方言映射等都依赖某处缓存的结构信息。如果用户在内嵌控制台跑了一条 `ALTER TABLE t ADD COLUMN c int`（或一条隐式提交了的事务），随后立刻预览/EXPLAIN，命中旧结构的产物就是「正确性人设」的直接破口。DBX 的对策是一个小而硬的机制：识别「可能改变对象元数据的 SQL」（含事务提交关键字），在 SQL 结果返回前主动失效连接级对象缓存，且失败也要失效——因为 Oracle 系 DDL 隐式提交，DDL 报错不代表没提交。这个语义值得原样继承。

与本插件既有工作的关系：T-PreviewExecDs 已处理过「预览执行跨项目数据源错乱」的缓存族（cachedTempPsiFile 三分量），T-ExecSlowOps 给 DialectPreMapping 留了四缓存字段 @Volatile——缓存面存在但分散，尚无「执行了 DDL 之后该失效谁」的统一答案。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| 执行链缓存 | DataSourceSelector 七处 @Volatile（含 cachedTempPsiFile/consumeCachedTempPsiFile 链） | `execute/DataSourceSelector.kt:85-141,2998` |
| 解析缓存 | WrapperCache LRU 512（T-PerfTail 加的上限）——缓存的是 PSI 解析产物，不持 schema | `psi/WrapperCache.kt:28` |
| schema 消费面 | McpSchemaContextService 每次取数无自持缓存；预览列补全走 PSI + 平台数据源 introspection；EXPLAIN 目标解析（resolveExplainTarget）每次解析 | `mcp/`、`SqlVisualizerToolset.kt:178` |
| DDL 识别 | SqlTypeClassifier 有 DDL 关键字级分类（CREATE/ALTER/DROP/TRUNCATE），但仅用于「是否需确认」，无失效动作 | `execute/SqlTypeClassifier.kt` |
| 缺口 | 执行链跑完 DDL/提交后无任何缓存失效动作；无统一 DDL 识别入口 | — |

## 三、方案（先盘点，两分支定形）

**T-DC-01（缓存面盘点，决定分支）**：穷举执行链之后仍跨调用存活的缓存，逐个标注「是否持有可被 DDL 改变的信息」（表结构/列清单/类型映射/方言探测结论）。产出盘点表落 §八。预判：多数面直连平台 introspection（平台有自己的失效机制，不动），自持面集中在 DataSourceSelector 的 @Volatile 族与 DialectPreMapping 缓存。

**T-DC-02（统一 DDL/提交识别，纯逻辑）**：新增 `execute/MetadataInvalidator.kt`（object，零 UI）：`mayChangeMetadata(sql: String): Boolean`——JSqlParser AST 判 DDL（parser 权威，对齐 RollbackSqlGenerator 原则），关键字表兜底 COMMIT/END/ROLLBACK/ABORT（DBX 语义对齐；关键字表判提交类，AST 判 DDL，两路任一命中即失效）；解析失败 fail closed 按「可能改变」处理（宁多失效不多缓存）。表驱动测试。

**T-DC-03（分支 A：有自持 schema 面）**：对 T-DC-01 盘点出的每个可失效面，在执行链完成点（SqlTypeClassifier 判 DDL/提交命中处）挂失效回调——失效在执行结果返回前调用，失败只记日志不吞执行结果（DBX 语义原样继承）。每处失效一行注释指向本计划。

**T-DC-04（分支 B：盘点证实无自持 schema 面）**：不硬造缓存去失效。落三件轻量资产：①T-DC-02 的识别器（本身就是资产，RollbackSql/执行链将来可用）；②执行链留一个显式 `invalidateAfterExecution` 空挂接点 + 注释说明「未来新增 schema 缓存必须在此登记」（防回归的架构位）；③盘点表与结论沉淀进架构文档（明确「本插件 schema 真相源是平台 introspection，不自持」——这本身就是有价值的定案，防止后人重复排查）。

**T-DC-05（文档同步）**：CHANGELOG [Unreleased]（按分支 A/B 分别是 Added 或 docs）、变更记录总册行、第四轮报告 §8.4 O-12 状态回填。

## 四、验收标准

1. T-DC-01 盘点表落 §八，每个缓存面有「持有/不持 schema 信息」结论与证据（file:line）。
2. T-DC-02 识别器表驱动测试全绿（DDL 正例/反例、四提交关键字、解析失败 fail closed、多语句任一命中）。
3. 分支 A：DDL 执行后各登记面可证失效（单测构造缓存态→执行→断言清空）；分支 B：挂接点在位且有守卫注释。
4. 失效路径异常不吞执行结果（try-catch + Logger，测试覆盖）。
5. 全量 test 绿（基线 4246/0/0/6 + 新增数），跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. 262 真机：控制台执行 `ALTER TABLE t ADD COLUMN ...` 后立刻对同表预览/EXPLAIN，核对拿到新结构（分支 A 必走；分支 B 顺势记录平台 introspection 的自刷新时延，沉淀结论）。
2. Oracle/达梦真实实例（有环境时）：DDL 故意写错（列已存在）后核对失效仍发生（隐式提交语义，环境受限则如实标注未验证）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 盘点误判「无缓存」 | 某个间接层（如平台 PSI 缓存包装）实际持旧结构 | 盘点表强制带证据；真机走查 §五-1 实测兜底 |
| 过度失效伤性能 | 每条 DDL 后全量清缓存 | 只在 DDL/提交关键字命中时触发；失效动作 O(1)（引用置空）而非遍历重建 |
| 关键字误判 | 列名/字符串里含 COMMIT 等词触发误失效 | 关键字表只在 AST 解析失败兜底时启用，且误失效代价仅一次重取（fail safe 方向） |
| 分支 B 显得「没干活」 | 盘点结论可能就是没有自持面 | 定案本身就是交付（防重复排查 + 架构位留好）；报告 §8.4 已预设此分支 |

## 七、实施顺序

```
T-DC-01（盘点表，定分支）→ T-DC-02（识别器+测试）
→ T-DC-03 或 T-DC-04（按分支）→ T-DC-05（文档同步）→ 真机走查 §五
```

## 八、实施记录（实施后回填；含 T-DC-01 盘点表）

**2026-09-15 实施完毕（分支 A；识别器表驱动 12 例 + 编排 3 例全绿）**

### T-DC-01 缓存面盘点表（跨调用存活面逐个取证）

| 缓存面 | 位置 | 是否持 schema 信息 | 处置 |
|---|---|---|---|
| DatabaseSchemaResolver.metaCache（表注释+列注释+类型+notNull，WeakHashMap<数据源, 表→TableMeta>） | `ui/highlight/DatabaseSchemaResolver.kt:87` | **是**（T-Perf-09 引入，无任何失效动作） | **登记失效（invalidateAll）** |
| DataSourceSelector @Volatile 族（cachedTempPsiFile/ProjectName/dialectMappedVf/lastTempFileFailure/代际计数/提示标记） | `execute/DataSourceSelector.kt:86-144` | 否（临时 PSI 文件/项目名/方言幂等标记/执行代际） | 不登记 |
| DialectPreMapping | `execute/DialectPreMapping.kt` | 否（无自持缓存，只读探测平台 SqlDialectMappings，平台自管失效） | 不登记 |
| WrapperCache LRU 512 | `psi/WrapperCache.kt` | 否（PSI 解析产物，不持表结构） | 不登记 |
| McpSchemaContextService | `mcp/McpSchemaContextService.kt` | 否（每次取数无自持缓存） | 不登记 |
| RollbackSnapshotStore | `sql/rollback/RollbackSnapshotStore.kt` | 用户粘贴的 before-image 历史镜像（DDL 后反而更需要，语义即快照） | 不登记 |
| 预览列补全/EXPLAIN 目标解析 | PSI + 平台 introspection | 否（真相源=平台，平台自有失效） | 不登记 |

**结论：分支 A**——自持 schema 面恰一处（metaCache），按 T-DC-03 挂失效；同时按 T-DC-04② 预留显式登记位（MetadataInvalidator.register + 「新增 schema 缓存必须登记」守卫注释在类 KDoc）。

### 实施明细

- **T-DC-02**：`execute/MetadataInvalidator.kt`——`mayChangeMetadata` 两路判定（AST 前缀 Create/Alter/Drop/Truncate/Rename/Comment + Commit/RollbackStatement；**不透明类型关键字扫描兜底**——实证 JSqlParser 5.3 把残缺 `CREATE TABLE` 宽松包成 UnsupportedStatement、`BEGIN…END` 包成 Block，首词路由会双双漏判，改为仅对这两类不透明容器按词边界正则扫 DDL 动词+事务提交词并集；结构可判语句不受字面量误伤）；解析失败 fail closed true（`commit work`/`ABORT` 等实测即抛异常走此路）。
- **T-DC-03**：DatabaseSchemaResolver 补 `invalidateAll()`（O(1) 引用清空）；挂接点=DataSourceSelector.executeOnConsole（全执行链唯一提交收口，三调用路径共用），SQL 取控制台编辑器文档（提交时刻即执行内容）；失效在提交前、外层 runCatching 双保险（hook 失败/动作失败均只记日志绝不吞执行结果——DBX 语义原样继承）。
- **T-DC-04 兼做**：登记位守卫注释（新增 schema 缓存必须在 MetadataInvalidator 登记否则 DDL 后旧结构泄漏）落在类 KDoc；盘点表即上表（架构结论：本插件 schema 真相源是平台 introspection，自持面仅 metaCache 一处且已挂失效）。
- **测试**：MetadataInvalidatorTest 15 例——DDL 正例 10 形态（含小写/CREATE OR REPLACE VIEW/RENAME）、提交关键字 5、DML/查询/字符串内关键字/注释内 DDL 反例、解析失败 fail closed、多语句任一命中、空与纯注释、编排（登记动作命中/SELECT 不触发/动作异常不外抛不阻断后续/内置登记在册/invalidateAll 幂等）。
