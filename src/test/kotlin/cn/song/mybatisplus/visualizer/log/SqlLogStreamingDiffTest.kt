package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import cn.song.mybatisplus.visualizer.log.SqlLogPanelKt.MAX_SYNC_APPEND
import cn.song.mybatisplus.visualizer.log.SqlLogPanelKt.VisibleDiff
import cn.song.mybatisplus.visualizer.log.SqlLogPanelKt.diffVisible
import org.junit.Test

/**
 * T-Perf-03 流式增量刷新差分纯逻辑测试（自 dev 移植）：
 * 缓冲尾部追加 / 头部淘汰+追加 / 清空 / 过滤离散增删 各路径的差分决策。
 */
class SqlLogStreamingDiffTest {

    private fun entry(sql: String) = LogSqlEntry(
        sql, sql, emptyList(), 1_700_000_000_000L,
    )

    private fun list(vararg sqls: String) = sqls.map { entry(it) }

    @Test
    fun test空列表纯追加_返回Insert尾部() {
        val new = list("SELECT 1", "SELECT 2")
        val diff = diffVisible(emptyList(), new, 0)
        assertEquals(VisibleDiff.Insert(0, new), diff)
    }

    @Test
    fun test尾部追加_返回Insert() {
        val old = list("A", "B", "C")
        val new = old + list("D", "E")
        val diff = diffVisible(old, new, 0)
        assertEquals(VisibleDiff.Insert(3, new.drop(3)), diff)
    }

    @Test
    fun test头部淘汰加追加_返回HeadEvictAppend() {
        // 缓冲满后流式 steady state：头部淘汰 a、b，尾部追加 e、f
        // （new 的 c、d 必须复用 old 的同一实例——差分按元素同一性对齐）
        val old = list("a", "b", "c", "d")
        val new = old.drop(2) + list("e", "f")
        val diff = diffVisible(old, new, 2)
        assertEquals(VisibleDiff.HeadEvictAppend(2, new.drop(2)), diff)
    }

    @Test
    fun test头部淘汰对齐不成立_回退全量重建() {
        // evicted=1 但 old 去头后与 new 不成前缀（中间还有移除）——不可增量
        val old = list("a", "b", "c")
        val new = list("c", "e")
        val diff = diffVisible(old, new, 1)
        assertEquals(VisibleDiff.FullRebuild.INSTANCE, diff)
    }

    @Test
    fun test无变化_返回Same() {
        val old = list("a", "b", "c")
        assertEquals(VisibleDiff.Same.INSTANCE, diffVisible(old, old, 0))
    }

    @Test
    fun test清空_返回尾部纯移除() {
        val old = list("a", "b", "c")
        val diff = diffVisible(old, emptyList(), 0)
        assertEquals(VisibleDiff.TailRemove(0), diff)
    }

    @Test
    fun test中部离散移除_回退全量重建() {
        // 去重裁剪移除中间条目：前缀与后缀对齐但中间缺失——无法增量
        val old = list("a", "b", "dup", "c")
        val new = list("a", "b", "c")
        val diff = diffVisible(old, new, 0)
        assertEquals(VisibleDiff.FullRebuild.INSTANCE, diff)
    }

    @Test
    fun test排序变化导致离散重排_回退全量重建() {
        val old = list("a", "b", "c")
        val new = list("c", "a", "b")
        val diff = diffVisible(old, new, 0)
        assertEquals(VisibleDiff.FullRebuild.INSTANCE, diff)
    }

    @Test
    fun test超大同步追加_回退全量重建走分批链() {
        val old = emptyList<LogSqlEntry>()
        val new = List(MAX_SYNC_APPEND + 1) { entry("S$it") }
        val diff = diffVisible(old, new, 0)
        assertEquals(VisibleDiff.FullRebuild.INSTANCE, diff)
    }

    @Test
    fun test同字段不同实例_按同一性视为不同() {
        // 数据类 equals 相同但非同一实例：diff 按同一性判定——不可增量（防同值不同源误对齐）
        val old = list("a", "b")
        val new = list("a", "b") // 新建的同内容实例
        val diff = diffVisible(old, new, 0)
        assertTrue("非同一实例应回退全量重建", diff is VisibleDiff.FullRebuild)
    }
}
