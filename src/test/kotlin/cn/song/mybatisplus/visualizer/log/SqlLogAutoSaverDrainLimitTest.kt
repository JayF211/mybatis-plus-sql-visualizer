package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T-PS4-13：close 排水 EDT 限批量 单元测试（纯 JUnit，无平台依赖）。
 *
 * 语义定案：
 *  - configure 路径（调用线程常为 EDT）close 单次同步排水上限 [SqlLogAutoSaver.DEFAULT_CONFIGURE_DRAIN_LIMIT]
 *    （默认 500，构造可注入），超出部分**留在队列不清空**（旧实现 close 末尾 clear 会丢余量），
 *    后续 submit → ensureExecutor 重建写线程由 drainAndWrite 一并补写；
 *  - dispose 路径（SqlLogManager.dispose → 无参 close）保持全量排水语义不变（终态不丢条）。
 *
 * 确定性手法：测试线程先持 saver 监视器再批量 submit（写任务被调度后阻塞在监视器上），
 * 监视器内重入调 configure——排水、余量断言均在无竞争时序下观察。
 */
class SqlLogAutoSaverDrainLimitTest {

    private fun tempDir(): String {
        val dir = Files.createTempDirectory("ps413-test-").toFile()
        dir.deleteOnExit()
        return dir.absolutePath
    }

    private fun entryOf(sql: String) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = 1724567890000L,
    )

    private fun blockCount(file: File): Int =
        if (file.exists()) file.readText().split("-- source:").size - 1 else 0

    private fun awaitBlockCount(file: File, expected: Int, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (blockCount(file) >= expected) return
            Thread.sleep(20)
        }
        throw AssertionError("等待超时：${file.name} 未达到 $expected 块（现有 ${blockCount(file)}）")
    }

    /** 监视器内制造 N 条积压并重入 configure（限批排水），返回 configure 后（已落盘块数, 队列余量）。 */
    private fun backlogAndConfigure(saver: SqlLogAutoSaver, dir: String, backlog: Int): Pair<Int, Int> {
        synchronized(saver) {
            repeat(backlog) { i -> saver.submit(entryOf("SELECT * FROM t WHERE id = $i")) }
            saver.configure(dir, "cap.sql", null) // 重入：限批排水，余量留队
            return blockCount(File(dir, "cap.sql")) to saver.pendingCount()
        }
    }

    @Test
    fun `configure排水限批_只写上限条_余量留队`() {
        val saver = SqlLogAutoSaver(configureDrainLimit = 100)
        val dir = tempDir()
        saver.configure(dir, "cap.sql", null)
        val (written, pending) = backlogAndConfigure(saver, dir, backlog = 250)
        assertEquals("限批排水只写上限 100 条", 100, written)
        assertEquals("余量 150 条留在队列未清空", 150, pending)
        val blocks = File(dir, "cap.sql").readText().split("-- source:").drop(1)
        assertTrue("首条为队首 id=0（FIFO 未乱序）", blocks.first().contains("id = 0"))
        assertTrue("第 100 条为 id=99", blocks[99].contains("id = 99"))
        saver.close()
    }

    @Test
    fun `余量经后续submit补写_全量落盘不丢`() {
        val saver = SqlLogAutoSaver(configureDrainLimit = 100)
        val dir = tempDir()
        saver.configure(dir, "cap.sql", null)
        val (written, _) = backlogAndConfigure(saver, dir, backlog = 250)
        assertEquals("前置：限批排水只写 100 条", 100, written)
        // 后续 submit → ensureExecutor 重建写线程 → drainAndWrite 补写余量 + 新条目
        saver.submit(entryOf("SELECT * FROM t WHERE id = final"))
        val file = File(dir, "cap.sql")
        awaitBlockCount(file, 251)
        assertEquals("250 + 1 条全部落盘（不丢不重）", 251, blockCount(file))
        assertEquals("队列清空", 0, saver.pendingCount())
        val blocks = file.readText().split("-- source:").drop(1)
        assertTrue("补写保持 FIFO：第 101 条为 id=100", blocks[100].contains("id = 100"))
        assertTrue("末条为 final", blocks[250].contains("id = final"))
        saver.close()
    }

    @Test
    fun `close无参全量排水_dispose语义余量全落盘`() {
        val saver = SqlLogAutoSaver(configureDrainLimit = 100)
        val dir = tempDir()
        saver.configure(dir, "cap.sql", null)
        backlogAndConfigure(saver, dir, backlog = 250)
        // dispose 等价路径：无参 close 不受 configureDrainLimit 限制，全量排水
        saver.close()
        assertEquals("dispose 全量排水：250 条全部落盘", 250, blockCount(File(dir, "cap.sql")))
        assertEquals("队列清空", 0, saver.pendingCount())
        saver.close()
    }

    @Test
    fun `configure耗时上界_限批后不随积压规模线性增长`() {
        val saver = SqlLogAutoSaver(configureDrainLimit = 100)
        val dir = tempDir()
        saver.configure(dir, "cap.sql", null)
        var elapsedMs = -1L
        var writtenAtConfigure = -1
        synchronized(saver) {
            repeat(2000) { i -> saver.submit(entryOf("SELECT * FROM big WHERE id = $i")) }
            val start = System.nanoTime()
            saver.configure(dir, "cap.sql", null)
            elapsedMs = (System.nanoTime() - start) / 1_000_000
            // 阻塞中的写任务仍拿不到监视器，此刻块数即 configure 排水产物（确定性观察点）
            writtenAtConfigure = blockCount(File(dir, "cap.sql"))
        }
        assertTrue("限批排水 100 条应远快于全量（实测 ${elapsedMs}ms）", elapsedMs < 5_000)
        assertEquals("只落盘上限条数", 100, writtenAtConfigure)
        saver.close()
    }

    @Test
    fun `排水上限默认500`() {
        assertEquals(500, SqlLogAutoSaver.DEFAULT_CONFIGURE_DRAIN_LIMIT)
        assertEquals(500, SqlLogAutoSaver().configureDrainLimit)
    }
}
