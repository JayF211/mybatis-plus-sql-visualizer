# maintainPage XML SQL 预览开发计划

> 状态：✅ 已完成（commit 0556f9e）
> 立项依据：用户真实项目使用 vTradex Forest 低代码框架，需要在插件中预览 `<maintainPage>` 的 `<datasource>` SQL。
> 需求来源：用户提供的真实业务 XML 配置（`TfLangLib` 维护页面）。
> **确认**：`<datasource>` 内容为 HQL（非原生 SQL），无动态参数。

## 1. 目标与范围

在插件中支持 vTradex Forest 框架的 `<pages>` XML 格式 SQL 预览，使开发者能在 IDEA 中直接查看 `<datasource>` CDATA 中的 SQL，并支持参数替换、列信息展示、实体类推断补全。

**已确认决策**：

- **独立解析器**：新建 `PagesXmlParser`，不走 MyBatis `XmlMapperParser` 路径（新格式无动态 SQL 标签，不需要 OGNL/SqlNode 树）。
- **纯 SQL 路径**：`<datasource>` CDATA 中的 SQL 是静态模板，直接展示（格式化 + 参数替换），不经过 `XmlPreviewModel` 动态渲染。
- **复用统一展示**：`PagesXmlPreviewService.preview()` 返回标准 `PreviewResult`，gutter 和 action 共用 `SqlPreviewService.showResult()` 弹窗。
- **列信息 tab**：预览弹窗增加「列信息」tab，展示 `<columns>` 元数据（id + title + dataType）。
- **实体类推断**：`entityClass` 属性 → PSI 解析 → 表名/列名补全上下文（复用 `EntityResolver` 四级链路）。
- **动态参数**：⏳ 待确认（见第 6 节），MVP 仅支持 `?` 占位符提取。

**不含**（文档化边界）：

- 动态 SQL 标签解析（`<if>` / `<foreach>` 等）——新格式无此语法，CDATA 内是纯 SQL。
- `${}` / `#{}` 参数语法——框架参数绑定方式未确认，待用户提供示例后实施。
- `WHERE 1=1` 后动态条件 toggle——框架运行时行为，静态预览无法推断条件分支。
- 跨文件 include / 命名空间展开——新格式无此机制。
- SQL 执行（Database 控制台）——纯静态 SQL 预览，执行能力后续按需追加。

## 2. 架构与数据流

```
编辑器锚点（XmlTag: <maintainPage>）
  → PagesXmlGutterLineMarkerProvider          ui/gutter/PagesXmlGutterLineMarkerProvider.kt
      （O(1) 判定：tag.name=="maintainPage" && parentTag.name=="pages"）
  → SqlPreviewService.previewAsync()          ui/SqlPreviewService.kt
      （后台线程 ReadAction 执行转换）
  → PagesXmlPreviewService.preview()          ui/PagesXmlPreviewService.kt
      （XML 文本 + maintainPage id → PreviewResult）
      ├─ PagesXmlParser.parse(fileText)        xml/PagesXmlParser.kt
      │     （DOM 解析 → List<PagesStatement>）
      ├─ 按 id 定位目标语句（null → 首个）
      ├─ 纯 SQL → 格式化 + 参数提取
      ├─ 列信息 → PreviewResult.columns 扩展
      └─ entityClass → EntityResolver → SqlCompletionContext（补全上下文）
  → PreviewResult → SqlPreviewDialog 弹窗
      （SQL 高亮 + 参数对照 + 列信息 tab + 复制）
```

**与现有 XML 预览的关系**：

| 维度 | MyBatis mapper（现有） | maintainPage（本次） |
|------|----------------------|---------------------|
| 解析器 | `XmlMapperParser` | `PagesXmlParser`（新建） |
| 预览服务 | `XmlPreviewService` | `PagesXmlPreviewService`（新建） |
| 预览模型 | `XmlPreviewModel`（动态 SQL 渲染） | 无（纯 SQL 直出） |
| Gutter | `XmlSqlGutterLineMarkerProvider` | `PagesXmlGutterLineMarkerProvider`（新建） |
| 展示链路 | `SqlPreviewService.showResult()` | `SqlPreviewService.showResult()`（共用） |

