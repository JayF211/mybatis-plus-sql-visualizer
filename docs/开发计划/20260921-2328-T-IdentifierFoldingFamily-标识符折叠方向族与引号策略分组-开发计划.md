# T-IdentifierFoldingFamily 标识符折叠方向族与引号策略分组 — 开发计划

- 日期：2026-09-21 23:28
- 任务编号：T-IdentifierFoldingFamily（第十轮报告 §9.4 **O-51**，**P2**）
- 来源：第十轮报告 §8 DBX 增量清单 **#55**——DBX `7e5bcade`：引号策略按**方言分组**（`sqlCompletion.ts:1442-1452`：`ORACLE_COMPAT={oracle,oceanbase-oracle,yashandb,oscar,xugu}`、`UPPER_FOLDING={dameng,db2}`；`:3810-3823` 分支）
- 性质：**正确性（窄面）**——承接刚落地的 O-42（Oracle 目录来源引号），把「单方言特判」升级为「按折叠方向分组」
- 实施分支：自 master 拉任务分支 t-identifier-folding-family
- 验证基线：master `03f6d25`（最近可核读数 491 类/5604 用例 0 失败 与 5577 例 0 失败，属不同批次树；实施时以当次实测为准）

---

## 一、背景与依据（现状核实 + 两处真机实测，含三处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「我方 O-42 已落地，但 DM 未覆写 `quoteIfNeeded` → 与 Oracle 同族未覆盖，是缺陷」 | **证伪（口径修正 1，本轮真机实测）**：达梦 DM8 实测**大小写不敏感**——`SELECT COUNT(*) FROM sys.sysobjects` / `FROM sys."sysobjects"` / `FROM sys."SYSOBJECTS"` **三种形态均返回 1029 行**。字典里系统表是大写（`SYSOBJECTS`），但裸写小写与引号小写都能命中 → **DM 不折叠、按大小写不敏感匹配** → 「DM 未覆写」**不是缺陷**，本计划原判作废 | DM 从「待覆盖缺口」改为「无需处理的族」 |
| 2 | 「引号策略按方言分组」 | **成立，且本轮实测给出了分组依据（口径修正 2，本轮真机实测）**：**Oracle 与 PG 的折叠方向恰好相反**。Oracle（第九轮实测）：裸写小写**折叠为大写**（`FROM dual` 命中 `DUAL`），引号保留原样（`FROM "dual"` → `ORA-00942`）。PG（本轮实测）：裸写大写**折叠为小写**（`FROM INFORMATION_SCHEMA.TABLES` 命中 `information_schema.tables`，返回 261 行），引号保留原样（`FROM information_schema."tables"` → 261 行；**`FROM information_schema."TABLES"` → `relation "information_schema.TABLES" does not exist`**） | 分组维度＝**折叠方向**（UPPER / LOWER / NONE），不是「数据库品牌」 |
| 3 | 「O-42 的『含小写即加引号』规则」 | **是 Oracle 专属规则，套到 PG 族方向相反（口径修正 3）**：O-42 的实现（`sql/Dialect.kt:292-297`）只在 `OracleDialect` 覆写；PG/KingBase 走默认委托（`:72`）→ **完全不按大小写判定**。对 PG 而言，**含大写**的目录名（以引号创建）会被裸写 → 折叠为小写 → 查不到 | PG/KingBase 侧存在真实缺口（方向与 Oracle 相反） |

### 1.1 我方现状（O-42 落地后的形态）

| 落点 | 内容 |
|---|---|
| `sql/Dialect.kt:159-165` | `enum class IdentifierCaseSource { CATALOG, ENTITY }`——**按名字来源**分流（不是按方言族） |
| `sql/Dialect.kt:55-71` | `quoteIfNeeded` KDoc：CATALOG 含小写 → 必包；ENTITY 裸写 |
| `sql/Dialect.kt:72` | 默认实现委托 `quoteIfKeyword`（**无大小写判定**） |
| `sql/Dialect.kt:292-297` | **唯一覆写者**（Oracle）：`if (caseSource == CATALOG && identifier.any { it.isLowerCase() }) quoteIdentifier(identifier) else quoteIfKeyword(identifier)` |
| `database/DbTableSpecAdapter.kt:60` | **唯一接线点**：`identifierCaseSource = IdentifierCaseSource.CATALOG` |
| `generator/CrudSqlGenerator.kt:148-149` | `quoted(spec, id) = spec.dialect.quoteIfNeeded(id, spec.identifierCaseSource)` |
| `model/BatchModel.kt:87` | `q(id) = dialect.quoteIfNeeded(id, identifierCaseSource)` |
| `generator/CrudTableSpec.kt:51` | 默认 `IdentifierCaseSource.ENTITY`（实体来源，裸写正确） |

**无方言族概念**：`ORACLE_COMPAT` / `UPPER_FOLDING` / `dialectFamily` / `uppercaseFold` 全仓**零命中**（仅出现在竞品报告与本计划里作为概念引用）。

