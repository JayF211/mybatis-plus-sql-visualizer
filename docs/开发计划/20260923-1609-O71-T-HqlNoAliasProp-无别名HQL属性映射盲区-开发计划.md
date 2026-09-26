# O-71 · T-HqlNoAliasProp：无别名 HQL 属性映射盲区（WHERE 条件列名未转换）

> 状态：**计划待确认**（分析阶段已实测复现，未动任何生产代码）
> 日期：2026-09-23
> 提出方式：用户反馈截图（字段没正常自动转换），demo 项目未能复现
> 分析会话：主控亲历，复现探针已落盘（见 §八）

## 一、现象与实测复现

用户反馈截图（`commonDao.findByQuery` 预览卡）里同一张预览 SQL 同时出现「部分正常、部分不正常」：

```
SELECT WORKSTATION_STATUS.ID, ..., WORKSTATION_STATUS.STATION_CODE, ...   -- 正常
FROM WORKSTATION_STATUS                                                    -- 正常
WHERE stationCode = ?                                                      -- 异常 1：属性名原样残留
    AND updateInfo.BE_DELETE = 0                                           -- 异常 2：嵌入段前缀残留
```

原始 HQL（无别名形态）：

```java
commonDao.findByQuery(
    "from WorkstationStatus where stationCode = :stationCode " +
    " and updateInfo.beDelete = 0", "stationCode", workstation.getName());
```

实体 `WorkstationStatus extends VersionalEntity`，父类链含 `@Embedded UpdateInfo updateInfo`（beDelete/creator/createdTime 等在其中）。

**迷惑性所在**：SELECT 列表与表名完全正常——因为它们由 `SelectClauseExpander`（T-BareFromSelect 裸 FROM 补列清单）从 `TableInfo.columns` **独立生成**，根本不经过属性映射；WHERE 条件才是属性映射的辖区。所以「半对半错」不是两条缺陷共同作用于同一处，而是一条缺陷（WHERE 盲区）对照一条正常通道。

### 实测复现（本会话已完成，非推断）

探针测试 `src/test/kotlin/cn/song/mybatisplus/visualizer/converter/jpa/HqlConverterBarePropertyReproTest.kt`（**未提交，临时探针**），手工构造与截图同构的 TableInfo（stationCode 自有驼峰字段 + beDelete 为 embeddedParent=updateInfo 的展平列），跑 `testFast` 两用例全绿（BUILD SUCCESSFUL in 51s）：

| 用例 | 断言（钉住现状） | 结果 |
|---|---|---|
| testReproUserScenario | 产物保留 `stationCode = ?`（异常 1 形态） | 通过＝复现 |
| testReproUserScenario | 产物保留 `updateInfo.BE_DELETE = 0`（异常 2 形态） | 通过＝复现 |
| testAliasedEquivalentConverts（对照组） | 带别名 `w.stationCode` → `w.STATION_CODE`、`w.updateInfo.beDelete` → `w.BE_DELETE` | 通过＝带别名形态完全正常 |

对照组同时回答了「为什么 demo 没复现」（§三）。

## 二、根因分析（代码定位）

转换链：`SqlPreviewService.previewCustomDaoHql` → `HqlConverter.convert`（`HqlConverter.kt:106` convertInternal）→ 步骤 9 `mapPropertyToColumn` → **`JpaAliasPropertyMapper.mapProperties`**（`JpaAliasTableResolver.kt:226`，与 JpqlConverter 共用同一份实现）。

### 缺陷 1：裸属性结构性不进映射（异常 1）

`JpaAliasPropertyMapper.PROPERTY_PATTERN`（`JpaAliasTableResolver.kt:183`）：

```
\b([a-zA-Z_][a-zA-Z0-9_]*)(\s*\.\s*)((?:[a-zA-Z_][a-zA-Z0-9_]*\s*\.\s*)*)([a-zA-Z_][a-zA-Z0-9_]*)\b
```

