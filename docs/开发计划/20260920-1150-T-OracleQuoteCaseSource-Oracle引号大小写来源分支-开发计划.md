# T-OracleQuoteCaseSource Oracle 系引号大小写来源分支 — 开发计划

- 日期：2026-09-20 11:50
- 任务编号：T-OracleQuoteCaseSource（第九轮报告 §9.4 **O-42**，P2~P3，**含决策点 D1**）
- 来源：第九轮报告 §8 DBX 增量清单 **#46**——DBX `8518a5b`（2026-09-19 09:47，6 文件 +101/−8）：Oracle 裸标识符折叠为大写，只有全大写名可不带引号，混合大小写必须双引号转义，否则 Oracle 静默改语义；DBX 把 `quoteSqlIdentifier` 的 oracle 分支正则从 `[A-Za-z]` 收紧为 `[A-Z]`
- 性质：**标识符引用正确性**（**有回归风险，见 §一 口径修正 2**）——不改 SQL 语义、不改保留字集合
- 实施分支：自 master 拉任务分支 t-oracle-quote-case-source
- 验证基线：master `22d7c49`（testShards 5383/0/0/6 + testDatabase 10/0/0/0 + agent 58/0/0/0）

---

## 一、背景与依据（现状核实，含五处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「我方 `CrudSqlGenerator`/回滚链的 Oracle 分支照 DBX 收紧」 | **回滚链不存在该分支（口径修正）**：`sql/rollback/RollbackSqlGenerator.kt` **零引号逻辑**、直接裸插值（`:232` `DELETE FROM $table WHERE …`、`:402` `INSERT INTO $table ($columnList) VALUES `、`:437`、`:496` `UPDATE $table SET $setPart WHERE …`）。受影响的是 **CRUD 生成链 + 其余 `quoteIfKeyword` 消费面** | 任务描述范围须改写：不含回滚链 |
| 2 | 「按 DBX 规则收紧 Oracle 分支」 | **直接照搬会产生正确性回归（重大口径修正，本计划最重要的结论）**：DBX 的 `quoteSqlIdentifier` 输入是**目录里的真实名字**，故「非全大写 → 必包」成立。我方 `CrudTableSpec` 有**两条来源、语义相反**：**目录来源**（`database/DbTableSpecAdapter.kt:44` `columnName = col.name`、`:52` `tableName = das.name`——名字是数据库返回的真实大小写，DBX 规则**语义适用**）；**实体来源**（`actions/GenerateCrudSqlFromEntityAction.kt:129-143`，名字来自 `EntityResolver`，`:151`/`:502`/`:103`/`:109` 走 `NamingStrategy.toSnakeCase(...)`，即 `@TableName`/`@TableField` 注解值或 camelCase→snake_case 的**小写派生名**）——这些名字**正是「不带引号创建」的名字**，Oracle 把裸写的小写折叠成大写后命中，**恰恰是正确行为**；若按 DBX 规则包成 `"biz_name"`，Oracle 将按小写精确匹配，对以裸写创建的表**必然失败** | 落点**必须限定目录来源路径**，或引入「名字大小写来源」标记后分支处理；**不能无条件收紧** |
| 3 | 「达梦族 UPPER_FOLDING 是另一分支勿混」 | **我方无对应物可混**：`UPPER_FOLDING`/`UPPER_FOLDING_IDENTIFIER_DATABASES`/`upperFolding`/`foldToUpper` 全仓**零命中**；`uppercase`/`fold` 命中的全是 UI 自动折叠（`config/PluginSettings.kt:70-80` 等）。DM 与 Oracle 共用 `Dialect.kt:53` 同一条判定，差异仅在 `DM_RESERVED`（`sql/SqlKeywords.kt:208`）与包裹符号（`Dialect.kt:358`）。唯一相关认知在 `SqlKeywords.kt:203-204` 的**注释**（「Oracle 保留而 DM 仅关键字…裸用折叠大写后合法」），**未落成常量/分支** | 若要落地「折叠族」需**新建结构**，不是「收紧一个正则」 |
| 4 | 「`DbTypeQuoteHint` 相关」 | **同名不同义**：`generator/DbTypeQuoteHint.kt:10` 是「**DB 类型名 → 参数值**是否加字符串引号」（数值族 false、其余 true），与**标识符引用**无关；`sql/ParamQuote.kt:15` 同理 | 立项文本中不得把它算作本任务落点 |
| 5 | 「顺带补 MERGE 多行样例单测（预期直接绿）」 | **成立**：`util/SqlStatementSplitter.kt` 全文**无 MERGE 特判**（批首合并常量只有 IF：`:40` `CONTROL_FLOW_BATCH_HEADS = setOf("IF")`，`:265-288` `mergeControlFlowBatches`）；Oracle 形态 MERGE 体内**无顶层 `;`** → 分号状态机天然整段产出；`SqlStatementSplitterTest.kt` 现有 **0 条 MERGE 用例** | 该项定位为**守卫用例**（防未来给切分器加规则时误伤），不是修 bug |

