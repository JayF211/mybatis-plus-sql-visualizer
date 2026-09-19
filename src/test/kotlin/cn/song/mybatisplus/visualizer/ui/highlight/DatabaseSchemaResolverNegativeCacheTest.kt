package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.execute.MetadataInvalidator
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.ColumnMeta
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.TableMeta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-MetaCheapSource（O-21 T-MC-01）负命中缓存表驱动测试。
 *
 * 单测环境无 database 插件，真实反射扫描链走不通（T-Embed 系列同款约束），故经
 * [DatabaseSchemaResolver.resolveFromSources] 的扫描探针注入计数——探针调用次数即「全扫次数」，
 * 负缓存生效语义（仅首次全扫 / TTL 过期重扫 / DDL 失效重扫 / 正命中零扫描）全部落在探针计数上。
 * 有数据源场景的完整链路按既有惯例留 RunIde 沙盒真机走查（计划 §五）。
 *
 * T-MC-03 identity 定案（DBX 吸收清单 #26「identity 列 INSERT 包裹」）：**不适用**——
 * 本插件 crud_sql 仅从 MP 实体 PSI 生成语句模板（mpsv_crud_sql 入参仅 filePath/className，
 * 无查询结果/SELECT 入口），INSERT 模板不含数据行，identity 包裹场景不触达；
 * 报告回填由主会话收口。
 *
 * T-MC-02（表名索引）为可选件，按计划默认不启用，仅留实施注释，无测试面。
 */
class DatabaseSchemaResolverNegativeCacheTest {

    /** 扫描探针调用计数（= 全扫次数；每次测试隔离重置）。 */
    private var scanCount = 0

    @Before
    fun resetCacheState() {
        DatabaseSchemaResolver.clearNegativeCache()
        DatabaseSchemaResolver.negativeTtlMs = DatabaseSchemaResolver.NEGATIVE_TTL_MS
        scanCount = 0
    }

    @After
    fun restoreCacheState() {
        DatabaseSchemaResolver.clearNegativeCache()
        DatabaseSchemaResolver.negativeTtlMs = DatabaseSchemaResolver.NEGATIVE_TTL_MS
    }

    /** 空结果探针（表不存在语义）：计数并返回空 TableMeta。 */
    private fun countingEmptyScan(@Suppress("UNUSED_PARAMETER") ds: Any, @Suppress("UNUSED_PARAMETER") table: String): TableMeta {
        scanCount++
        return TableMeta(null, emptyMap())
    }

    @Test
    fun `负缓存命中不重复全扫`() {
        val ds = Any()
        DatabaseSchemaResolver.resolveFromSources("ghost_table", listOf(ds), ::countingEmptyScan)
        assertEquals("未命中首次全扫", 1, scanCount)
        val again = DatabaseSchemaResolver.resolveFromSources("ghost_table", listOf(ds), ::countingEmptyScan)
        assertEquals("TTL 内负命中：不再全扫", 1, scanCount)
        assertEquals(null, again.comment)
        assertTrue(again.columns.isEmpty())
        // 表名小写归一同键（与正缓存同口径），大小写变体复用同一负缓存键
        DatabaseSchemaResolver.resolveFromSources("GHOST_TABLE", listOf(ds), ::countingEmptyScan)
        assertEquals("大小写归一后仍是同一负缓存键，不复扫", 1, scanCount)
        // 不同表名不复用负缓存
        DatabaseSchemaResolver.resolveFromSources("other_table", listOf(ds), ::countingEmptyScan)
        assertEquals(2, scanCount)
    }

