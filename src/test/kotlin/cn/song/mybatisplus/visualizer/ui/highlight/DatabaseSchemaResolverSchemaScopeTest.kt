package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.ColumnMeta
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.DasAccessors
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.ModelTraversal
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.TableMeta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P7 / P7b：同名表**按库（schema）限定**的匹配语义 + 遍历 0 张表判「查不了」。
 *
 * 背景（真机 2026-09-20）：`scan: table=user, ds=jpa_demo@localhost, tablesScanned=335, hit=true,
 * foundColumns=60`——一次遍历把所有同名表的列合并进同一个 Map（60 = 业务表 9 列 + `mysql.user`
 * 系统表 50+），表注释也被最后一张覆盖。三个成因各自成断言：
 *
 *  1. **匹配**只比表名不看库 → 表祖先链须含本数据源 URL 的库名（[JdbcUrlDatabase] 口径），
 *     且首个匹配即锁定（旧行为=继续合并、后一张覆盖）；
 *  2. **缓存**键只有表名，而命中发生在匹配之前 → 键带库名维度（同名表在两个库各自成条目）；
 *  3. **补全候选**跨全部数据源 putIfAbsent 并集 → 每个表名只认首个持有它的数据源。
 *
 * P7b：模型非 null 但遍历 0 张表（内省未完成）此前被当「确认没有此表」记负缓存——DDL 生效探测
 * 的 500/1500/3000ms 三次退避重试会被首次那一记负缓存全挡掉，误报「DDL 未生效」。
 *
 * 单测环境无 database 插件（`Class.forName` 全链不可用，见 [DatabaseSchemaResolverObjectKindTest]），
 * 故经 [DatabaseSchemaResolver.traversalForTest] 注入遍历替身 + [DatabaseSchemaResolver
 * .dataSourceDatabaseForTest] 注入库名替身；**匹配/合并/判定逻辑仍是生产代码本体**。
 */
class DatabaseSchemaResolverSchemaScopeTest {

    @Before
    fun resetState() {
        DatabaseSchemaResolver.clearNegativeCache()
        DatabaseSchemaResolver.invalidateAll()
        DatabaseSchemaResolver.negativeTtlMs = DatabaseSchemaResolver.NEGATIVE_TTL_MS
        DatabaseSchemaResolver.traversalForTest = null
        DatabaseSchemaResolver.dataSourceDatabaseForTest = null
    }

    @After
    fun restoreState() {
        DatabaseSchemaResolver.clearNegativeCache()
        DatabaseSchemaResolver.invalidateAll()
        DatabaseSchemaResolver.negativeTtlMs = DatabaseSchemaResolver.NEGATIVE_TTL_MS
        DatabaseSchemaResolver.traversalForTest = null
        DatabaseSchemaResolver.dataSourceDatabaseForTest = null
    }

    // ---------- 假模型（只保留 P7 判定用到的四件事：名字 / 注释 / 祖先链 / 列） ----------

    private class FakeNs(val nsName: String, val up: Any? = null)
    private class FakeTable(val tableName: String, val columns: List<String>, val up: Any? = null)
    private class FakeColumn(val columnName: String, val columnComment: String?)

    private val accessors = DasAccessors(
        isTable = { it is FakeTable },
        isColumn = { it is FakeColumn },
        name = {
            when (it) {
                is FakeTable -> it.tableName
                is FakeColumn -> it.columnName
                is FakeNs -> it.nsName
                else -> null
            }
        },
        comment = { (it as? FakeColumn)?.columnComment },
        children = { (it as? FakeTable)?.columns?.map { c -> FakeColumn(c, "注释_$c") } },
        parent = {
            when (it) {
                is FakeTable -> it.up
                is FakeNs -> it.up
                else -> null
            }
        },
        typeSpec = { "int(11)" },
        notNull = { true },
    )

    /** 库节点 → 模式节点 → 表（祖先链含库名，与 DBN 的 Database → Schema → Table 同形）。 */
    private fun tableIn(database: String?, tableName: String, columns: List<String>): FakeTable =
        FakeTable(tableName, columns, FakeNs("public", database?.let { FakeNs(it) }))

    /** 注入遍历替身并返回调用计数（计数 = 真实扫描次数，负缓存语义落在它上面）。 */
    private fun stubTraversal(vararg elements: Any?): () -> Int {
        var calls = 0
        DatabaseSchemaResolver.traversalForTest = {
            calls++
            ModelTraversal(elements.toList(), accessors)
        }
        return { calls }
    }

    private fun stubDatabase(name: String?) {
        DatabaseSchemaResolver.dataSourceDatabaseForTest = { name }
    }

    // ---------- P7b：遍历 0 张表 = 查不了 ----------

