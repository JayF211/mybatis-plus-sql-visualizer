# T-IdentifierFoldingFamily 标识符折叠方向族与引号策略分组 — 开发计划

- 日期：2026-09-21 23:28
- 任务编号：T-IdentifierFoldingFamily（第十轮报告 §9.4 **O-51**，**P2**）
- 来源：第十轮报告 §8 DBX 增量清单 **#55**——DBX `7e5bcade`：引号策略按**方言分组**（`sqlCompletion.ts:1442-1452`：`ORACLE_COMPAT={oracle,oceanbase-oracle,yashandb,oscar,xugu}`、`UPPER_FOLDING={dameng,db2}`；`:3810-3823` 分支）
- 性质：**正确性（窄面）**——承接刚落地的 O-42（Oracle 目录来源引号），把「单方言特判」升级为「按折叠方向分组」
- 实施分支：自 master 拉任务分支 t-identifier-folding-family
- 验证基线：master `28a1efa`（最近可核读数 491 类/5604 用例 0 失败 与 5577 例 0 失败，属不同批次树；实施时以当次实测为准）

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
| 基线读数 | 本次更新 | 四组真机读数已复现留档（DBX MCP 只读，2026-09-22）：**Oracle** `FROM dual` → 1、`FROM "dual"` → `ORA-00942`（UPPER）；**PG**（`ce028080` / cyzc_db）`FROM INFORMATION_SCHEMA.TABLES` → 366、`FROM information_schema."tables"` → 366、`FROM information_schema."TABLES"` → `relation "information_schema.TABLES" does not exist`（LOWER）；**KingBase**（`da5e4c3e` / dbx_test）裸写 → 492、`"TABLES"` → `kb: relation ... does not exist`（LOWER，本轮实测）；**DM8**（`bb136253`）三形态 `sys.sysobjects` / `sys."sysobjects"` / `sys."SYSOBJECTS"` 均 1029（NONE）。 |
| T-IFF-01 | 本次更新 | `sql/Dialect.kt` 新增 `enum class IdentifierFolding { UPPER, LOWER, NONE }` + `Dialect.identifierFolding`（接口默认 `get() = NONE`）。归属：Oracle=UPPER、PostgreSQL=LOWER、KingBase=LOWER、其余 6 方言（MySQL/SQL Server/H2/SQLite/MariaDB/DM）继承默认 NONE。**均为属性赋值式产出点**（`override val identifierFolding = IdentifierFolding.X`），满足 `EnumReachabilityGuardTest` 判据（`:251` 要的是 produced>0，不是消费分支）——已实跑该守卫类通过。 |
| T-IFF-02 | 本次更新 | 分流提升为基类实现：`Dialect.quoteIfNeeded` 默认体 = `if (caseSource == CATALOG) quoteCatalogName(identifier) else quoteIfKeyword(identifier)`，新增文件私有扩展 `private fun Dialect.quoteCatalogName`（UPPER 含小写即包 / LOWER 含大写即包 / NONE 落 `quoteIfKeyword`）。**OracleDialect 的 `quoteIfNeeded` 覆写已删除**（`grep` 全仓唯一覆写者形态消除），改为 `override val identifierFolding = UPPER`。分流条件显式含 `caseSource == CATALOG` → ENTITY 路径三族逐字不变。 |
| T-IFF-04 | 本次更新 | `CrudSqlGeneratorTest`：新增 `pgFamilyCatalogSourceQuotesNamesContainingUppercase`（PG+KingBase，含大写 `Issue_New`/`User_Name` 加引号、全小写 `id` 仍裸写）、`pgFamilyCatalogSourceLeavesAllLowercaseNamesBare`、`pgFamilyEntitySourceKeepsDerivedNamesBare`（含大写派生名探针 `BizOrder`/`OrderNo`）；`:377` 由 `oracleIsTheOnlyDialectWhereCaseSourceChangesOutput` 改名为 `bothFoldingFamiliesChangeOutputByCaseSource`（两族并列，改名非删除）；`:357` 由「其余 8 方言」改写为 `nonFoldingDialectsRenderByteIdenticalRegardlessOfCaseSource`（NONE 族 6 方言逐库点名 + 夹具改用含大写的 `Biz_Order`，比原小写夹具更严）。`DialectTest` 新增 `标识符折叠方向族`（9 方言 + 逐库点名 UPPER/LOWER 成员）、`目录来源按折叠族引用`、`upsert列名按目录来源同一判定`。门禁读数：`gradlew.bat testFast --tests "*CrudSqlGeneratorTest*"` → BUILD SUCCESSFUL（20 例）；`--tests "*DialectTest*"` → BUILD SUCCESSFUL（20 例）；改前必红两次读数见下。 |
| §五 端到端 | 本次更新 | **按原文不可执行（环境受限，未验证）**：全部 8 个 PG 系在册连接上都不存在任何含大写字母的表/视图/物化视图/列/模式/库名/类型（本轮实跑 `pg_class.relname ~ '[A-Z]'`、`pg_attribute.attname`、`pg_namespace.nspname`、`pg_database.datname`、`pg_type.typname` 全 0 行；含 PG 18.3/16.14/14.23、openGauss 5.0.0、KingBase 三库、Baidu PG 两库），而 PG 只对「带引号创建」的对象保留大写 —— 要造出该对象必须 `CREATE TABLE`，直接违反 §五「只读验证、禁止建删对象」。**替代证据**：① 新用例改前必红（两次读数：改前 `org.junit.ComparisonFailure at CrudSqlGeneratorTest.kt:372`、`:530`，2 例红；把基类分流临时改回 `quoteIfKeyword` 反向验证 → 6 例红，还原后全绿）；② 上表四组真机读数已证「裸写大写折叠为小写 → 查不到、引号保留原样」这一前提，规则正确性由前提 + 单测闭环。**待任务发起方定夺**：是否放宽纪律在专用 scratch schema 建一次性对象以补齐真机执行证据。 |

