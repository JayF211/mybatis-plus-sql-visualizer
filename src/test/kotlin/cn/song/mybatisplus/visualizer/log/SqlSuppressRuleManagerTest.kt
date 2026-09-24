package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQL 屏蔽规则管理器单元测试（O-69 T-SqlSuppress，纯逻辑——直接构造，无 Application 依赖）。
 *
 * 覆盖 §五 验收标准 4：CRUD + 持久化 round-trip（loadState/saveState）+ 上限 500 拒绝 +
 * 命中计数累加；以及归一化口径（验收标准 2 的 manager 侧：空白变体命中、大小写不命中）。
 */
class SqlSuppressRuleManagerTest {

    /** 每用例独立实例（不触 Application 级单例，防跨测试类串台）。 */
    private fun newManager(): SqlSuppressRuleManager = SqlSuppressRuleManager()

    // ---------- CRUD ----------

    @Test
    fun `新增规则_规范化文本入库存原始样例`() {
        val m = newManager()
        assertEquals(SqlSuppressRuleManager.AddResult.ADDED, m.addRule("  SELECT   1  "))
        val rule = m.rules.single()
        assertEquals("规范化文本存 pattern（trim + 连续空白折叠）", "SELECT 1", rule.pattern)
        // 原始 SQL 存 sampleSql（仅去首尾空白，内部空白原样保留——高亮展示用户原始形态，规范化只在 pattern）
        assertEquals("SELECT   1", rule.sampleSql)
        assertTrue("自动分配 id", rule.id.isNotBlank())
        assertTrue("记录创建时间", rule.createdAt > 0)
        // 运行时匹配集：pattern 指纹
        assertEquals(setOf(sqlFingerprint("SELECT 1")), m.suppressedKeys)
        assertTrue(m.isSuppressed(sqlFingerprint("SELECT 1")))
    }

    @Test
    fun `同规范化文本重复新增_幂等跳过`() {
        val m = newManager()
        m.addRule("SELECT 1")
        assertEquals(
            "空白变体同规范化文本 → 不重复建规则",
            SqlSuppressRuleManager.AddResult.DUPLICATE,
            m.addRule("  SELECT   1  "),
        )
        assertEquals(1, m.rules.size)
    }

    @Test
    fun `空白输入不成规则`() {
        val m = newManager()
        assertEquals(SqlSuppressRuleManager.AddResult.DUPLICATE, m.addRule("   "))
        assertEquals(0, m.rules.size)
        assertTrue(m.suppressedKeys.isEmpty())
    }

    @Test
    fun `删除规则_键集收缩_无变化不发通知`() {
        val m = newManager()
        m.addRule("SELECT 1")
        m.addRule("SELECT 2")
        var notified = 0
        val listener = { notified += 1 }
        m.addListener(listener)
        val id1 = m.rules.first { it.pattern == "SELECT 1" }.id
        m.removeRule(id1)
        assertEquals(1, notified)
        assertEquals(listOf("SELECT 2"), m.rules.map { it.pattern })
        assertFalse(m.isSuppressed(sqlFingerprint("SELECT 1")))
        m.removeRule("不存在的id")
        assertEquals("删不到东西不发通知", 1, notified)
        m.removeListener(listener)
    }

    @Test
    fun `清空规则_键集清空`() {
        val m = newManager()
        m.addRule("SELECT 1")
        m.clearRules()
        assertTrue(m.rules.isEmpty())
        assertTrue(m.suppressedKeys.isEmpty())
        m.clearRules() // 本就为空不再通知（不抛异常即可）
    }

    // ---------- 归一化口径（§五 验收 2 的 manager 侧） ----------

    @Test
    fun `空白变体命中同一规则`() {
        val m = newManager()
        m.addRule("SELECT 1")
        // 规范化 = trim + 连续空白折叠单空格；大小写与分号不动（既有口径，勿漂移）
        assertTrue(
            "多空白/首尾空白变体的指纹与规则指纹相同",
            sqlFingerprint(normalizeSqlWhitespace("  SELECT   1  ")) ==
                sqlFingerprint(normalizeSqlWhitespace("SELECT 1")),
        )
    }

    @Test
    fun `大小写不同不命中_口径与既有规范化一致`() {
        // D2 定案：匹配口径 = 还原 SQL 空白规范化后精确匹配（大小写敏感）——
        // normalizeSqlWhitespace 不动大小写（COUNT 前缀判定、达梦带引号标识符均依赖），防口径漂移注释。
        val m = newManager()
        m.addRule("SELECT 1")
        assertNotEquals(
            sqlFingerprint("SELECT 1"),
            sqlFingerprint("select 1"),
        )
        assertFalse(m.isSuppressed(sqlFingerprint("select 1")))
        // 大小写变体可各自成规则（去重按规范化文本，非大小写折叠）
        assertEquals(SqlSuppressRuleManager.AddResult.ADDED, m.addRule("select 1"))
        assertEquals(2, m.rules.size)
    }

