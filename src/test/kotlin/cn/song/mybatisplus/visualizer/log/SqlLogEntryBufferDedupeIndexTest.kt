package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SqlLogPerf P6（20261006-1020 计划）：去重索引等价性守卫。
 *
 * isDuplicate 从锁内 O(n) 全表扫描改 dedupeKey→最近时间戳的 O(1) 查表（65 条/秒并发灌入下
 * 三通道写线程与 EDT snapshot 抢同一把锁，扫描是热路径大头）。索引必须与旧全表扫描**逐字等价**——
 * 本文件每个用例对应一个索引维护面（入队 upsert / 头部淘汰回扫 / dedupeExisting 重建 /
 * dedupeEnabled 翻 true 重建 / clear 清空 / removeEntry 单键重算），断言全部按**旧语义可推演的
 * 期望值**写：任一维护面漏账，对应用例必红（摘掉修复行 → 必红，反向验证）。
 */
class SqlLogEntryBufferDedupeIndexTest {

    private fun shardingExecutions(): List<LogSqlEntry> {
        val parser = ShardingSphereLogParser()
        // UI013：真实 5.5.2/H2 fixture 的 100/101 日志原文；只显式闭合，不改变静默冲刷协议。
        listOf(
            "18:32:08.911 INFO  [main] ShardingSphere-SQL - Logic SQL: SELECT order_id, reference_code FROM ui_order WHERE order_id = ? AND reference_code = ?",
            "18:32:08.914 INFO  [main] ShardingSphere-SQL - Actual SQL: ds_0 ::: SELECT order_id, reference_code FROM ui_order_0 WHERE order_id = ? AND reference_code = ? ::: [100, BOOK,100]",
            "18:32:08.949 INFO  [main] ShardingSphere-SQL - Logic SQL: SELECT order_id, reference_code FROM ui_order WHERE order_id = ? AND reference_code = ?",
            "18:32:08.949 INFO  [main] ShardingSphere-SQL - Actual SQL: ds_1 ::: SELECT order_id, reference_code FROM ui_order_1 WHERE order_id = ? AND reference_code = ? ::: [101, BOOK,101]",
        ).forEach(parser::feed)
        parser.closePending()
        return parser.takeEntries().mapIndexed { i, entry -> entry.copy(timestamp = 1000L + i,
            captureProvenance = CaptureProvenance.CONSOLE_EP) }
    }