    @Test
    fun `遍历0张表_判查不了且不写负缓存_下次调用重扫`() {
        stubDatabase("mpj_demo")
        val calls = stubTraversal() // model 非 null（替身即已拿到模型），但元素列表为空

        val ds = Any()
        val meta = DatabaseSchemaResolver.resolveFromSources("t_zero", listOf(ds))
        assertTrue("遍历 0 张表 → 走「无法解析」分支（scanBailed）", meta.scanBailed)
        assertNull(meta.comment)
        assertTrue(meta.columns.isEmpty())
        assertEquals("首次进入扫描", 1, calls())

        val again = DatabaseSchemaResolver.resolveFromSources("t_zero", listOf(ds))
        assertTrue("未写负缓存", again.scanBailed)
        assertEquals("未写负缓存 → 下次调用仍重扫（内省完成后即可命中）", 2, calls())
    }

    @Test
    fun `元素非空但无表节点_同样判查不了`() {
        stubDatabase("mpj_demo")
        val calls = stubTraversal(FakeNs("mpj_demo"), FakeNs("public", FakeNs("mpj_demo")))

        val ds = Any()
        val meta = DatabaseSchemaResolver.resolveFromSources("t_zero", listOf(ds))
        assertTrue("遍历有元素但一张 DasTable 都没筛出（模型只建到库/模式）→ 查不了", meta.scanBailed)
        DatabaseSchemaResolver.resolveFromSources("t_zero", listOf(ds))
        assertEquals("同样不写负缓存", 2, calls())
    }

    @Test
    fun `遍历到表但未命中_仍按未找到记负缓存`() {
        stubDatabase("mpj_demo")
        val calls = stubTraversal(tableIn("mpj_demo", "other_table", listOf("id")))

        val ds = Any()
        val meta = DatabaseSchemaResolver.resolveFromSources("t_absent", listOf(ds))
        assertFalse("扫到过表 → 不是「查不了」", meta.scanBailed)
        assertNull(meta.comment)
        DatabaseSchemaResolver.resolveFromSources("t_absent", listOf(ds))
        assertEquals("真·未命中仍按负缓存只扫一次（T-MetaCheapSource 语义不变）", 1, calls())
    }

    @Test
    fun `bail判定_已有原因不被0张表覆盖`() {
        assertEquals(
            "遍历 0 张表 → bail 非空",
            DatabaseSchemaResolver.TRAVERSED_ZERO_TABLES,
            DatabaseSchemaResolver.bailAfterTraversal(hit = false, scannedTables = 0, existingBail = null),
        )
        assertNull(
            "扫到过表且未命中 → 不是查不了",
            DatabaseSchemaResolver.bailAfterTraversal(hit = false, scannedTables = 3, existingBail = null),
        )
        assertNull(
            "命中 → 不是查不了",
            DatabaseSchemaResolver.bailAfterTraversal(hit = true, scannedTables = 1, existingBail = null),
        )
        assertEquals(
            "已有更精确的 bail（模型未加载等）不被覆盖",
            "数据源模型未加载(getModel=null)",
            DatabaseSchemaResolver.bailAfterTraversal(
                hit = false,
                scannedTables = 0,
                existingBail = "数据源模型未加载(getModel=null)",
            ),
        )
    }

    // ---------- P7：同名表按库限定 + 首个匹配锁定 ----------

    @Test
    fun `同名表只取首个匹配_不再合并后续同名表的列`() {
        stubDatabase("mpj_demo")
        stubTraversal(
            tableIn("mpj_demo", "user", listOf("id", "username")),
            tableIn("mpj_demo", "user", listOf("id", "host", "password_last_changed")),
        )

        val meta = DatabaseSchemaResolver.resolveFromSources("user", listOf(Any()))
        assertEquals(
            "首个匹配即锁定：后续同名表的列不得并进来（旧行为=合并成 4 列）",
            setOf("id", "username"),
            meta.columns.keys,
        )
    }

    @Test
    fun `同名系统表不在本库时_不命中_不被系统表列污染`() {
        stubDatabase("mpj_demo")
        stubTraversal(
            // 遍历顺序把系统表放在前面（真机 mysql.user 常在业务库之前/之后不定）
            tableIn("mysql", "user", listOf("host", "user", "authentication_string")),
            tableIn("mpj_demo", "user", listOf("id", "username")),
        )

        val meta = DatabaseSchemaResolver.resolveFromSources("user", listOf(Any()))
        assertEquals(
            "祖先链不含本数据源库名的同名表被跳过，取到业务库那张",
            setOf("id", "username"),
            meta.columns.keys,
        )
    }

    @Test
    fun `库名取不到_退回按名匹配_首个同名表即命中`() {
        stubDatabase(null) // URL 无库段 / Oracle SID 形态
        stubTraversal(
            tableIn("mysql", "user", listOf("host")),
            tableIn("mpj_demo", "user", listOf("id", "username")),
        )

        val meta = DatabaseSchemaResolver.resolveFromSources("user", listOf(Any()))
        assertEquals(
            "取不到库名 → 退回按名匹配（不引入新失败模式），首个同名表即锁定",
            setOf("host"),
            meta.columns.keys,
        )
    }