**无标识符去引号路径**（DBX #56 的对偶面**不适用**）：`unquoteIdentifier` / `stripQuotes` / `dequote` / `removeSurrounding` 零命中；仅有的两处是反引号剥离（`reverse/JavaCodeGenerator.kt:916-917`、`sql/extract/SelectStarDetector.kt:33`），语义不同（Java 侧字段匹配，不回流成 SQL）。

### 1.2 折叠方向三族（本轮实测依据）

| 族 | 方言 | 裸写折叠 | 目录名含…时**必须**加引号 | 我方现状 |
|---|---|---|---|---|
| **UPPER** | Oracle | → 大写 | **含小写** | ✅ O-42 已做 |
| **LOWER** | PostgreSQL / KingBase（PG 系） | → 小写 | **含大写** | ❌ **未做**（走默认委托） |
| **NONE（不敏感）** | 达梦 DM8（本机实例实测） | 不折叠、不敏感匹配 | 都不需要 | ✅ 现状即正确 |

**注意**：DBX 把 `{dameng, db2}` 归入 `UPPER_FOLDING`，而**我方实测的 DM8 实例大小写不敏感** → 两方口径不一致。可能原因：DM8 有 `CASE_SENSITIVE` 初始化参数，不同实例行为不同。**本计划按我方实测口径处理**（DM 入 NONE 族），并在 §八 待验证前提中登记该分歧。

### 1.3 既有测试

`sql/DialectTest.kt:93-102`（9 方言 `quoteIdentifier` 逐库断言）、`sql/SqlKeywordsTest.kt`、`generator/CrudSqlGeneratorTest.kt`（17 例，含 O-42 六例：`:255` oracleCatalogSourceQuotesNamesContainingLowercase、`:284` oracleCatalogSourceLeavesAllUppercaseNamesBare、`:303` oracleCatalogSourceKeepsReservedWordQuoting、`:317` oracleEntitySourceKeepsDerivedNamesBare、`:357` otherDialectsRenderByteIdenticalRegardlessOfCaseSource、`:377` oracleIsTheOnlyDialectWhereCaseSourceChangesOutput）。

**注意**：`:377` 的用例名「**oracle 是唯一**因 caseSource 改变输出的方言」在本计划实施后**必须改名/改断言**——PG 族将成为第二个。

## 二、目标与非目标

**目标**：① 引入**折叠方向族**（UPPER / LOWER / NONE）作为方言的显式属性；② `quoteIfNeeded` 按族分流（UPPER 族含小写即包、LOWER 族含大写即包、NONE 族不判）；③ 修掉 PG/KingBase 侧「含大写的目录名被裸写」这一真实缺口。

**非目标**：不做去引号路径（我方无此路径）；不改实体来源路径（`ENTITY` 语义逐字不变——那是正确性底线）；不按数据库品牌硬编码（按**折叠方向**分组）；不动保留字集合（`ORACLE_RESERVED` / `DM_RESERVED` / `KINGBASE_RESERVED` 各自的官方核对结论保持）。

## 三、方案

**T-IFF-01（折叠方向族）**：在 `sql/Dialect.kt` 新增族属性（建议 `enum class IdentifierFolding { UPPER, LOWER, NONE }` + `Dialect.identifierFolding` 默认 `NONE`），Oracle → `UPPER`，PostgreSQL / KingBase → `LOWER`，其余（MySQL / MariaDB / H2 / SQLite / DM / SQLServer）→ `NONE`。

**T-IFF-02（按族分流）**：把 `quoteIfNeeded` 的覆写从 `OracleDialect`（`:292-297`）**提升为基类按族实现**（消除「唯一覆写者」形态）：`CATALOG` 且族为 `UPPER` 且含小写 → 包；`CATALOG` 且族为 `LOWER` 且含大写 → 包；`ENTITY` → 逐字不变；其余 → `quoteIfKeyword`。

**T-IFF-03（PG/KingBase 侧验证）**：用 DBX MCP 的 PG 连接（见 §五）验证改后产物——对目录来源含大写的名字生成 CRUD，**实际执行成功**（改前会 `relation … does not exist`）。

**T-IFF-04（测试与文档）**：`CrudSqlGeneratorTest.kt` 新增 PG 族用例（含大写 → 加引号；全小写 → 裸写）；**改 `:377` 的用例名与断言**（Oracle 不再是唯一受 caseSource 影响的方言）；`DialectTest.kt` 补族属性断言（9 方言逐库）。文档：CHANGELOG `[Unreleased]`（**引号行为变化，属用户可见**）+ 变更记录行 + 第十轮报告 §8 #55 落地备注。

## 四、验收标准

1. **Oracle 侧行为逐字节不变**（O-42 六例全绿，含 `:377` 改名前后的等价断言）。
2. **PG 族新行为**：目录来源含大写的名字加引号、全小写裸写（新用例）。
3. **实体来源两族都不变**：`ENTITY` 的派生名在 Oracle 与 PG 下**都仍裸写**（防回归硬要求，沿用 O-42 的判据）。
4. 族属性断言：9 方言逐库声明与预期一致（Oracle=UPPER、PG/KingBase=LOWER、其余=NONE）。
5. **真机端到端**：PG 连接上，目录来源含大写的名字生成的 SQL **实际执行成功**（§五-2）。
6. 全量 testShards + testDatabase + agent 绿。

