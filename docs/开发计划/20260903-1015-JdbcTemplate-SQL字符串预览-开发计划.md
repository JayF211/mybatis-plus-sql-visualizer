# JdbcTemplate / NamedParameterJdbcTemplate SQL 字符串预览 — 开发计划

> **创建日期**：2026-09-03
> **优先级**：P1（Spring 项目核心 DAO 层刚需，正向预览缺口）
> **涉及文件**：11 个改动（5 新 + 6 改），估计 ~600 行 + ~350 行测试
> **数据来源**：用户真实代码两段（`String sql = "SELECT ... :CUR_USER ..."` 拼接 + `StringBuilder` 拼接）+ Spring JDBC 源码
> **前置**：M47（`JdbcTemplateLogParser` 日志捕获）已在 v2026.9.2 交付；本需求与其互补，共用 SQL 模型与 `LogSqlRestorer.PLAIN_TYPES` 引号规则

---

## 问题分析

### 现状

插件已有 **SQL 日志捕获**（M47，`org.springframework.jdbc.core` DEBUG/TRACE 日志 → 还原可执行 SQL，`SqlSource.JDBC`），但缺少**正向预览**：用户像这样直接写 SQL 字符串的常见 DAO 层代码，编辑器里没有任何水印图标，快捷键 Alt+Shift+Q 也不识别——插件对 JdbcTemplate 场景是"日志看得见、源码点不了"。

用户真实形态（两段示例）：
1. **字符串字面量 `+` 拼接**（`String sql = "SELECT ..." + "...";`）——两个分支 UNION ALL、`CASE WHEN`、子查询、`:CUR_USER` 命名参数
2. **`StringBuilder` 分段拼接**（`StringBuilder sql = new StringBuilder(); String querySelect = "SELECT ..."; sql.append(querySelect).append(queryWhere)...`）——按 `querySelect`/`queryWhere`/`orderSql` 分段条件组合

两段共性问题：
- SQL 用 **命名参数** `:CUR_USER`（`NamedParameterJdbcTemplate` 风格）而非 `?` 占位符
- SQL 分散在**多个拼接片段**中，不是单一字符串字面量

### 覆盖缺口（能力层）

| 维度 | 现状（@Select 注解 / HQL 字符串） | JdbcTemplate 字符串场景需要 |
|------|-------------------------------|----------------------------|
| 识别 | 注解 PSI / createQuery 调用 | `queryForList`/`update` 等方法调用的字符串/`StringBuilder` 实参 |
| 字符串提取 | 单字面量 via 常量求值器 | 字面量 `+` 拼接 **+ StringBuilder 分片聚合** |
| 参数形态 | `#{param}` / `?` / `:name`(HQL) | `:NAME` 命名参数（数量不固定、可复用） |
| 引用 SQL 值 | `@Param` 方法参数名 | 运行时方法参数 / 字段 / `:CUR_USER` 等语义常量 |
| 占位符替换 | `?` 按出现顺序 | `:name` 按名回填（面板参数行） |

**关键差距 1 — 命名参数回填**：现有 `ParameterReplacer.buildExecutableSql` 只支持 `?` 顺序替换；`PreviewResult.unresolvedParams` 与 `ParamHint` 全部按 **`?` 出现顺序** 建模。`:CUR_USER` 需新增"按名匹配"的渲染路径（面板参数输入框输入值后，把同名 `:CUR_USER` 全部替换，与 `#{}`/`?` 语义对齐 NamedParameterJdbcTemplate：同名参数所有出现共用一值）。

**关键差距 2 — StringBuilder 聚合**：需求示例二把 SQL 存在 `querySelect`（局部变量）+ `sql.append(...)`（StringBuilder 变量）里，条件性追加 `changeTable`/`changeWhere`。要把"该调用实际拼接出的 SQL"呈现给用户，需沿局部变量初值 `StringBuilder` 追踪 append/变量引用。

---

## 解决方案概览

新增一个独立的 **_psource 包**（`src/main/kotlin/cn/song/mybatisplus/visualizer/psource/`），不含任何 IDE 平台依赖（纯 PSI 结构遍历 + 纯函数渲染），单测可在轻量环境运行；gutter/action 复用既有 `SqlPreviewService` 弹窗链路。