    @Test
    fun `TTL过期语义_默认TTL内不重扫_归零后重扫`() {
        val ds = Any()
        try {
            DatabaseSchemaResolver.resolveFromSources("ghost_t", listOf(ds), ::countingEmptyScan)
            DatabaseSchemaResolver.resolveFromSources("ghost_t", listOf(ds), ::countingEmptyScan)
            assertEquals("默认 TTL 内负命中不重扫", 1, scanCount)
            DatabaseSchemaResolver.negativeTtlMs = 0 // 时间戳惰性判定：已记条目立判过期
            DatabaseSchemaResolver.resolveFromSources("ghost_t", listOf(ds), ::countingEmptyScan)
            assertEquals("TTL 归零后重新全扫", 2, scanCount)
        } finally {
            DatabaseSchemaResolver.negativeTtlMs = DatabaseSchemaResolver.NEGATIVE_TTL_MS
        }
    }

    @Test
    fun `失效登记生效_DDL后负缓存清空_非DDL不清`() {
        val ds = Any()
        DatabaseSchemaResolver.resolveFromSources("t_pre", listOf(ds), ::countingEmptyScan)
        assertEquals(1, scanCount)
        // 非 DDL：负缓存保留，不重扫
        MetadataInvalidator.invalidateAfterExecution("SELECT * FROM t_pre")
        DatabaseSchemaResolver.resolveFromSources("t_pre", listOf(ds), ::countingEmptyScan)
        assertEquals("非 DDL 不触发负缓存失效", 1, scanCount)
        // DDL：MetadataInvalidator 登记动作（label=DatabaseSchemaResolver.negativeCache）清空负缓存 → 重扫
        MetadataInvalidator.invalidateAfterExecution("CREATE TABLE t_pre (id INT)")
        DatabaseSchemaResolver.resolveFromSources("t_pre", listOf(ds), ::countingEmptyScan)
        assertEquals("DDL 失效后负缓存清空、重新全扫", 2, scanCount)
    }

    @Test
    fun `建表场景_负缓存失效后正命中接管`() {
        val ds = Any()
        var tableExists = false
        val scan: (Any, String) -> TableMeta = { _, _ ->
            scanCount++
            if (tableExists) TableMeta("新表", linkedMapOf("id" to ColumnMeta(null, "bigint", null)))
            else TableMeta(null, emptyMap())
        }
        DatabaseSchemaResolver.resolveFromSources("t_new", listOf(ds), scan)
        assertEquals("建表前：全扫 + 记负缓存", 1, scanCount)
        DatabaseSchemaResolver.resolveFromSources("t_new", listOf(ds), scan)
        assertEquals("TTL 内不再扫（此时表仍未建）", 1, scanCount)
        tableExists = true
        MetadataInvalidator.invalidateAfterExecution("CREATE TABLE t_new (id BIGINT)")
        val meta = DatabaseSchemaResolver.resolveFromSources("t_new", listOf(ds), scan)
        assertEquals("DDL 失效负缓存 → 重扫拿到新表", 2, scanCount)
        assertEquals("新表", meta.comment)
        DatabaseSchemaResolver.resolveFromSources("t_new", listOf(ds), scan)
        assertEquals("建表后正命中接管，不再重扫", 2, scanCount)
    }

    @Test
    fun `正命中行为不变_命中后零扫描且同实例返回`() {
        val ds = Any()
        val found = TableMeta(
            "用户表",
            linkedMapOf("id" to ColumnMeta("主键", "int(11)", true)),
        )
        val scan: (Any, String) -> TableMeta = { _, _ ->
            scanCount++
            found
        }
        val first = DatabaseSchemaResolver.resolveFromSources("users", listOf(ds), scan)
        assertEquals(1, scanCount)
        assertEquals(found, first)
        // 第二次：正缓存命中——零扫描、同实例（防回归断言：正命中路径行为与 T-Perf-09 原实现一致）
        val second = DatabaseSchemaResolver.resolveFromSources("users", listOf(ds), scan)
        assertEquals("正命中不再进扫描", 1, scanCount)
        assertSame("正缓存命中返回同一实例", first, second)
        assertEquals(found, second)
        // 正命中不写负缓存：即便后续正缓存被外部清掉，也只会重扫而非被负缓存挡住
        DatabaseSchemaResolver.clearNegativeCache()
        val third = DatabaseSchemaResolver.resolveFromSources("users", listOf(ds), scan)
        assertEquals("正命中路径零负缓存副作用", 1, scanCount)
        assertSame(first, third)
    }