要求「限定符 + 点号 + 属性」至少两段——**裸标识符（无点号）根本不命中这个正则**。无别名 HQL 里 `WHERE stationCode = ?` 的 `stationCode` 是单词，没有任何映射步骤处理它，原样透传到产物。

这是 O-57 当时的**刻意契约**而非疏忽：`HqlAliasColumnMappingTest.test隐式作用域_裸属性不回归`（`HqlAliasColumnMappingTest.kt:168`）明确钉死「`beAutoZone = true` 原样保留」，注释写着「O-57：无别名限定的裸属性行为与改前一致（正则要求带点号）」。O-57 的原则是「只收窄不丢失」（对既有产物严格收窄+新增，不产生回归面），裸属性替换被保守排除。代价当时未被看见：无别名/漏写别名前缀的 HQL，WHERE 条件**永远不会**映射成列名，产物 SQL 拿去执行必报 Unknown column（Hibernate 真实生成的列名是大写下划线）。

### 缺陷 2：限定符位的嵌入段无法剥离（异常 2）

`updateInfo.beDelete` 是两段路径，命中 PROPERTY_PATTERN：qualifier=`updateInfo`、middle=空、field=`beDelete`。`owner = scope.byAlias["updateInfo"] ?: rootTable` 回落根实体；`columnByField("beDelete")` 命中展平列（fieldName=beDelete、columnName=BE_DELETE、embeddedParent=updateInfo，写入点 `JpaEntityResolver.kt:327`）。

接下来走 `mapProperties` 的三分支（`JpaAliasTableResolver.kt:236`）：

- `isFlattenedEmbeddedPath(middle, column)`（`JpaAliasTableResolver.kt:264`）**只检查中间段**是否为嵌入段——两段形态里 middle 为空，恒 false；
- 于是走「只换属性名」分支：`"$qualifier$dot$middle${column.columnName}"` = `updateInfo.` + `BE_DELETE`。

即：嵌入段剥离判据（列自身的 embeddedParent）设计时只考虑了三段形态 `别名.updateInfo.属性` → `别名.列名`（有测试覆盖：`pickTicket.updateInfo.createdTime` → `pickTicket.created_time`），没有覆盖**无别名时嵌入段自己占 qualifier 位**的两段形态 `updateInfo.属性` → 产物应为裸列名 `BE_DELETE`，现在却残留 `updateInfo.`。`updateInfo.` 在 SQL 语义下会被当「库.表」限定，产物同样无效。

### 「继承父类」的真实角色

用户的猜测（「是不是继承父类的问题」）只对一半：

- 继承 `@MappedSuperclass` 父类且父类带 `@Embedded updateInfo`，是**异常 2 的必要条件**——HQL 里出现 `updateInfo.beDelete` 这种「嵌入段开头」的路径，才暴露缺陷 2；带别名写法下同样的继承结构完全正常（对照组实测 `w.updateInfo.beDelete` → `w.BE_DELETE`）。
- 异常 1 与继承无关——`stationCode` 是实体自有字段，任何无别名 HQL 的驼峰裸属性都会触发。

## 三、为什么 demo 项目没复现

按证据分层（已实测 / 代码可判 / 定不下来）：

- **已实测**：带别名形态两条链全正常（探针对照组）；插件全部既有测试夹具（HqlConverterTest、CustomDaoHqlLocatorTest、HqlAliasColumnMappingTest 等）的 HQL 全部带别名（grep 核对，唯一裸属性出现处就是上述「不回归」契约测试本身）。
- **代码可判**：无别名形态必然触发本缺陷——转换管道里不存在裸属性的第二条映射通道。
- **定不下来（不阻塞修复）**：用户 demo 里具体写的 HQL 形态。最可能的解释是 demo 里用了带别名写法（`from XxxEntity e where e.xxx = ...`），或 demo 实体字段名恰好与列名同形（全小写字段名 fieldName==columnName 时即便命中映射分支也看不出差异）。可向用户求证，但不必等——修复针对的是形态而非具体项目。