| 改动 | 文件 | 说明 |
|------|------|------|
| ① 定位器 | `psource/JdbcTemplateSqlLocator.kt`（新） | 识别 JdbcTemplate 调用实参 → SQL 拼接信息 |
| ② 字符串提取 | `psource/SqlStringConcatenator.kt`（新） | 字面量 `+` 拼接 / StringBuilder 分片聚合 → 单一 SQL 文本 |
| ③ 命名参数解析 | `psource/NamedParamParser.kt`（新） | `:NAME` 正则收集去重 → 参数模型 |
| ④ 调用类识别 | `psource/JdbcTemplateMethodDetector.kt`（新） | 方法名/参数特征判定是 JdbcTemplate（拦截误标） |
| ⑤ 预览服务 | `psource/JdbcTemplateSqlPreviewService.kt`（新） | 组装 `PreviewResult`（复用 `LogSqlRestorer.PLAIN_TYPES` 引号规则） |
| ⑥ gutter 图标 | `ui/gutter/JdbcTemplateSqlGutterLineMarkerProvider.kt`（新） | 方法调用的 SQL 实参行显示水印图标（O(1) 语法判定） |
| ⑦ 预览入口 | `ui/SqlPreviewService.kt` | `preview` 开头新增 JDBC 分支 |
| ⑧ 快捷键/右键入口 | `actions/ConvertToSqlAction.kt` | 光标/JPA 回落前新增 JDBC 分支 |
| ⑨ 命名参数渲染 | `sql/ParameterReplacer.kt` + `model/PreviewResult.kt` | 按名回填（`namedParamValues`/`namedParamHints`）+ 可执行 SQL 生成 |
| ⑩ 设置开关 | `config/PluginSettings.kt` + `PluginSettingsPanel.kt` | `jdbcSqlPreviewEnabled`（默认开） |
| ⑪ i18n | 2 个 properties | 水印 tooltip / 设置项 |
| ⑫ 面板匹配 | `ui/SqlPreviewPanel.kt` | 参数输入框收集值走 `:name` 回填路径 |

**不做（明确边界）**：
- 不做 NamedParameterJdbcTemplate 日志格式的新解析（M47 已覆盖 `?` 形态；`:name` 走日志时 Spring 仍输出 `?`）
- 不做 `BeanPropertySqlParameterSource` 属性值回溯（`:CUR_USER` 在运行期取值，预览仅回填输入）
- 不解析 `qyMessageLogMapper.selectSendMsgByTaskId(...)` 等**方法返回值拼进 SQL**（动态 SQL 由 Java 生成，无法静态预览——与 Provider 注解降级语义一致，见错误处理）

---

## 数据流

```
编辑器源码
    ↓ JdbcTemplateSqlGutterLineMarkerProvider.getLineMarkerInfo（O(1) 方法名 + 字符串实参判定）
    ↓ 点击 / Alt+Shift+Q / 右键
    ↓ SqlPreviewService.previewAsync（后台 ReadAction）──► SqlPreviewService.preview
    ↓ JdbcTemplateSqlLocator.locate（实参 PSI → 拼接片段信息）
    ↓ SqlStringConcatenator.concat（字面量拼接 / StringBuilder 聚合 → 单一 SQL 文本）
    ↓ NamedParamParser.collect（:NAME → List<NamedParam>）
    │  （无命名参数 → 走现有 `?` 顺序渲染，NamedParameterJdbcTemplate 字符串重载场景）
    ↓ JdbcTemplateSqlPreviewService.buildResult（组装 PreviewResult）
    │    sql/compactSql = 格式化 SQL（`?` 与 `:name` 共存）
    │    unresolvedParams / namedParamSources（参数行来源）
    │    namedParamHints（引号提示：CUR_USER → 引号，数值 → 裸）
    ↓ SqlPreviewDialog / SqlPreviewPanel
        参数行输入值 → collectParamValues
        → ParameterReplacer.buildExecutableSql（`:name` 按名整体替换 + `?` 顺序替换）
```

---

## 改动清单

### 改动 ①：`psource/JdbcTemplateSqlLocator.kt`（新）

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/psource/JdbcTemplateSqlLocator.kt`

定位"JdbcTemplate / NamedParameterJdbcTemplate 的 SQL 字符串实参"，返回：

```kotlin
/** JDBC 模板 SQL 调用信息（定位器产物）。 */
data class JdbcTemplateSqlCall(
    val methodName: String,          // queryForList / update / queryForObject ...
    val sqlExpr: PsiExpression?,     // 字符串实参表达式（可能 null = StringBuilder 变量）
    val sqlString: String,           // 拼接聚合后的 SQL 文本（可能含 :name / ?）
    val builderVariable: String?,    // StringBuilder 变量名（sqlExpr 为变量引用时非 null）
    val isLiteral: Boolean,          // true=完全静态可聚合；false=含运行时值（提示降级）
    val parameterExpression: PsiExpression?, // 参数实参（Map/对象，未用，保留扩展点）
)

