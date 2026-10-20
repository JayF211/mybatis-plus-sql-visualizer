package cn.song.mybatisplus.visualizer.ui.annotate

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector
import cn.song.mybatisplus.visualizer.execute.SchemaValidationGate
import cn.song.mybatisplus.visualizer.sql.DataSourceUrlProbe
import cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
import cn.song.mybatisplus.visualizer.ui.inlay.PreviewInlayScope
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

/**
 * UI025：预览编辑器错列标注的**判别测试**（真实平台 fixture + 真实缓存 + 真实校验器）。
 *
 * 两条通道分别覆盖：
 *  - [PreviewUnknownColumnAnnotator]：非配置目录下的预览文件（`myFixture.configureByText`）；
 *  - [PreviewSchemaHighlighter]：真实 `.idea/sql-visualizer/` 下的预览/exec 文件——平台 daemon
 *    不分析配置目录，故真机可见的红色波浪线由编辑器级通道提供。
 *
 * 判别面：
 *  1. 错列 → 恰一条红，偏移落在真实列 token 上，文案含列名 + 建议；
 *  2. 正确列 → 零；
 *  3. 缓存冷 → 零（只读已预热缓存，不触发 DB I/O）；
 *  4. 非预览文件 / 用户自建同前缀文件 → 零；
 *  5. 无绑定目标 / 目标库不匹配 / JOIN / CTE / 限定列 / 多语句 → 零；
 *  6. 同一 issue 只标一次；编辑后旧标注清除。
 *
 * 本类依赖 Database 插件提供 SQL 语言/文件类型，故只登记在 `testDb*` 任务
 * （见 buildSrc/mpv-testing.gradle.kts 的 dbIntegrationTaskSpecs），默认 `test` 排除。
 */
class PreviewUnknownColumnAnnotatorTest : BasePlatformTestCase() {

    private val source = Any()
    private val table = "user"
    private val realColumns = setOf("id", "username")

    private val wrongSql = "UPDATE user SET usernme='x' WHERE id=71"
    private val correctSql = "UPDATE user SET username='x' WHERE id=71"

    private val LAST_USED_ID = "uuid-ui029"
    private val LAST_USED_NAME = "mp_demo@localhost"
    private val MYSQL_URL = "jdbc:mysql://localhost:3306/mp_demo"

    override fun setUp() {
        super.setUp()
        DatabaseSchemaResolver.invalidateAll()
        DatabaseSchemaResolver.clearNegativeCache()
        DatabaseSchemaResolver.dataSourceDatabaseForTest = { "mp_demo" }
        DataSourceUrlProbe.invalidateAllForTest()
        PreviewFallbackTarget.dataSourceByNameForTest = null
        DataSourceSelector.rememberedLookupForTest = null
    }

    override fun tearDown() {
        try {
            runCatching { PreviewSchemaHighlighter.uninstall(myFixture.editor) }
            DataSourceSelector.schemaTargetReaderForTest = null
            DataSourceSelector.preparedEditor = null
            DataSourceSelector.preparedConsole = null
            runCatching { DataSourceSelector.plantLastUsedMemoryForTest(project, null, null) }
            DataSourceSelector.rememberedLookupForTest = null
            PreviewFallbackTarget.dataSourceByNameForTest = null
            DataSourceUrlProbe.probeByNameForTest = null
            DataSourceUrlProbe.invalidateAllForTest()
            DatabaseSchemaResolver.dataSourceDatabaseForTest = null
            DatabaseSchemaResolver.invalidateAll()
            DatabaseSchemaResolver.clearNegativeCache()
        } finally {
            super.tearDown()
        }
    }

    /** 预热精确目标缓存（与生产 `prefetchTables` 同源：同一个 metaCache + 库名维度）。 */
    private fun warm(columns: Set<String> = realColumns) {
        DatabaseSchemaResolver.resolveFromSources(table, listOf(source)) { _, _ ->
            DatabaseSchemaResolver.TableMeta(
                comment = "users",
                columns = columns.associateWith { DatabaseSchemaResolver.ColumnMeta(null, "varchar(64)") },
                allColumnNames = columns,
            )
        }
    }

    /** 绑定当前编辑器为已备控制台的执行文档（与生产 `schemaValidationTarget` 身份判据同路）。 */
    private fun bind(database: String = "mp_demo") {
        DataSourceSelector.preparedEditor = myFixture.editor
        DataSourceSelector.preparedConsole = source
        DataSourceSelector.schemaTargetReaderForTest = { SchemaValidationGate.Target(source, database) }
    }

