package cn.song.mybatisplus.visualizer.mcp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 / T-McpSchemaScope：sqlv_schema_context 的表名匹配限定到数据源自己的 schema/库。
 *
 * **背景（根因回归护栏）**：一期按表名全局匹配（不认 schema、别名只做存在性校验），真机
 * `tables: "user"` + `dataSource: "mp_demo@localhost"` 的返回里同时出现 `mp_demo.user` 业务列与
 * `mysql.user` 系统列（`scan: tablesScanned=335, hit=true, foundColumns=60`——同名表列被并进同一个
 * Map，表注释取到 `mysql.user` 的「Users and global privileges」）。
 *
 * 单测环境无 database 插件，故经 [McpSchemaContextService.modelTablesForTest] /
 * [McpSchemaContextService.dataSourceUrlForTest] 注入替身，直测「限定目标库」整条取数链路
 * （生产路径零注入，行为与测试同源）。
 */
class McpSchemaContextScopeTest {

    private val businessColumns = listOf(
        column("id", notNull = true),
        column("username", notNull = true),
        column("email"),
    )

    private val systemColumns = listOf(
        column("Host", notNull = true),
        column("User", notNull = true),
        column("Select_priv", notNull = true),
    )

    private fun column(name: String, notNull: Boolean = false) =
        McpSchemaFormatter.ColumnLine(name, "varchar(64)", null, notNull)

    /** `mp_demo` 库里的业务表 `user`。 */
    private val mpDemoUser = McpSchemaContextService.ModelTable(
        name = "user",
        ancestorNames = listOf("mp_demo", "localhost"),
        comment = "用户表",
        columns = businessColumns,
    )

    /** 同一数据源里 `mysql` 库的同名系统表。 */
    private val mysqlUser = McpSchemaContextService.ModelTable(
        name = "user",
        ancestorNames = listOf("mysql", "localhost"),
        comment = "Users and global privileges",
        columns = systemColumns,
    )

    private val mpDemoUrl = "jdbc:mysql://localhost:3306/mp_demo?useUnicode=true"

    @After
    fun tearDown() {
        McpSchemaContextService.modelTablesForTest = null
        McpSchemaContextService.dataSourceUrlForTest = null
        McpSchemaContextService.dsNameForTest = null
        // T-PerfSweep6 T-PS6-02：跨调用索引缓存是进程级静态状态，而本类各用例用**字符串** "ds"
        // 当数据源键（生产键是 DBN 数据源对象）——字符串 equals 会让不同用例命中同一条缓存，
        // 探针计数随用例顺序漂移（`取数_同一数据源一次调用只遍历一次模型` 曾读成 0 次）。
        // 与本类已有的替身清理同处收口。
        McpSchemaContextService.clearIndexCache()
    }

    private fun inject(model: List<McpSchemaContextService.ModelTable>, url: String? = mpDemoUrl) {
        McpSchemaContextService.modelTablesForTest = { model }
        McpSchemaContextService.dataSourceUrlForTest = { url }
    }

    // ------------------------------------------------------------------
    // 索引层：同名表跨 schema 只收目标库那张
    // ------------------------------------------------------------------

    @Test
    fun `同名表跨schema_只收目标库那张`() {
        val index = McpSchemaContextService.indexModelTables(listOf(mpDemoUser, mysqlUser), "mp_demo")
        val entry = index["user"]
        assertEquals("用户表", entry?.comment)
        assertEquals(listOf("id", "username", "email"), entry?.columns?.map { it.name })
        assertFalse("系统表列不得混入", entry!!.columns.any { it.name == "Host" || it.name == "Select_priv" })
    }

    @Test
    fun `目标库里的表不存在_不拿别库同名表顶替`() {
        // 只请求 mp_demo 库，而模型里只有 mysql.user
        val index = McpSchemaContextService.indexModelTables(listOf(mysqlUser), "mp_demo")
        assertNull(index["user"])
    }

    @Test
    fun `目标库名取不到_退回按名匹配（一期口径）`() {
        val index = McpSchemaContextService.indexModelTables(listOf(mpDemoUser, mysqlUser), null)
        assertEquals("首个同名候选（保序先到先得）", "用户表", index["user"]?.comment)
    }

    @Test
    fun `无列无注释_不算命中`() {
        val empty = McpSchemaContextService.ModelTable("user", listOf("mp_demo", "localhost"), null, emptyList())
        assertTrue(McpSchemaContextService.indexModelTables(listOf(empty), "mp_demo").isEmpty())
    }