/** 入口：锚点（PsiMethodCallExpression 或 PsiIdentifier）→ JdbcTemplateSqlCall。 */
fun locate(anchor: PsiElement): JdbcTemplateSqlCall?
```

识别规则（对齐用户两段示例）：

1. **方法名集合**（大小写不敏感，O(1) 文本匹配）：
   `query*`（query/queryForObject/queryForList/queryForMap/queryForRowSet/queryForLong/queryForInt）、`update`/`batchUpdate`、`execute`。命名为 `:name` 占位即"命名参数"（`NamedParameterJdbcTemplate` 常见）或 `?`（普通 JdbcTemplate 字符串重载）。
   **排除**（避免误标非 SQL 语义）：`queryForObject` 第 2/3 参为 `Class`；`execute` 的 `CallableStatementCreator` 回调。
2. **实参结构判定**：
   - `queryForList(String sql, Map<String,?> paramMap)` → 第 1 实参为字符串/变量
   - `update(String sql, Map/args...)` → 第 1 实参
   - `query(String sql, RowMapper, ...)` → 第 1 实参
   - 实参为 `PsiLiteralExpression` / `PsiBinaryExpression`（`+` 拼接）/ `PsiPolyadicExpression` → 直接静态聚合
   - 实参为 `PsiReferenceExpression`：
     - 解析到局部变量且 initializer 为字符串拼接 → 按改动 ② 聚合该变量
     - 解析到 `StringBuilder` 变量（initializer 为 `new StringBuilder(...)`）→ 改动 ② 的 StringBuilder 聚合
     - 解析到字段/方法参数（如 `String querySelect = ...` 且 querySelect 在本方法内）→ 同样按 initializer 聚合（示例二）
   - 实参为字符串 `+` 拼接（示例一 `querySelect + changeTable + queryWhere`，但其中 `changeTable` 是**局部 String 变量**）→ 递归：字面量直接收，变量沿 initializer 收
3. **聚合失败/含非字符串运行时值**（方法调用返回值、字段依赖外部等）→ `isLiteral=false`，预览降级：给出已聚合的静态片段 + 提示"含运行时拼片段（如 qml.msgid），完整 SQL 运行期才确定"（对齐 Provider 注解语义）。

---

### 改动 ②：`psource/SqlStringConcatenator.kt`（新）

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/psource/SqlStringConcatenator.kt`

聚合策略（纯 PSI 遍历，`PsiElement.children` + `instanceof`，无 resolve 依赖）：

```kotlin
/** 静态聚合表达式为 SQL 文本；不可静态求值的部分以占位符标记拼接。 */
fun concat(expr: PsiExpression): ConcatenationResult
```

两路实现：

**路径 A — 字面量 `+` 拼接**：`PsiPolyadicExpression`（含链式 `+`）递归收集 `PsiLiteralExpression` 的字符串值并 `joinToString("")`；穿插的 `PsiReferenceExpression` 解析到局部变量后沿其 initializer 递归（示例一非字面量片段）：`PsiLiteralExpression.value as? String`（失败退回 `text` 原文）。

**路径 B — StringBuilder 聚合**（示例二）：
- `StringBuilder sql = new StringBuilder();`（`new StringBuilder("...")` 可选带初值段）
- 收集**同一方法体内**对该变量的 `sql.append(expr)` 调用（`PsiTreeUtil.collectElementsOfType(method.body, PsiMethodCallExpression)` 过滤 `methodExpression.referenceName == "append"` 且 qualifier 解析到该变量）
- 按 `append` 调用在源码中的 offset 排序，递归聚合每个实参（实参为 String 变量 → 沿 initializer；为字面量 → 直接收）
- 可选支持 `new StringBuilder(SOME_CONST)` 初值

**去重/防抖**：
- 同一 `PsiMethodCallExpression` 只产出一个候选（gutter 在方法名 identifier 上有唯一锚点）
- 聚合时的变量沿 `initializer` 走，**不跨方法体**、**不受 `MAX_TRACE_DEPTH` 以外的深度限制**（示例二变量链浅）
- 相同 SQL 文本的候选去重（面板"多选"连接）

返回结构（`data class ConcatenationResult(val sql: String, val complete: Boolean)`），`complete=false` 时含运行时片段占位标记。

---