## 五、验证清单（可经 DBX MCP 执行）

**两个连接本轮均已实测可用**，且都已取到基线读数：

| 连接 | 用途 |
|---|---|
| PostgreSQL（`ce028080-e7fb-40ea-8155-4842f6ecc611`） | PG 族折叠方向（**已实测**：裸大写折小写、引号大写失败） |
| Oracle Free 23 / FREEPDB1（`311778f3-3f03-4e13-b561-74ca36873af1`） | Oracle 族对照（第九轮已实测） |
| 达梦 DM8（`bb136253-259c-4f68-b96f-b98b19e72f95`） | NONE 族（**已实测**：三形态均命中） |

**纪律：三个库均为共享测试库，只读验证、禁止建删对象。**

| # | 场景 | 判据 |
|---|---|---|
| 1 | 复现基线三组读数（Oracle / PG / DM） | 与 §1.2 表一致（作为判据成立的前提留档） |
| 2 | PG：目录来源含大写的名字 | 生成的 SQL 带引号；**实际执行成功**（改前 `relation … does not exist`） |
| 3 | PG：目录来源全小写的名字 | 裸写；执行成功（与改前一致） |
| 4 | Oracle：O-42 六例的对应真机场景 | 与改前逐字节一致 |
| 5 | KingBase（`da5e4c3e-…`） | 确认是否与 PG 同方向（若可用） |
| 6 | DM8 | 确认 NONE 族下行为与改前一致 |

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **实体来源被连带改动**（最大风险） | 若把族判定写进默认路径而非按 `caseSource` 分流，会让实体来源的派生名被加引号 → 正确性回归（O-42 已实测过该形态：裸写 `user_tables` 命中、加引号 `ORA-00942`） | 验收 3 用「两族都仍裸写」的用例钉住；T-IFF-02 的分流条件显式含 `caseSource == CATALOG` |
| DM 口径分歧 | 我方实测 DM8 不敏感，DBX 把 DM 归 `UPPER_FOLDING` | 按我方实测处理（NONE），并在计划 §八 登记分歧；**不照抄 DBX** |
| PG 版本/实例差异 | 本轮实测是 PG 一种实例；`CASE_SENSITIVE` 类参数在 DM 上已知存在，PG 侧无此参数（行为由标准决定，风险低） | §五-5 补 KingBase；PG 侧标准行为，风险低 |
| 既有用例名失真 | `CrudSqlGeneratorTest.kt:377` 的用例名将被证伪 | T-IFF-04 显式要求改名（不是删除） |
| 族抽象过度 | 引入新枚举可能被 `EnumReachabilityGuardTest`（死常量守卫）要求 src/main 有产出点 | 三个族值在 `Dialect` 实现里都有消费点（UPPER/LOWER/NONE 分支），预期通过；实施时确认 |

## 七、实施顺序

```
§五-1（复现基线三组读数）→ T-IFF-01（族属性）→ T-IFF-02（按族分流）
  → T-IFF-04（用例，含 :377 改名）→ §五-2/3（PG 端到端）→ 收口
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| 基线读数 | 本次更新 | Oracle（第九轮）/ PG（本轮）/ DM（本轮）三组已实测留档 |
| T-IFF-01 | （实施后填） | 族枚举命名与各方言归属须回填 |
| T-IFF-02 | （实施后填） | 分流条件与「Oracle 唯一覆写者」消除情况须回填 |
| T-IFF-04 | （实施后填） | 新用例、`:377` 改名、门禁读数须回填 |
| §五 端到端 | （实施后填） | PG 侧改前/改后实测读数须回填（**决定性证据**） |

**待验证前提（实施者先证伪再动手）**：

1. **DM 的口径分歧未解**：我方实测 DM8 大小写不敏感（三形态均命中），而 DBX 把 `{dameng, db2}` 归入 `UPPER_FOLDING`。可能因 DM8 的 `CASE_SENSITIVE` 初始化参数不同实例而异。**本计划按我方实测取 NONE**；若后续遇到 case-sensitive 的 DM8 实例证据，须重新评估（届时 DM 移入 UPPER 族）。
2. **KingBase 的折叠方向未实测**——按 PG 兼容推断为 LOWER，**须用 DBX MCP 的 KingBase 连接（`da5e4c3e-…`）证伪**。
3. 「PG 族含大写即包」的判据形态——本计划按「含任一大写字母即包」表述，**确切判据**（是否排除保留字、是否用 `any { it.isUpperCase() }`）须与 O-42 的 `any { it.isLowerCase() }` 形态对称实现，并在用例中钉住。
4. H2 / SQLite / SQLServer 的折叠语义未实测（本计划归 NONE）——SQLServer 大小写取决于排序规则（collation），可能因实例而异；实施时确认是否需单独族或维持 NONE。
