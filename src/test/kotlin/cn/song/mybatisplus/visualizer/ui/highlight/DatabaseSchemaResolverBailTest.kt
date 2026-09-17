package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.ColumnMeta
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.TableMeta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-DbSchemaCompletion：`scanBailed`（查不了 vs 没有）语义测试。
 *
 * 修复前 model/traverser/columnKind 三处 null 直接 `return TableMeta(null, emptyMap())`——上层
 * [DatabaseSchemaResolver.resolveFromSources] 把空结果一律当「该数据源确认没有此表」记进负缓存。
 * 真机后果：预览打开常早于数据源建连（`getModel()` 为 null），这一拍会把表钉进负缓存 30s，
 * 即便随后连上、ObjectKind 取法也修好了，补全与 JOIN 富化仍然全空。
 *
 * 本测试钉住两条语义（探针注入，单测环境无 database 插件）：
 *  1. 查不了的扫描**不写负缓存**、下次调用重试；
 *  2. 一个数据源查不了**不挡后续数据源**（真正的扫描继续）。
 * 与 [DatabaseSchemaResolverNegativeCacheTest] 的「真·未命中仍按负缓存只扫一次」互为正反面。
 */
class DatabaseSchemaResolverBailTest {

    @Before
    fun resetCacheState() {
        DatabaseSchemaResolver.clearNegativeCache()
        DatabaseSchemaResolver.negativeTtlMs = DatabaseSchemaResolver.NEGATIVE_TTL_MS
    }

    @After
    fun restoreCacheState() {
        DatabaseSchemaResolver.clearNegativeCache()
        DatabaseSchemaResolver.negativeTtlMs = DatabaseSchemaResolver.NEGATIVE_TTL_MS
    }

    @Test
    fun `查不了不写负缓存_下次调用重试`() {
        var scans = 0
        val bailScan: (Any, String) -> TableMeta = { _, _ ->
            scans++
            TableMeta(null, emptyMap(), scanBailed = true)
        }
        val ds = Any()

        val first = DatabaseSchemaResolver.resolveFromSources("t_bail", listOf(ds), bailScan)
        assertEquals("首次进扫描", 1, scans)
        assertTrue("全部数据源都查不了 → 聚合结果标记 scanBailed", first.scanBailed)
        assertNull(first.comment)

        DatabaseSchemaResolver.resolveFromSources("t_bail", listOf(ds), bailScan)
        assertEquals("未写负缓存 → 下次调用仍重试（数据源晚连接后即可命中）", 2, scans)
    }

    @Test
    fun `一个数据源查不了不挡后续数据源`() {
        var scans = 0
        val dsBail = Any()
        val dsOk = Any()
        val scan: (Any, String) -> TableMeta = { ds, _ ->
            scans++
            if (ds === dsBail) {
                TableMeta(null, emptyMap(), scanBailed = true)
            } else {
                TableMeta("角色表", linkedMapOf("id" to ColumnMeta("主键", "int(11)", true)))
            }
        }

        val meta = DatabaseSchemaResolver.resolveFromSources("role", listOf(dsBail, dsOk), scan)
        assertEquals("查不了的数据源不短路，继续扫下一个", 2, scans)
        assertEquals("角色表", meta.comment)
        assertFalse("有数据源真正扫过 → 聚合结果不算查不了", meta.scanBailed)
    }

    @Test
    fun `确认没有此表仍写负缓存_与查不了区分`() {
        var scans = 0
        val ds = Any()
        val notFound: (Any, String) -> TableMeta = { _, _ ->
            scans++
            TableMeta(null, emptyMap())
        }

        DatabaseSchemaResolver.resolveFromSources("t_absent", listOf(ds), notFound)
        val second = DatabaseSchemaResolver.resolveFromSources("t_absent", listOf(ds), notFound)
        assertEquals("真·未命中仍按负缓存只扫一次（T-MetaCheapSource 语义不变）", 1, scans)
        assertFalse(second.scanBailed)
    }
}