## 四、待验证前提（实施者先证伪再动手）

1. **已实测（本会话）**：两缺陷的触发形态与归因（探针三断言）。实施者第一步先跑 `./gradlew testFast --tests "cn.song.mybatisplus.visualizer.converter.jpa.HqlConverterBarePropertyReproTest"` 确认基线绿。
2. **待实施时验证**：`PROPERTY_OR_LITERAL` 单遍交替里新增裸词分支后，原「字面量分支在前」的保护顺序仍然成立（字面量内容永不改写）——需用既有 `test字面量内的点号路径不被替换` 一组负例回归确认外，补「裸词在字面量内」新负例。
3. **待实施时验证**：`mapPropertyToColumn` 的调用时点在 `mapEntityToTable` **之后**（`HqlConverter.kt:145-151`），FROM 后的实体名已变表名——裸词替换不会碰到实体名，但**别名**仍在 SQL 里且不含点号，裸词分支必须排除 `scope.knownQualifiers` 与 `CLAUSE_KEYWORDS`，否则 `where z.createdTime > :t` 的 `z` 若恰好撞某字段名会被误转。实施时构造「别名=字段名」撞名负例验证防线。
4. **待实施时验证**：UPDATE/DELETE 的 HQL 同走 `mapProperties`（语句分派在步骤 10），裸属性修复对 `UPDATE Entity SET status = :status` 的 SET 裸属性**同样生效**；但注意 `appendUnmappedOnHint` 对 UPDATE/DELETE 显式短路（`JpaAliasTableResolver.kt:300`）——新裸词逻辑不得把尾注判定搞回非短路态。补 UPDATE 场景测试钉住。
5. **推断（低风险）**：JpqlConverter 与 HqlConverter 共用 `mapProperties`，本修复两链同步生效；`testJpql链路_与Hql链路一致` 应保持互证。

## 五、修复方案

改动收敛在 `JpaAliasPropertyMapper`（`converter/jpa/JpaAliasTableResolver.kt`）单文件 + 测试；不动定位器与转换器管线结构。

### T-01：裸属性 → 列名映射（异常 1）

在 `mapProperties` 的单遍扫描中，为「无点号裸标识符」增加处理分支（可用 `PROPERTY_OR_LITERAL` 之外的补充正则，或扩为三支交替：字面量 | 点号路径 | 裸词）。替换口径**从严**：

- `owner.columnByField(word)` 精确命中（fieldName 全等）且 `fieldName != columnName` 才替换；不猜命名规则、不模糊匹配。
- 排除表：`CLAUSE_KEYWORDS` 扩充 SQL 词表（TRUE/FALSE/NULL/COUNT/SUM/AVG/MIN/MAX/CONCAT/UPPER/LOWER 等）；词后紧跟 `(` 的（函数调用）不替换。
- 排除 `scope.knownQualifiers`（FROM/JOIN 出现过的别名、实体名、表名）——前提 3 的撞名防线。
- 单引号字面量内不替换（沿用 STRING_LITERAL 同一口径；未闭合单引号行为与现实现一致）。
- 歧义口径：多 FROM 元素（自连接等）场景裸属性**沿用根实体列清单**精确匹配，不猜副表——与既有「未命中回落根实体」原则一致；HQL 语义上多别名下裸属性本就歧义，能转对根实体字段是改善，转不了保持透传。

**契约变更（显式登记）**：`test隐式作用域_裸属性不回归` 的期望反转——`beAutoZone` 应映射为列名；该测试改名（如 `test隐式作用域_裸属性映射为列名`）并在注释登记 O-71 推翻 O-57 保守契约的因果。`JpaAliasPropertyMapper` / `JpaAliasTableResolver` KDoc 中「只收窄不丢失」「正则要求带点号」的表述同步更新。

### T-02：限定符位嵌入段剥离（异常 2）