### 改动 ③：`psource/NamedParamParser.kt`（新）

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/psource/NamedParamParser.kt`（纯函数）

```kotlin
/** 命名参数（NamedParameterJdbcTemplate `:name` 风格；含 `:name` 与 `::name` 双冒号守卫）。 */
data class NamedParam(
    val name: String,       // 去冒号后（CUR_USER / flowKey / msgId）
    val quotedLike: Boolean, // 引号提示（近似：常见语义常量名）
)

/** 收集 SQL 中全部命名参数（去重，保序），`:name` 不匹配时跳过（如 JSON 路径 `:x`）。 */
fun collectNamedParams(sql: String): List<NamedParam>
```

关键正则（对齐 NamedParameterJdbcTemplate 的参数扫描语义）：

```kotlin
private val NAMED_PARAM_PATTERN = Regex("""(?<![::])[:]([A-Z_a-z][A-Z_a-z0-9]*)""")
```

- `:CUR_USER` → `CUR_USER`；`::msgId` → 跳过（双冒号）
- `:1`/`:2` 位置命名参数 → `[0-9]+` 分支：名 `1`/`2`，值按 `?` 顺序（Spring 的 `:1` 语义）
- 去重保序（同名参数在 SQL 中多次出现，面板参数行只一条；替换时全部命中）

**引号提示（`quotedLike`）**：近似判定——匹配字符串值形态的命名参数（`CUR_USER`/`USER_ID`/`msgId`/`flowKey` 等语义常量名）默认引号；纯数字/布尔名（`pageNumber`/`pageSize` 等）裸渲染。与 `LogSqlRestorer.PLAIN_TYPES` 的"数值直出"语义对齐（见改动 ⑤）。

**复用 `LogSqlRestorer.PLAIN_TYPES`**：`quotedLike=false` 的判定完全复用该集合（含 `Long`/`Integer` 等 Java 类型名），避免重复维护引号规则。

---

### 改动 ④：`psource/JdbcTemplateMethodDetector.kt`（新）

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/psource/JdbcTemplateMethodDetector.kt`（纯函数）

判定"这个方法调用是 JdbcTemplate / NamedParameterJdbcTemplate 的候选"（gutter O(1) 用 + action 守卫用）：

```kotlin
/** JDBC 模板方法名集合（O(1) 判定，零 resolve）。 */
val JDBC_TEMPLATE_METHODS = setOf("query", "queryForObject", "queryForList", "queryForMap", ...)

fun isJdbcTemplateMethodReference(referenceName: String): Boolean
```

注意与既有 `CustomDaoHqlLocator`（`findByQuery`/`executeHql`）、HQL `createQuery` 已各自有 gutter 的**互斥**：`query*` 是 Spring 方法名，与 `createQuery`（Hibernate）/ `findByQuery`（自定义 DAO）天然区分；不重复 `createQuery` 系列。

**排除规则**（对齐 Spring 实际重载）：
- `queryForObject(String, Class)` / `queryForObject(String, Class, Object...)` → 第 2 实参是 `PsiClassObjectAccessExpression` 时仍预览 SQL（SQL 在第 1 参），不退
- `execute(CallableStatementCreator)` / `execute(ConnectionCallback)` → **实参非字符串** → 不预览（`update(String)` 等字符串实参才预览）

---

### 改动 ⑤：`psource/JdbcTemplateSqlPreviewService.kt`（新）

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/psource/JdbcTemplateSqlPreviewService.kt`

```kotlin
/** JDBC 模板 SQL 预览服务（正向入口）：SqlStringConcatenator + NamedParamParser → PreviewResult。 */
object JdbcTemplateSqlPreviewService {
    fun preview(call: PsiMethodCallExpression, project: Project?): PreviewResult?
    fun preview(anchor: PsiElement): PreviewResult?   // Q-trigger（光标/PsiIdentifier）
}
```

组装规则（对齐 `AnnotationSqlPreviewService.buildResult` 的输出语义）：

```kotlin
val sqlText = concat(sqlExpr) ?: return null
val named = NamedParamParser.collectNamedParams(sqlText)

// SQL/compact：格式化（SqlFormatter.format）后渲染；末尾加 ";"（与注解预览一致）
// 命名参数 → unresolvedParams 之外的独立来源（面板参数行）：
val namedSources = named.map { it.name }
// 引号提示（对齐 LogSqlRestorer.PLAIN_TYPES）：
val namedHints = named.map { NamedParamParser.quoteHint(it) }   // ParamHint(quote=true/false)

