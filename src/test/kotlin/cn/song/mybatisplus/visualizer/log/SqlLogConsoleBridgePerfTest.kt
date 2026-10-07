package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志捕捉性能优化测试（T-LogPerf-01/03/04，源自《性能分析 20260902-0054》优化建议）。
 *
 * 覆盖：快速轮询指数退避计算、descriptor 短路判定、processConsoleLine 配置快照语义
 * （hibernateEnabled 快照 + 逐行零 configProvider 依赖）。纯 JUnit，无平台服务依赖。
 */
class SqlLogConsoleBridgePerfTest {

    // ---------- T-LogPerf-01：快速轮询指数退避 ----------

    @Test
    fun `退避计算——起步500ms每拍翻倍2s封顶`() {
        var delay = SqlLogConsoleBridge.nextFastDelay(500, backoff = true)
        assertEquals(1000, delay)
        delay = SqlLogConsoleBridge.nextFastDelay(delay, backoff = true)
        assertEquals(2000, delay)
        // 已到上限后不再增长
        delay = SqlLogConsoleBridge.nextFastDelay(delay, backoff = true)
        assertEquals(2000, delay)
    }

    @Test
    fun `退避重置——检测到控制台回落起步间隔`() {
        assertEquals(500, SqlLogConsoleBridge.nextFastDelay(2000, backoff = false))
        assertEquals(500, SqlLogConsoleBridge.nextFastDelay(1000, backoff = false))
    }

    // ---------- T-LogPerf-03：descriptor 短路判定 ----------

    private class ConsoleStub

    @Test
    fun `短路判定——缓存命中的console已挂载则跳过`() {
        val console = ConsoleStub()
        assertTrue(SqlLogConsoleBridge.shouldSkipRescan(console, listOf(console)))
    }

    @Test
    fun `短路判定——未挂载或未知console不跳过`() {
        val console = ConsoleStub()
        // 未挂载（attachedConsoles 为空）
        assertFalse(SqlLogConsoleBridge.shouldSkipRescan(console, emptyList()))
        // 缓存为 null（上一轮未找到 console，如 HTTP 控制台/无组件）
        assertFalse(SqlLogConsoleBridge.shouldSkipRescan(null, listOf(console)))
        // 身份不同（不同 console 实例）——IdentityHashMap 语义
        assertFalse(SqlLogConsoleBridge.shouldSkipRescan(ConsoleStub(), listOf(console)))
    }

    // ---------- T-LogPerf-04：processConsoleLine 配置快照语义 ----------

    private fun newCtx(hibernateEnabled: Boolean): BridgeLineContext = BridgeLineContext(
        myBatisParser = MyBatisLogParser(sqlPrefix = "Preparing:", paramPrefix = "Parameters:", mixedPrefix = true, maxParseLength = 20000),
        hibernateParser = HibernateLogParser(sqlPrefix = "Hibernate:", paramPrefix = "binding parameter", mixedPrefix = true, maxParseLength = 20000),
        jdbcParser = JdbcTemplateLogParser(enabled = false, maxParseLength = 20000),
        configProvider = { ParserConfig("Preparing:", "Parameters:", hibernateEnabled, 20000) },
        hibernateEnabled = hibernateEnabled,
    )