    // ---------- P7：缓存维度（库名进键） ----------

    @Test
    fun `同名表在两个库的数据源下_各自只拿本库那张表的列`() {
        val dsA = Any()
        val dsB = Any()
        DatabaseSchemaResolver.dataSourceDatabaseForTest = { ds -> if (ds === dsA) "db_a" else "db_b" }
        stubTraversal(
            tableIn("db_a", "user", listOf("a_only")),
            tableIn("db_b", "user", listOf("b_only")),
        )

        val a = DatabaseSchemaResolver.resolveFromSources("user", listOf(dsA))
        val b = DatabaseSchemaResolver.resolveFromSources("user", listOf(dsB))
        assertEquals("A 库数据源只拿 A 库那张表", setOf("a_only"), a.columns.keys)
        assertEquals("B 库数据源只拿 B 库那张表", setOf("b_only"), b.columns.keys)
    }

    @Test
    fun `缓存键带库名_同数据源换库后不吃旧库的缓存条目`() {
        val ds = Any()
        var currentDb = "db_a"
        DatabaseSchemaResolver.dataSourceDatabaseForTest = { currentDb }
        val calls = stubTraversal(tableIn("db_a", "user", listOf("a_only")))

        val a = DatabaseSchemaResolver.resolveFromSources("user", listOf(ds))
        assertEquals(setOf("a_only"), a.columns.keys)
        assertEquals(1, calls())

        // 同一数据源改连另一个库（重建连接 URL）：旧实现键只有表名 → 直接吃 db_a 的缓存条目
        currentDb = "db_b"
        DatabaseSchemaResolver.traversalForTest = {
            ModelTraversal(listOf(tableIn("db_b", "user", listOf("b_only"))), accessors)
        }
        val b = DatabaseSchemaResolver.resolveFromSources("user", listOf(ds))
        assertEquals("库名进键后按 db_b 重新取数，不吃 db_a 的缓存", setOf("b_only"), b.columns.keys)
    }

    @Test
    fun `缓存键_库名取不到时退回纯表名`() {
        val ds = Any()
        stubDatabase(null)
        val calls = stubTraversal(tableIn(null, "user", listOf("id")))

        val first = DatabaseSchemaResolver.resolveFromSources("user", listOf(ds))
        assertEquals(setOf("id"), first.columns.keys)
        DatabaseSchemaResolver.resolveFromSources("USER", listOf(ds))
        assertEquals("库名缺失时键仍是表名小写（大小写归一，正缓存命中不重扫）", 1, calls())
    }

    // ---------- P7：补全候选不跨数据源并集 ----------

    @Test
    fun `补全候选_每个表名只认首个持有它的数据源`() {
        val cols = { names: List<String> -> names.associateWith { ColumnMeta("注释_$it", "int(11)") } }
        val merged = DatabaseSchemaResolver.mergeCachedColumns(
            listOf("user", "role"),
            listOf(
                { name: String -> if (name == "user") cols(listOf("a_only", "shared")) else null },
                { name: String -> if (name == "user") cols(listOf("b_only", "shared")) else cols(listOf("role_id")) },
            ),
        )
        assertEquals(
            "user 只取首个持有它的数据源（旧行为=两源并集成 a_only/shared/b_only）",
            setOf("a_only", "shared"),
            merged.filterKeys { it != "role_id" }.keys,
        )
        assertTrue("后一个数据源里的 role 表照常进候选（首源未持有时不被前源挡住）", merged.containsKey("role_id"))
    }

    @Test
    fun `补全候选_无任何数据源持有时为空`() {
        val merged = DatabaseSchemaResolver.mergeCachedColumns(
            listOf("ghost"),
            listOf({ _: String -> null }, { _: String -> null }),
        )
        assertTrue("未预热/无数据源 → 空 Map（调用方回落实体列，改动前语义）", merged.isEmpty())
    }

    // ---------- 缓存与负缓存的口径回归（P7 改动不得破坏既有语义） ----------

    @Test
    fun `正命中仍零扫描且同实例返回`() {
        stubDatabase("mpj_demo")
        val calls = stubTraversal(tableIn("mpj_demo", "user", listOf("id")))
        val ds = Any()

        val first = DatabaseSchemaResolver.resolveFromSources("user", listOf(ds))
        val second = DatabaseSchemaResolver.resolveFromSources("user", listOf(ds))
        assertEquals("正命中不再进扫描", 1, calls())
        assertEquals(first, second)
        assertTrue("命中表列非空（调用方据此判定已解析）", first.columns.isNotEmpty())
    }

    @Test
    fun `列元数据_注释与类型串按 DBN 口径收录`() {
        stubDatabase("mpj_demo")
        stubTraversal(tableIn("mpj_demo", "user", listOf("id")))
        val meta: TableMeta = DatabaseSchemaResolver.resolveFromSources("user", listOf(Any()))
        assertEquals(ColumnMeta("注释_id", "int(11)", true), meta.columns["id"])
    }
}