    /**
     * UI029 日志条目预览场景：**不做 bind**（没有已备控制台），只布置面板自身已确定的目标——
     * last-used 记忆指向在籍 MySQL/MariaDB 数据源；数据源对象与 URL 经测试缝注入（headless
     * 无 Database 插件），其余判定（方言/库名/缓存热度/作用域）全部走生产真链。
     */
    private fun configureLastUsed(
        name: String = LAST_USED_NAME,
        url: String = MYSQL_URL,
        dataSource: Any? = source,
    ) {
        DataSourceSelector.plantLastUsedMemoryForTest(project, LAST_USED_ID, name)
        DataSourceSelector.rememberedLookupForTest = { _ ->
            listOf(name) to { id: String -> name.takeIf { id == LAST_USED_ID } }
        }
        PreviewFallbackTarget.dataSourceByNameForTest = { _, requested ->
            dataSource.takeIf { requested == name }
        }
        DataSourceUrlProbe.probeByNameForTest = { _, requested -> url.takeIf { requested == name } }
    }

    /** 日志条目预览（无已备控制台）：只造预览文件，不调用 [bind]。 */
    private fun configureLogPreview(name: String, sql: String) {
        myFixture.configureByText(name, sql)
    }

    /** ExternalAnnotator 通道：非 `.idea` 的 fixture 文件（daemon 会分析）。 */
    private fun configurePreview(name: String, sql: String) {
        myFixture.configureByText(name, sql)
        bind()
    }

    /** Highlighter 通道：真实 `.idea` 路径（daemon 不分析，走编辑器级标注）。 */
    private fun configureDotIdea(name: String, sql: String) {
        val psi = myFixture.addFileToProject(".idea/sql-visualizer/preview/$name", sql)
        myFixture.configureFromExistingVirtualFile(psi.virtualFile!!)
        bind()
        PreviewSchemaHighlighter.install(myFixture.editor)
    }

    /** 只取本插件 ExternalAnnotator 标注（统一前缀 `Schema 校验` / `Schema check`）。 */
    private fun annotatorErrors(): List<HighlightInfo> {
        val prefixZh = "Schema 校验"
        val prefixEn = "Schema check"
        return myFixture.doHighlighting().filter { info ->
            info.severity == HighlightSeverity.ERROR &&
                info.description?.let { it.startsWith(prefixZh) || it.startsWith(prefixEn) } == true
        }
    }

    /** 只取本插件编辑器级标注（`HighlighterLayer.ERROR + 1` 图层）。 */
    private fun highlighterMarks(): List<RangeHighlighter> {
        PreviewSchemaHighlighter.refreshNowForTest(myFixture.editor)
        UIUtil.dispatchAllInvocationEvents()
        return myFixture.editor.markupModel.allHighlighters.filter { it.layer == HighlighterLayer.ERROR + 1 }
    }

    // ------------------------------------------------------------------
    // ExternalAnnotator 通道
    // ------------------------------------------------------------------

