package cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JOIN 建边日志探针闸门测试（T-PerfSweep6 T-PS6-07 / 性能报告发现 7）。
 *
 * 缺陷面：N6 后 `JoinVirtualFkProvider.getForeignKeys` **每表**一行 INFO，建图时刷出 O(表数) 行
 * idea.log。改为「本次建图首行 INFO + 其余 DEBUG」，复位信号来自每次建图的
 * `NativeDatabaseDiagramSupport.registerJoinForeignKeys`。
 *
 * 本类只测**闸门本身**（纯 JVM 对象）：真实 INFO/DEBUG 行的形态断言需要 database 插件环境
 * （`JoinVirtualFkProvider` 实现 database 插件接口，plain `test` 分片加载不了它），
 * 故日志形态按计划 §五 走查项 7 留真机。**「每图恰一行」的语义在此锁定**：
 * 一次建图内 `claimFirstLine()` 只对第一个调用者为真，`reset()` 后重新可领。
 *
 * k2j 适配：JoinVirtualFkProbe 是 JoinVirtualFkProvider.java 里的 package-private 顶层类，
 * 本测试同包直访（与生产侧实际形态一致）。
 */
class JoinVirtualFkProbeTest {

    @Test
    fun `一次建图内首行只给一个调用者`() {
        JoinVirtualFkProbe.reset()
        assertTrue("建图首行应打 INFO（EP 被触达的二分判据）", JoinVirtualFkProbe.claimFirstLine())
        repeat(5) {
            assertFalse(
                "同一图内第 ${it + 2} 次回调必须降级为 DEBUG（否则仍是 O(表数) 行 INFO）",
                JoinVirtualFkProbe.claimFirstLine(),
            )
        }
        assertTrue("首行已被占用", JoinVirtualFkProbe.firstLineClaimedForTest())
    }

    @Test
    fun `每次建图复位后重新可领首行`() {
        JoinVirtualFkProbe.reset()
        assertTrue(JoinVirtualFkProbe.claimFirstLine())
        assertFalse(JoinVirtualFkProbe.claimFirstLine())

        // 第二次建图（registerJoinForeignKeys 会调 reset）
        JoinVirtualFkProbe.reset()
        assertFalse("复位后应可重新领首行", !JoinVirtualFkProbe.claimFirstLine())
        assertFalse("新图内第二次仍为 DEBUG", JoinVirtualFkProbe.claimFirstLine())
    }

    @Test
    fun `并发领首行恰有一个胜者`() {
        // 建图链可能并发回调（平台建边分片）——首行闸门必须原子，否则同一图打出多行 INFO
        repeat(200) {
            JoinVirtualFkProbe.reset()
            val winners = java.util.concurrent.atomic.AtomicInteger(0)
            val threads = (0 until 4).map { idx ->
                Thread({
                    if (JoinVirtualFkProbe.claimFirstLine()) winners.incrementAndGet()
                }, "fk-probe-$idx").apply { isDaemon = true; start() }
            }
            threads.forEach { it.join(5_000) }
            assertTrue("每轮恰一个线程领到首行，实际 $winners", winners.get() == 1)
        }
    }
}