PreviewResult(
    sql = formattedSql,
    compactSql = formattedCompactSql,
    unresolvedParams = ?占位符来源（按序） + namedSources,   // 面板输入框来源集合
    paramTable = ParameterReplacer.buildParamTable(...),
    paramHints = ?序 hints + namedHints,
    tableName = hintTableName(sqlText),   // 复用 SqlTextAnalysis/表名提取启发式（可省略）
    initialParamValues = emptyMap(),      // 命名参数无值预填（与日志识别不同）
    wrapperType = null,
    warning = 若 concat.complete==false → 降级提示,
)
```

**参数行交互关键**：面板参数输入框 source 就用命名参数名（`CUR_USER`/`flowKey`/`msgId`），用户输入值后回填到同名 `:CUR_USER` 全部出现位置（改动 ⑨ 提供按名渲染）。`unresolvedParams` 里同时保留命名参数名（见改动 ⑨ 的兼容策略：渲染函数按名匹配优先，其余按 `?` 序）。

---

### 改动 ⑥：`ui/gutter/JdbcTemplateSqlGutterLineMarkerProvider.kt`（新）

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/JdbcTemplateSqlGutterLineMarkerProvider.kt`

模式：**严格复刻 `AnnotationSqlGutterLineMarkerProvider`**（74 行，锚点 O(1) + click 走 `previewAsync`）。

```kotlin
class JdbcTemplateSqlGutterLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val token = element as? PsiIdentifier ?: return null
        // 方法名 identifier 锚点：element.text ∈ JDBC_TEMPLATE_METHODS 且父链构成方法调用
        val parent = element.parent as? PsiReferenceExpression ?: return null
        val grandParent = parent.parent as? PsiMethodCallExpression ?: return null
        if (grandParent.methodExpression.referenceName != element.text) return null
        if (element.text !in JdbcTemplateMethodDetector.JDBC_TEMPLATE_METHODS) return null
        if (!gutterEnabled()) return null
        // 快速参数检查：第 1 实参存在即可（用户点击时才做字符串识别）
        if (grandParent.argumentList.expressions.isEmpty()) return null
        return LineMarkerInfo(element, element.textRange, SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.jdbcTemplate.tooltip") },
            JdbcTemplateGutterNavigationHandler, GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.jdbcTemplate.accessible.name") })
    }
}
```

**性能红线**：`getLineMarkerInfo` 只做 O(1) 语法判定（方法名 ∈ 集合 + 第 1 实参存在），点击才做聚合/识别（`previewAsync` 后台）。不 resolve、不调用 `JdbcTemplateSqlLocator`（定位在 click/action 里做）。

**点击处理**：`SqlPreviewService.previewAsync(project, { JdbcTemplateSqlPreviewService.preview(methodCall) }) { result -> showResult(project, result) }`。

注册进 plugin.xml：

```xml
<!-- JDBC 模板 SQL 字符串预览：queryForList/update 等方法行显示预览图标（O(1) 方法名判定） -->
<codeInsight.lineMarkerProvider language="JAVA"
                                implementationClass="cn.song.mybatisplus.visualizer.ui.gutter.JdbcTemplateSqlGutterLineMarkerProvider"/>
```

---