## 3. 现状分析

### 3.1 已完成（可复用基础）

| 模块 | 已有能力 | 复用方式 |
|------|----------|----------|
| DOM 解析 | `XmlMapperParser.parseDocument()`（安全配置 + EntityResolver） | `PagesXmlParser` 复用相同 DOM 安全配置 |
| 统一展示 | `SqlPreviewService.showResult()` → `SqlPreviewDialog` | `PagesXmlPreviewService.preview()` 返回 `PreviewResult`，直接走 showResult |
| 参数替换 | `ParameterReplacer.buildParamTable()` | `?` 占位符提取后复用 |
| SQL 格式化 | `SqlFormatter`（多行/单行） | 复用格式化逻辑 |
| 实体解析 | `EntityResolver`（@TableName / @Column 四级链路） | `entityClass` → findClass → resolve → 补全上下文 |
| 补全上下文 | `SqlCompletionContext`（表名/列名/注释） | 实体解析结果注入 PreviewResult |
| Gutter 注册 | `XmlSqlGutterLineMarkerProvider`（XML language） | 同模式新建 `PagesXmlGutterLineMarkerProvider` |
| 右键菜单 | `ConvertToSqlAction`（XML 分支） | 新增 `<pages>` 根元素检测与分发 |
| i18n | `MpSqlVisualizerBundle.properties` + `_zh.properties` | 新增 maintainPage 相关 key |
| 测试基础设施 | `BasePlatformTestCase` + myFixture | 同风格新增解析器 + 预览服务测试 |

### 3.2 未覆盖（本次需实现）

| 缺失项 | 影响 |
|--------|------|
| `<pages>` XML 解析器 | 无法提取 maintainPage 语句清单 |
| maintainPage 预览服务 | 无法生成 PreviewResult |
| maintainPage gutter 图标 | 无法快速触发预览 |
| 右键菜单 `<pages>` 支持 | 无法通过快捷键/右键预览 |
| 列信息展示 | 无法查看 columns 元数据 |
| 实体类推断接线 | 无法从 entityClass 获取补全上下文 |

## 4. 详细实施方案

### 4.1 解析器层（PagesXmlParser）

**新建文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/xml/PagesXmlParser.kt`

#### 4.1.1 数据模型

```kotlin
/** maintainPage 语句（id + 元数据 + SQL + 列信息） */
data class PagesStatement(
    val id: String,              // maintainPage 的 id 属性
    val title: String,           // title 属性
    val autoQuery: Boolean,      // autoQuery 属性
    val entityClass: String,     // entityClass 属性（全限定类名）
    val sql: String,             // datasource CDATA 内容（trim）
    val columns: List<PagesColumn>,  // columns 子元素
)

/** 列元数据 */
data class PagesColumn(
    val id: String,              // 列标识（如 t.id, a.name）
    val title: String,           // 显示标题
    val dataType: String,        // 数据类型（long/string/enum...）
    val visible: Boolean,        // 是否可见
    val sortable: Boolean,       // 是否可排序
)

/** <pages> 文件解析结果 */
data class PagesModel(
    val statements: List<PagesStatement>,
)

/** 非 <pages> 根元素 */
class PagesXmlParseException(message: String) : RuntimeException(message)
```

#### 4.1.2 解析逻辑

```kotlin
class PagesXmlParser {

    fun parse(fileText: String): PagesModel {
        val document = parseDocument(fileText)  // 复用安全 DOM 配置
        val root: Element = document.documentElement ?: throw PagesXmlParseException("XML 文档为空。")
        if (root.tagName != "pages") {
            throw PagesXmlParseException("不是 maintainPage 文件：根元素为 <${root.tagName}>（期望 <pages>）。")
        }
        val statements = childElements(root)
            .filter { it.tagName == "maintainPage" }
            .map { parseMaintainPage(it) }
        return PagesModel(statements)
    }