### 1.1 我方引号判定现状（逐条实测）

| file:line | 职责 |
|---|---|
| `sql/Dialect.kt:37` | `fun quoteIdentifier(identifier: String): String = identifier`——接口默认**不引用** |
| `sql/Dialect.kt:52-53` | `fun quoteIfKeyword(identifier) = if (identifier.lowercase() in reservedWords) quoteIdentifier(identifier) else identifier`——**全仓唯一的「按需引用」判定入口**；判定维度是**保留字**，不是大小写 |
| `Dialect.kt:188 / 211 / 237 / 255 / 278 / 301 / 328 / 358 / 382` | 九方言 `quoteIdentifier` 覆写（MySQL 反引号 / PG·Oracle·H2·SQLite·DM·KingBase 双引号 / SQLServer 方括号 / MariaDB 反引号）——**无条件包裹**，不含大小写或保留字判定 |
| `Dialect.kt:237` | Oracle 分支原文：`override fun quoteIdentifier(identifier: String): String = "\"$identifier\""` |
| `sql/SqlKeywords.kt:123` | `ORACLE_RESERVED` 保留字集合 |

**结论**：我方**没有**「Oracle 全大写才裸写」口径；当前按「**非保留字一律裸写**」放行混合大小写——与 DBX 收紧前的**效果**相近，但**判定维度不同**（DBX 判大小写，我方判保留字）。

### 1.2 消费方全清单（`quoteIfKeyword` 命中 65 处 / 10 文件）

| file:line | 消费面 |
|---|---|
| `generator/CrudSqlGenerator.kt:146`（`quoted()`）→ `:53/60/87/88/118/134/148/152` | **CRUD 四件套生成器**（SELECT/UPDATE/DELETE/逻辑删除） |
| `actions/GenerateCrudSqlFromDbTableAction.kt:46`、`actions/GenerateCrudSqlFromEntityAction.kt:57`、`mcp/SqlVisualizerCrudSqlTool.kt:90` | CRUD 生成器的三个入口（IDE 两动作 + MCP `sqlv_crud_sql`） |
| `converter/jpa/JpaCrudOperationConverter.kt`（25 处，336–711） | JPA CRUD 转换链 |
| `psi/BatchOperationConverter.kt`（16 处） | 批量转换链 |
| `model/BatchModel.kt:106-122` | 批量 UPSERT/INSERT/UPDATE 渲染 |
| `model/ColumnRef.kt:32-33` | 解析期 raw 拼接 + SqlGenerator 共用（列名本体按需包、别名前缀不包） |
| `converter/jpa/JpaQueryEnhancer.kt:450-451` | JOIN ON 渲染 |
| `sql/SqlGenerator.kt:493`（`renderTableName`）→ `:150/229/276/279/313/363` | **日志转 SQL 的 FROM/UPDATE/DELETE/JOIN 表名** |
| `converter/jpa/DerivedQueryConverter.kt:398` | 仅注释引用 |
| `Dialect.kt` 内部 `:164/171/172/283/284` | upsert 子句 + H2 `MERGE INTO` 整语句 |

**不使用引号判定的链（改动不受影响）**：`sql/rollback/RollbackSqlGenerator.kt`（零引号）、`sql/JoinDiagramGenerator.kt` 与 `ui/diagram/nativefw/**`（JOIN 图）、`ui/highlight/SqlCompletion*.kt` 与 `ui/completion/**`（补全）、`mcp/**` 工具层（仅经 `CrudSqlGenerator`）。

### 1.3 两条来源路径（决定 D1 的核心事实）

