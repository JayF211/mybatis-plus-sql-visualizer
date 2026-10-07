package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-05：[ParamHistoryCache] 纯逻辑单测（LRU + fingerprint + 持久化往返）。
 *
 * 纯逻辑、无 IDE 依赖（PluginSettings.getInstance() 在测试环境返回默认 State），
 * 轻量 JUnit 4 测试即可。
 */
class ParamHistoryCacheTest {

    // ------------------------------------------------------------------
    // save + recall 往返
    // ------------------------------------------------------------------

    @Test
    fun testSaveAndRecallRoundTrip() {
        val cache = ParamHistoryCache()
        cache.clear()
        val key = cache.computeKey("user", "SELECT * FROM user WHERE id = ?")
        assertNotNull(key)
        cache.save(key!!, mapOf("id" to "123", "name" to "张三"))
        val recalled = cache.recall(key)
        assertNotNull("应命中缓存", recalled)
        assertEquals(mapOf("id" to "123", "name" to "张三"), recalled)
    }

    @Test
    fun testRecallMissReturnsNull() {
        val cache = ParamHistoryCache()
        cache.clear()
        val key = cache.computeKey("user", "SELECT * FROM user")
        assertNull("未保存的 key 应返回 null", cache.recall(key!!))
    }

    // ------------------------------------------------------------------
    // LRU 淘汰
    // ------------------------------------------------------------------

    @Test
    fun testLruEvictionOverMaxSize() {
        // 小上限便于测试淘汰（上限 3）
        val cache = ParamHistoryCache(maxSize = 3)
        cache.clear()
        val sql = "SELECT * FROM tbl WHERE id = ?"
        // 存入 4 条（超过上限 3）
        repeat(4) { i ->
            val key = "tbl:$i"
            cache.save(key, mapOf("id" to "$i"))
        }
        assertEquals("超过上限后应淘汰到 maxSize", 3, cache.size())
        // 最久未访问的第 0 条应被淘汰
        assertNull("最早条目应被淘汰", cache.recall("tbl:0"))
        // 第 1/2/3 条仍在
        assertNotNull(cache.recall("tbl:1"))
        assertNotNull(cache.recall("tbl:2"))
        assertNotNull(cache.recall("tbl:3"))
    }

    @Test
    fun testLruAccessOrderUpdate() {
        val cache = ParamHistoryCache(maxSize = 3)
        cache.clear()
        repeat(3) { i -> cache.save("tbl:$i", mapOf("id" to "$i")) }
        // 访问第 0 条（最近访问 → 排到尾部，不再是最久未访问）
        cache.recall("tbl:0")
        // 插入第 4 条 → 淘汰最久未访问的第 1 条（第 0 条刚被访问过）
        cache.save("tbl:3", mapOf("id" to "3"))
        assertNotNull("最近访问的第 0 条应保留", cache.recall("tbl:0"))
        assertNull("最久未访问的第 1 条应被淘汰", cache.recall("tbl:1"))
    }

    // ------------------------------------------------------------------
    // computeKey 一致性
    // ------------------------------------------------------------------

    @Test
    fun testComputeKeyConsistency() {
        val cache = ParamHistoryCache()
        cache.clear()
        val key1 = cache.computeKey("user", "SELECT * FROM user WHERE id = ?")
        val key2 = cache.computeKey("user", "SELECT * FROM user WHERE id = ?")
        assertEquals("相同 tableName+rawSql 应生成相同 key", key1, key2)
    }

    @Test
    fun testComputeKeyDifferentSql() {
        val cache = ParamHistoryCache()
        cache.clear()
        val key1 = cache.computeKey("user", "SELECT * FROM user WHERE id = ?")
        val key2 = cache.computeKey("user", "SELECT * FROM user WHERE name = ?")
        assertFalse("不同 SQL 应生成不同 key", key1 == key2)
    }

    @Test
    fun testComputeKeyNullTableNameReturnsNull() {
        // R22 T-01（D1=A）契约变更：无表名不再返回 null——以 SQL 文本规范化指纹为键持久化
        // （缺口①修复；旧契约「无表名不缓存」随纯文本预览参数全丢的缺陷一并废除）。
        val cache = ParamHistoryCache()
        cache.clear()
        assertNotNull("无表名应以规范化指纹为键（R22 契约）", cache.computeKey(null, "SELECT 1"))
        assertNotNull("空表名同上", cache.computeKey("", "SELECT 1"))
        assertTrue(cache.computeKey(null, "SELECT 1")!!.startsWith("sql:"))
    }