    private fun parseMaintainPage(element: Element): PagesStatement {
        val id = element.getAttribute("id")
        val title = element.getAttribute("title")
        val autoQuery = element.getAttribute("autoQuery") == "true"
        val entityClass = element.getAttribute("entityClass")
        // <datasource> CDATA → sql
        val datasourceTag = childElements(element).firstOrNull { it.tagName == "datasource" }
        val sql = datasourceTag?.textContent?.trim().orEmpty()
        // <columns>/<column> → 列元数据
        val columnsTag = childElements(element).firstOrNull { it.tagName == "columns" }
        val columns = columnsTag?.let { parseColumns(it) } ?: emptyList()
        return PagesStatement(id, title, autoQuery, entityClass, sql, columns)
    }

    private fun parseColumns(columnsTag: Element): List<PagesColumn> =
        childElements(columnsTag)
            .filter { it.tagName == "column" }
            .map { element ->
                PagesColumn(
                    id = element.getAttribute("id"),
                    title = element.getAttribute("title"),
                    dataType = element.getAttribute("dataType"),
                    visible = element.getAttribute("visible") == "true",
                    sortable = element.getAttribute("sortable") == "true",
                )
            }
}
```

#### 4.1.3 DOM 安全配置

复用 `XmlMapperParser.newDocumentBuilder()` 的安全配置（禁外部实体 + EntityResolver 拦截 DTD），提取为共享私有方法或复制配置。

### 4.2 预览服务层（PagesXmlPreviewService）

**新建文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/PagesXmlPreviewService.kt`

```kotlin
object PagesXmlPreviewService {

    /**
     * 预览 maintainPage SQL。
     *
     * @param fileText 完整 XML 文本
     * @param maintainPageId 目标 maintainPage 的 id（null → 首个）
     * @param project 项目（用于实体类推断）
     */
    fun preview(
        fileText: String,
        maintainPageId: String?,
        project: Project? = null,
    ): PreviewResult {
        val pagesModel = try {
            PagesXmlParser().parse(fileText)
        } catch (e: PagesXmlParseException) {
            return emptyResult(e.message ?: "XML 解析失败。")
        }

        val statement = pagesModel.statements.firstOrNull { it.id == maintainPageId }
            ?: pagesModel.statements.firstOrNull()
            ?: return emptyResult("未找到可预览的 SQL 语句（<pages> 中无 <maintainPage>）。")

        return toResult(statement, project)
    }

    /** PagesStatement → PreviewResult（纯 SQL 路径） */
    private fun toResult(statement: PagesStatement, project: Project?): PreviewResult {
        val sql = statement.sql
        // 提取 ? 占位符 → param1..N
        val params = extractPlaceholders(sql)
        // 实体类推断 → 补全上下文
        val entityContext = resolveEntityContext(statement.entityClass, project)
        return PreviewResult(
            sql = sql,
            compactSql = sql.replace(WHITESPACE_REGEX, " ").trim(),
            unresolvedParams = params,
            paramTable = ParameterReplacer.buildParamTable(params),
            wrapperType = null,
            tableName = entityContext?.tableName,
            columns = entityContext?.columns.orEmpty(),
            columnComments = entityContext?.columnComments.orEmpty(),
            tableComment = entityContext?.tableComment,
            warning = null,
            xmlModel = null,          // 无动态 SQL
            previewModel = null,      // 无动态条件
            // 🆕 列信息通过 PreviewResult 扩展字段传递（见 4.2.1）
        )
    }

    /** 实体类推断：全限定名 → PSI findClass → EntityResolver → SqlCompletionContext */
    private fun resolveEntityContext(entityClass: String?, project: Project?): SqlCompletionContext? {
        if (entityClass.isNullOrBlank() || project == null) return null
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(entityClass, GlobalSearchScope.allScope(project)) ?: return null
        val tableInfo = EntityResolver().resolve(psiClass) ?: return null
        return SqlCompletionContext(
            tableName = tableInfo.tableName,
            columns = tableInfo.allColumnNames,
            tableAlias = null,
            columnComments = tableInfo.columns.mapNotNull { col ->
                col.comment?.takeIf { it.isNotBlank() }?.let { col.columnName to it }
            }.toMap(),
            tableComment = tableInfo.comment,
        )
    }
}
```

#### 4.2.1 列信息传递扩展

`PreviewResult` 新增可选字段（向后兼容，默认 null）：