**本次实施的新增偏离（与计划正文不同处）**：

1. **计划漏改 UPSERT 路径，本任务一并接线**：`Dialect.renderUpsert` / `renderUpsertStatement` 增加 `caseSource: IdentifierCaseSource = IdentifierCaseSource.ENTITY` 尾参（默认值语义保持，既有调用点逐字节不变），PG 系后缀辅助改走 `quoteIfNeeded`。若不接线，PG 族会产出「基座加引号、后缀裸写」的半包形态（`ON CONFLICT (Id) ...` 里的裸写 `Id` 被折叠为小写 → 列不存在）——`model/BatchModel.kt:141-143` 的原注释明文点名了这一条件（「若日后为别的方言引入目录来源判定，此处须一并接线」），本任务正是该条件。
2. **H2 归 NONE 与实测相反（2026-09-22 第二轮收口已订正为 UPPER）**：H2 2.3.232 实测裸写 `Issue_New` 被折成 `ISSUE_NEW`、带引号创建的 `"lower_tbl"` 无法裸写访问（与 Oracle 同族），本任务当时**不扩面**（改 H2 会让 H2 现有产物变化，超出本任务写集与验收面），登记为后续。**后续已落地（独立复核 minor㉘）**：`H2Dialect.identifierFolding` 改为 `UPPER`；`DialectTest` 被钉死的断言与 `CrudSqlGeneratorTest` 的族点名清单同步解冻。判别力由真实 H2 引擎对照实验提供（`DialectH2VerificationTest.标识符折叠方向_H2引擎实测对照`：引号创建两个名字后裸写访问——裸写小写名失败、裸写大写名命中 ⇒ 折为大写）。反向验证：摘掉 H2 覆盖 → 定向 60 例 5 失败（恰为 5 条判别用例）。
3. **KingBase 已由「推断」升级为「实测」**：待验证前提 2 的「未实测」作废，见上表基线读数。
4. **DM 口径分歧保留按我方实测**：三形态均 1029 行 → NONE；DBX 的 `UPPER_FOLDING={dameng,db2}` 不照抄（待验证前提 1 维持）。
5. **文档落点**：CHANGELOG / `docs/变更记录.md` / 第十轮报告 #55 落地备注按任务描述第 6 条由 T-Round10-08 统一写，本任务只回填本计划文档 §八（计划 §三 T-IFF-04 与任务描述口径不一致，取任务描述）。
6. **两处 KDoc 失真已同步**：`Dialect` 接口头「五件套」→「六件套（含折叠方向族）」；`CrudTableSpec` / `BatchModel` / `DbTableSpecAdapter` 三处把语义绑死在 Oracle 的 KDoc/注释改为按族表述。

**待验证前提（实施者先证伪再动手）**：

1. **DM 的口径分歧未解**：我方实测 DM8 大小写不敏感（三形态均命中），而 DBX 把 `{dameng, db2}` 归入 `UPPER_FOLDING`。可能因 DM8 的 `CASE_SENSITIVE` 初始化参数不同实例而异。**本计划按我方实测取 NONE**；若后续遇到 case-sensitive 的 DM8 实例证据，须重新评估（届时 DM 移入 UPPER 族）。
2. ~~**KingBase 的折叠方向未实测**~~ → **已实测证实为 LOWER**（见上表基线读数：裸写 492 行、引号大写 `kb: relation ... does not exist`），前提作废。
3. 「PG 族含大写即包」的判据形态——本计划按「含任一大写字母即包」表述，**确切判据**（是否排除保留字、是否用 `any { it.isUpperCase() }`）须与 O-42 的 `any { it.isLowerCase() }` 形态对称实现，并在用例中钉住。
4. ~~H2 / SQLite / SQLServer 的折叠语义未实测（本计划归 NONE）~~ → **H2 已实测证伪**（2.3.232 折为大写，与 Oracle 同族），本任务当时**有意维持 NONE 不扩面**（登记为后续，见偏离 2）；**该后续已于 2026-09-22 第二轮收口落地为 UPPER（独立复核 minor㉘）**，判别力由真实 H2 引擎对照实验提供。SQLite / SQLServer 仍未实测（SQL Server 大小写取决于 collation），维持 NONE。