    // ------------------------------------------------------------------
    // fingerprint 稳定性
    // ------------------------------------------------------------------

    @Test
    fun testFingerprintStability() {
        val sql = "SELECT * FROM user WHERE id = ?"
        val fp1 = ParamHistoryCache.fingerprint(sql)
        val fp2 = ParamHistoryCache.fingerprint(sql)
        assertEquals("相同 SQL → 相同指纹", fp1, fp2)
        assertEquals("指纹应为 16 hex 字符", 16, fp1.length)
    }

    @Test
    fun testFingerprintDifferentSql() {
        val fp1 = ParamHistoryCache.fingerprint("SELECT * FROM user")
        val fp2 = ParamHistoryCache.fingerprint("SELECT * FROM order")
        assertFalse("不同 SQL → 不同指纹", fp1 == fp2)
    }

    // ------------------------------------------------------------------
    // clear 清空
    // ------------------------------------------------------------------

    @Test
    fun testClearEmptiesCache() {
        val cache = ParamHistoryCache()
        cache.clear()
        val key = cache.computeKey("user", "SELECT * FROM user")
        cache.save(key!!, mapOf("id" to "1"))
        assertEquals(1, cache.size())
        cache.clear()
        assertEquals("clear 后应为空", 0, cache.size())
        assertNull("clear 后 recall 返回 null", cache.recall(key))
    }

    // ------------------------------------------------------------------
    // 持久化往返
    // ------------------------------------------------------------------

    @Test
    fun testPersistenceRoundTrip() {
        val cache1 = ParamHistoryCache()
        cache1.clear()
        val key = cache1.computeKey("user", "SELECT * FROM user WHERE id = ?")
        cache1.save(key!!, mapOf("id" to "42", "status" to "active"))
        // 模拟 IDE 重启：新建 cache 实例，从持久化恢复
        val cache2 = ParamHistoryCache()
        val recalled = cache2.recall(key)
        assertNotNull("新实例应从持久化恢复", recalled)
        assertEquals(mapOf("id" to "42", "status" to "active"), recalled)
        cache2.clear()
    }

    // ==== R22 T-01（D1=A）：无表名预览以 SQL 文本规范化指纹为键 ====

    /** 缺口①修复：无表名 computeKey 恒非 null（改前返回 null 不缓存——纯文本预览参数全丢）。 */
    @Test
    fun `R22_T01_无表名_键非空且同SQL同键`() {
        val cache = ParamHistoryCache()
        val k1 = cache.computeKey(null, "SELECT * FROM custom_dao_table WHERE a = ?")
        assertNotNull("无表名预览应有键（dedupeKey 同源规范化指纹）", k1)
        assertTrue("键应以 sql: 前缀标识无表名形态", k1!!.startsWith("sql:"))
        assertEquals("同 SQL 同键", k1, cache.computeKey(null, "SELECT * FROM custom_dao_table WHERE a = ?"))
        // 规范化口径：空白差异折叠（dedupeKey 同源）
        assertEquals("空白差异折叠后同键", k1, cache.computeKey(null, "SELECT  *  FROM custom_dao_table WHERE a = ?"))
        assertNotEquals("不同 SQL 不同键", k1, cache.computeKey(null, "SELECT 1"))
    }

    /** 存量兼容：有表名键形态逐字节不变（tableName:fingerprint）。 */
    @Test
    fun `R22_T01_有表名_现键兼容`() {
        val cache = ParamHistoryCache()
        val key = cache.computeKey("t_user", "SELECT * FROM t_user WHERE id = ?")
        assertNotNull(key)
        assertTrue("有表名键保持 tableName: 前缀", key!!.startsWith("t_user:"))
    }

    // ==== R22 T-04（D2 定案）：命名方案多方案结构 ====

    /** 缺口④/D2：旧 `paramHistoryJson` 存量自动成为「默认方案」——零迁移损失，老用户无感。 */
    @Test
    fun `R22_T04_D2_旧字段数据自动成为默认方案`() {
        // 旧构建数据形态 = 默认方案 save 产物（同键同格式）：cache1.save 写 paramHistoryJson 旧字段
        val cache1 = ParamHistoryCache()
        cache1.clear()
        val key = cache1.computeKey("user", "SELECT * FROM user WHERE id = ?")!!
        cache1.save(key, mapOf("id" to "7", "name" to "张三"))
        // 模拟 IDE 重启：新实例从旧字段恢复——数据必须落进默认方案（不是凭空丢、也不是新造方案）
        val cache2 = ParamHistoryCache()
        assertEquals("D2：旧字段数据应从默认方案召回", mapOf("id" to "7", "name" to "张三"), cache2.recall(key))
        assertEquals("D2：旧数据不应生成命名方案", emptyList<String>(), cache2.schemeNames())
        assertEquals("D2：激活方案应保持默认方案", ParamHistoryCache.DEFAULT_SCHEME, cache2.activeSchemeName())
        cache2.clear()
    }