```kotlin
// PreviewResult.kt 新增字段
data class PreviewResult(
    // ... 既有字段 ...
    /** maintainPage 列元数据（仅 pages XML 预览非 null） */
    val pagesColumns: List<PagesColumn>? = null,
)
```

#### 4.2.2 参数提取

```kotlin
/** 提取 SQL 中 ? 占位符 → param1..N */
private fun extractPlaceholders(sql: String): List<String> {
    val count = sql.count { it == '?' }
    return if (count == 0) emptyList() else (1..count).map { "param$it" }
}
```

### 4.3 Gutter 图标层（PagesXmlGutterLineMarkerProvider）

**新建文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/PagesXmlGutterLineMarkerProvider.kt`

```kotlin
class PagesXmlGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val token = element as? XmlToken ?: return null
        if (token.tokenType != XmlTokenType.XML_NAME) return null
        val tag = token.parent as? XmlTag ?: return null
        // maintainPage 标签 + 直接父级是 <pages>（避免非 pages XML 误显）
        if (tag.name != "maintainPage") return null
        if (tag.parentTag?.name != "pages") return null
        if (!gutterEnabled()) return null
        return LineMarkerInfo(
            element,
            element.textRange,
            SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.pages.tooltip") },
            PagesGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.pages.accessible.name") },
        )
    }

    /** 点击处理：后台解析 + 渲染 */
    private object PagesGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project = elt.project
            val tag = elt.parent as? XmlTag ?: return
            val file = tag.containingFile as? XmlFile ?: return
            SqlPreviewService.previewAsync(project, {
                PagesXmlPreviewService.preview(file.text, tag.getAttributeValue("id"), project)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", PagesXmlGutterLineMarkerProvider::class.java)

        /** 选择区内 maintainPage 候选（供 ConvertToSqlAction 复用） */
        internal fun statementsInRange(file: XmlFile, start: Int, end: Int): List<XmlTag> =
            PsiTreeUtil.collectElementsOfType(file, XmlTag::class.java)
                .filter { it.name == "maintainPage" && it.textRange.startOffset in start until end }
                .sortedBy { it.textRange.startOffset }
    }
}
```

### 4.4 右键菜单集成（ConvertToSqlAction）

**修改文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt`

在 `actionPerformed()` 的 XML 分支中增加 `<pages>` 检测：

```java
if (file is XmlFile) {
    val rootTag = file.rootTag
    when (rootTag?.name) {
        "pages" -> { previewPages(project, file, editor); return }
        else -> { previewXml(project, file, editor); return }  // 现有 mapper 路径
    }
}
```

新增方法：

```kotlin
/** <pages> XML 预览入口：光标所在 maintainPage 优先，不在语句内时全部候选 */
private fun previewPages(project: Project, file: XmlFile, editor: Editor) {
    val element = file.findElementAt(editor.caretModel.offset)
    val tag = element?.let { PsiTreeUtil.getParentOfType(it, XmlTag::class.java) }
    var statement = tag
    while (statement != null && statement.name != "maintainPage") {
        statement = statement.parentTag
    }
    if (statement != null) {
        previewPagesStatement(project, file, statement)
        return
    }
    // 光标不在 maintainPage 内 → 全部候选
    val statements = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
        .filter { it.name == "maintainPage" }
    when (statements.size) {
        0 -> SqlPreviewService.showResult(project, null)
        1 -> previewPagesStatement(project, file, statements[0])
        else -> { /* 多候选走 SqlSelectionDialog（仿 T-M25-01 mapper 交互） */ }
    }
}

/** 预览单个 maintainPage（后台转换，EDT 渲染） */
private fun previewPagesStatement(project: Project, file: XmlFile, tag: XmlTag) {
    SqlPreviewService.previewAsync(project, {
        PagesXmlPreviewService.preview(file.text, tag.getAttributeValue("id"), project)
    }) { result ->
        SqlPreviewService.showResult(project, result)
    }
}
```

### 4.5 列信息展示（SqlPreviewPanel 扩展）