| 来源 | 构造点 | 名字语义 | DBX 规则是否适用 |
|---|---|---|---|
| **目录来源** | `database/DbTableSpecAdapter.kt:44`（`columnName = col.name`）、`:52`（`tableName = das.name`） | 数据库返回的**真实大小写** | **适用** |
| **实体来源** | `actions/GenerateCrudSqlFromEntityAction.kt:129-143`（`:151`/`:502`/`:103`/`:109` 走 `NamingStrategy.toSnakeCase(...)`） | **小写派生名**（注解值或 camelCase→snake_case） | **不适用，收紧即回归** |

`generator/CrudTableSpec.kt:41` 当前**无承载「名字大小写来源」的字段**。

### 1.4 测试影响面（实测）

| 类 | 关键用例 | 是否会被改动弄红 |
|---|---|---|
| `sql/DialectTest.kt` | `:91-103` 标识符引用差异（Oracle `quoteIdentifier("id")` → `"id"`） | 否（`quoteIdentifier` 无条件包裹，语义不变） |
| `sql/SqlKeywordsTest.kt` | `:37-52` 方言差异_判定按本方言；`:54-64` 普通标识符与方言非保留字不命中；`:66-80` 方言引用符号按方言切换 | 否（保留字命中路径不变；`:54-64` 只断言 MySQL/PG 集合） |
| `generator/CrudSqlGeneratorTest.kt` | 各方言产物断言 | **可能红**——若含「Oracle 小写标识符裸写」断言 |
| `sql/rollback/RollbackSqlGeneratorTest.kt` | — | 否（该文件零 `OracleDialect` 引用） |
| `sql/verify/DialectDocGoldenTest.kt` / `DialectRenderDumpTest.kt:44-60` / `DialectVerifySupport.kt:238/293-294/326-327` / `DialectH2VerificationTest.kt:81` | 金标与引擎级形态 | 否（用无条件包裹函数 / 不判大小写） |

引用 `OracleDialect` 的测试文件共 **9 个**：`config/ProjectPluginSettingsTest.kt`、`model/BatchModelTest.kt`、`sql/DialectExplainTest.kt`、`sql/DialectTest.kt`、`sql/SqlKeywordsTest.kt`、`sql/verify/DialectDockerVerificationTest.kt`、`sql/verify/DialectRenderDumpTest.kt`、`sql/verify/DialectVerifySupport.kt`、`ui/SqlPreviewServiceProjectSettingsTest.kt`。

**注意**：`DialectH2VerificationTest.kt:81` 的 `MODE=Oracle` 是 H2 兼容模式，**折叠语义与真 Oracle 不等价**——**不能**用它验证本改动。

## 二、目标与非目标

**目标**（仅在 D1 选「做」时）：让**目录来源**的 Oracle 标识符按「全大写才裸写」引用，消除「混合大小写列名裸写被 Oracle 静默改语义」这一静默错误；同时**不改变实体来源路径的既有行为**（那里的裸写小写派生名是正确行为）。

**非目标**：不改回滚链（零引号逻辑，不在范围内）；不改 `DbTypeQuoteHint`/`ParamQuote`（参数值引号，与本任务无关）；不改保留字集合与 `quoteIdentifier` 的无条件包裹语义；不新建「折叠族」结构除非 D1 选做且窄口径不可行；不追 DBX 的 `dataGridConditionCompletion` 等无对应面。

## 三、方案

### 决策点 D1（**须用户定案，实施前必须定**）

| 选项 | 内容 | 代价 | 推荐度 |
|---|---|---|---|
| **A（窄口径，推荐）** | 只对**目录来源**路径收紧：新增「名字大小写来源」标记（`CrudTableSpec` 加字段或按调用点分派），`quoteIfKeyword` 或其新增重载按来源分支——目录来源走「非全大写 → 加引号」，实体来源**维持现状** | 4 文件量级（`sql/Dialect.kt`、`sql/SqlKeywords.kt`、`database/DbTableSpecAdapter.kt`、`generator/CrudSqlGenerator.kt`）+ `generator/CrudTableSpec.kt`（承载来源标记） | ★★★ |
| **B（不做，登记观察）** | 只落「MERGE 守卫用例」与本节的口径核实结论，引号语义维持现状 | 0 | ★★（若真机 Oracle 环境不可得，此项是诚实选择） |
| **C（照搬 DBX 无条件收紧）** | 把 Oracle 分支改成「非全大写必包」 | **会破坏实体来源路径**（§一 口径修正 2） | **弃用**（明确否决，理由在册） |