    @Test
    fun `UI013自动去重保留100与101每次原配对并逐次保存历史`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val stored = mutableListOf<LogSqlEntry>()
        var accepted = 0
        var merged = 0
        b.onEntryStored = { stored += it }
        b.onEntryAccepted = { accepted++ }
        b.onEntryMerged = { _, _ -> merged++ }
        val pair = shardingExecutions()
        assertEquals(pair[0].dedupeKey, pair[1].dedupeKey)
        pair.forEach { assertTrue(b.addEntry(it)) }
        // 即使原文也相同，仍不能证明同一次执行，不丢第三次配对。
        assertTrue(b.addEntry(pair[0].copy(timestamp = 1002)))
        val rows = b.snapshot()
        assertEquals(3, rows.size)
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.sequenceNumber })
        assertEquals(listOf(pair[0].actualSql, pair[1].actualSql, pair[0].actualSql), rows.map { it.actualSql })
        assertEquals(rows, stored)
        assertEquals(3, accepted)
        assertEquals(0, merged)
        assertTrue(b.dedupeCountsSnapshot().isEmpty())
        assertEquals(3, structureKeyCounts(rows)[pair[0].structureKey])
    }

    @Test
    fun `UI013手动裁剪与重建不丢Sharding且不豁免其他三族`() {
        val pair = shardingExecutions()
        for (source in listOf(SqlSource.MYBATIS, SqlSource.HIBERNATE, SqlSource.JDBC)) {
            val b = SqlLogEntryBuffer(maxEntries = 20)
            b.dedupeEnabled = false
            val plain = pair[0].copy(source = source, actualSql = null)
            listOf(pair[0], plain, pair[1], plain.copy(timestamp = 1003)).forEach { assertTrue(b.addEntry(it)) }
            b.dedupeEnabled = true
            assertEquals(1, b.dedupeExisting())
            assertEquals(2, b.snapshot().count { it.source == SqlSource.SHARDINGSPHERE })
            assertEquals(listOf(pair[0].actualSql, pair[1].actualSql),
                b.snapshot().filter { it.source == SqlSource.SHARDINGSPHERE }.map { it.actualSql })
            assertFalse(b.addEntry(plain.copy(timestamp = 1004)))
            assertTrue(b.addEntry(pair[1].copy(timestamp = 1005)))
            b.rebuildDedupeIndexAfterHandoff(nowMs = 1006)
            assertTrue(b.addEntry(pair[0].copy(timestamp = 1007)))
            assertFalse(b.addEntry(plain.copy(timestamp = 1008)))
        }
    }

    @Test
    fun `UI013跨通道同键不吞Sharding也不污染其他三族融合`() {
        for (source in listOf(SqlSource.MYBATIS, SqlSource.HIBERNATE, SqlSource.JDBC)) {
            for (shardingFirst in listOf(true, false)) {
                val b = SqlLogEntryBuffer(maxEntries = 10)
                val sharding = shardingExecutions().first()
                val bridge = sharding.copy(source = source, actualSql = null)
                val agent = bridge.copy(timestamp = 1002, captureProvenance = CaptureProvenance.AGENT,
                    sourceFile = "OrderService.java", sourceLine = 42, executionTimeMs = 7)
                val firstTwo = if (shardingFirst) listOf(sharding, bridge) else listOf(bridge, sharding)
                firstTwo.forEach { assertTrue(b.addEntry(it)) }
                assertFalse(b.addEntry(agent))
                val rows = b.snapshot()
                assertEquals(2, rows.size)
                assertEquals(sharding.actualSql, rows.single { it.source == SqlSource.SHARDINGSPHERE }.actualSql)
                val merged = rows.single { it.source == source }
                assertEquals(CaptureProvenance.MERGED, merged.captureProvenance)
                assertEquals("OrderService.java", merged.sourceFile)
                assertEquals(42, merged.sourceLine)
                assertEquals(7L, merged.executionTimeMs)
                // 重开/删除/淘汰回扫不得把 Sharding 放回索引，吞掉下一次其他族观察。
                b.dedupeEnabled = false
                b.dedupeEnabled = true
                b.removeEntry(merged)
                assertTrue(b.addEntry(agent.copy(timestamp = 1003)))
                assertEquals(1, b.dedupeCount(bridge.restoredSql))
            }
        }
    }

    @Test
    fun `UI013淘汰回扫不把Sharding重新装进其他来源索引`() {
        val sharding = shardingExecutions().first()
        val normal = sharding.copy(source = SqlSource.JDBC, actualSql = null)
        val b = SqlLogEntryBuffer(maxEntries = 2)
        assertTrue(b.addEntry(normal))
        assertTrue(b.addEntry(sharding.copy(timestamp = 1001)))
        assertTrue(b.addEntry(entry("SELECT unrelated", 1002))) // 淘汰 normal，同键只剩 Sharding。
        assertTrue(b.addEntry(normal.copy(timestamp = 1003)))
        assertFalse(b.addEntry(normal.copy(timestamp = 1004)))
    }

    @Test
    fun `UI013缺失Actual也不据Logic推定重复`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val missing = shardingExecutions().first().copy(actualSql = null)
        assertTrue(b.addEntry(missing))
        assertTrue(b.addEntry(missing.copy(timestamp = 1002)))
        assertEquals(0, b.dedupeExisting())
        assertEquals(2, b.size())
    }

    /** 窗口内同键重复必丢且计数；超窗重入必收（索引 upsert 面 + 窗口语义基线）。 */
    @Test
    fun `窗口内重复丢弃计数_超窗重入允许`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM dup WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        assertFalse("窗口内重复应丢弃", b.addEntry(entry(sql, 30_000)))
        assertEquals("丢弃计数 1", 1, b.dedupeCount(sql))
        assertTrue("超窗（70s > 60s 窗口）重入应允许", b.addEntry(entry(sql, 70_000)))
        assertFalse("重入后再来窗口内重复应丢弃", b.addEntry(entry(sql, 100_000)))
        assertEquals("两次丢弃计数累计 2", 2, b.dedupeCount(sql))
    }

    /** 头部淘汰回扫面：被淘汰者非该键最新时，索引不得误删/误留旧时间戳。 */
    @Test
    fun `淘汰非最新同键条目_索引保持最近时间戳`() {
        val b = SqlLogEntryBuffer(maxEntries = 2)
        val sql = "SELECT * FROM evict WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        assertTrue("超窗重入", b.addEntry(entry(sql, 70_000)))
        assertTrue(b.addEntry(entry("SELECT * FROM other WHERE id = 9", 71_000))) // 挤掉 A@0（头部）
        assertEquals("容量 2：A@0 被淘汰", 2, b.snapshot().size)
        // 索引若误留 A@0（未回扫）→ 100-0=100s 超窗被收；正确基线（队内 A@70s）→ 30s < 60s 丢弃
        assertFalse("淘汰后索引应保持队内最近时间戳 A@70s（新条目落窗口内必丢）", b.addEntry(entry(sql, 100_000)))
    }

    /** dedupeExisting 重建面：手动裁剪只留最早一条，索引必须随队重建（不得留被裁掉的最新时间戳）。 */
    @Test
    fun `手动去重裁剪后_索引随队重建`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM manual WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        assertTrue("超窗重入", b.addEntry(entry(sql, 70_000)))
        assertEquals("裁剪掉后到者", 1, b.dedupeExisting())
        assertEquals("队内只剩最早一条", 0, b.snapshot().first().timestamp)
        // 索引若误留被裁掉的 A@70s → 100-70=30s < 60s 被丢；正确基线（队内 A@0）→ 100s 超窗必收
        assertTrue("裁剪后索引应回到队内时间戳 A@0（超窗新条目必收）", b.addEntry(entry(sql, 100_000)))
    }

    /** dedupeEnabled 翻 true 重建面：关闭期间入队不维护索引，重开必须全量补账。 */
    @Test
    fun `去重开关重开_索引全量补账`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM toggle WHERE id = 1"
        b.dedupeEnabled = true
        assertTrue(b.addEntry(entry(sql, 0)))
        b.dedupeEnabled = false
        assertTrue("关闭期间不去重", b.addEntry(entry(sql, 70_000)))
        b.dedupeEnabled = true
        // 索引若未补账（仍 A@0）→ 80-0=80s 超窗被收；正确基线（队内 A@70s）→ 10s < 60s 必丢
        assertFalse("重开后索引应补账到队内最近 A@70s（窗口内必丢）", b.addEntry(entry(sql, 80_000)))
    }

    /** clear 清空面：索引随队清空，清空后同键立即重入必收。 */
    @Test
    fun `清空后索引随队清空_同键立即重入允许`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM wipe WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        b.clear()
        assertTrue("清空后同键重入应允许（索引不得残留旧时间戳）", b.addEntry(entry(sql, 10_000)))
    }

    /** removeEntry 单键重算面：删除该键最新条目后，索引必须回到队内余条的时间戳。 */
    @Test
    fun `删除单条后_索引回扫队内余条`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM remove WHERE id = 1"
        b.dedupeEnabled = false // 先关去重装两个同键条目（超窗形态）
        b.addEntry(entry(sql, 0))
        b.addEntry(entry(sql, 70_000))
        b.dedupeEnabled = true
        // removeEntry 按同一性删除（buffer 入库的是带序号 copy，外部须持快照实例——SqlLogEntryBufferTest 同口径）
        val storedSecond = b.snapshot().last { it.timestamp == 70_000L }
        b.removeEntry(storedSecond)
        // 索引若误留被删的 A@70s → 80-70=10s < 60s 被丢；正确基线（队内 A@0）→ 80s 超窗必收
        assertTrue("删除最新同键后索引应回扫到队内 A@0（超窗新条目必收）", b.addEntry(entry(sql, 80_000)))
    }

    /** 等价性基线的锚定断言：窗口语义逐字不变（now - latest < window，边界等值不丢）。 */
    @Test
    fun `窗口边界等值不丢弃`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM edge WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        assertTrue("now-latest == window（60s）不小于窗口 → 不丢", b.addEntry(entry(sql, 60_000)))
    }

    // ---------- T-AttachZeroEvent P3 + O-147 W2-补：接管沿的通道纪元重建面 ----------

    /**
     * 接管沿重建面（陈旧键）：桥纪元旧索引不得吞 agent 纪元首批同键事件。
     * 12:54 现场：接管前 60s 内桥刚捕过同端点，接管后同 SQL 的 agent 事件全落窗口内被当重复
     * 丢弃（面板零新条目、「attach 即生效」看似失效）——接管沿重建后必须收。
     * 反向验证锚点：摘掉方法体的索引重建（或摘掉 AgentManager.updateOnline 的接线）本用例必红。
     */
    @Test
    fun `接管沿重建_陈旧桥纪元键清掉_同键首批不吞`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM handoff WHERE id = 1"
        assertTrue("桥纪元：接管前同键条目在队", b.addEntry(entry(sql, 0)))
        // 接管沿（SqlCaptureGate.paused false→true，生产由 updateOnline 接线）：now=100_000、grace=5s
        // → ts=0 属陈旧键，清掉
        b.rebuildDedupeIndexAfterHandoff(graceMs = 5_000, nowMs = 100_000)
        assertTrue("接管沿后同键首批事件不得被陈旧索引吞（5s << 60s 窗口）", b.addEntry(entry(sql, 100_005)))
    }

    /** 同面守卫之二：agent↔agent 短窗重复仍被折——重建只豁免纪元切换那一瞬，不豁免新纪元内部。 */
    @Test
    fun `接管沿重建后_新纪元内部短窗重复仍折叠`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM handoff2 WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0))) // 桥纪元（陈旧）
        b.rebuildDedupeIndexAfterHandoff(graceMs = 5_000, nowMs = 100_000)
        assertTrue(b.addEntry(entry(sql, 100_005))) // agent 纪元首批（陈旧键已清 → 照常入队）
        assertFalse("agent 纪元内部短窗重复照常折叠", b.addEntry(entry(sql, 100_008)))
        assertEquals("折叠计数照常累加", 1, b.dedupeCount(sql))
        assertEquals("条目不被重建波及（清索引不清条目）", 2, b.snapshot().size)
    }

    /**
     * O-147 W2-补（2026-10-14 真机复验）：**新鲜键保留**——接管沿前几毫秒入库的控制台副本（实测 6ms
     * 窗口）必须在索引里存活，agent 副本到达时按融合升级，而不是新开一行（那正是用户看到的
     * 「一行带源码、一行无源码」）。
     * 反向验证锚点：把 [SqlLogEntryBuffer.rebuildDedupeIndexAfterHandoff] 的 grace 置 0（等价旧「全清」）
     * 本用例必红（rows.size == 2）。
     */
    @Test
    fun `接管沿重建_新鲜键保留_同一次执行融合为一行`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM handoffFresh WHERE id = 1"
        assertTrue("桥纪元：接管沿前毫秒级入库的控制台副本（无源码）", b.addEntry(entry(sql, 100_000)))
        // 接管沿：now=100_010、grace=5s → ts=100_000 ≥ 95_010，保留
        b.rebuildDedupeIndexAfterHandoff(graceMs = 5_000, nowMs = 100_010)
        val agentCopy = LogSqlEntry(
            rawSql = sql,
            restoredSql = sql,
            params = emptyList(),
            timestamp = 100_030,
            executionTimeMs = 3,
            sourceFile = "a/A.java",
            sourceLine = 7,
            captureProvenance = CaptureProvenance.AGENT,
        )
        assertFalse("同一次执行的 agent 副本应融合（不新开行）", b.addEntry(agentCopy))
        val rows = b.snapshot().filter { it.restoredSql == sql }
        assertEquals("同一次执行只留一行", 1, rows.size)
        assertEquals("且该行升级为带源码（可跳转）", "a/A.java", rows.single().sourceFile)
    }

    // ---------- T-PerfSweep26-01（F83）：淘汰回扫键级计数化 ----------

    /**
     * F83 用例①（B 案写错即红）：同键多条在队，淘汰恰为索引最新的队首实例后，索引必须重指队内次新——
     * 计数结构少加账/多减账把「队内仍有同键余条」误判成「唯一键」时，索引被误摘，本用例红。
     */
    @Test
    fun `同键多条在队_淘汰最新_索引重指次新`() {
        val b = SqlLogEntryBuffer(maxEntries = 2)
        val sql = "SELECT * FROM rescan WHERE id = 1"
        b.dedupeEnabled = false
        b.addEntry(entry(sql, 70_000))
        b.addEntry(entry(sql, 0)) // 入队序 [A@70s, A@0]：索引最新（A@70s）落在队首
        b.dedupeEnabled = true // 翻 true 全量重建：索引指向队内最新 A@70s
        assertTrue(b.addEntry(entry("SELECT * FROM rescan WHERE id = 2", 500))) // 挤掉队首 A@70s
        // 索引若未重指队内次新 A@0（被误摘）→ 30s 相对空索引必收（红）；正确基线（A@0）→ 30s < 60s 必丢
        assertFalse("淘汰最新后索引应重指队内次新 A@0（窗口内必丢）", b.addEntry(entry(sql, 30_000)))
    }

    /**
     * F83 用例②（修复前必红）：唯一键稳态淘汰不得触发全队反扫——唯一键下每接受一条新 SQL 都在
     * synchronized(entries) 内付一次 O(maxEntries) 反扫且结果必空。断言走 [SqlLogEntryBuffer.evictionRescanCountForTest]
     * 计数缝（测试专用读数，V38 计数缝同款形态）。
     */
    @Test
    fun `唯一键稳态淘汰_零全队反扫`() {
        val b = SqlLogEntryBuffer(maxEntries = 3)
        b.addEntry(entry("SELECT * FROM stable1 WHERE id = 1", 0))
        b.addEntry(entry("SELECT * FROM stable2 WHERE id = 2", 1))
        b.addEntry(entry("SELECT * FROM stable3 WHERE id = 3", 2))
        val before = b.evictionRescanCountForTest
        b.addEntry(entry("SELECT * FROM stable4 WHERE id = 4", 3)) // 淘汰 stable1：唯一键稳态
        assertEquals("唯一键淘汰不得触发全队反扫", before, b.evictionRescanCountForTest)
    }

    /**
     * F83 交互面：接管沿重建（[SqlLogEntryBuffer.rebuildDedupeIndexAfterHandoff]）只把陈旧键摘出索引、
     * 条目仍留队内——计数结构重建时必须按「队内现存」全量补账（不看 grace 截断），否则淘汰该陈旧
     * 在队实例时计数被减成 0 走「唯一键」短路，把仍新鲜的索引误摘（短窗重复漏折叠）。
     */
    @Test
    fun `接管沿重建后_陈旧在队条目淘汰_不误摘新鲜键索引`() {
        val b = SqlLogEntryBuffer(maxEntries = 2)
        val sql = "SELECT * FROM handoffCount WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0))) // 陈旧（ts=0）
        b.rebuildDedupeIndexAfterHandoff(graceMs = 5_000, nowMs = 100_000) // 索引清掉陈旧键，条目仍在队
        assertTrue("接管沿后首批照常入队", b.addEntry(entry(sql, 100_005))) // 队内 [A@0, A@100.005s]，索引→A@100.005s
        assertTrue(b.addEntry(entry("SELECT * FROM handoffCount WHERE id = 2", 100_006))) // 挤掉队首 A@0（在队但不在索引）
        // 计数若漏掉 A@0（重建只统计新鲜键）→ 淘汰 A@0 时 remaining==0 误摘索引 → 下条必收（红）
        assertFalse("淘汰不在索引的陈旧在队条目后，新鲜键索引须保留（短窗重复必丢）",
            b.addEntry(entry(sql, 100_007)))
    }

    private fun entry(sql: String, ts: Long) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = ts,
    )
}