    @Test
    fun `尾分号剥离_规则带分号命中不带分号的日志还原SQL`() {
        // T-SqlSuppressUx（20260924 用户走查返修）：规则粘贴态常带收尾 `;` 而日志还原链不带
        // （实证「已拦截 0 次」根因）——屏蔽口径 = 去重口径 + 尾分号剥离（suppressFingerprint）。
        // 剥离只作用字符串收尾：字面量内分号其后必有引号等其余内容，trimEnd(';') 触不到。
        val m = newManager()
        assertEquals(SqlSuppressRuleManager.AddResult.ADDED, m.addRule("SELECT 1;"))
        assertTrue(
            "不带分号的日志形态命中带分号规则",
            m.isSuppressed(suppressFingerprint("SELECT 1")),
        )
        assertTrue(
            "带分号规则自身指纹同样在集（对称）",
            m.isSuppressed(suppressFingerprint("SELECT 1;")),
        )
        // 字面量内分号不影响既有命中（语义不外溢）
        m.addRule("SELECT * FROM t WHERE s = 'a;b'")
        assertTrue(
            m.isSuppressed(suppressFingerprint("SELECT * FROM t WHERE s = 'a;b'")),
        )
        assertFalse(
            "形状不同的语句不因分号剥离被误吞",
            m.isSuppressed(suppressFingerprint("SELECT * FROM t WHERE s = 'a'")),
        )
    }

    // ---------- 上限（§五 验收 4 / D6） ----------

    @Test
    fun `超上限500拒新增_既有规则不受影响`() {
        val m = newManager()
        repeat(SqlSuppressRuleManager.MAX_RULES) { i ->
            assertEquals("第 ${i + 1} 条应成功", SqlSuppressRuleManager.AddResult.ADDED, m.addRule("SELECT $i"))
        }
        assertEquals(SqlSuppressRuleManager.MAX_RULES, m.rules.size)
        assertEquals(
            "第 501 条拒绝",
            SqlSuppressRuleManager.AddResult.LIMIT_REACHED,
            m.addRule("SELECT overflow"),
        )
        assertEquals("上限后仍可重名跳过（先查存在再查上限）", SqlSuppressRuleManager.AddResult.DUPLICATE, m.addRule("SELECT 0"))
        assertEquals(SqlSuppressRuleManager.MAX_RULES, m.rules.size)
        assertEquals("上限不淘汰既有规则（D6 先到先得）", SqlSuppressRuleManager.MAX_RULES, m.suppressedKeys.size)
    }

    // ---------- 命中计数（§五 验收 4 / D4） ----------

    @Test
    fun `命中计数按指纹归账到规则_规则删除后停止归账`() {
        val m = newManager()
        m.addRule("SELECT 1")
        val rule = m.rules.single()
        assertEquals(0, m.hitCount(rule.id))
        m.recordHit(sqlFingerprint("SELECT 1"))
        m.recordHit(sqlFingerprint("SELECT 1"))
        m.recordHit(sqlFingerprint("SELECT 999")) // 未命中任何规则的键
        assertEquals(2, m.hitCount(rule.id))
        m.removeRule(rule.id)
        m.recordHit(sqlFingerprint("SELECT 1")) // 规则已删 → 索引无键 → 静默跳过
        assertEquals("删除后不再归账", 2, m.hitCount(rule.id))
    }

    // ---------- 变更通知 ----------

    @Test
    fun `增删清都发布通知且订阅者读到新键集`() {
        val m = newManager()
        val observedSizes = mutableListOf<Int>()
        val listener = {
            observedSizes.add(m.suppressedKeys.size)
            Unit
        }
        m.addListener(listener)
        m.addRule("SELECT 1")
        m.addRule("SELECT 2")
        val id = m.rules.first().id // 创建序：first = SELECT 1 的规则
        m.removeRule(id)
        m.clearRules()
        assertEquals("add/add/remove/clear 各发布一次", listOf(1, 2, 1, 0), observedSizes)
        m.removeListener(listener)
    }

    // ---------- 持久化 round-trip（§五 验收 4） ----------

    @Test
    fun `loadState_saveState_roundtrip_重建键集`() {
        val source = newManager()
        source.addRule("SELECT 1")
        source.addRule("SELECT * FROM t WHERE x = 1")
        val saved = source.getState()
        assertEquals(2, saved.rules.size)

        val restored = newManager()
        assertTrue(restored.rules.isEmpty())
        assertTrue(restored.suppressedKeys.isEmpty())
        restored.loadState(saved)
        assertEquals(
            "规则列表原样恢复",
            source.rules.map { it.pattern },
            restored.rules.map { it.pattern },
        )
        assertEquals(
            "匹配键集按 pattern 指纹重建",
            source.suppressedKeys,
            restored.suppressedKeys,
        )
        assertTrue(restored.isSuppressed(sqlFingerprint(normalizeSqlWhitespace("  SELECT   1  "))))
    }

    @Test
    fun `loadState 空State_规则清空且键集重建`() {
        val m = newManager()
        m.addRule("SELECT 1")
        m.loadState(SqlSuppressRuleManager.State(mutableListOf()))
        assertTrue(m.rules.isEmpty())
        assertTrue(m.suppressedKeys.isEmpty())
    }

    @Test
    fun `规则bean_摘要截断与ensureId幂等`() {
        val rule = SqlSuppressRule(
            pattern = "SELECT " + "x".repeat(200),
            sampleSql = "SELECT x",
            createdAt = 1L,
        )
        rule.ensureId()
        val firstId = rule.id
        rule.ensureId()
        assertEquals("ensureId 幂等", firstId, rule.id)
        assertTrue(rule.patternSummary().endsWith("…"))
        assertTrue(rule.patternSummary().length <= SqlSuppressRule.MAX_PATTERN_SUMMARY_LEN + 1)
    }
}
