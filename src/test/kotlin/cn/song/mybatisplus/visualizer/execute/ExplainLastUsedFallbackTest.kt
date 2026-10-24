package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-ExplainPwdPrompt W4 护栏：结构化 EXPLAIN 的 last-used 取源回落判定
 * （[DataSourceSelector.resolveLastUsedExplainFallback]）纯逻辑表。
 *
 * 缺陷形态（2026-10-24 沙盒实测）：数据源无 Dialects 映射时平台
 * `DatabaseRunners.getAttachDataSourceRunners` 返回空（Database 视图已连接仍 runners=0），
 * 绑定链在选择器弹出前就失败，结构化通道（askPassword=true 的原生密码框）永远走不到。
 * 回落＝真绑定缺失时按面板「数据源（上次使用）」记忆取源；防错源纪律＝**歧义即弃**。
 *
 * 本类零平台引用（testFast 快桶成员）。
 */
class ExplainLastUsedFallbackTest {

    private val candidates = listOf(
        "mp_demo@localhost" to "DS-MP",
        "mpj_demo@localhost" to "DS-MPJ",
        "jpa_demo@localhost" to "DS-JPA",
    )

    @Test
    fun uniqueNameHitResolvesDataSource() {
        assertEquals(
            "按名唯一命中应取该数据源对象",
            "DS-MPJ",
            DataSourceSelector.resolveLastUsedExplainFallback("mpj_demo@localhost", candidates),
        )
    }

    @Test
    fun ambiguousNameIsAbandoned() {
        val dup = candidates + ("mp_demo@localhost" to "DS-MP-2")
        assertNull(
            "同名多源必须弃（不猜）——回落错源比不回落更糟",
            DataSourceSelector.resolveLastUsedExplainFallback("mp_demo@localhost", dup),
        )
    }

    @Test
    fun missingNameYieldsNull() {
        assertNull(
            "记忆名不在在籍名单（已删除/改名）→ 弃",
            DataSourceSelector.resolveLastUsedExplainFallback("gone_demo@localhost", candidates),
        )
    }

    @Test
    fun blankOrNullNameYieldsNull() {
        assertNull("空名弃", DataSourceSelector.resolveLastUsedExplainFallback("", candidates))
        assertNull("null 名弃", DataSourceSelector.resolveLastUsedExplainFallback(null, candidates))
        assertNull("空名单弃", DataSourceSelector.resolveLastUsedExplainFallback("mp_demo@localhost", emptyList()))
    }
}