    @Test
    fun `库名大小写不敏感`() {
        val index = McpSchemaContextService.indexModelTables(listOf(mpDemoUser), "MP_DEMO")
        assertEquals("用户表", index["user"]?.comment)
    }

    // ------------------------------------------------------------------
    // 取数链路：目标数据源 + 目标库双重限定
    // ------------------------------------------------------------------

    @Test
    fun `取数_只返回目标库那张且列不合并`() {
        inject(listOf(mpDemoUser, mysqlUser))
        val result = McpSchemaContextService.collectEntries(listOf("ds"), listOf("user"), 8)
        assertEquals(1, result.entries.size)
        assertTrue("不应记入缺失: ${result.missing}", result.missing.isEmpty())
        assertEquals(listOf("id", "username", "email"), result.entries[0].columns.map { it.name })
        assertEquals("用户表", result.entries[0].comment)
        assertEquals("标签沿用请求写法", "user", result.entries[0].name)
    }

    @Test
    fun `取数_首个数据源只有系统表同名_不得冒充命中`() {
        // ds1 = jpa_demo 数据源（URL 库 jpa_demo，模型里只有 mysql.user）
        // ds2 = mp_demo 数据源（URL 库 mp_demo，模型里有 mp_demo.user）
        McpSchemaContextService.dataSourceUrlForTest = { ds ->
            if (ds == "ds-jpa") "jdbc:mysql://localhost:3306/jpa_demo" else mpDemoUrl
        }
        McpSchemaContextService.modelTablesForTest = { ds ->
            if (ds == "ds-jpa") listOf(mysqlUser) else listOf(mpDemoUser)
        }
        val result = McpSchemaContextService.collectEntries(
            listOf("ds-jpa", "ds-mp"),
            listOf("user"),
            8,
        )
        assertEquals(1, result.entries.size)
        assertTrue(result.missing.isEmpty())
        assertEquals("应命中 mp_demo 那张，而非 jpa 数据源里的 mysql.user", "用户表", result.entries[0].comment)
        assertEquals(listOf("id", "username", "email"), result.entries[0].columns.map { it.name })
    }

    @Test
    fun `取数_同一数据源一次调用只遍历一次模型`() {
        var scans = 0
        McpSchemaContextService.dataSourceUrlForTest = { mpDemoUrl }
        McpSchemaContextService.modelTablesForTest = {
            scans++
            listOf(mpDemoUser, mysqlUser)
        }
        val result = McpSchemaContextService.collectEntries(
            listOf("ds"),
            listOf("user", "role", "absent"),
            8,
        )
        assertEquals("模型只读一次（索引共用）", 1, scans)
        assertEquals(1, result.entries.size)
        assertEquals(listOf("role", "absent"), result.missing)
    }

    @Test
    fun `取数_扫描实况逐源记录供错误文案自解释`() {
        inject(listOf(mpDemoUser, mysqlUser))
        val diagnostics = mutableListOf<String>()
        McpSchemaContextService.collectEntries(listOf("ds"), listOf("user"), 8, diagnostics)
        assertEquals("每源一行", 1, diagnostics.size)
        assertTrue("应含目标库名: ${diagnostics[0]}", diagnostics[0].contains("targetDb=mp_demo"))
        assertTrue("应含索引命中数: ${diagnostics[0]}", diagnostics[0].contains("indexed=1"))
    }

    // ------------------------------------------------------------------
    // 来源披露（T-McpWalkFindings S3）：跨源静默解析不披露来源的两次真机实证
    // （20260923 jpa_demo 顶替意图库、20261005 两套 user 列结构对不上）后，成功路径
    // 披露命中数据源；未指名 alias 附 auto-resolved + pin 提示。
    // ------------------------------------------------------------------

    @Test
    fun `来源披露_collectEntries记录命中源名并保序去重`() {
        McpSchemaContextService.dsNameForTest = { if (it == "ds-mp") "mp_demo@localhost" else null }
        McpSchemaContextService.dataSourceUrlForTest = { mpDemoUrl }
        // ds-other 模型为空（不占命中），ds-mp 命中——sourceNames 只收命中源
        McpSchemaContextService.modelTablesForTest = { ds ->
            if (ds == "ds-mp") listOf(mpDemoUser) else emptyList()
        }
        val result = McpSchemaContextService.collectEntries(listOf("ds-other", "ds-mp"), listOf("user"), 8)
        assertEquals(listOf("mp_demo@localhost"), result.sourceNames)
        assertEquals("命中表 1 张", 1, result.entries.size)
    }