**D1 建议**：选 **A**；若无法安排真机 Oracle 验证（§五），退 **B** 并把已核实的「实体来源路径收紧即回归」结论登记为经验（该结论本身有独立价值）。

### 任务分解（D1=A 时）

**T-OQCS-01（大小写来源标记）**：给 `generator/CrudTableSpec.kt`（`:41` 附近）增加「名字来源」承载（新增字段，或按调用点分派——二选一，见「待验证前提 1」）；`database/DbTableSpecAdapter.kt:44`/`:52` 标为**目录来源**，`actions/GenerateCrudSqlFromEntityAction.kt` 派生路径标为**实体来源**。

**T-OQCS-02（引用判定分支）**：`sql/Dialect.kt` 的按需引用入口（`:52-53`）增加来源形参或新增重载 `quoteIfNeeded(identifier, caseSource)`——**目录来源**：Oracle 族按 `^[A-Z][A-Z0-9_$#]*$`（全大写才裸写，含 DBX 的保留字排除）+ 保留字命中即包；**实体来源**：走既有 `quoteIfKeyword` 逻辑（**逐字不变**）。

**T-OQCS-03（消费面接线）**：`generator/CrudSqlGenerator.kt:146` 的 `quoted()` 传入来源标记；JPA CRUD（`converter/jpa/JpaCrudOperationConverter.kt`）、批量转换（`psi/BatchOperationConverter.kt`、`model/BatchModel.kt`）、日志转 SQL（`sql/SqlGenerator.kt:493`）按各自的名字来源传入——**日志转 SQL 的名字来自运行期 SQL 文本**，属第三类（**原样文本**），须单独定口径（见「待验证前提 2」）。

**T-OQCS-04（MERGE 守卫用例）**：`util/SqlStatementSplitter.kt` 无 MERGE 特判，`SqlStatementSplitterTest.kt` 新增 1 条**多行 MERGE** 样例（Oracle 形态：`MERGE INTO … USING … ON (…) WHEN MATCHED THEN UPDATE SET … WHEN NOT MATCHED THEN INSERT (…) VALUES (…);`），断言整段产出为**单条**语句——**预期直接绿**，定位为守卫（防未来给切分器加规则时误伤）。

**T-OQCS-05（测试与文档）**：`generator/CrudSqlGeneratorTest.kt` 新增 Oracle **目录来源**用例（全大写名裸写 / 混合大小写加引号）；**同时补实体来源用例**（小写派生名**仍裸写**——这是防回归的关键用例）；门禁读数；CHANGELOG `[Unreleased]` + 变更记录行 + 第九轮报告 §8 #46 落地备注。

## 四、验收标准

1. **目录来源**：Oracle 下混合大小写标识符被双引号包裹、全大写标识符裸写（新增用例）。
2. **实体来源不变（防回归硬要求）**：小写派生名（`NamingStrategy.toSnakeCase` 产物）在 Oracle 下**仍然裸写**（新增用例钉住——这条比第 1 条更重要，因为它是 C 方案的否决依据）。
3. 其他方言（PG / MySQL / DM / KingBase / SQLServer / H2 / SQLite / MariaDB）产物**逐字节不变**（既有测试全绿）。
4. MERGE 多行样例切分为单条语句（守卫用例，预期直接绿）。
5. 既有测试面：`DialectTest`、`SqlKeywordsTest`、`RollbackSqlGeneratorTest`、`DialectRenderDumpTest`、`DialectVerifySupport` 全绿；`CrudSqlGeneratorTest` 按新增用例调整后全绿（**若既有断言含 Oracle 小写裸写则须逐条核对是否属目录来源**）；全量 testShards + testDatabase + agent 绿。

## 五、真机走查清单（留人工）

**本任务属语义级改动，H2/单测无法覆盖「裸写折叠 vs 引号精确匹配」的差异**——`DialectH2VerificationTest.kt:81` 的 `MODE=Oracle` **不可替代**真 Oracle。

