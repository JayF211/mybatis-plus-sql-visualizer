# T-MetaCheapSource 元数据负命中缓存与索引 — 开发计划

- 日期：2026-09-16 03:18
- 任务编号：T-MetaCheapSource（第五轮新建议 **O-21**，P3；**口径经 dev 锚点核实重定形**）
- 来源：第五轮报告 §7 DBX 吸收清单 **#25**（元数据获取廉价源优先，oracle-go ALL_VIEWS.TEXT 先行）与 **#26**（identity 列 INSERT 包裹）。报告设想经核实两端均需改形：SchemaContextTool 走 IDE DasModel 内省模型（方言中立的抽象层，不存在「裸 SQL 查询排序」问题），真实成本点是**逐数据源全模型遍历直到命中 + 负命中不缓存**（agent 传错表名时每次调用全量重扫）；identity 包裹面对 crud_sql「从实体生成 INSERT 模板（无数据行）」的现状**不触达**，定案不适用
- 性质：MCP schema 面性能小补丁
- 实施分支：自 master 拉任务分支 t-meta-cheap-source
- 验证基线：合流后 master 全量 testShards 绿

---

## 一、背景与依据

agent 传不存在的表名是常态（幻觉表名、拼写错）。当前 DatabaseSchemaResolver.tableMeta 对负命中不缓存：每次未命中都把全部数据源的 DasModel 遍历一遍（多数据源 × 大模型时一次调用可到秒级），而 agent 场景同一个错误表名可能在一次会话里被反复尝试。DBX #25 的精神是「别为不存在的对象反复发昂贵查询」——在 DasModel 语境下对应的不是换便宜源，而是负命中缓存 + 表名索引。负缓存必须可失效：DDL 建表后负命中缓存要清——正好挂 MetadataInvalidator 登记表（T-DdlCacheInvalidate 建的机制，守卫注释就位）。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| 查询路径 | DasModel.traverser() 全量遍历筛 DasTable；每数据源遍历直到命中 | `ui/highlight/DatabaseSchemaResolver.kt:169-254`（遍历口径 :19,:187） |
| 缓存 | 正命中 per-datasource 弱缓存；**负命中不缓存** | `ui/highlight/DatabaseSchemaResolver.kt:130-145` |
| 失效机制 | MetadataInvalidator.register 登记表（当前唯一登记 metaCache）+ DDL/事务后失效 | `execute/MetadataInvalidator.kt:54-59,106-108` |
| 守卫 | 「新增缓存漏登记」防漏登记注释在位 | `ui/highlight/DatabaseSchemaResolver.kt:87` |
| identity 面 | crud_sql 生成 INSERT 模板不含数据行，reverse_sql 不产 INSERT——DBX #26 场景不触达 | `mcp/CrudSqlTool.kt:86-102` |

## 三、方案

**T-MC-01（负命中缓存）**：tableMeta 负命中进 per-datasource 负缓存（会话级，TTL 30s 防长会话陈旧）；登记进 MetadataInvalidator（标签如 `DatabaseSchemaResolver.negativeCache`）——DDL/事务后与正缓存一起失效；守卫注释同步。

**T-MC-02（表名索引，可选）**：数据源粒度一次遍历建 `name → DasTable` 索引（弱引用 + 同款失效登记），把「逐名全扫」变「一次扫描多次查询」；仅当 T-MC-01 后真机仍慢才启用（避免过度工程，P3 自律）。

**T-MC-03（identity 定案 + 文档）**：DBX #26 定案「不适用」写入实施记录与报告回填（防后续有人重提）；CHANGELOG 仅性能条目（若有感）或不动；第五轮报告 O-21 状态回填。

## 四、验收标准

1. 同一不存在表名连续查询 N 次：仅首次全扫（负缓存生效），DDL 失效后再次全扫（失效正确）。
2. 正命中行为逐字节不变（防回归断言）；全部缓存走 MetadataInvalidator 登记（无游离缓存面——对齐 T-DdlCacheInvalidate 盘点表口径）。
3. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. MCP schema_context 先查不存在表名（观察首扫耗时），紧接再查同名字段（应秒回），控制台建表后马上再查（负缓存已被失效，能查到新表）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 负缓存陈旧 | 建表后短时间内误报不存在 | TTL 30s + MetadataInvalidator 失效双保险；真机走查专测建表后立查 |
| 弱引用索引抖动 | GC 后索引重建成本 | 索引为可选子任务（T-MC-02），默认不启用；启用前真机数据支撑 |
| ReadAction 约束 | 遍历在 ReadAction 内，缓存读写注意线程 | 沿用 tableMeta 现有 ReadAction 包裹形态，缓存容器用并发安全结构 |

## 七、实施顺序

```
T-MC-01（负缓存+失效登记）→（条件）T-MC-02 → T-MC-03（定案+回填）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

**2026-09-17 实施完毕（提交 f3d8f8b，dev 分支直接交付）**

- **T-MC-01**：tableMeta 负命中进 per-datasource 负缓存（弱键同正缓存口径，键=数据源×表名小写）；TTL 30s 用时间戳惰性判定（不用定时器）防长会话陈旧、过期即重扫；登记进 MetadataInvalidator（label=DatabaseSchemaResolver.negativeCache），DDL/事务后与正缓存一起清空，守卫注释同步；正命中路径行为不变（扫描探针注入式表驱动测试锁定：同实例返回、零重扫、建表后失效即命中）。
- **T-MC-02 定案**：表名索引为可选件按计划 P3 自律默认不启用，留实施注释（避免过度工程）。
- **T-MC-03 定案**：identity 定案不适用（crud_sql 仅实体 PSI 生成、INSERT 无数据行），定案写入测试注释防后续重提；CHANGELOG 记 Changed 性能条目（本轮批次），第五轮报告 O-21 状态回填留主会话。
- **测试**：NegativeCacheTest 5 + FallbackTest 2 + MetadataInvalidatorTest 13 全绿。
- **真机走查留人工**（§五）：不存在表名首扫/再查秒回/建表后立查（负缓存已被失效）。