`mapProperties` 三分支中，当 `scope.byAlias[qualifier]` 未命中（qualifier 不是别名/实体名/表名）且 `column.embeddedParent == qualifier` 时，产出 `${column.columnName}`（剥掉限定符与点号，裸列名）。三段形态既有行为（`别名.updateInfo.属性` → `别名.列名`）不动。

### 明确不做

- 不按 NamingStrategy 盲猜「转换后像列名就替换」——猜错列名比保留属性名更误导（沿用 JpaFromEntityMapper 既有原则）。
- 不动 `SelectClauseExpander`、`JpaJoinRewriter`、定位器与 SqlPreviewService 管线。

## 六、测试与验收

### 新增/改造

1. **探针转正**：`HqlConverterBarePropertyReproTest` 两用例断言翻转为期望行为（`STATION_CODE = ?`、`AND BE_DELETE = 0`）——先红（现状）后绿（修复），即为反向验证载体。
2. **负例矩阵**（新增于 HqlAliasColumnMappingTest 或新文件）：
   - 关键字/函数名不误转（`where name like :kw`、`select count(1)`、`beAutoZone = true` 的 true）；
   - 字面量内裸词不动（`where remark = 'stationCode'`）；
   - 别名/实体名撞字段名不误转（前提 3）；
   - 多别名歧义沿用根实体口径；
   - UPDATE SET 裸属性生效且不落尾注（前提 4）；
   - Jpql 链与 Hql 链一致；
   - 幂等（重复调用不变）。
3. **既有回归**：HqlAliasColumnMappingTest 全量、HqlConverter 全家、JpqlConverter 全家、`test隐式作用域_裸属性不回归` 改造后通过。

### 反向验证（守卫纪律）

临时摘除 T-01/T-02 修复分支 → 探针用例必红，贴两次读数（修复前红＝现状基线已在 §一 记录；修复后绿；再摘除再红）。

### 验收口径

- 用户场景 HQL 产物：`WHERE STATION_CODE = ? AND BE_DELETE = 0`（与 Hibernate 真实语义一致）。
- 带别名形态产物与现状**逐字一致**（零回归面）。
- 真机 runIde 用用户无别名形态跑一次预览卡（可选，实施完成后交用户确认）。

### 验证口径

纯字符串管道无平台依赖，日常验证走 `testFast` 定向；收口按「测试三档分层」由只读收口代理跑全量，主控不碰 src/main。

## 七、波及面与风险

- JpqlConverter 共用实现，两链同步变化——JPQL 用户若有依赖「裸属性透传」的产物，行为变化是修复而非回归（透传产物本就无效）。
- 新增替换面的唯一误伤窗口：裸词恰好等于某属性 fieldName 且位于关键字/字面量防线之外、语义又非属性——已知场景仅前提 3 的别名撞名，已设防线并配负例。
- O-57「不产生『原本会被替换、现在不替换』」红线不受影响：本次只新增替换面。

## 八、实施记录

（实施者回填；工作树、提交链、门禁读数按批次收口清单七步登记）

### 2026-09-23 实施回填（主控亲自实施，未派子代理）

**工作树与提交链**：

- 工作树 `F:/code/Java/Gitee/SongRenShuo/mpsv-hql-noalias-prop`，分支 `t-hql-noalias-prop`（扁平名，自 master `69a4028` 切出）。
- `69a4028` docs(计划)：计划文件 + 现状探针（master 上先行提交）。
- `f025a67` fix(jpa)：T-01 + T-02 实施 + 探针转正（`git mv` 为 `HqlBarePropertyMappingTest`）+ 契约改造。

**生产代码落点**（收敛在 `converter/jpa/JpaAliasTableResolver.kt` 单文件，管线结构未动）：

