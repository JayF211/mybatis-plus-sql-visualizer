package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.structureFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R54（O-108，第十六轮 §6；V81 三前提判别）：历史耗时回填匹配键两级化——exact 命中优先
 * （原全文精确匹配，现行为零回退），未命中改比 [LogSqlEntry.structureKey] 取同键**中位数**
 * 兜底。
 *
 * O-108 现场（第十六轮报告 §4）：10.6 起预览默认空参填 `?`（PluginSettings 空参默认），
 * `?` 版预览 SQL 对实值版日志 SQL 全文精确匹配**必失配**——历史耗时列在默认配置下恒空。
 *
 * V81①（候选成立性前提）：[structureFingerprint] 对 `?` 占位与实值**归一同 token**——
 * 字符串字面量与裸数字折叠为 `?`、而 `?` 本身逐字保留（parameterizeLiterals else 分支），
 * 同形状同指纹。本测试组直接钉住该前提：若将来口径改变（`?` 折成别的 token / 数字不再
 * 折叠），这里先红，兜底链随之失效须重估。
 *
 * V81③：同模板循环 N 次时首条可能是冷启动离群值——中位数取样（无耗时条目不参与）。
 */
class HistoryExecutionTimeBackfillTest {

    private fun entry(sql: String, time: Long? = null): LogSqlEntry =
        LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList(), executionTimeMs = time)

    // ------------------------------------------------------------------
    // V81①：占位与实值归一（候选成立性——若红则 R54 候选不成立须回案）
    // ------------------------------------------------------------------

    @Test
    fun testR54_V81a_占位符与数字实值同结构指纹() {
        assertEquals(
            "`?` 逐字保留＋裸数字折叠为 `?` → 同指纹（V81① 前提）",
            structureFingerprint("SELECT * FROM t WHERE a = ? AND b = ?"),
            structureFingerprint("SELECT * FROM t WHERE a = 42 AND b = 7"),
        )
    }

    @Test
    fun testR54_V81b_占位符与字符串实值同结构指纹() {
        assertEquals(
            "字符串字面量（含 '' 转义）整体折叠为 `?` → 同指纹（V81① 前提）",
            structureFingerprint("UPDATE t SET n = ? WHERE k = ?"),
            structureFingerprint("UPDATE t SET n = 'x''y' WHERE k = 'z'"),
        )
    }

    // ------------------------------------------------------------------
    // O-108 判别：`?` 版预览对实值日志的兜底回填（先红）
    // ------------------------------------------------------------------

    @Test
    fun testR54_占位版预览_实值日志同模板兜底回填() {
        val entries = listOf(
            entry("SELECT * FROM t WHERE a = 1", 10L),
            entry("SELECT * FROM t WHERE a = 2", 30L),
            entry("SELECT * FROM t WHERE a = 3", 90L),
        )
        assertEquals(
            "O-108：`?` 版预览对实值日志全文必失配，结构指纹兜底取同键中位数（修复前 null → 红）",
            30L,
            SqlPreviewService.backfillExecutionTimeMs(entries, "SELECT * FROM t WHERE a = ?"),
        )
    }

    @Test
    fun testR54_精确命中优先_不走兜底() {
        val entries = listOf(
            entry("SELECT * FROM t WHERE a = 7", 10L), // 结构同键但非精确
            entry("SELECT * FROM t WHERE a = 1", 99L), // 精确命中
        )
        assertEquals(
            "exact 命中优先（现行为零回退，兜底只在未命中时接手）",
            99L,
            SqlPreviewService.backfillExecutionTimeMs(entries, "SELECT * FROM t WHERE a = 1"),
        )
    }

    @Test
    fun testR54_偶数条中位数_取中间均值() {
        val entries = listOf(
            entry("SELECT * FROM t WHERE a = 1", 10L),
            entry("SELECT * FROM t WHERE a = 2", 101L),
        )
        assertEquals("偶数条取中间两值均值（10+101)/2", 55L,
            SqlPreviewService.backfillExecutionTimeMs(entries, "SELECT * FROM t WHERE a = ?"))
    }

    @Test
    fun testR54_无耗时条目不参与取样() {
        val entries = listOf(
            entry("SELECT * FROM t WHERE a = 1", null),
            entry("SELECT * FROM t WHERE a = 2", 40L),
        )
        assertEquals("executionTimeMs=null 的同键条目不进中位数取样", 40L,
            SqlPreviewService.backfillExecutionTimeMs(entries, "SELECT * FROM t WHERE a = ?"))
    }

    @Test
    fun testR54_精确命中但无耗时_落兜底() {
        val entries = listOf(
            entry("SELECT * FROM t WHERE a = ?", null), // 精确命中但无耗时（未还原条目常态）
            entry("SELECT * FROM t WHERE a = 2", 40L),
        )
        assertEquals(
            "精确命中条目无耗时值＝未取得耗时，同落结构兜底（同为 O-108 空转面）",
            40L,
            SqlPreviewService.backfillExecutionTimeMs(entries, "SELECT * FROM t WHERE a = ?"),
        )
    }

    @Test
    fun testR54_异形模板不兜底() {
        val entries = listOf(entry("SELECT * FROM t WHERE b = 1", 10L))
        assertNull(
            "列名不同＝异形（不同 structureKey），不命中兜底",
            SqlPreviewService.backfillExecutionTimeMs(entries, "SELECT * FROM t WHERE a = ?"),
        )
    }

    @Test
    fun testR54_全部无耗时_回填null() {
        val entries = listOf(entry("SELECT * FROM t WHERE a = 1", null))
        assertNull(
            "同键全部无耗时 → 无代表值可取 → null",
            SqlPreviewService.backfillExecutionTimeMs(entries, "SELECT * FROM t WHERE a = ?"),
        )
    }

    @Test
    fun testR54_空日志清单_null() {
        assertNull(SqlPreviewService.backfillExecutionTimeMs(emptyList(), "SELECT * FROM t WHERE a = ?"))
    }
}
