package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.execute.MetadataInvalidator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `sqlv_schema_context` 跨调用索引缓存测试（T-PerfSweep6 T-PS6-02 / 性能报告发现 2）。
 *
 * 缺陷面：每次调用重建全量索引——遍历目标数据源整个 DasModel、为所有表所有列走反射链，
 * 最终只输出 ≤limit 张表；同一数据源的模型在两次调用之间通常完全没变。
 *
 * 探针口径：单测环境无 database 插件，经 [McpSchemaContextService.modelTablesForTest] 注入替身，
 * **探针调用次数 = 索引重建次数**（等价于「DasModel 全遍历 + 逐列反射」次数）。
 *
 * 覆盖点：
 *  1. 跨调用命中不重扫（同数据源第二次调用探针计数不增）；
 *  2. 输出与首次**逐条一致**（缓存只省计算，不改结果）；
 *  3. DDL 失效后重扫（`MetadataInvalidator` 登记动作生效）；
 *  4. TTL 过期后重扫（归零旋钮）；
 *  5. 库名维度不符即重扫（URL 换库不脏读）；
 *  6. 不同数据源各自成条目。
 */
class McpSchemaContextIndexCacheTest {

    private var scans = 0

    private val businessColumns = listOf(
        McpSchemaFormatter.ColumnLine("id", "int(11)", "主键", true),
        McpSchemaFormatter.ColumnLine("username", "varchar(64)", null, true),
    )

    private fun table(name: String, db: String = "mp_demo") = McpSchemaContextService.ModelTable(
        name = name,
        ancestorNames = listOf(db, "localhost"),
        comment = "$name 表",
        columns = businessColumns,
    )

    private val mpDemoUrl = "jdbc:mysql://localhost:3306/mp_demo"

    @Before
    fun resetState() {
        McpSchemaContextService.clearIndexCache()
        McpSchemaContextService.indexTtlMs = McpSchemaContextService.INDEX_TTL_MS
        scans = 0
    }

    @After
    fun restoreState() {
        McpSchemaContextService.modelTablesForTest = null
        McpSchemaContextService.dataSourceUrlForTest = null
        McpSchemaContextService.clearIndexCache()
        McpSchemaContextService.indexTtlMs = McpSchemaContextService.INDEX_TTL_MS
    }

    private fun inject(model: List<McpSchemaContextService.ModelTable>, url: String? = mpDemoUrl) {
        McpSchemaContextService.modelTablesForTest = { scans++; model }
        McpSchemaContextService.dataSourceUrlForTest = { url }
    }

    @Test
    fun `跨调用命中不重扫且输出逐条一致`() {
        inject(listOf(table("user"), table("role")))
        val ds = Any()

        val first = McpSchemaContextService.collectEntries(listOf(ds), listOf("user"), 8)
        assertEquals("首次调用建索引", 1, scans)
        assertEquals(1, first.first.size)
        assertTrue(first.second.isEmpty())

        val second = McpSchemaContextService.collectEntries(listOf(ds), listOf("user"), 8)
        assertEquals("TTL 内第二次调用不得重扫（T-PS6-02 的省法）", 1, scans)
        assertEquals("命中缓存后输出必须逐条一致", first.first, second.first)
        assertEquals("缺失表读数一致", first.second, second.second)
    }

    @Test
    fun `DDL失效后重扫`() {
        inject(listOf(table("user")))
        val ds = Any()
        McpSchemaContextService.collectEntries(listOf(ds), listOf("user"), 8)
        assertEquals(1, scans)

        // 非 DDL 不失效
        MetadataInvalidator.invalidateAfterExecution("SELECT * FROM user")
        McpSchemaContextService.collectEntries(listOf(ds), listOf("user"), 8)
        assertEquals("非 DDL 不触发索引失效", 1, scans)

        // DDL → 登记动作清空跨调用索引 → 重扫（建表后立查新表的前提）
        MetadataInvalidator.invalidateAfterExecution("CREATE TABLE t_new (id INT)")
        McpSchemaContextService.collectEntries(listOf(ds), listOf("user"), 8)
        assertEquals("DDL 失效后必须重扫", 2, scans)
    }

    @Test
    fun `TTL过期后重扫`() {
        inject(listOf(table("user")))
        val ds = Any()
        McpSchemaContextService.collectEntries(listOf(ds), listOf("user"), 8)
        McpSchemaContextService.collectEntries(listOf(ds), listOf("user"), 8)
        assertEquals("默认 TTL 内不重扫", 1, scans)

        McpSchemaContextService.indexTtlMs = 0 // 时间戳惰性判定：已记条目立判过期
        McpSchemaContextService.collectEntries(listOf(ds), listOf("user"), 8)
        assertEquals("TTL 归零后重扫", 2, scans)
    }

    @Test
    fun `库名维度不符即重扫_不脏读`() {
        var url = mpDemoUrl
        McpSchemaContextService.modelTablesForTest = { scans++; listOf(table("user")) }
        McpSchemaContextService.dataSourceUrlForTest = { url }
        val ds = Any()

        McpSchemaContextService.collectEntries(listOf(ds), listOf("user"), 8)
        assertEquals(1, scans)
        // 换库：同名表的结论按库不同（P7/P4 口径），索引必须重建而不是复用上一库的
        url = "jdbc:mysql://localhost:3306/other_db"
        McpSchemaContextService.collectEntries(listOf(ds), listOf("user"), 8)
        assertEquals("目标库名变化必须重扫（缓存键带库名维度）", 2, scans)
    }

    @Test
    fun `不同数据源各自成条目`() {
        inject(listOf(table("user")))
        val dsA = Any()
        val dsB = Any()
        McpSchemaContextService.collectEntries(listOf(dsA), listOf("user"), 8)
        McpSchemaContextService.collectEntries(listOf(dsB), listOf("user"), 8)
        assertEquals("两个数据源各扫一次", 2, scans)
        assertEquals("各持一条缓存", 2, McpSchemaContextService.indexCacheSize())

        McpSchemaContextService.collectEntries(listOf(dsA, dsB), listOf("user"), 8)
        assertEquals("两条都命中，不再重扫", 2, scans)
    }

    @Test
    fun `缓存条目与调用内索引不是同一份状态`() {
        inject(listOf(table("user")))
        val ds = Any()
        val first = McpSchemaContextService.collectEntries(listOf(ds), listOf("user"), 8).first
        val second = McpSchemaContextService.collectEntries(listOf(ds), listOf("user"), 8).first
        // collectEntries 对命中表做 name 覆写（copy），故两次调用产出的是各自的对象图——
        // 断言「结果等值但缓存确实被复用」：计数探针已证复用（scans==1），此处只钉不共享可变对象
        assertEquals(first, second)
        assertNotSame("调用间不得共享同一 TableEntry 实例（避免调用方改一处影响他处）", first[0], second[0])
    }
}
