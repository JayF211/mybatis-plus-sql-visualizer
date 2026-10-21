package cn.song.mybatisplus.visualizer.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-PresetMethodScope：缓存键的第三维「声明作用域」纯逻辑单测。
 *
 * 背景（2026-10-21 沙盒报障）：`WmsInventoryJarQueryService` 两个方法渲染出的 SQL 逐字相同，
 * 旧键只有「表名 + SQL 指纹」，预设/激活态/参数历史全部共享。本测试锁定三件事：
 * 1. 带 scope 的键与旧键格式可区分，且旧格式逐字节不变（存量兼容）；
 * 2. 两个 scope 下同名预设、激活指针、参数快照互不可见；
 * 3. 升级前的旧键数据在带 scope 键下可读（回落），并在首次列名单时惰性复制到新键。
 */
class ParamHistoryCacheScopeTest {

    private lateinit var cache: ParamHistoryCache

    @Before
    fun setUp() {
        // 测试态持久化走 companion 静态 fallbackStorage，跨用例必须清
        ParamHistoryCache().clear()
        cache = ParamHistoryCache()
    }

    @After
    fun tearDown() {
        ParamHistoryCache().clear()
    }

    // ------------------------------------------------------------------
    // 键格式
    // ------------------------------------------------------------------

    /** 无 scope 时逐字节保持旧格式（tableName:fingerprint / sql:fingerprint）。 */
    @Test
    fun testComputeKeyWithoutScopeKeepsLegacyFormat() {
        val withTable = cache.computeKey("t_user", "SELECT * FROM t_user WHERE id = ?")!!
        assertTrue("有表名键保持 tableName: 前缀", withTable.startsWith("t_user:"))
        assertFalse("无 scope 不得出现分隔符", withTable.contains(ParamHistoryCache.SCOPE_SEPARATOR))

        val noTable = cache.computeKey(null, "SELECT 1")!!
        assertTrue("无表名键保持 sql: 前缀", noTable.startsWith("sql:"))
        assertFalse(noTable.contains(ParamHistoryCache.SCOPE_SEPARATOR))
    }

    /** 带 scope 时在旧键尾部追加 `|@<scope>`，旧键部分不变。 */
    @Test
    fun testComputeKeyWithScopeAppendsScopeSuffix() {
        val legacy = cache.computeKey("t_user", "SELECT * FROM t_user WHERE id = ?")!!
        val scoped = cache.computeKey("t_user", "SELECT * FROM t_user WHERE id = ?", "scope-a")!!
        assertEquals(legacy + ParamHistoryCache.SCOPE_SEPARATOR + "scope-a", scoped)
        assertEquals("旧键可由带 scope 键切回", legacy, cache.legacyKeyOf(scoped))
        assertEquals("无 scope 键切回自身", legacy, cache.legacyKeyOf(legacy))

        assertNotEquals(
            "同 SQL 不同 scope 必须不同键",
            cache.computeKey("t_user", "SELECT 1", "a"),
            cache.computeKey("t_user", "SELECT 1", "b"),
        )
        assertEquals(
            "空 scope 等同无 scope",
            legacy,
            cache.computeKey("t_user", "SELECT * FROM t_user WHERE id = ?", ""),
        )
    }

    /** scope 内部若含分隔符须被清洗，保证回落切分唯一。 */
    @Test
    fun testScopeSanitizesSeparator() {
        val legacy = cache.computeKey("t_user", "SELECT 1")!!
        val scoped = cache.computeKey("t_user", "SELECT 1", "a|@b")!!
        val separator = ParamHistoryCache.SCOPE_SEPARATOR
        assertEquals(
            "分隔符在键里只应出现一次",
            1,
            scoped.windowed(separator.length).count { it == separator },
        )
        assertEquals("清洗后旧键仍可切回", legacy, cache.legacyKeyOf(scoped))
    }

    // ------------------------------------------------------------------
    // 两 scope 隔离
    // ------------------------------------------------------------------

    @Test
    fun testSameSchemeTwoScopesIsolated() {
        val keyA = cache.computeKey("t_order", "SELECT * FROM t_order WHERE id = ?", "scope-a")!!
        val keyB = cache.computeKey("t_order", "SELECT * FROM t_order WHERE id = ?", "scope-b")!!

        assertEquals(
            ParamHistoryCache.SchemeOpResult.OK,
            cache.saveAsScheme("生产参数", keyA, linkedMapOf("id" to "A")),
        )
        assertEquals("B 方法不应看到 A 的预设", emptyList<String>(), cache.schemeNamesFor(keyB))
        assertEquals("A 方法应看到自己的预设", listOf("生产参数"), cache.schemeNamesFor(keyA))

        assertEquals(
            ParamHistoryCache.SchemeOpResult.OK,
            cache.saveAsScheme("生产参数", keyB, linkedMapOf("id" to "B")),
        )
        assertEquals("同名预设跨方法各存各值", "A", cache.recallFromScheme("生产参数", keyA)!!["id"])
        assertEquals("同名预设跨方法各存各值", "B", cache.recallFromScheme("生产参数", keyB)!!["id"])
    }