    // ---------- T-PS5-06：单调用全扫限额 ----------

    @Test
    fun `单调用k表miss只全扫一次`() {
        val ds = Any()
        val names = (1..20).map { "ghost_$it" }
        val results = DatabaseSchemaResolver.withScanBudget {
            names.map { DatabaseSchemaResolver.resolveFromSources(it, listOf(ds), ::countingEmptyScan) }
        }
        assertEquals("同一数据源在一次调用内全扫至多一次（20 表 miss）", 1, scanCount)
        assertTrue("未找到口径不变（无注释无列）", results.all { it.comment == null && it.columns.isEmpty() })
    }

    @Test
    fun `多数据源每源各一次上限`() {
        val ds1 = Any()
        val ds2 = Any()
        DatabaseSchemaResolver.withScanBudget {
            DatabaseSchemaResolver.resolveFromSources("g1", listOf(ds1, ds2), ::countingEmptyScan)
            DatabaseSchemaResolver.resolveFromSources("g2", listOf(ds1, ds2), ::countingEmptyScan)
        }
        assertEquals("两数据源 × 两表 = 每源一次（共 2 次，而非 4 次）", 2, scanCount)
    }

    @Test
    fun `命中与miss混合调用结果正确`() {
        val ds = Any()
        val existing = TableMeta("用户表", linkedMapOf("id" to ColumnMeta("主键", "int(11)", true)))
        val scan: (Any, String) -> TableMeta = { _, table ->
            scanCount++
            if (table == "users") existing else TableMeta(null, emptyMap())
        }
        val (hit, miss1, miss2) = DatabaseSchemaResolver.withScanBudget {
            Triple(
                DatabaseSchemaResolver.resolveFromSources("users", listOf(ds), scan),
                DatabaseSchemaResolver.resolveFromSources("ghost_a", listOf(ds), scan),
                DatabaseSchemaResolver.resolveFromSources("ghost_b", listOf(ds), scan),
            )
        }
        assertEquals("命中表返回真实元数据", existing, hit)
        assertTrue("miss 表按未找到披露", miss1.columns.isEmpty() && miss1.comment == null)
        assertTrue("后续 miss 表同样按未找到披露", miss2.columns.isEmpty() && miss2.comment == null)
        assertEquals("一次调用内同一数据源只全扫一次", 1, scanCount)
    }

    @Test
    fun `命中与miss同调用内只全扫一次`() {
        val ds = Any()
        val existing = TableMeta("用户表", linkedMapOf("id" to ColumnMeta("主键", "int(11)", true)))
        val scan: (Any, String) -> TableMeta = { _, table ->
            scanCount++
            if (table == "users") existing else TableMeta(null, emptyMap())
        }
        DatabaseSchemaResolver.withScanBudget {
            assertEquals(existing, DatabaseSchemaResolver.resolveFromSources("users", listOf(ds), scan))
            val miss = DatabaseSchemaResolver.resolveFromSources("ghost_a", listOf(ds), scan)
            assertTrue("同调用内后续 miss 表按未找到披露", miss.columns.isEmpty() && miss.comment == null)
        }
        assertEquals("命中后同一数据源不再重扫（遍历已完整）", 1, scanCount)
    }

    @Test
    fun `查不了不记账_下次调用仍重试`() {
        val ds = Any()
        val bailScan: (Any, String) -> TableMeta = { _, _ ->
            scanCount++
            TableMeta(null, emptyMap(), true)
        }
        DatabaseSchemaResolver.withScanBudget {
            DatabaseSchemaResolver.resolveFromSources("t_bail", listOf(ds), bailScan)
            // 查不了 → 不记账 → 同调用内下一张表仍重扫（数据源晚连接场景必须能自愈）
            DatabaseSchemaResolver.resolveFromSources("t_other", listOf(ds), bailScan)
        }
        assertEquals("查不了不记账：同调用内逐表重扫", 2, scanCount)
        DatabaseSchemaResolver.withScanBudget {
            DatabaseSchemaResolver.resolveFromSources("t_bail", listOf(ds), bailScan)
        }
        assertEquals("跨调用仍重试", 3, scanCount)
    }