1. 真 Oracle 库：以**裸写小写**创建表 `t_demo`，用目录来源生成的 CRUD 对**混合大小写**列名做查询——确认引号引用生效且命中。
2. 真 Oracle 库：同一张表，用实体来源（`@TableName` 小写注解）生成 CRUD——确认仍裸写且命中（防回归）。
3. 真达梦/金仓（若可得）：确认 DM 侧行为未受牵连。
4. 环境受限时的诚实口径：若无真 Oracle 环境，本任务按 **D1=B** 收口，只交付 §1 的口径核实结论 + MERGE 守卫用例，**不得宣称引号语义已修**。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **实体来源回归**（本任务最大风险） | 无条件收紧会让「以裸写创建的小写派生名」被加引号 → Oracle 精确匹配失败 | D1 的 C 方案明确否决；验收 2 用「实体来源仍裸写」的用例钉住；T-OQCS-03 对每条消费面单独确认名字来源 |
| 日志转 SQL 的名字来源是第三类 | `sql/SqlGenerator.kt:493` 的表名来自运行期 SQL 文本（原样），既非目录也非实体 | 列为**待验证前提 2**——若无法判定来源，则该路径**维持现状不动**（宁可不改也不猜） |
| 无真 Oracle 环境 | 语义级改动无法在本机验证 | §五 第 4 条：退 D1=B 并如实登记；**禁止**拿 H2 `MODE=Oracle` 的读数冒充验证 |
| 既有断言被误改 | `CrudSqlGeneratorTest` 可能含 Oracle 小写裸写断言，若属目录来源则该断言本身是错的（要改），若属实体来源则是对的（不能改） | 逐条核对断言的构造路径（目录 vs 实体），产出核对表后再改 |
| 消费面 65 处的波及 | 改 `Dialect.kt:52-53` 一处会波及 10 文件 65 处 | 采用「新增重载 + 逐点接线」而非「改既有函数语义」，让未接线的点维持旧行为 |

## 七、实施顺序

```
[D1 定案：A / B（C 已否决）]
  D1=A: T-OQCS-01（来源标记）→ T-OQCS-02（判定分支）→ T-OQCS-03（消费面逐点接线，含逐条核对表）
        → T-OQCS-05（含防回归用例）→ 真机走查 §五（无环境则退 B）
  D1=B: T-OQCS-04（MERGE 守卫用例）+ 口径核实结论入档 → 收口
  两条路线均包含 T-OQCS-04（守卫用例与 D1 无关，可独立先落）
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| D1 定案 | （实施后填） | A/B 选择与理由须回填 |
| T-OQCS-01 | （实施后填） | 来源标记的承载形态（新字段 vs 调用点分派）须回填 |
| T-OQCS-02 | （实施后填） | 判定分支的最终正则与保留字排除须回填 |
| T-OQCS-03 | （实施后填） | 65 处的逐条来源核对表与未接线点清单须回填 |
| T-OQCS-04 | （实施后填） | MERGE 守卫用例的实测结果（预期直接绿）须回填 |
| T-OQCS-05 | （实施后填） | 防回归用例读数、`CrudSqlGeneratorTest` 逐条核对结果、门禁读数须回填 |

**待验证前提（实施者先证伪再动手）**：

1. 「名字来源可在 `CrudTableSpec` 承载」——本计划只确认 `generator/CrudTableSpec.kt:41` 附近**无**该字段，未读全文；新字段 vs 调用点分派的选择须实施时读该类与全部构造点后定。
2. **日志转 SQL 路径（`sql/SqlGenerator.kt:493`）的名字来源未判定**——它既非目录也非实体，若无法判定则该路径维持现状（本计划建议不动）。
3. 「Oracle 全大写才裸写的判据形态」来自 DBX `sqlCompletion.ts:3587-3598`（`^[A-Z][A-Z0-9_$#]*$` + 保留字排除）——我方是否照抄该正则须实施时确认（我方保留字集合 `ORACLE_RESERVED` 与 DBX 的 `POSTGRES_IDENTIFIER_KEYWORDS` 不同源）。
4. 「`CrudSqlGeneratorTest` 里哪些 Oracle 断言属目录来源」未逐条枚举——实施时须逐条核对，**这是防误改的关键步骤**。
5. DM/DB2 等「大写折叠族」的其他成员是否同规则未核实——本计划只覆盖 Oracle；若日后扩展须先建族标记（属结构新增）。