    /** 缺口④：另存方案 → 切走 → 切回，参数快照（含勾选态特殊键）逐字节还原。 */
    @Test
    fun `R22_T04_方案另存切走切回_逐字节还原`() {
        val cache = ParamHistoryCache()
        cache.clear()
        val key = cache.computeKey(null, "SELECT * FROM t_order WHERE id = ?")!!
        // 默认方案：正常流参数（含 T-02 勾选态特殊键形态）
        val normal = mapOf("id" to "1", "__chk__:onlyActive" to "1", "__chk__:onlyVip" to "0", "__fev__:ids" to "2,3")
        cache.save(key, normal)
        // 另存「边界流」：边界流参数（勾选/foreach 形态不同）
        val boundary = mapOf("id" to "999", "__chk__:onlyActive" to "0", "__fev__:ids" to "8")
        cache.saveAsScheme("边界流", key, boundary)
        assertEquals("另存应切换激活方案", "边界流", cache.activeSchemeName())
        assertEquals("激活方案召回边界流快照", boundary, cache.recall(key))
        // 切走再切回：默认方案快照逐字节还原（T-DevAcceptFix2 ①：切换按**键**——键=本条语句）
        cache.switchScheme(ParamHistoryCache.DEFAULT_SCHEME, key)
        assertEquals("切回默认方案快照逐字节还原", normal, cache.recall(key))
        cache.switchScheme("边界流", key)
        assertEquals("再切回命名方案快照逐字节还原", boundary, cache.recall(key))
        cache.clear()
    }

    /** 方案隔离：命名方案快照不串默认方案，默认方案不被另存覆盖。 */
    @Test
    fun `R22_T04_方案隔离_命名与默认互不串写`() {
        val cache = ParamHistoryCache()
        cache.clear()
        val key = cache.computeKey("t_user", "SELECT * FROM t_user WHERE id = ?")!!
        cache.save(key, mapOf("id" to "1"))
        cache.switchScheme(ParamHistoryCache.DEFAULT_SCHEME, key) // saveAsScheme 会切激活，显式回默认再存
        cache.saveAsScheme("边界流", key, mapOf("id" to "2"))
        assertEquals(mapOf("id" to "2"), cache.recallFromScheme("边界流", key))
        cache.switchScheme(ParamHistoryCache.DEFAULT_SCHEME, key)
        assertEquals("默认方案快照不被另存覆盖", mapOf("id" to "1"), cache.recall(key))
        assertEquals("命名方案条目数", 1, cache.schemeEntryCount("边界流"))
        cache.clear()
    }