- T-02：`mapProperties` 三分支中新增「owner 为空（qualifier 非别名）且 `column.embeddedParent == qualifier`」分支，产出裸列名；带别名三段形态走既有 `isFlattenedEmbeddedPath` 分支不变。
- T-01：新增 `BARE_WORD_OR_LITERAL`（字面量分支在前 + 函数调用负前瞻）、`BARE_WORD_EXCLUDED`（TRUE/FALSE/EMPTY/MEMBER/ESCAPE/SOME/ANY/ALL/OF）与 `mapBareProperties`；排除表还含文件顶层 `CLAUSE_KEYWORDS`（自 `JpaAliasTableResolver` 提为顶层共用）与 `scope.knownQualifiers`。
- `mapProperties` 尾部串接：`appendUnmappedOnHint(mapBareProperties(mapped, rootTable, scope), scope)`——尾注生成仍在裸词映射之后，尾注文本不进裸词扫描。
- KDoc：类 KDoc 契约变更登记（O-57 保守契约推翻 + 既有红线仍成立）；`CLAUSE_KEYWORDS` 顶层化注释。

**测试落点**：

- `HqlBarePropertyMappingTest`（纯 JUnit，手工 TableInfo，免平台依赖）：用户场景 2 例 + 负例矩阵 6 例 + UPDATE 1 例 + 幂等 1 例，共 10 例。
- `HqlAliasColumnMappingTest`：`test隐式作用域_裸属性不回归` 改造为 `test隐式作用域_裸属性映射为列名`（期望 `be_auto_zone = true`），新增 `testJpql链路_裸属性一致`、`testUpdate_SET裸属性映射`。

**读数**（`test --tests` 两类定向）：

- RED 基线（实施前）：31 条跑、8 红，全红在预期断言——现状产物原样保留 `beAutoZone = true` / `stationCode = ?` / `set beAutoZone = ?`（三类形态均实证）。
- 反向验证（临时摘除 T-01/T-02 修复分支，源内标记 REVERSE-VERIFY 后恢复）：**同样 8 条红**，与 RED 基线形态一致——绿由两修复带来，非断言空转。
- 实施后：定向 **BUILD SUCCESSFUL 全绿**（31 条）；`testFast` 全桶 **3172 条，1 失败**——失败为 `GuardSelfTestCoverageMetaGuardTest`（`mcp/McpGateFilterRegistrationGuardTest.kt` 未登记进 GUARD_CLASSES），**master 基线自带**（69a4028 已含该类，非本任务引入，留相关会话收口）；本任务新增 10 例已进桶（3162→3172）。
- 全量门禁：**未执行**——只读收口代理按预案在第 2 步停止：`timeout 10/15 docker info`、`docker version` 三次探测全部超时挂起（引擎半活态），照「半活态跑全量会无限挂死」定例不跑 `testShards`。收口代理登记已正常注销。**权威全量门禁缺口未闭合**，待 docker daemon 恢复后可直接重派收口代理（步骤：register 独立沙盒 → docker info 验活 → testShards → done）；当前绿读数止于定向 31 条全绿 + `testFast` 全桶 3172 条（残余风险面主要是 ui 包平台测试类，其中走 HqlConverter 生产链的形态已由 `HqlAliasColumnMappingTest` 的 project 形态用例定向覆盖）。

**实施过程中的坑（留档）**：

1. `testFast` 不兼容 `--tests` 过滤：`No tests found for given includes`（划桶 include 与命令行 filter 组合后为空）。定向请用标准 `test --tests`；`testFast` 只裸跑全桶。
2. 新测试类「跑不到」的排查顺序：先查 `@Test` 注解是否真的写了（本次 Write 重写时漏注解，JUnit4 发现器判「无测试」，产物 class 二进制里无 `org/junit/Test` 字符串可作判据——javap 只显方法不显注解，用 grep 查 class 二进制），再查配置缓存/划桶。
3. 工作树副本的 Edit 前必须先 Read 工作树路径（此前读过的是主仓路径，Edit 工具按路径校验）。

**遗留与移交**：

- `GuardSelfTestCoverageMetaGuardTest` 基线红：MCP 守卫类登记缺口，属 MCP 相关会话任务面，本任务不动。
- demo 侧复验（用户无别名形态真机 runIde 走查）：待合并后由用户确认，不阻塞合并。