**修改文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanel.kt`

在预览弹窗中增加「列信息」tab（与 JOIN 图 tab 并列，仅 `pagesColumns` 非 null 时显示）：

```kotlin
// 在 contentTabPane 构建逻辑中增加：
if (result.pagesColumns != null) {
    val columnInfoPanel = buildColumnInfoPanel(result.pagesColumns)
    contentTabPane.addTab("列信息", columnInfoPanel)
}
```

列信息面板（简单 JBTable 或 JBLabel 表格）：

```kotlin
private fun buildColumnInfoPanel(columns: List<PagesColumn>): JComponent {
    val header = arrayOf("列标识", "标题", "类型", "可见")
    val data = columns.map {
        arrayOf(it.id, it.title, it.dataType, if (it.visible) "✅" else "❌")
    }.toTypedArray()
    return JBScrollPane(JBTable(data, header))
}
```

### 4.6 plugin.xml 注册

**修改文件**：`src/main/resources/META-INF/plugin.xml`

在 `<extensions defaultExtensionNs="com.intellij">` 中新增：

```xml
<!-- maintainPage XML 预览：<maintainPage> 行显示预览图标 -->
<codeInsight.lineMarkerProvider language="XML"
    implementationClass="cn.song.mybatisplus.visualizer.ui.gutter.PagesXmlGutterLineMarkerProvider"/>
```

### 4.7 i18n 文案

**修改文件**：`src/main/resources/messages/MpSqlVisualizerBundle.properties` + `_zh.properties`

```properties
# 英文
gutter.pages.tooltip=Preview maintainPage SQL
gutter.pages.accessible.name=Preview maintainPage SQL

# 中文
gutter.pages.tooltip=预览 maintainPage SQL
gutter.pages.accessible.name=预览 maintainPage SQL
```

## 5. 文件清单

| 操作 | 文件路径 | 说明 |
|------|----------|------|
| 新建 | `xml/PagesXmlParser.kt` | XML 解析器 + 数据模型（PagesStatement / PagesColumn） |
| 新建 | `ui/PagesXmlPreviewService.kt` | 预览服务（纯 SQL 路径 → PreviewResult） |
| 新建 | `ui/gutter/PagesXmlGutterLineMarkerProvider.kt` | Gutter 图标（maintainPage 行） |
| 修改 | `actions/ConvertToSqlAction.kt` | 右键菜单集成（`<pages>` 根元素检测 + 分发） |
| 修改 | `model/PreviewResult.kt` | 新增 `pagesColumns` 可选字段 |
| 修改 | `ui/SqlPreviewPanel.kt` | 列信息 tab 展示 |
| 修改 | `META-INF/plugin.xml` | Gutter 注册 |
| 修改 | `messages/MpSqlVisualizerBundle.properties` | 新增 i18n key |
| 修改 | `messages/MpSqlVisualizerBundle_zh.properties` | 新增 i18n key（中文） |
| 新建 | `xml/PagesXmlParserTest.kt` | 解析器测试 |
| 新建 | `ui/PagesXmlPreviewServiceTest.kt` | 预览服务测试 |

## 6. 动态参数（✅ 确认无动态参数，无需实施）

### 6.1 确认结论（2026-08-26）

- **`<datasource>` 内容为 HQL**（Hibernate Query Language），非原生 SQL
- **无动态参数** — HQL 中的参数绑定由框架运行时处理，XML 中不出现占位符
- **无需参数提取与回填** — 纯 HQL 展示即可

### 6.2 对实现的影响

| 原计划 | 实际处理 |
|--------|----------|
| `?` 占位符提取 | 保留代码但 HQL 中不会出现，不影响功能 |
| `${}` / `#{}` 参数回填 | 不实施 |
| 参数输入框 | HQL 预览时不会显示（`unresolvedParams` 为空） |
| 实体类推断 | **更重要** — HQL 使用实体类名和字段名，`entityClass` → `EntityResolver` 补全上下文可直接支持 HQL 字段补全 |

### 6.3 后续可选增强（不在 F-45 范围）

- HQL → SQL 转换预览（复用现有 `HqlConverter`）
- HQL 实体字段补全（从 `entityClass` 解析 `@Column` 映射）
- HQL 语法高亮优化（关键字与 SQL 略有差异）

## 7. 测试计划