    fun testWrongColumnProducesExactlyOneRedOnRealToken() {
        warm()
        configurePreview(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        val errors = annotatorErrors()
        assertEquals("错列应恰有一条插件标注", 1, errors.size)
        val info = errors.single()
        val text = myFixture.editor.document.text
        assertEquals("标注必须落在真实列 token 上", "usernme", text.substring(info.startOffset, info.endOffset))
        assertEquals("偏移必须等于 AST token 起点", text.indexOf("usernme"), info.startOffset)
        assertTrue("文案应含建议列名：${info.description}", info.description!!.contains("username"))
        assertTrue("文案应含错列名：${info.description}", info.description!!.contains("usernme"))
    }

    fun testWrongColumnInMemoryPreviewNameAlsoAnnotated() {
        warm()
        configurePreview(PreviewInlayScope.MEMORY_PREVIEW_FILE_NAME, wrongSql)
        assertEquals(1, annotatorErrors().size)
    }

    fun testCorrectColumnProducesNoRed() {
        warm()
        configurePreview(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, correctSql)
        assertTrue("正确列不得标注：${annotatorErrors().map { it.description }}", annotatorErrors().isEmpty())
    }

    fun testColdCacheProducesNoRed() {
        configurePreview(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        assertTrue("缓存冷不得标注", annotatorErrors().isEmpty())
    }

    fun testPlainUserSqlFileProducesNoRed() {
        warm()
        configurePreview("demo.sql", wrongSql)
        assertTrue("用户自己的 SQL 文件不得标注", annotatorErrors().isEmpty())
    }

    fun testLoosePreviewPrefixUserFileProducesNoRed() {
        warm()
        configurePreview("mpsql-preview-custom.sql", wrongSql)
        assertTrue("宽松前缀不得命中", annotatorErrors().isEmpty())
        myFixture.configureByText("mpsql-preview-123.sql", wrongSql)
        bind()
        assertTrue("缺序号段不得命中", annotatorErrors().isEmpty())
        myFixture.configureByText("mpsql-preview-abc-1.sql", wrongSql)
        bind()
        assertTrue("owner 非数字不得命中", annotatorErrors().isEmpty())
        myFixture.configureByText("mpsql-preview-123-1-extra.sql", wrongSql)
        bind()
        assertTrue("多余段不得命中", annotatorErrors().isEmpty())
    }

    fun testUserFileNamedExecOutsidePluginPathIsNotAnnotated() {
        warm()
        configurePreview("mpsql-exec.sql", wrongSql)
        assertTrue("插件路径之外的同名文件不得标注", annotatorErrors().isEmpty())
    }

    fun testUnboundTargetProducesNoRed() {
        warm()
        myFixture.configureByText(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        assertTrue("无绑定目标不得标注", annotatorErrors().isEmpty())
    }

    /**
     * UI029 根因证据：从 SQL 日志条目打开的预览由面板自建编辑器，**没有已备控制台**
     * （preparedEditor/preparedConsole 均为 null）→ 旧目标链
     * [DataSourceSelector.schemaValidationTarget] 必然返回 null，标注整链被跳过。
     * 本用例只断言旧入口的 null 事实，不受回落分支影响。
     */
    fun testLogPreviewWithoutPreparedConsoleHasNoLegacyTarget() {
        warm()
        myFixture.configureByText(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        assertNull(
            "日志预览无已备控制台：旧目标链必须为 null",
            DataSourceSelector.schemaValidationTarget(myFixture.editor.document),
        )
    }

    // ------------------------------------------------------------------
    // UI029：日志条目预览的回落目标（last-used 记忆，无已备控制台）
    // ------------------------------------------------------------------

    fun testLogPreviewWithLastUsedMysqlGetsExactlyOneRed() {
        warm()
        configureLastUsed()
        configureLogPreview(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        val errors = annotatorErrors()
        assertEquals("日志预览应恰有一条错列标注", 1, errors.size)
        val info = errors.single()
        val text = myFixture.editor.document.text
        assertEquals("标注必须落在真实列 token 上", "usernme", text.substring(info.startOffset, info.endOffset))
        assertEquals("偏移必须等于 AST token 起点", text.indexOf("usernme"), info.startOffset)
        assertTrue("文案应含建议列名：${info.description}", info.description!!.contains("username"))
        assertTrue("文案应含错列名：${info.description}", info.description!!.contains("usernme"))
    }

    fun testLogPreviewWithLastUsedMariaDbGetsOneRed() {
        warm()
        configureLastUsed(url = "jdbc:mariadb://localhost:3306/mp_demo")
        configureLogPreview(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        assertEquals("MariaDB 同属可证方言", 1, annotatorErrors().size)
    }

    fun testLogPreviewWithoutLastUsedMemoryProducesNoRed() {
        warm()
        configureLogPreview(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        assertTrue("last-used 缺失不得标注", annotatorErrors().isEmpty())
    }

    fun testLogPreviewNonMySqlSourceProducesNoRed() {
        warm()
        configureLastUsed(url = "jdbc:postgresql://localhost:5432/mp_demo")
        configureLogPreview(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        assertTrue("方言不可证为 MySQL/MariaDB 不得标注", annotatorErrors().isEmpty())
    }

    fun testLogPreviewUnresolvableSourceUrlProducesNoRed() {
        warm()
        configureLastUsed(url = "")
        configureLogPreview(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        assertTrue("URL 取不到（方言未知）不得标注", annotatorErrors().isEmpty())
    }

    fun testLogPreviewEmptyDatabaseProducesNoRed() {
        warm()
        configureLastUsed()
        DatabaseSchemaResolver.dataSourceDatabaseForTest = { "" }
        configureLogPreview(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        assertTrue("库名为空不得标注", annotatorErrors().isEmpty())
    }

    fun testLogPreviewColdCacheProducesNoRed() {
        configureLastUsed()
        configureLogPreview(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        assertTrue("缓存冷不得标注", annotatorErrors().isEmpty())
    }

    fun testLogPreviewJoinProducesNoRed() {
        warm()
        configureLastUsed()
        configureLogPreview(
            PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE,
            "SELECT u.usernme FROM user u JOIN other o ON u.id = o.id",
        )
        assertTrue("JOIN 不得标注", annotatorErrors().isEmpty())
    }

    fun testLogPreviewMultipleStatementsProduceNoRed() {
        warm()
        configureLastUsed()
        configureLogPreview(
            PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE,
            "UPDATE user SET usernme='x' WHERE id=1; UPDATE user SET username='y' WHERE id=2",
        )
        assertTrue("多语句不得标注", annotatorErrors().isEmpty())
    }

    fun testLogPreviewFallbackDoesNotAnnotateUserSqlFile() {
        warm()
        configureLastUsed()
        configureLogPreview("demo.sql", wrongSql)
        assertTrue("回落不得越出预览作用域（用户自己的 .sql 零标注）", annotatorErrors().isEmpty())
    }

    fun testDifferentDatabaseDoesNotBorrowWarmedCache() {
        warm()
        configurePreview(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        bind(database = "other_db")
        assertTrue("目标库不匹配不得标注", annotatorErrors().isEmpty())
    }

    fun testJoinProducesNoRed() {
        warm()
        configurePreview(
            PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE,
            "SELECT u.usernme FROM user u JOIN other o ON u.id = o.id",
        )
        assertTrue("JOIN 不得标注", annotatorErrors().isEmpty())
    }

    fun testCteProducesNoRed() {
        warm()
        configurePreview(
            PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE,
            "WITH x AS (SELECT usernme FROM user) SELECT * FROM x",
        )
        assertTrue("CTE 不得标注", annotatorErrors().isEmpty())
    }

    fun testQualifiedColumnProducesNoRed() {
        warm()
        configurePreview(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, "SELECT t.usernme FROM user t")
        assertTrue("限定列不得标注", annotatorErrors().isEmpty())
    }

    fun testExplicitDatabaseProducesNoRed() {
        warm()
        configurePreview(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, "SELECT usernme FROM other_db.user")
        assertTrue("显式库名不得标注", annotatorErrors().isEmpty())
    }

    fun testMultipleStatementsProduceNoRed() {
        warm()
        configurePreview(
            PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE,
            "UPDATE user SET usernme='x' WHERE id=1; UPDATE user SET username='y' WHERE id=2",
        )
        assertTrue("多语句不得标注", annotatorErrors().isEmpty())
    }

    fun testSameIssueAnnotatedOnceEvenIfTokenRepeats() {
        warm()
        configurePreview(
            PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE,
            "UPDATE user SET usernme='x' WHERE usernme='y'",
        )
        assertEquals("同一 issue 只标一次", 1, annotatorErrors().size)
    }

    fun testEditClearsStaleAnnotation() {
        warm()
        configurePreview(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        assertEquals(1, annotatorErrors().size)
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText(correctSql)
        }
        assertTrue("改正后旧波浪线必须消失", annotatorErrors().isEmpty())
    }

    // ------------------------------------------------------------------
    // Highlighter 通道（真实 .idea 路径 = 真机可见面）
    // ------------------------------------------------------------------

    fun testDotIdeaExecFileGetsOneRedWave() {
        warm()
        val psi = myFixture.addFileToProject(".idea/sql-visualizer/exec/mpsql-exec.sql", wrongSql)
        myFixture.configureFromExistingVirtualFile(psi.virtualFile!!)
        bind()
        PreviewSchemaHighlighter.install(myFixture.editor)
        val marks = highlighterMarks()
        assertEquals("exec 控制台文件应恰有一条标注", 1, marks.size)
        val mark = marks.single()
        val text = myFixture.editor.document.text
        assertEquals("usernme", text.substring(mark.startOffset, mark.endOffset))
        assertEquals(text.indexOf("usernme"), mark.startOffset)
        val tooltip = mark.errorStripeTooltip as String
        assertTrue("悬停文案应含建议：$tooltip", tooltip.contains("username"))
        assertTrue("悬停文案应含错列：$tooltip", tooltip.contains("usernme"))
    }

    fun testDotIdeaPreviewFileGetsRedWave() {
        warm()
        configureDotIdea(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        assertEquals(1, highlighterMarks().size)
    }

    /**
     * UI029 真机可见面：日志条目预览的真实载体在 `.idea/sql-visualizer/preview/` 下，平台 daemon
     * 不分析配置目录 → 只能靠 Highlighter 通道。无已备控制台时由 last-used 回落目标供标注。
     */
    fun testDotIdeaLogPreviewWithoutConsoleGetsRedWave() {
        warm()
        configureLastUsed()
        val psi = myFixture.addFileToProject(
            ".idea/sql-visualizer/preview/${PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE}",
            wrongSql,
        )
        myFixture.configureFromExistingVirtualFile(psi.virtualFile!!)
        PreviewSchemaHighlighter.install(myFixture.editor)
        val marks = highlighterMarks()
        assertEquals("日志预览应恰有一条编辑器级标注", 1, marks.size)
        val mark = marks.single()
        val text = myFixture.editor.document.text
        assertEquals("usernme", text.substring(mark.startOffset, mark.endOffset))
        assertEquals(text.indexOf("usernme"), mark.startOffset)
        val tooltip = mark.errorStripeTooltip as String
        assertTrue("悬停文案应含建议：$tooltip", tooltip.contains("username"))
        assertTrue("悬停文案应含错列：$tooltip", tooltip.contains("usernme"))
    }

    fun testDotIdeaLogPreviewWithoutLastUsedProducesNoMark() {
        warm()
        val psi = myFixture.addFileToProject(
            ".idea/sql-visualizer/preview/${PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE}",
            wrongSql,
        )
        myFixture.configureFromExistingVirtualFile(psi.virtualFile!!)
        PreviewSchemaHighlighter.install(myFixture.editor)
        assertTrue("无面板确定目标不得标注", highlighterMarks().isEmpty())
    }

    fun testDotIdeaLogPreviewNonMySqlProducesNoMark() {
        warm()
        configureLastUsed(url = "jdbc:postgresql://localhost:5432/mp_demo")
        val psi = myFixture.addFileToProject(
            ".idea/sql-visualizer/preview/${PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE}",
            wrongSql,
        )
        myFixture.configureFromExistingVirtualFile(psi.virtualFile!!)
        PreviewSchemaHighlighter.install(myFixture.editor)
        assertTrue("方言不可证不得标注", highlighterMarks().isEmpty())
    }

    fun testHighlighterCorrectColumnProducesNoMark() {
        warm()
        configureDotIdea(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, correctSql)
        assertTrue("正确列不得标注", highlighterMarks().isEmpty())
    }

    fun testHighlighterColdCacheProducesNoMark() {
        configureDotIdea(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        assertTrue("缓存冷不得标注", highlighterMarks().isEmpty())
    }

    fun testHighlighterNonPreviewFileProducesNoMark() {
        warm()
        configureDotIdea("user-notes.sql", wrongSql)
        assertTrue("非预览文件不得标注", highlighterMarks().isEmpty())
    }

    fun testHighlighterLoosePrefixUserFileProducesNoMark() {
        warm()
        configureDotIdea("mpsql-preview-custom.sql", wrongSql)
        assertTrue("宽松前缀不得命中", highlighterMarks().isEmpty())
    }

    fun testHighlighterTargetMismatchProducesNoMark() {
        warm()
        configureDotIdea(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        bind(database = "other_db")
        assertTrue("目标库不匹配不得标注", highlighterMarks().isEmpty())
    }

    fun testHighlighterSameIssueAnnotatedOnce() {
        warm()
        configureDotIdea(
            PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE,
            "UPDATE user SET usernme='x' WHERE usernme='y'",
        )
        assertEquals("同一 issue 只标一次", 1, highlighterMarks().size)
    }

    fun testHighlighterEditClearsStaleMark() {
        warm()
        configureDotIdea(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, wrongSql)
        assertEquals(1, highlighterMarks().size)
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText(correctSql)
        }
        assertTrue("改正后旧波浪线必须消失", highlighterMarks().isEmpty())
    }

    // ------------------------------------------------------------------
    // 纯规划器：AST 偏移（含 CRLF / 多行 / 限定列）
    // ------------------------------------------------------------------

    fun testPlannerOffsetsFollowAstTokensAcrossLines() {
        val sql = "UPDATE user\r\nSET usernme = 'x'\r\nWHERE id = 71"
        val marks = PreviewUnknownColumnPlanner.plan(
            sql,
            listOf(SchemaValidator.UnknownColumn("user", "usernme", "username")),
        )
        assertEquals(1, marks.size)
        val mark = marks.single()
        assertEquals("usernme", sql.substring(mark.startOffset, mark.endOffset))
        assertEquals(sql.indexOf("usernme"), mark.startOffset)
        assertEquals("username", mark.suggestion)
    }

    fun testPlannerSkipsQualifiedTokenWhenIssueMatchesBareName() {
        val sql = "SELECT t.usernme, usernme FROM user t"
        val marks = PreviewUnknownColumnPlanner.plan(
            sql,
            listOf(SchemaValidator.UnknownColumn("user", "usernme", "username")),
        )
        assertEquals(1, marks.size)
        assertEquals("usernme", sql.substring(marks.single().startOffset, marks.single().endOffset))
        // 首个裸列在逗号之后；不得标到 t.usernme 的 usernme 片段上
        assertEquals(sql.lastIndexOf("usernme"), marks.single().startOffset)
    }
}