    @Test
    fun `作用域外零行为变化`() {
        val ds = Any()
        assertEquals("作用域外默认无账本", false, DatabaseSchemaResolver.inScanBudgetForTest())
        DatabaseSchemaResolver.resolveFromSources("g1", listOf(ds), ::countingEmptyScan)
        DatabaseSchemaResolver.resolveFromSources("g2", listOf(ds), ::countingEmptyScan)
        assertEquals("未开启限额时维持既有逐表全扫语义", 2, scanCount)
        DatabaseSchemaResolver.withScanBudget { assertEquals(true, DatabaseSchemaResolver.inScanBudgetForTest()) }
        assertEquals("退出作用域即恢复", false, DatabaseSchemaResolver.inScanBudgetForTest())
        DatabaseSchemaResolver.resolveFromSources("g3", listOf(ds), ::countingEmptyScan)
        assertEquals("作用域退出后仍逐表全扫", 3, scanCount)
    }

    @Test
    fun `限额内负缓存跨调用语义不变`() {
        val ds = Any()
        DatabaseSchemaResolver.withScanBudget {
            DatabaseSchemaResolver.resolveFromSources("g_x", listOf(ds), ::countingEmptyScan)
            DatabaseSchemaResolver.resolveFromSources("g_y", listOf(ds), ::countingEmptyScan)
        }
        assertEquals(1, scanCount)
        // 跨调用：负缓存仍生效（g_x/g_y 都已记负缓存）
        DatabaseSchemaResolver.resolveFromSources("g_x", listOf(ds), ::countingEmptyScan)
        DatabaseSchemaResolver.resolveFromSources("g_y", listOf(ds), ::countingEmptyScan)
        assertEquals("限额跳过的表同样记了负缓存 → 跨调用不重扫", 1, scanCount)
    }

    // ---------- T-PS5-13：负缓存容量上界 ----------

    @Test
    fun `负缓存容量上界_溢出整体清空且结论不变`() {
        val ds = Any()
        val max = DatabaseSchemaResolver.NEGATIVE_CACHE_MAX
        for (i in 0 until max) {
            DatabaseSchemaResolver.resolveFromSources("cap_t$i", listOf(ds), ::countingEmptyScan)
        }
        assertEquals("未溢出时逐条累积", max, DatabaseSchemaResolver.negativeCacheSize())

        // 再记一条 → 溢出整体清空 + 本键写入
        DatabaseSchemaResolver.resolveFromSources("cap_overflow", listOf(ds), ::countingEmptyScan)
        assertTrue(
            "溢出整体清空（只余溢出后写入的条目，实测 ${DatabaseSchemaResolver.negativeCacheSize()}）",
            DatabaseSchemaResolver.negativeCacheSize() <= 1,
        )

        // 结论不变：被清掉的旧表重扫（清空只丢命中率不丢正确性）
        val before = scanCount
        val old = DatabaseSchemaResolver.resolveFromSources("cap_t0", listOf(ds), ::countingEmptyScan)
        assertEquals("清空后旧表重扫", before + 1, scanCount)
        assertTrue("重扫结论仍是未找到", old.columns.isEmpty() && old.comment == null)

        // 溢出后写入的表照常命中负缓存
        val mid = scanCount
        DatabaseSchemaResolver.resolveFromSources("cap_overflow", listOf(ds), ::countingEmptyScan)
        assertEquals("溢出后写入的条目命中负缓存（TTL 语义不变）", mid, scanCount)
    }
}
