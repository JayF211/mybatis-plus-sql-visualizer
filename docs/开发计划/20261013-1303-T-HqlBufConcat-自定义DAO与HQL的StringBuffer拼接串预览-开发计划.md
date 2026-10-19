# T-HqlBufConcat：自定义 DAO / HQL 的 StringBuffer·StringBuilder 拼接串无法预览（开发计划）

> 仓库：`mybatis-plus-sql-visualizer`（插件仓，当前分支 `master`）
> 日期：2026-10-13
> 触发来源：用户 2026-10-13 报障 —— 把 HQL 用 `StringBuffer sb` 逐句 `sb.append(...)` 拼好后
> 传给自研 DAO `commonDao.findByQueryMaxNum(sb.toString(), new String[]{}, new Object[]{}, 0, 10)`，
> 该行既不亮预览图标，手动触发动作也弹「未在光标处找到 …MyBatis-Plus Wrapper…」通用提示。
> 用户附带猜测：**「这个 var 可能是在 class 中，疑似依赖或者 jar 中的信息」**（即怀疑要跨 jar 解析 DAO 实现）。
> 状态：**根因已定位到具体代码分支（见 §2.3）；方案与改动清单待确认，本轮只出计划、不动代码。**

---

## 一、需求

1. 用户在 Java 源码里用 `StringBuffer` / `StringBuilder` + `.append(...)` 逐步拼出的 HQL/SQL，
   经 `sb.toString()` 传给自定义 DAO 方法时，**能识别、能预览**（图标 + 动作两条入口一致）。
2. 识别口径与既有 JdbcTemplate 通道对齐（该通道已支持同一形态，见 §2.4），不引入第二套语义。
3. 预览产物是可读 SQL；对拼接过程中嵌入的运行时值（如 `param.get("仓库序号")`）沿用既有
   `/*运行时值*/…*/` 占位降级，不抛异常、不出错误 SQL 结构。
4. 同族收敛：标准 Hibernate 通道 `session.createQuery(sb.toString())` 属同一缺陷类，
   一并收敛（避免「同样的代码换个入口就不认」）。

## 二、现状与实证

### 2.1 用户代码与观测

用户方法体（节选，来自报障截图）：

```java
StringBuffer sb = new StringBuffer();
sb.append("SELECT new Map(");
sb.append(" loc.id AS 库位序号, ");
// …十余条 sb.append(" loc.xxx AS 中文别名, ")…
sb.append(" FROM WmsLocation loc ");
sb.append(" WHERE 1=1 ");
if (param.containsKey("仓库序号") && …) {
    sb.append(" and loc.warehouseId = ").append(param.get("仓库序号"));
}
// …若干条件分支 append…
sb.append(" AND NOT EXISTS (SELECT sub.id FROM WmsInventory sub "
        + "  WHERE sub.locationId = loc.id  AND sub.qty.baseQty > 0) ");
// …
List<Map<String, Object>> result =
        commonDao.findByQueryMaxNum(sb.toString(), new String[]{}, new Object[]{}, 0, 10);
```

观测：`commonDao.findByQueryMaxNum(...)` 行无预览图标；手动触发动作弹通用提示
「未在光标处找到 MyBatis-Plus Wrapper 调用或 IService/BaseMapper 内置方法…」。

### 2.2 先排除两个误导项

**(a) 不是「装在 jar 里的 DAO 解析不到」——识别不走跨 jar 解析。**
`CustomDaoHqlLocator` 认领这条调用靠的是「名字层启发 + 内容启发」，**不解析 DAO 实现体**：

- `PreviewReceiverResolver.classify`（`psi/PreviewReceiverResolver.kt:49-72`）对 qualifier 变量名做后缀启发，
  `commonDao` 命中 `DAO_NAME_SUFFIXES` 的 `Dao` → 家族 `CUSTOM_DAO`（名字命中即返回，**不触发 `qualifier.type` 的 resolve**）；
- `CustomDaoHqlLocator.matchesDaoQualifier`（`:344-349`）对 qualifier 名判后缀，`commonDao` 命中；
- `matchesQueryMethodName`（`:359-362`）对方法名判关键字，`findByQueryMaxNum` 含 `Query` 命中。

所以「`findByQueryMaxNum` 的实现在哪个 jar」与本次能否识别**无关**：依赖在不在 jar 里，都不是本缺陷的成因。