### 改动 ⑦：`ui/SqlPreviewService.kt` — preview 入口新增 JDBC 分支

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.kt`（`preview(anchor)` 方法，约 162 行处）

在 Wrapper 转换之前加入 JDBC 字符串预览分支（与 `previewCustomDaoHql` 并列）：

```kotlin
fun preview(anchor: PsiElement): PreviewResult? {
    // JDBC 模板 SQL 字符串预览（正向）：调用实参是字符串/StringBuilder → 预览
    JdbcTemplateSqlLocator.locate(anchor)?.let { call ->
        return JdbcTemplateSqlPreviewService.preview(call, project)
    }
    // <- 原 Wrapper 转换路径不变
```

**注意顺序**：`JdbcTemplateSqlLocator.locate` 对 `PsiMethodCallExpression` / `PsiIdentifier` 的判定严格**只命中 JDBC 模板方法名集合**，与 Wrapper 链（`.eq(...)`/`.lambdaQuery()`）、HQL（`createQuery`）不冲突；放最前无碍既有路径（对非 JDBC 锚点立即返回 null，cost 是几个 O(1) 判断）。

---

### 改动 ⑧：`actions/ConvertToSqlAction.kt` — 光标分支新增 JDBC

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt`

在 `actionPerformed` 的"光标处"分支（分支 B，约 122 行）里，`service.preview(element)` 前已有 `service.preview` 覆盖（改动 ⑦ 加入 JDBC 分支后自动生效）；只需把 `update()` 的可用文件判定补上——JDBC 不限文件类型（Java 即够，无需新判定）。**无需改动**（`service.preview` 已覆盖光标场景）。但需确认 `SqlPreviewService.preview` 的 cache 路径不污染 JDBC（JDBC 分支直接 `JdbcTemplateSqlPreviewService.preview` 返回，不写 Wrapper 缓存——缓存键是 Wrapper 链锚点，JDBC 实参不在其内，无冲突）。

**选择区分支**不扩展（选择区内多个 JDBC 调用 → 多候选弹窗可后续迭代；首版光标/水印覆盖主流场景）。

---

### 改动 ⑨：命名参数渲染 — `sql/ParameterReplacer.kt` + `model/PreviewResult.kt`

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/sql/ParameterReplacer.kt`

新增按名回填函数（**不破坏既有 `?` 顺序语义**，两路并行）：

```kotlin
/**
 * 按命名参数名把 SQL 中 `:name` 替换为用户输入值（NamedParameterJdbcTemplate 语义）。
 * `:name` 未出现在参数值表 → 原样保留；`?` 不受影响（仍走 [buildExecutableSql]）。
 * 值含单引号 → 转义 `''`；引号规则与 [ParamHint.quote] 一致。
 */
fun replaceNamedParams(sql: String, paramValues: Map<String, String>, hints: Map<String, ParamHint>): String {
    if (paramValues.isEmpty()) return sql
    return NAMED_PARAM_REGEX.replace(sql) { m ->
        val name = m.groupValues[1]
        val value = paramValues[name]
        if (value.isNullOrBlank()) m.value
        else renderNamedValue(value, hints[name])
    }
}
```

配套 `model/PreviewResult.kt` 扩展（保持向后兼容，默认值不破坏既有构造）：

```kotlin
/** JDBC 命名参数（NamedParameterJdbcTemplate `:name`）：来源名 → 引号提示。
 *  非空时面板按名回填；空表示纯 `?` 顺序回填。 */
val namedParamHints: Map<String, ParamHint> = emptyMap(),
```

**面板集成**（`ui/SqlPreviewPanel.kt`，改动 ⑫）：
- `differences()`/`showSql` 计算可执行 SQL 时：`val sql2 = if (result.namedParamHints.isNotEmpty()) ParameterReplacer.replaceNamedParams(base, paramValues, result.namedParamHints) else base;` 然后对其中残留 `?` 再走 `backfillParamsBySource`（`?` 与 `:name` 共存时先按名、后按序）。
- 参数行来源去重：`unresolvedParams` 里同时含命名参数名与 `?` 序来源 → 面板渲染参数行的顺序按 `paramValues` 键顺序，`distinct()` 防重复输入框。

**命名参数在 `unresolvedParams` 的兼容策略**：为让参数行**显示命名参数并接受输入**（交互），命名参数名**加入** `unresolvedParams`（这就是用户看到的参数来源）；渲染函数靠 `namedParamHints` 非空区分"这些来源是命名参数，按名替换"。

---

### 改动 ⑩：设置开关

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt`

```kotlin
// ---------- JDBC 模板 SQL 字符串预览 ----------
/** 启用 JdbcTemplate / NamedParameterJdbcTemplate SQL 字符串预览（默认开）。 */
var jdbcSqlPreviewEnabled: Boolean = true,
```

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt`：SQL 预览分组（或 JDBC 日志分组旁）加启用复选框，方法与 Hibernate/Jdbc 日志开关一致（`JBCheckBox` + `isModified/apply/reset`）。若无现成分组，挂在「SQL 日志」分组下（JDBC 日志开关相邻）。

---

### 改动 ⑪：i18n

**文件**：`src/main/resources/messages/MpSqlVisualizerBundle.properties`

```properties
gutter.jdbcTemplate.tooltip=Preview JDBC template SQL string
gutter.jdbcTemplate.accessible.name=Preview JDBC template SQL
settings.preview.jdbc=Enable JdbcTemplate SQL string preview
settings.preview.jdbc.tip=Show gutter icon and enable Alt+Shift+Q for queryForList/update etc. string SQL arguments.
```

**文件**：`.../MpSqlVisualizerBundle_zh.properties`（同上中文）。

（对照现有 `gutter.annotation.tooltip` 等键的命名风格。）

---

### 改动 ⑫：`ui/SqlPreviewPanel.kt` — 命名参数回填接线

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanel.kt`

- 参数行输入框：`source` 即命名参数名，与 `?` 来源混列（现有 `buildParamRow` 按 `unresolvedParams` 建行已覆盖；**无需新 UI**）
- `showSql`/`backfillParamsBySource`：先 `ParameterReplacer.replaceNamedParams`，再对残留 `?` 走既有 `backfillParamsBySource`（顺序修复共享 SQL 中 `?` 与 `:name` 共存的回退）
- `serializeParamsToJson`/`deserializeParamsToJson`（历史召回）天然按 source 键值，命名参数名直接进 JSON，无侵入

---

## 参数回填规则

### 命名参数渲染

| `:name` | 值输入 | `quotedLike` | 渲染 |
|---------|--------|--------------|------|
| `:CUR_USER` | `admin` | true（引号） | `'admin'` |
| `:CUR_USER` | `(空)` | — | `:CUR_USER` 保留（与 `?` 空值保留语义一致） |
| `:flowKey` | `CoreApplyDutiesEntrep` | true | `'CoreApplyDutiesEntrep'` |
| `:pageNumber` | `1` | false（数值名启发式） | `1`（裸） |
| `:msgId` | `abc` | true | `'abc'` |

与 `LogSqlRestorer.PLAIN_TYPES` 一致：数值/布尔类型名 → 裸渲染，其余 → 引号。

### `?` 与 `:name` 共存

- `:name` 按名替换（先）
- 剩余 `?` 按 `unresolvedParams` 中非命名参数部分依序替换（后）
- 命名参数同名复用：`:CUR_USER` 出现 3 次，一个参数行，全部替换为该值

### 边界

- 字符串值含单引号 → `''` 转义（与 `renderValue` 一致）
- 命名参数数量不匹配参数行 → 未输入值保留 `:name`
- `::name`（双冒号）→ 不识别（NamedParameterJdbcTemplate 不支持）
- PostgreSQL 类型转换语法 `1::integer` → 双冒号守卫不误标

---

## 降级与错误处理

| 场景 | 行为 |
|------|------|
| 实参非字符串/StringBuilder（`query(connectionCallback)` 等） | 不预览（gutter 不显示，action 返回 null） |
| 拼接含运行时值（方法返回值、字段外部依赖、`qml.msgid`） | 预览静态片段 + `warning`：`含运行时拼接片段：完整 SQL 在运行期才确定` |
| 方法名命中但实参是 `BeanPropertySqlParameterSource` / `MapSqlParameterSource` | 仅解析 SQL 字符串，参数值不回填（预览 `:name` 输入框由用户填）——对齐 NamedParameterJdbcTemplate 语义 |
| `StringBuilder` 无 append 链 | 只有 `new StringBuilder("...")` 初值 → 返回初值 |
| 空字符串 | 返回 null（面板未找到提示） |

---

## 测试计划

### 单测（轻量，纯 JUnit，无 IDE 依赖）

| 文件 | 用例 |
|------|------|
| `SqlStringConcatenatorTest.kt` | 字面量 `+` 拼接；字面量+局部 String 变量混合（示例一）；StringBuilder 分段 append（示例二）；`new StringBuilder("初值")`+append；无 append；运行时值降级 |
| `NamedParamParserTest.kt` | `:CUR_USER` 收集去重；`::` 双冒号跳过；数字命名参数 `:1`；无参数；`1::integer` 类型转换不误标；`joinToString("")` 空参数 |
| `JdbcTemplateSqlPreviewServiceTest.kt` | 组装 PreviewResult：`?` 序 + `:name` 混合来源；`namedParamHints` 引号提示；warning 降级 |
| `ParameterReplacerNamedTest.kt` | `:name` 按名替换；同名复用；空值保留；单引号转义；数值名裸渲染；`?`+`:name` 共存 |
| `JdbcTemplateSqlLocatorTest.kt` | 方法名命中/排除；实参结构判定；变量追踪 |

模式参照 `src/test/kotlin/.../log/JdbcTemplateLogParserTest.kt`（纯解析器测试风格，无 fixture）。

### IDE 集成验证（沙盒）

1. 打开含示例一/示例二代码的文件 → 三处 `queryForList`/`update` 行各出现一个水印图标
2. 点击水印 → 弹窗展示完整 SQL（UNION ALL / CASE WHEN 子查询原样格式化），参数行出现 `CUR_USER` 等命名参数
3. 输入 `CUR_USER` 值 → 可执行 SQL 中 `:CUR_USER` 三处全部替换为 `'值'`
4. Alt+Shift+Q 光标在 SQL 字符串内触发 → 同链路预览
5. 关闭 `settings.preview.jdbc` 开关 → 图标消失、快捷键不可用
6. 与既有 Wrapper 水印、`@Select` 注解水印、HQL `createQuery` 水印共存验证（无重复图标、无串扰）

### 回归

- 既有 MyBatis/Hibernate/JPA/HQL/CriteriaQuery 水印全量回归（`SqlPreviewServiceTest` 相关套件）
- `ConvertToSqlAction` 既有选择区/光标分支行为不变

---

## 工作量估算

| 改动 | 行数 | 耗时 |
|------|------|------|
| ① JdbcTemplateSqlLocator | ~140 | 45 min |
| ② SqlStringConcatenator | ~120 | 40 min |
| ③ NamedParamParser | ~70 | 25 min |
| ④ JdbcTemplateMethodDetector | ~40 | 10 min |
| ⑤ JdbcTemplateSqlPreviewService | ~110 | 35 min |
| ⑥ gutter provider | ~75 | 20 min |
| ⑦ SqlPreviewService 分支 | ~10 | 5 min |
| ⑧ ConvertToSqlAction | ~5 | 5 min |
| ⑨ ParameterReplacer + PreviewResult | ~60 | 25 min |
| ⑩ 设置开关 | ~25 | 10 min |
| ⑪ i18n | ~8 | 5 min |
| ⑫ SqlPreviewPanel 接线 | ~30 | 15 min |
| 单测 5 文件 | ~350 | 90 min |
| **合计** | **~870 行** | **~5.5 工时** |

---

## 与既有能力的边界与协同

| 能力 | 关系 |
|------|------|
| M47 `JdbcTemplateLogParser`（日志捕获） | **互补**：日志管运行期捕获，本需求管源码正向预览；共用 `LogSqlRestorer.PLAIN_TYPES` 引号规则 |
| 示例一（字面量拼接）vs 示例二（StringBuilder） | 两路均在改动 ② 覆盖；示例二依赖局部变量追踪（querySelect/queryWhere 变量 + append 调用） |
| 命名参数 `:CUR_USER` | 新增按名回填（改动 ⑨）；HQL `:name` 原本走 HqlQueryLocator 路径，不冲突 |
| `?` 占位符（普通 JdbcTemplate `query(String, RowMapper, Object...)`） | 复用既有 `?` 顺序渲染，无需新逻辑 |

---

## 风险与开放问题

1. **示例二 `querySelect` 变量中 `WP.PROC_NAME AS proc_name` 换行处的无空格拼接**：Java `"..." + "..."` 是精确连接、不插空格，`SELECT` 与 `FROM` 之间无空格时拼出的 SQL 可能非法。**处理**：聚合后统一 `SqlFormatter` 格式化（关键字/子句分行），避免拼接空格问题。
2. **命名参数引号提示是启发式**：`:CUR_USER` 无法从 SQL 文本判断 DB 列类型；近似按名称/值语义。**兜底**：与 `?` 相同的面板启发式（`NUMBER_REGEX` 数值裸渲染、否则引号）已存在，`quotedLike` 仅做初筛。
3. **方法返回对象拼进 SQL（不可静态求值）**：如实参含 `qyMessageLog.getMsgid()` 等调用，静态无法取值。首版降级提示（warning），不阻塞预览静态片段。
4. **SQL 文件与纯 SQL 场景**：本需求仅 Java 方法调用内字符串；不覆盖 SQL 文件（那种直接有 IDE SQL 高亮）。
5. **与 `SqlPreviewService.preview` 的缓存**：JDBC 分支不写 Wrapper 缓存、直接构建，避免"同一 offset 缓存污染"（JDBC 不参与 Wrapper 链缓存键）。

---

## 交付清单

- [ ] `psi/psource`（新包 `cn.song.mybatisplus.visualizer.psource`）5 个新文件
- [ ] `ui/gutter/JdbcTemplateSqlGutterLineMarkerProvider.kt` + plugin.xml 注册
- [ ] `ui/SqlPreviewService.kt` / `actions/ConvertToSqlAction.kt` 分支
- [ ] `sql/ParameterReplacer.kt` + `model/PreviewResult.kt` 命名参数渲染
- [ ] `config/PluginSettings.kt` / `PluginSettingsPanel.kt` 开关 + i18n
- [ ] `ui/SqlPreviewPanel.kt` 命名参数回填接线
- [ ] 5 个单测文件全绿 + 沙盒真机验证
- [ ] CHANGELOG / 文档三处同步（项目约定）