    @Test
    fun `来源披露_取不到名不进披露`() {
        // dsNameForTest 返回 null = 生产反射链取不到名的退化路径（`?`）——不披露
        McpSchemaContextService.dsNameForTest = { null }
        McpSchemaContextService.dataSourceUrlForTest = { mpDemoUrl }
        McpSchemaContextService.modelTablesForTest = { listOf(mpDemoUser) }
        val result = McpSchemaContextService.collectEntries(listOf("ds"), listOf("user"), 8)
        assertTrue("取不到名不得进披露: ${result.sourceNames}", result.sourceNames.isEmpty())
    }

    @Test
    fun `来源披露_显式指名不带auto提示`() {
        val outcome = McpSchemaContextService.outcomeOf(
            listOf(McpSchemaFormatter.TableEntry("user", "用户表", businessColumns)),
            emptyList(),
            8,
            1,
            sourceNames = listOf("mp_demo@localhost"),
            autoResolved = false,
        )
        val text = outcome.text!!
        assertTrue("应披露来源: $text", text.startsWith("source: mp_demo@localhost\n"))
        assertFalse("指名路径不得带 auto 提示: $text", text.contains("auto-resolved"))
        assertTrue("正文应保留表结构: $text", text.contains("用户表"))
    }

    @Test
    fun `来源披露_跨源auto带pin提示`() {
        val outcome = McpSchemaContextService.outcomeOf(
            listOf(McpSchemaFormatter.TableEntry("user", "用户表", businessColumns)),
            emptyList(),
            8,
            1,
            sourceNames = listOf("jpa_demo@localhost"),
            autoResolved = true,
        )
        val text = outcome.text!!
        assertTrue("应披露 auto-resolved: $text", text.contains("source: jpa_demo@localhost (auto-resolved"))
        assertTrue("应带 pin 提示: $text", text.contains("pass 'dataSource' to pin one"))
    }

    @Test
    fun `来源披露_未命中错误路径无source行`() {
        val outcome = McpSchemaContextService.outcomeOf(
            emptyList(),
            listOf("user"),
            8,
            1,
            listOf("ds=mp_demo targetDb=mp_demo modelTables=0 indexed=0"),
            sourceNames = listOf("mp_demo@localhost"),
            autoResolved = true,
        )
        assertNull("错误分支无正文", outcome.text)
        assertFalse("错误文案不得带 source 行: ${outcome.error}", outcome.error!!.contains("source:"))
    }

    @Test
    fun `错误文案_scope过滤掉全部表时点明库不匹配并带实况`() {
        // 复核发现（20260920）：entries 为空有两个成因——真没元数据（未连接/内省未完成），
        // 或**候选表被库名 scope 全部过滤掉**（表不在该数据源的当前库里）。旧文案只覆盖前者，
        // agent 会照文案做无用重试（重连/等内省）。本用例钉住「后者也在文案里」。
        val outcome = McpSchemaContextService.outcomeOf(
            emptyList(),
            listOf("user"),
            8,
            1,
            listOf("ds=prod targetDb=mp_demo modelTables=42 indexed=0"),
        )
        assertNull("错误分支不得同时给正文", outcome.text)
        val error = outcome.error!!
        assertTrue("应带错误码: $error", error.startsWith("[${McpToolErrors.SCHEMA_NOT_AVAILABLE}]"))
        assertTrue(
            "应点明「表可能不在当前库」: $error",
            error.contains("may not exist in the data source's current database"),
        )
        assertTrue("应带 targetDb 实况（agent 据此判定库名不匹配）: $error", error.contains("targetDb=mp_demo"))
        assertTrue("应带 indexed 实况: $error", error.contains("indexed=0"))
    }

    @Test
    fun `错误文案_无实况时不追加空括号`() {
        val outcome = McpSchemaContextService.outcomeOf(emptyList(), listOf("user"), 8, 1)
        val error = outcome.error!!
        assertFalse("无实况不应出现空括号: $error", error.contains("(scanned: )"))
        assertTrue("仍应含表名: $error", error.contains("no schema metadata for user"))
    }

    @Test
    fun `错误文案_命中时不报错`() {
        inject(listOf(mpDemoUser))
        val diagnostics = mutableListOf<String>()
        val result = McpSchemaContextService.collectEntries(listOf("ds"), listOf("user"), 8, diagnostics)
        val outcome = McpSchemaContextService.outcomeOf(
            result.entries, result.missing, 8, 1, diagnostics,
            sourceNames = listOf("mp_demo@localhost"), autoResolved = true,
        )
        assertNull("命中路径不得带错误: ${outcome.error}", outcome.error)
        assertTrue("应给正文: ${outcome.text}", outcome.text?.contains("user") == true)
    }
}