### 7.1 解析器测试（PagesXmlParserTest）

**文件**：`src/test/kotlin/cn/song/mybatisplus/visualizer/xml/PagesXmlParserTest.kt`

| 用例 | 输入 | 预期 |
|------|------|------|
| 标准单 maintainPage | 完整 XML（1 个 maintainPage） | 解析出 1 个 PagesStatement，字段完整 |
| 多个 maintainPage | 3 个 maintainPage | 解析出 3 个，顺序一致 |
| CDATA SQL 提取 | 含换行/缩进的 SQL | sql 字段 trim 后完整 |
| columns 解析 | 4 个 column 子元素 | 4 个 PagesColumn，属性正确 |
| 空 datasource | `<datasource></datasource>` | sql = "" |
| 无 columns | 无 `<columns>` 子元素 | columns = emptyList() |
| 非 pages 根元素 | 根元素为 `<mapper>` | 抛 PagesXmlParseException |
| 非良构 XML | 缺少闭合标签 | 抛 PagesXmlParseException |
| 无 maintainPage | `<pages></pages>` | statements = emptyList() |
| autoQuery 属性 | autoQuery="true" / "false" | 布尔值正确 |

### 7.2 预览服务测试（PagesXmlPreviewServiceTest）

**文件**：`src/test/kotlin/cn/song/mybatisplus/visualizer/ui/PagesXmlPreviewServiceTest.kt`

| 用例 | 输入 | 预期 |
|------|------|------|
| 标准预览 | 完整 XML + id | PreviewResult.sql = CDATA SQL |
| 按 id 定位 | 3 个 maintainPage + 指定 id | 返回目标语句 |
| id 未命中 | 不存在的 id | 返回首个语句 |
| 无 maintainPage | 空 pages | 空结果 + warning |
| ? 占位符提取 | SQL 含 2 个 ? | unresolvedParams = ["param1", "param2"] |
| 无参数 SQL | 纯 SQL 无 ? | unresolvedParams = emptyList() |
| 列信息传递 | 4 个 column | pagesColumns 非 null，4 条 |
| 实体类推断 | entityClass = 有效 FQN | tableName / columns 非空 |
| 实体类不存在 | entityClass = 无效 FQN | 降级为 null，不影响预览 |

### 7.3 Gutter 测试（集成验证）

| 用例 | 操作 | 预期 |
|------|------|------|
| <pages> 文件打开 | 打开含 maintainPage 的 XML | maintainPage 行显示 gutter 图标 |
| 非 pages 文件 | 打开普通 XML | 不显示图标 |
| 点击 gutter | 点击图标 | 弹出预览弹窗，显示 SQL |
| 右键菜单 | 光标在 maintainPage 内 → 右键 | 显示预览选项 |
| 快捷键 | Alt+Shift+Q | 触发预览 |

## 8. 实施顺序

```
阶段 1（核心）
  ① PagesXmlParser + 数据模型
  ② PagesXmlPreviewService
  ③ PreviewResult 扩展（pagesColumns）
  ④ 解析器测试 + 预览服务测试

阶段 2（入口）
  ⑤ PagesXmlGutterLineMarkerProvider
  ⑥ ConvertToSqlAction 集成
  ⑦ plugin.xml 注册
  ⑧ i18n 文案

阶段 3（展示）
  ⑨ SqlPreviewPanel 列信息 tab
  ⑩ 集成测试 + 沙盒验证

⏳ 阶段 4（动态参数，待确认后实施）
  ⑪ 参数语法确认 → 方案选定 → 实施
```

## 9. 风险与边界

| 风险 | 缓解 |
|------|------|
| 框架参数语法未知 | MVP 仅支持 `?`，动态参数待确认后追加 |
| entityClass 无法解析 | 降级为 null，不影响 SQL 展示（仅补全上下文缺失） |
| 多个 maintainPage 同 id | 按首个匹配（id 应唯一，框架约束） |
| CDATA 含特殊字符（`<` `>` `&`） | DOM textContent 自动解码，无需额外处理 |
| 与现有 mapper gutter 冲突 | 二者注册在同一 XML language，判定条件互斥（`maintainPage` vs `select/insert/update/delete`） |