    @Test
    fun testActiveSchemeIsolatedPerScope() {
        val keyA = cache.computeKey("t_order", "SELECT 1", "scope-a")!!
        val keyB = cache.computeKey("t_order", "SELECT 1", "scope-b")!!
        cache.saveAsScheme("甲", keyA, linkedMapOf("id" to "1"))
        cache.saveAsScheme("乙", keyB, linkedMapOf("id" to "2"))

        assertEquals("甲", cache.activeSchemeFor(keyA))
        assertEquals("乙", cache.activeSchemeFor(keyB))

        // 切回默认只影响当前键
        cache.switchScheme(ParamHistoryCache.DEFAULT_SCHEME, keyA)
        assertEquals(ParamHistoryCache.DEFAULT_SCHEME, cache.activeSchemeFor(keyA))
        assertEquals("另一个方法的激活态不受影响", "乙", cache.activeSchemeFor(keyB))
    }

    // ------------------------------------------------------------------
    // 存量回落与惰性迁移
    // ------------------------------------------------------------------

    @Test
    fun testRecallFallsBackToLegacyKey() {
        val legacy = cache.computeKey("t_order", "SELECT * FROM t_order WHERE id = ?")!!
        cache.save(legacy, linkedMapOf("id" to "old"))

        val scoped = cache.computeKey("t_order", "SELECT * FROM t_order WHERE id = ?", "scope-a")!!
        assertEquals("带 scope 键未命中应回落旧键", "old", cache.recall(scoped)!!["id"])
        assertFalse("回落读取不得伪造直接条目", cache.hasDirectEntry(scoped))
    }

    @Test
    fun testRecallFromSchemeFallsBackToLegacyKey() {
        val legacy = cache.computeKey("t_order", "SELECT 1")!!
        cache.saveAsScheme("旧预设", legacy, linkedMapOf("id" to "old"))
        val scoped = cache.computeKey("t_order", "SELECT 1", "scope-a")!!
        assertEquals("方案桶同样回落旧键", "old", cache.recallFromScheme("旧预设", scoped)!!["id"])
    }

    @Test
    fun testSchemeNamesForMigratesLegacySnapshot() {
        val legacy = cache.computeKey("t_order", "SELECT 1")!!
        cache.saveAsScheme("旧预设", legacy, linkedMapOf("id" to "old"))
        val scoped = cache.computeKey("t_order", "SELECT 1", "scope-a")!!
        assertFalse("迁移前新键无直接条目", cache.schemeHasDirectEntry("旧预设", scoped))

        assertEquals("旧预设在带 scope 键下仍列出", listOf("旧预设"), cache.schemeNamesFor(scoped))
        assertTrue("首次列名单应完成惰性复制", cache.schemeHasDirectEntry("旧预设", scoped))
        assertEquals("迁移后仍可召回", "old", cache.recallFromScheme("旧预设", scoped)!!["id"])
        assertEquals("旧键条目保留", "old", cache.recallFromScheme("旧预设", legacy)!!["id"])
    }

    @Test
    fun testMigrateKeepsScopesIndependentAfterCopy() {
        val legacy = cache.computeKey("t_order", "SELECT 1")!!
        cache.saveAsScheme("预设", legacy, linkedMapOf("id" to "old"))
        val keyA = cache.computeKey("t_order", "SELECT 1", "scope-a")!!
        val keyB = cache.computeKey("t_order", "SELECT 1", "scope-b")!!
        cache.schemeNamesFor(keyA)
        cache.schemeNamesFor(keyB)

        // 迁移后各自覆盖，互不影响
        cache.saveAsScheme("预设", keyA, linkedMapOf("id" to "A"))
        assertEquals("A", cache.recallFromScheme("预设", keyA)!!["id"])
        assertEquals("B 保持迁移时的旧值", "old", cache.recallFromScheme("预设", keyB)!!["id"])
    }

    @Test
    fun testActiveSchemeMigratedWithSnapshot() {
        val legacy = cache.computeKey("t_order", "SELECT 1")!!
        cache.saveAsScheme("旧预设", legacy, linkedMapOf("id" to "old"))
        val scoped = cache.computeKey("t_order", "SELECT 1", "scope-a")!!
        assertEquals("迁移前激活指针回落旧键记录", "旧预设", cache.activeSchemeFor(scoped))
        cache.schemeNamesFor(scoped)
        assertEquals("迁移后指针仍指向该预设", "旧预设", cache.activeSchemeFor(scoped))
    }

    /** 无方法上下文（日志/历史双击）不得凭空产生 scope：键保持旧格式，旧数据照常召回。 */
    @Test
    fun testNoScopeContextKeepsLegacyBehaviour() {
        val key = cache.computeKey(null, "SELECT * FROM custom_dao WHERE a = ?")!!
        cache.save(key, linkedMapOf("a" to "1"))
        assertEquals("无表名无 scope 的键照常往返", "1", cache.recall(key)!!["a"])
        assertNull("不同 SQL 仍是不同键", cache.recall(cache.computeKey(null, "SELECT 2", null)!!))
        assertNotNull(cache.computeKey(null, "SELECT 2"))
    }
}