    @Test
    fun `配置快照——hibernateEnabled=false时hibernate解析器不喂行`() {
        val ctx = newCtx(hibernateEnabled = false)
        processConsoleLine("Hibernate: SELECT * FROM user WHERE id=?", ctx, null, {})
        val entries = processConsoleLine("binding parameter [1] as [INTEGER] - [1]", ctx, null, {})
        // hibernate 解析器未被喂行 → 不产生条目
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `配置快照——逐行不再依赖configProvider`() {
        // configProvider 抛异常也不影响喂行（逐行零调用——T-LogPerf-04 核心断言）
        val ctx = BridgeLineContext(
            myBatisParser = MyBatisLogParser(sqlPrefix = "Preparing:", paramPrefix = "Parameters:", mixedPrefix = true, maxParseLength = 20000),
            hibernateParser = HibernateLogParser(sqlPrefix = "Hibernate:", paramPrefix = "binding parameter", mixedPrefix = true, maxParseLength = 20000),
            jdbcParser = JdbcTemplateLogParser(enabled = false, maxParseLength = 20000),
            configProvider = { error("per-line config sync should be gone") },
            hibernateEnabled = false,
        )
        processConsoleLine("==>  Preparing: SELECT * FROM user WHERE id = 1", ctx, null, {})
        val entries = processConsoleLine("==>  Parameters: 1(Long)", ctx, null, {})
        assertEquals(1, entries.size)
        assertEquals("SELECT * FROM user WHERE id = 1", entries.first().rawSql)
    }

    // ---------- F50（T-PerfQuick5）：反射 Method 缓存——CHM 禁 null 用 Optional + 键含 ClassLoader 身份 ----------

    /** 键含 ClassLoader 身份：同类名不同 loader 键必不同（跨 loader 撞键即键设计失效）；同 loader 键稳定（命中前提）。 */
    @Test
    fun `F50_记忆化键_含ClassLoader身份`() {
        val l1 = object : ClassLoader() {}
        val l2 = object : ClassLoader() {}
        assertTrue(
            "同签名类名跨 ClassLoader 的键必须不同",
            SqlLogConsoleBridge.methodCacheKey("com.example.Foo", l1, "getAllDescriptors") !=
                SqlLogConsoleBridge.methodCacheKey("com.example.Foo", l2, "getAllDescriptors"),
        )
        assertEquals(
            "同 loader 同类名同方法的键必须稳定",
            SqlLogConsoleBridge.methodCacheKey("com.example.Foo", l1, "getAllDescriptors"),
            SqlLogConsoleBridge.methodCacheKey("com.example.Foo", l1, "getAllDescriptors"),
        )
    }

    /**
     * 负缓存干净命中：缺失方法返回 null 且二次查询不抛。
     * 反向验证锚：修复前 `methodCache[key] = null` 对 CHM 是运行时 NPE（T-LogPerf-02 的
     * 「找不到也缓存 null」从未生效）——回退即本测试红。
     */
    @Test
    fun `F50_负缓存_干净命中不抛`() {
        val miss1 = SqlLogConsoleBridge.cachedMethod(ConsoleStub::class.java, "noSuchMethodForSure")
        assertNull("缺失方法应返回 null", miss1)
        val miss2 = SqlLogConsoleBridge.cachedMethod(ConsoleStub::class.java, "noSuchMethodForSure")
        assertNull("负缓存二次查询仍应干净返回 null", miss2)
        // 正路径：记忆化命中同一 Method
        val m1 = SqlLogConsoleBridge.cachedMethod(String::class.java, "length")
        val m2 = SqlLogConsoleBridge.cachedMethod(String::class.java, "length")
        assertEquals("正缓存应命中同一 Method", m1, m2)
    }

    // ---------- F57（T-PerfSweep18）：methodCache 容量上限——超限整体代际失效 ----------

    /**
     * 键空间无界防护：灌入超过 METHOD_CACHE_MAX 条互异键（负缓存条目同样占位），
     * 缓存必须整体代际失效回落到有界，且失效后查询语义不变（重付一次 getMethod 仍返回正确 Method）。
     * 反向验证锚：摘掉 cachedMethod 内的容量清空分支后，70 条互异键全数滞留 → 本测试红。
     */
    @Test
    fun `F57_容量上限_超限整体代际失效`() {
        // 预热一条正缓存（稍后验证失效后语义不变）
        val warm = SqlLogConsoleBridge.cachedMethod(String::class.java, "trim")
        assertTrue("预热条目应解析成功", warm != null)
        for (i in 0 until 70) {
            // 互异键：同类不同方法名（负缓存路径同样写入条目）
            SqlLogConsoleBridge.cachedMethod(ConsoleStub::class.java, "f57No$i")
        }
        assertTrue(
            "超限后代际失效，缓存必须回落到有界（实际 ${SqlLogConsoleBridge.methodCacheSize()}）",
            SqlLogConsoleBridge.methodCacheSize() <= 64,
        )
        // 失效不破坏语义：清空后重查同一方法仍返回等价 Method
        val again = SqlLogConsoleBridge.cachedMethod(String::class.java, "trim")
        assertEquals("代际失效后重查应仍解析同一 Method", warm, again)
    }

    // ---------- F58（T-PerfSweep18 压轴）：token 每行提一次下传四解析器 ----------

    /**
     * 桥路径（processConsoleLine＝token 提一次下传的落点）双线程交错各归各桶：
     * exec-9 与 exec-2 的 Preparing/Parameters 交错到达，参数必须各配各的语句。
     * 反向验证锚：token 下传被破坏（恒传 null）时，Parameters 行按「收集态桶优先」
     * 路由会挂错语句（exec-2 的参数闭合 exec-9 的语句）→ 参数配对断言红。
     */
    @Test
    fun `F58_token下传_桥路径双线程交错参数各归各语句`() {
        val ctx = newCtx(hibernateEnabled = false)
        assertTrue(
            processConsoleLine(
                "2026-09-16 14:34:59.938 DEBUG 36516 [exec-9] UserMapper.selectPage [] - ==>  Preparing: SELECT id FROM user WHERE id = ?",
                ctx, null, {},
            ).isEmpty(),
        )
        assertTrue(
            processConsoleLine(
                "2026-09-16 14:34:59.939 DEBUG 36516 [exec-2] UserMapper.selectPage_mpCount [] - ==>  Preparing: SELECT name FROM user WHERE id = ?",
                ctx, null, {},
            ).isEmpty(),
        )
        val exec2Entry = processConsoleLine(
            "2026-09-16 14:34:59.940 DEBUG 36516 [exec-2] UserMapper.selectPage_mpCount [] - ==> Parameters: 20(Long)",
            ctx, null, {},
        )
        val exec9Entry = processConsoleLine(
            "2026-09-16 14:34:59.941 DEBUG 36516 [exec-9] UserMapper.selectPage [] - ==> Parameters: 10(Long)",
            ctx, null, {},
        )
        assertEquals("exec-2 参数行闭合 exec-2 语句", 1, exec2Entry.size)
        assertEquals("SELECT name FROM user WHERE id = 20", exec2Entry[0].restoredSql)
        assertEquals("exec-9 参数行闭合 exec-9 语句", 1, exec9Entry.size)
        assertEquals("SELECT id FROM user WHERE id = 10", exec9Entry[0].restoredSql)
    }
}