    /** 重命名/删除语义：默认方案受保护、重名拒绝、未知方案 NOT_FOUND、激活指针随迁。 */
    @Test
    fun `R22_T04_重命名与删除_语义`() {
        val cache = ParamHistoryCache()
        cache.clear()
        val key = cache.computeKey("t_a", "SELECT * FROM t_a WHERE id = ?")!!
        cache.saveAsScheme("A", key, mapOf("id" to "1"))
        cache.switchScheme(ParamHistoryCache.DEFAULT_SCHEME)
        cache.saveAsScheme("B", key, mapOf("id" to "2"))
        // 默认方案保护
        assertEquals(
            "默认方案不可重命名",
            ParamHistoryCache.SchemeOpResult.DEFAULT_PROTECTED,
            cache.renameScheme(ParamHistoryCache.DEFAULT_SCHEME, "X"),
        )
        assertEquals(
            "默认方案不可删除",
            ParamHistoryCache.SchemeOpResult.DEFAULT_PROTECTED,
            cache.deleteScheme(ParamHistoryCache.DEFAULT_SCHEME),
        )
        // 重名 / 未知
        assertEquals(
            "重命名到已存在名应拒绝",
            ParamHistoryCache.SchemeOpResult.DUPLICATE,
            cache.renameScheme("A", "B"),
        )
        assertEquals(
            "未知方案重命名 NOT_FOUND",
            ParamHistoryCache.SchemeOpResult.NOT_FOUND,
            cache.renameScheme("NOPE", "X"),
        )
        // 重命名保序 + 激活指针随迁
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.renameScheme("A", "A2"))
        assertEquals("创建序应保持", listOf("A2", "B"), cache.schemeNames())
        assertEquals(mapOf("id" to "1"), cache.recallFromScheme("A2", key))
        assertNull("旧名应消失", cache.recallFromScheme("A", key))
        cache.switchScheme("A2")
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.renameScheme("A2", "A3"))
        assertEquals("激活指针应随迁", "A3", cache.activeSchemeName())
        // 删除激活方案 → 归位默认方案
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.deleteScheme("A3"))
        assertEquals("删除激活方案后应归位默认方案", ParamHistoryCache.DEFAULT_SCHEME, cache.activeSchemeName())
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.deleteScheme("B"))
        assertEquals(emptyList<String>(), cache.schemeNames())
        cache.clear()
    }

    /** 命名方案持久化往返（模拟 IDE 重启）+ 未知激活名兜底归位默认方案。 */
    @Test
    fun `R22_T04_方案持久化往返与激活兜底`() {
        val cache1 = ParamHistoryCache()
        cache1.clear()
        val key = cache1.computeKey("t_b", "SELECT * FROM t_b WHERE id = ?")!!
        val snapshot = mapOf("id" to "42", "__chk__:flag" to "0")
        cache1.saveAsScheme("边界流", key, snapshot)
        // 新实例恢复：方案清单/内容/激活态全量还原
        val cache2 = ParamHistoryCache()
        assertEquals("方案清单应恢复", listOf("边界流"), cache2.schemeNames())
        assertEquals("方案快照应恢复", snapshot, cache2.recallFromScheme("边界流", key))
        assertEquals("激活方案应恢复", "边界流", cache2.activeSchemeName())
        assertEquals("激活方案路由召回应命中", snapshot, cache2.recall(key))
        cache2.clear()
    }

    /** 防脏态：switchScheme 对未知方案名忽略（激活态不漂移）。 */
    @Test
    fun `R22_T04_切换未知方案名忽略`() {
        val cache = ParamHistoryCache()
        cache.clear()
        cache.switchScheme("不存在")
        assertEquals("未知方案名不应改变激活态", ParamHistoryCache.DEFAULT_SCHEME, cache.activeSchemeName())
        cache.clear()
    }

    // ------------------------------------------------------------------
    // F49/F50（T-PerfQuick5 性能快件批）
    // ------------------------------------------------------------------

    /**
     * F49：switch 只写激活指针——不触发全量序列化（persistProbe 计数守卫）。
     * 反向验证锚：摘掉修复（switchScheme 回走 persistToStorage）→ 计数 +1 → 本测试红。
     */
    @Test
    fun `F49_switch只写指针_不触发全量序列化`() {
        val cache = ParamHistoryCache()
        cache.clear()
        val key = cache.computeKey("t_f49", "SELECT * FROM t_f49 WHERE id = ?")!!
        val probeCount = java.util.concurrent.atomic.AtomicInteger()
        ParamHistoryCache.persistProbe = { probeCount.incrementAndGet() }
        try {
            cache.saveAsScheme("S1", key, mapOf("id" to "1")) // 另存仍走全量序列化（1 次）
            val afterSave = probeCount.get()
            assertEquals("前置：另存应恰好触发一次全量序列化", 1, afterSave)
            cache.switchScheme(ParamHistoryCache.DEFAULT_SCHEME)
            cache.switchScheme("S1")
            cache.switchScheme("S1") // 同名幂等
            assertEquals(
                "switch 不得走全量序列化（只写激活指针）：计数 $afterSave → ${probeCount.get()}",
                afterSave,
                probeCount.get(),
            )
            // 指针本身仍轻量落盘：新实例恢复激活态
            val cache2 = ParamHistoryCache()
            assertEquals("激活指针经轻路径落盘仍可恢复", "S1", cache2.activeSchemeName())
            cache2.clear()
        } finally {
            ParamHistoryCache.persistProbe = null
        }
        cache.clear()
    }

    /** F49：方案个数上限——达 [ParamHistoryCache.MAX_SCHEME_COUNT] 后新建另存拒收，存量操作不受影响。 */
    @Test
    fun `F49_方案个数上限_新建拒收存量不受影响`() {
        val cache = ParamHistoryCache()
        cache.clear()
        val key = cache.computeKey("t_f49b", "SELECT * FROM t_f49b WHERE id = ?")!!
        repeat(ParamHistoryCache.MAX_SCHEME_COUNT) { i ->
            assertEquals(
                "第 ${i + 1} 个方案应成功",
                ParamHistoryCache.SchemeOpResult.OK,
                cache.saveAsScheme("S$i", key, mapOf("id" to "$i")),
            )
        }
        assertEquals(
            "达上限后新建另存应拒收",
            ParamHistoryCache.SchemeOpResult.LIMIT_REACHED,
            cache.saveAsScheme("溢出方案", key, mapOf("id" to "X")),
        )
        assertFalse("被拒方案不应入库", cache.hasScheme("溢出方案"))
        assertEquals("方案个数不得超上限", ParamHistoryCache.MAX_SCHEME_COUNT, cache.schemeNames().size)
        // 存量操作不受影响：既有方案覆盖写 / 重命名 / 删除
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.saveAsScheme("S0", key, mapOf("id" to "overwritten")))
        assertEquals(mapOf("id" to "overwritten"), cache.recallFromScheme("S0", key))
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.renameScheme("S1", "S1R"))
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.deleteScheme("S1R"))
        // 删除腾出名额后可新建
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.saveAsScheme("新名额", key, mapOf("id" to "N")))
        cache.clear()
    }

    /**
     * F50：方案存储 CHM 结构守卫——`schemes` 须为 ConcurrentHashMap。
     * 反向验证锚：摘掉 CHM 化（回退 LinkedHashMap）→ 本测试红（确定性红，并发压测只作辅助面）。
     */
    @Test
    fun `F50_方案存储_CHM结构守卫`() {
        val field = ParamHistoryCache::class.java.getDeclaredField("schemes")
        field.isAccessible = true
        assertTrue(
            "schemes 须为 ConcurrentHashMap（F50：后台 pooled 写回 vs EDT 读原裸 LinkedHashMap 无同步保护）",
            java.util.concurrent.ConcurrentHashMap::class.java.isAssignableFrom(field.type),
        )
    }

    /** F50：pooled 写回与 EDT 读并发——多线程 saveForScheme 建桶，主线程连续读清单，全量在册且读侧不抛。 */
    @Test
    fun `F50_pooled写回与EDT读并发_全量在册`() {
        val cache = ParamHistoryCache()
        cache.clear()
        val threads = (0 until 4).map { t ->
            Thread {
                repeat(25) { i -> cache.saveForScheme("并发$t-$i", "k", mapOf("id" to "$i")) }
            }
        }
        threads.forEach { it.start() }
        var reads = 0
        while (threads.any { it.isAlive }) {
            val names = cache.schemeNames() // EDT 侧读形态：结构遍历不得抛 CME
            check(names.size <= 4 * 25) { "方案清单越界：${names.size}" }
            reads++
        }
        threads.forEach { it.join(5000) }
        assertTrue("读循环应实际执行（防空转空绿）", reads > 0)
        assertEquals("并发建桶方案应全量在册", 100, cache.schemeNames().size)
        cache.clear()
    }

    /** F50：创建序经持久化往返不漂移（serializeSchemesToJson 按创建序遍历，CHM 哈希序不外泄）。 */
    @Test
    fun `F50_创建序_持久化往返不漂移`() {
        val cache1 = ParamHistoryCache()
        cache1.clear()
        val key = cache1.computeKey("t_f50", "SELECT * FROM t_f50 WHERE id = ?")!!
        // 以乱哈希序感明显的名字集创建
        val names = listOf("кирпич", "alpha", "方案Z", "0start", "Omega")
        names.forEachIndexed { i, n -> cache1.saveAsScheme(n, key, mapOf("id" to "$i")) }
        assertEquals("创建序在册", names, cache1.schemeNames())
        val cache2 = ParamHistoryCache() // 模拟重启恢复
        assertEquals("持久化往返后创建序不漂移", names, cache2.schemeNames())
        cache2.clear()
        cache1.clear()
    }

    // ------------------------------------------------------------------
    // F54（D31 道 B，性能十八轮）：默认方案桶跨线程同步——桶外粗锁
    // ------------------------------------------------------------------

    /**
     * F54 并发锤击正确性测试（V43 残余待证的主证）：pooled 写回形态（[saveForScheme]/
     * [saveAsScheme]——put+淘汰+全量持久化含默认桶遍历）与 EDT 读形态（[recall]＝
     * accessOrder 读即回写 LRU 序、[schemeNamesFor]/[activeSchemeFor]）读写交错——
     * 断言全程零异常（CME/结构损坏）＋各桶容量恒 ≤ maxSize（粗锁下淘汰不失守）。
     *
     * 反向验证锚点：摘掉桶外粗锁（synchronized 块改回直调）本测试必红——无同步的
     * accessOrder LinkedHashMap 在「put/遍历（持久化序列化）」与「读回写」并发下抛 CME。
     */
    @Test
    fun `F54_并发锤击_结构完整与容量上限`() {
        val cache = ParamHistoryCache(maxSize = 8)
        cache.clear()
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = listOf(
            // pooled 写回形态：默认桶 put + enforceMaxSize + 全量持久化（遍历默认桶与全部命名桶）
            Thread {
                repeat(400) { i ->
                    runCatching {
                        cache.saveForScheme(ParamHistoryCache.DEFAULT_SCHEME, "tbl:${i % 16}", mapOf("id" to "$i"))
                    }.onFailure(errors::add)
                }
            },
            // EDT 读形态：recall（accessOrder 读即回写 LRU 序）+ 淘汰
            Thread {
                repeat(2000) { i ->
                    runCatching { cache.recall("tbl:${i % 16}") }.onFailure(errors::add)
                }
            },
            // 命名桶读写交错（saveAsScheme 全量序列化遍历全部桶 + recallFromScheme 读回写）
            Thread {
                repeat(200) { i ->
                    runCatching {
                        if (i % 2 == 0) cache.saveAsScheme("s${i % 3}", "sk${i % 8}", mapOf("v" to "$i"))
                        else cache.recallFromScheme("s${i % 3}", "sk${i % 8}")
                    }.onFailure(errors::add)
                }
            },
            // UI 读形态：按键可见方案清单 + 按键激活方案（桶级 containsKey 探测）
            Thread {
                repeat(1000) { i ->
                    runCatching {
                        cache.schemeNamesFor("tbl:${i % 16}")
                        cache.activeSchemeFor("tbl:${i % 16}")
                    }.onFailure(errors::add)
                }
            },
        )
        threads.forEach { it.start() }
        threads.forEach { it.join(30_000) }
        assertTrue("并发窗口内不得抛异常（CME/结构损坏），实际 ${errors.size} 起：${errors.take(3)}", errors.isEmpty())
        assertTrue("默认桶容量恒 ≤ maxSize", cache.size() <= 8)
        (0 until 3).forEach { s ->
            assertTrue("命名桶 s$s 容量恒 ≤ maxSize", cache.schemeEntryCount("s$s") <= 8)
        }
        cache.clear()
    }

    /**
     * F54 LRU 淘汰序（读写交错下不漂移）：并发读线程持续 recall "tbl:a"（读即回写）期间
     * 主线程依次写 a/b/c/d——淘汰必须精确按 accessOrder 语义走（并发刷新过的 a 存活、
     * 未刷新的 b 被淘）。单写者＋固定刷新键设计使断言与读线程时序解耦（读线程只会把 a
     * 推向 MRU，不改变结论）。
     */
    @Test
    fun `F54_读写交错下LRU淘汰序_仍按最近访问`() {
        val cache = ParamHistoryCache(maxSize = 3)
        cache.clear()
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val readers = (0 until 2).map {
            Thread {
                while (!stop.get()) {
                    runCatching { cache.recall("tbl:a") }.onFailure(errors::add)
                }
            }
        }
        readers.forEach { it.start() }
        try {
            cache.save("tbl:a", mapOf("v" to "a"))
            cache.save("tbl:b", mapOf("v" to "b"))
            cache.save("tbl:c", mapOf("v" to "c"))
            // 并发读线程持续刷新 a（a → MRU）；插入第 4 条淘汰最久未访问的 b
            cache.save("tbl:d", mapOf("v" to "d"))
            assertTrue("并发窗口零异常：${errors.take(3)}", errors.isEmpty())
            assertNull("未被刷新的 b 应被淘汰", cache.recall("tbl:b"))
            assertNotNull("并发刷新过的 a 应存活", cache.recall("tbl:a"))
            assertNotNull("c 应存活", cache.recall("tbl:c"))
            assertNotNull("d 应存活", cache.recall("tbl:d"))
        } finally {
            stop.set(true)
            readers.forEach { it.join(5000) }
            assertTrue("读线程收尾零异常：${errors.take(3)}", errors.isEmpty())
            cache.clear()
        }
    }
}