**(b) 用户看到的旧提示文案，说明装的是旧版构建；但旧版不是本缺陷的成因。**
截图提示为「…Wrapper 调用**或** IService/BaseMapper 内置方法…」的窄口径文案。该窄文案在
`1b9b2a8e docs(bundle): 「未找到可预览 SQL 源」文案覆盖全类型面`（2026-10-11）已被替换为覆盖
JPA/JPQL/HQL/Criteria/JdbcTemplate 的全类型面文案（现 `action.notFound.message`）。
`1b9b2a8e` 不含任何 tag，故截图对应的是**发布构建**。本机实测安装的是
`mybatis-plus-sql-visualizer-2026.9.18-beta.4.jar`（`%APPDATA%\JetBrains\IntelliJIdea2024.1\plugins\`）。
但即便升到已发布的最新 tag（2026.10.9），本缺陷**依然存在**——见 §2.3 的代码分支：`psi/jpa`
两个定位器全文无 `StringBuilder|StringBuffer` 处理（实测 grep 为空）。

### 2.3 根因：HQL 文本提取层不认 `sb.toString()`

调用链（两条入口都走同一 `locate`，见 §2.5）：

`ConvertToSqlAction.kt:191` → `CustomDaoHqlLocator.locate(anchor)` →
`locateFromCall`（`:246`）→ `isCustomDaoHqlCall`（`:293`）。

`isCustomDaoHqlCall` 的 `CUSTOM_DAO/UNKNOWN` 支（`:308`）要求
`(matchesDaoQualifier || matchesQueryMethodName) && matchesHqlContent`。
前一项已命中（§2.2a），**卡在 `matchesHqlContent`**：

- `matchesHqlContent`（`:381-386`）先过 `PreviewReceiverResolver.isStringLikeFirstArg(call)`。
  首参是 `sb.toString()`，`isStringLikeExpression` 对 `PsiMethodCallExpression` 且
  `referenceName == "toString"` 返回 **true**（`PreviewReceiverResolver.kt` 的
  `isStringLikeExpression`）——**门控放行**；
- 接着 `extractArgumentText(firstArg)` → `extractHqlString(expression)`（`:417-455`）。
  该 `when` 只覆盖 `PsiLiteralExpression`（字面量）、`PsiBinaryExpression`/`PsiPolyadicExpression`（`+` 拼接）、
  `PsiReferenceExpression`（变量引用→initializer）；
  **首参是 `PsiMethodCallExpression`（`sb.toString()`），落入末支 `else -> null to false`（`:453`）**；
- `extractArgumentText` 返回 null → `matchesHqlContent` 返回 false → 整条调用不被认领。

**一句话根因：`sb.toString()` 是方法调用表达式，`CustomDaoHqlLocator.extractHqlString` 没有该分支，
落进「无法静态取得字符串 = 不是 HQL 承载」的兜底返回 null；既不会解包 `.toString()`，
也不会回溯 `StringBuffer` 变量的 `.append(...)` 序列。** 光标在该方法体内任意位置
（截图光标压在 `sb.append(" WHERE 1=1 ")` 一带）结论相同：`locate` 扫描方法体全部调用，
唯一可能命中的 `findByQueryMaxNum` 因内容启发失败返回 null。

### 2.4 同形态在 JdbcTemplate 通道已经支持（可直接复用）

`psource/SqlStringConcatenator.kt`（由 `20260903-1015-JdbcTemplate-SQL字符串预览-开发计划` 引入）
已实现「StringBuilder/StringBuffer 聚合」：

- `concatReference`（`:103-114`）沿变量 initializer 分流，`PsiNewExpression` → `concatNewStringBuilder`（`:127-161`）；
- `concatNewStringBuilder` 取 `new StringBuilder(...)` 初值，再 `collectAppendCalls`（`:169-206`）
  收集同一方法体内对该变量的全部 `append` 调用（按 `startOffset` 升序、同 offset 按文本长度升序，
  保证链式 `a.append(X).append(Y)` 的执行序），逐个 `concat` 实参；
- 实参不可静态求值（如 `param.get(...)`）→ 记 `complete=false` 并插入 `/*运行时值*/…*/` 占位。

`JdbcTemplateSqlLocator`（`psource/JdbcTemplateSqlLocator.kt:65-93`）正是这样用的，且它另有一个关键前置：
`resolveToStringCall`（`:154-165`）先把 `sql.toString()` **解包回 qualifier 变量**，
再交 `SqlStringConcatenator.concat(变量引用)`。

注意：`SqlStringConcatenator.concat`（`:59-67`）自身也没有 `PsiMethodCallExpression` 分支
（对 `sb.toString()` 直接返回 null）。所以复用不是「把首参直接喂给 concat」就完事，
**必须带上 `resolveToStringCall` 那步解包**。

### 2.5 两条入口与预筛都不构成额外阻塞（已排除）

- gutter：`CustomDaoHqlGutterLineMarkerProvider.getLineMarkerInfo`（`:63-64`）的
  `passesTextPrefilter` 对 `findByQueryMaxNum` 命中（方法名含 `query`）→ 放行进 `locate()` →
  同 §2.3 返回 null → 不亮图标。
- action：`ConvertToSqlAction.kt:191` 的 `CustomDaoHqlLocator.locate(element) != null` 判假 →
  落到通用 `action.notFound.message` 通知（即用户截图）。
- 两条入口共用 `locate` 与 `previewCustomDaoHql`（`SqlPreviewService.kt:971`），**一处修复两处生效**。

### 2.6 次要发现：`new Map(...)` 不会从 SQL 里摘掉（转换层）

用户 HQL 的 SELECT 是 HQL 动态实例化语法 `SELECT new Map(loc.id AS 库位序号, …)`。
`HqlConverter.convertInternal` 第 4 步 `handleNewExpression`（`HqlConverter.kt:265-298`）会把
`NEW X(a, b)` 摘成 `a, b`，但：

```kotlin
val prefix = "NEW"
val newIndex = hql.indexOf(prefix, searchStart)   // 仅匹配大写 NEW
```

**只匹配大写 `NEW`**；用户写的是小写 `new Map(`，`indexOf("NEW")` 不命中 →
`new Map(…)` 原样进入转换产物 → 预览 SQL 会含 `SELECT new Map(loc.id AS 库位序号, …) FROM wms_location …`，
不是合法 SQL。即使 §2.3 修好、能认领了，产物仍不可读。这是**第二个必须一起修的缺陷**。

> 注：HQL 关键字大小写不敏感，`new`/`NEW` 都是合法写法；此处是大写嗅探的实现缺口，
> 不是「用户写错大小写」。§2.3 与 §2.6 是两处独立缺陷，须同时修才拿到可用预览。

### 2.7 标准 Hibernate 通道同族缺陷

`HqlQueryLocator`（`session.createQuery(...)` 通道）：

- `hasStringHqlArgument`（`:190-213`）的 `when` 无 `PsiMethodCallExpression` 分支 → `else -> false`，
  所以 `createQuery(sb.toString())` 在**入口就返回 null**；
- 且其 `extractHqlString`（`:321-355`）末支是 `else -> firstArg.text to false`——正是
  `CustomDaoHqlLocator` 在 `T-GutterDupFreeze(S1)` 已删除的「把表达式源码文本当 HQL」反模式，
  两条定位器口径已分叉。

## 三、方案与取舍

### 3.1 提取层：委托共享聚合器，不新增第三套实现

新增一个共享入口（建议落在 `psi` 层，或直接复用 `psource/SqlStringConcatenator` 并补
「`.toString()` 解包 + 变量引用 + 拼接」的统合入口），供 `CustomDaoHqlLocator` 与
`HqlQueryLocator` 共用。形态：

```
fun aggregateHqlText(expr: PsiExpression): Pair<String?, Boolean>? {
    // 1. 若是 xxx.toString() / xxx.append(y).toString() → 解包到 qualifier 变量引用
    // 2. 交 SqlStringConcatenator.concat(解包后表达式)
    // 3. 返回 (sql, complete)
}
```

取舍：

| 候选 | 判断 |
|---|---|
| **复用 `SqlStringConcatenator` + 补 `.toString()` 解包（选定）** | 单一来源，JdbcTemplate 通道已验证同一形态；`complete=false` 占位降级语义现成；纯结构遍历、零 resolve 依赖 |
| 在 `CustomDaoHqlLocator.extractHqlString` 里再写一份 StringBuilder 遍历 | 三套逻辑必然再次分叉（`T-HQLGutter` 的根因就是「两个提取函数逻辑分叉」）；否决 |
| 只靠 PSI 常量折叠 / 求值 | 拼接串常含运行时值，静态不可求；且跨语句 `.append` 结构常量折叠也覆盖不到 |

`isLiteralHql` 语义改为 `concatResult.complete`（true=完全静态；false=含运行时值占位）。

### 3.2 转换层：`new` 大小写无关 + `map` 形态

`handleNewExpression` 改为大小写无关地识别 `new`（并确认 `new Map(...)` / `new map(...)`
与 `new com.xDto(...)` 同款摘除类名、保留构造实参）。摘除后 SELECT 区变
`loc.id AS 库位序号, …, loc.remainPassBox AS 剩余周转箱数`，交由既有 `mapPropertyToColumn` /
`SelectClauseExpander` 处理（列引用含点号，展开器按既有口径原样保留）。

### 3.3 语义边界：条件分支里的 append 会被「全量聚合」

`collectAppendCalls` 扫的是**整个方法体**、不区分 `if` 分支，因此 `if (param.containsKey("仓库序号")) sb.append(" and loc.warehouseId = ")…`
这类条件片段会被无条件纳入，预览得到的是「所有可选条件全在场」的最大化 SQL，而非某次运行的真实 SQL。
这是 JdbcTemplate 通道既有口径（那边同样如此），本轮**沿用不改**，但需在预览注释/文档里说明，
免得被当成新缺陷上报（列入 §六待确认）。

## 四、边界矩阵

| 形态 | 首参表达式 | 期望 |
|---|---|---|
| 纯字面量 | `findByQuery("select …")` | 既有行为不变 |
| `+` 拼接 | `findByQuery("select " + "…")` | 既有行为不变 |
| 拼接变量 | `String hql = "s"+"…"; findByQuery(hql)` | 既有行为不变（T-HQLGutter 已修） |
| **StringBuffer 变量直传** | `sb` | **新增：聚合 append 序列** |
| **StringBuffer.toString()** | `sb.toString()` | **新增（用户本次形态）** |
| **链式 append 后 toString** | `sb.append(a).toString()` | **新增：按执行序聚合** |
| StringBuilder 同上 | 同上 | 同上（聚合器类型无关，靠 append 结构判定） |
| 含运行时值 append | `sb.append(param.get("k"))` | 聚合 + `/*运行时值*/…*/` 占位，complete=false |
| 非字符串首参（wrapper 链） | `selectList(wrapper)` | 仍**不认领**（`isStringLikeFirstArg` 与 `T-GutterDupFreeze` 反向守卫保持） |
| `new Map(...)` 小写 | — | 转换层摘除（§3.2） |
| `NEW Dto(...)` 大写 | — | 既有行为不变 |

## 五、改动清单

1. `src/main/kotlin/cn/song/mybatisplus/visualizer/psi/jpa/CustomDaoHqlLocator.kt`
   - `extractHqlString` 增加 `PsiMethodCallExpression` 分支：`xxx.toString()` → 解包 qualifier → 交共享聚合器；
   - 或直接改为委托新共享入口（§3.1），字面量/`+`拼接/变量引用行为逐字节不变。
2. `src/main/kotlin/cn/song/mybatisplus/visualizer/psi/jpa/HqlQueryLocator.kt`
   - `hasStringHqlArgument` 增加 `toString()` 调用形态；
   - `extractHqlString` 委托共享入口，并**删掉 `else -> firstArg.text to false`** 反模式（与 S1 口径对齐）。
3. `src/main/kotlin/cn/song/mybatisplus/visualizer/psource/SqlStringConcatenator.kt`（或新建 `psi` 层包装）
   - 补「`.toString()` 解包」与统合入口；`concat` 的既有分支行为不变。
4. `src/main/kotlin/cn/song/mybatisplus/visualizer/converter/jpa/HqlConverter.kt`
   - `handleNewExpression` 的 `"NEW"` 改大小写无关（`indexOf` → 大小写不敏感匹配），保留括号计数逻辑。
5. 测试：
   - `psi/jpa/CustomDaoHqlLocatorTest.kt` 增「StringBuffer 直传 / toString / 链式 append / 含运行时值 / 非字符串反向守卫」；
   - `psi/jpa/HqlQueryLocatorTest.kt` 增 `createQuery(sb.toString())` 用例 + 非字符串不入场反向守卫；
   - `converter/jpa/*Test` 增 HQLConverter `new Map(...)`（小写）摘除用例，并覆盖中文别名原样保留；
   - gutter/服务集成用例：`locate` 非 null → `previewCustomDaoHql` 出可读 SQL。
6. `CHANGELOG.md`：`[Unreleased]` 增 `### Fixed` 条目。

## 六、验证方案

### 6.1 定向测试

```
./gradlew test --tests "cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocatorTest" ^
                --tests "cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocatorTest" --console=plain ^
                -Pkotlin.compiler.execution.strategy=in-process
```

（跑 gradle 前按仓规 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`，有活跃任务则
`register --name T-HqlBufConcat`；完成后 `done --name T-HqlBufConcat`。）

### 6.2 反向验证（守卫类改动必做）

- 摘掉 `extractHqlString` 的 `toString`/StringBuilder 分支 → 新增用例必须**变红**，贴两次读数；
- 反向守卫：把首参换成 `userMapper.selectList(wrapper)`（wrapper 链）→ 必须**仍不认领**
  （防 `T-GutterDupFreeze` 的「wrapper 链假 HQL」回归）。

### 6.3 不做

- 本轮只出计划、**不改代码、不跑构建**；不 runIde / 不 prepareSandbox / 不 clean / 不 `--stop`；
- 全量与构建校验（`verifyGate`/`verifyPlugin`/`buildPlugin`）留待实施收口，按三档分层纪律执行。

## 七、待验证前提（不确定的机制/形态，实施者先证伪再动手）

1. **`new Map(...)` 小写确实导致产物非法**：由 `indexOf("NEW")` 大小写敏感直接推得，但
   「转换后 SELECT 区到底长什么样、中文别名能否原样保留」需**用例实测**确认（§2.6 是代码级推断）。
2. **`WmsLocation` 等实体能否解析到表名/列名**：需在用户工程（或等价 demo）里实测
   `resolveHqlTable` 与 `mapPropertyToColumn` 的命中情况。实体不可解析时回落 `unknown_table`、
   不做属性映射——此时预览仍出 SQL 但表名/列名不还原，属「可见的降级」，需与用户确认是否可接受。
3. **`sub.qty.baseQty`（@Embedded/@Embeddable 组件路径）**：`mapAssociationPath` 按「三段式 =
   关联路径」处理，`qty.baseQty` 是组件字段而非关联，可能被误判为「需 JOIN」并加注释。
   本条**未验证**，实施时以该 HQL 原样跑一遍转换确认产物。
4. **`NOT EXISTS (...)` / `IN ('I','N')` / `= false` 的 HQL→SQL 透传**：三者理论上原样透传即合法，
   但用户 HQL 同时含子查询实体（`WmsInventory`、`WmsTask`）与布尔字面量，需端到端用例确认不掉字/不误映射。
5. **条件分支 append 的「最大化 SQL」语义**（§3.3）是否符合用户预期：需用户拍板，
   若要求「按某次运行真实条件」则超出静态分析能力，须显式告知边界。
6. **旧版构建（2026.9.18-beta.4）与新版的对应关系**：本缺陷在最新 tag（2026.10.9）同样存在，
   修复只能进更新版本；用户侧是否具备升级条件需确认（本计划不负责发布节奏）。

## 八、实施记录（2026-10-13）

### 8.1 改动文件

- `src/main/kotlin/…/converter/jpa/NewExpressionStripper.kt`（**新增**）：`HqlConverter` 与
  `JpqlConverter` 两份重复的 `handleNewExpression` 收敛为单一实现（整词 + 大小写不敏感 +
  「`new` 空白 类名 `(`」形态）；两个转换器的私有实现删除、调用点改 `NewExpressionStripper.strip(...)`、
  类 KDoc 第 4/3 步说明同步。
- `src/main/kotlin/…/psi/jpa/CustomDaoHqlLocator.kt`：`extractHqlString` 新增
  `PsiMethodCallExpression` 分支（`sb.toString()` / 链式 `append(...).toString()`）与
  `PsiNewExpression` 初始化变量分支，委托新私有 `aggregateHqlText`；导入 `psource.SqlStringConcatenator`；
  `extractHqlString` 的 KDoc 补两形态。
- `src/main/kotlin/…/psi/jpa/HqlQueryLocator.kt`：`hasStringHqlArgument` 放行 `toString`/`append` 形态；
  `extractHqlString` 接入 `aggregateHqlText`（含 `String hql = sb.toString()` 的 initializer 形态）；
  末支 `else -> firstArg.text to false` 改为 `null to false`（对齐 T-GutterDupFreeze S1）。
- `src/main/kotlin/…/psource/SqlStringConcatenator.kt`：新增共享入口 `concatUnwrappingToString`
  （`.toString()` / `.append(y)` 解包到根变量后交既有 `concat`），提为三通道共用。
- `CHANGELOG.md`：`[Unreleased] › Fixed` 顶部加 T-HqlBufConcat 条目。
- 计划本文档 §八/§九。

### 8.2 测试

新增 17 例：`NewExpressionStripperTest`（新增类 7 例）；`CustomDaoHqlLocatorTest` +5（共 24）、
`HqlQueryLocatorTest` +2（共 23）、`HqlConverterNewDtoTest` +1（共 3）、`JpqlConverterTest` +1（共 32）。

### 8.3 定向读数

```
./gradlew test --tests "…psi.jpa.CustomDaoHqlLocatorTest" --tests "…psi.jpa.HqlQueryLocatorTest"
  --tests "…converter.jpa.NewExpressionStripperTest" --tests "…converter.jpa.HqlConverterNewDtoTest"
  --tests "…converter.jpa.JpqlConverterTest" -Pkotlin.compiler.execution.strategy=in-process
```

`CustomDaoHqlLocatorTest 24/0/0`、`HqlQueryLocatorTest 23/0/0`、`NewExpressionStripperTest 7/0/0`、
`HqlConverterNewDtoTest 3/0/0`、`JpqlConverterTest 32/0/0`（合计 89 例，0 失败 0 错误），
BUILD SUCCESSFUL（GRADLE_EXIT=0）。

### 8.4 反向验证（两次）

- **反向 1（摘掉提取分支）**：`CustomDaoHqlLocator` 的 MCE 分支改回 `null to false`、
  `HqlQueryLocator` 的 MCE 与 `initializer is PsiMethodCallExpression` 两分支改回旧行为 →
  `CustomDaoHqlLocatorTest failures=3`、`HqlQueryLocatorTest failures=2`（`assertNotNull` 全红：
  `locate` 返回 null），GRADLE_EXIT=1。**新用例是判别性的**。
- **反向 2（复刻旧嗅探）**：`NewExpressionStripper` 的 `IGNORE_CASE` 去掉后大写用例失败（1 例，
  证明该 flag 承载大小写不敏感）；进一步把模式改成大写-only `NEW`（复刻旧行为）→
  `NewExpressionStripperTest failures=3`，红的正是三条小写用例
  （`lowercase new Map stripped` / `lowercase new with space before paren stripped` /
  `nested parens stripped by brace counting`），`uppercase`/`renew`/`unbalanced`/`noNew` 保持绿，
  GRADLE_EXIT=1。
- 还原后复跑：89/0/0 全绿（即提交态 = 已测态）。

### 8.5 未做

真机 / runIde / 全量测试 / 构建校验（`verifyGate`/`verifyPlugin`/`buildPlugin`）——按验证三档分层，
全量与门禁留待收口；本轮沙盒被录制任务占用。

## 九、遗留与边界（本轮未解决）

1. **条件分支 append 会被全量聚合**：`SqlStringConcatenator.collectAppendCalls` 扫描整个方法体、
   不区分 `if`，故 `if (param.containsKey("仓库序号")) sb.append(" and loc.warehouseId = ")` 这类
   条件片段也会被无条件计入 → 用户这段代码的预览是「所有可选条件全在场」的**最大化 SQL**，而非
   某次运行的真实 SQL。JdbcTemplate 通道既有口径，本轮**沿用未改**（见 §3.3），需向用户明确。
2. **真实工程端到端未验**：用户 HQL 里的 `WmsLocation` 实体解析、`sub.qty.baseQty`
   （`@Embeddable` 组件路径）是否被 `mapAssociationPath` 误判为关联、`NOT EXISTS` / `IN` / `false`
   的透传——见 §七待验证前提 2~4，仍需在 demo 或用户工程实测。
3. **`createQuery(sb)`（StringBuffer 变量直传）未放开**：`HqlQueryLocator.hasStringHqlArgument`
   的类型门仍只放行 String 类型变量，StringBuilder/StringBuffer 变量直传不放行——该写法在 Java 下
   不编译（`createQuery` 收 String），故未放开；已支持的现实形态是 `createQuery(sb.toString())` 与
   `String hql = sb.toString(); createQuery(hql)`。

