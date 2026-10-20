package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T-DialectStatusLiveRefresh：刷新策略纯函数——仅升级、手动/冲突不被覆盖。 */
class DialectStatusRefreshPolicyTest {
    private fun decision(
        status: DialectDecisionEngine.Status,
        name: String? = null,
        source: DialectDecisionEngine.Source? = DialectDecisionEngine.Source.IDEA_SCOPE,
    ) = DialectDecisionEngine.Decision(status, name, DialectDecisionEngine.Confidence.FALLBACK, source)

    @Test
    fun `stronger status replaces weaker one`() {
        val ambiguous = decision(DialectDecisionEngine.Status.AMBIGUOUS)
        val resolved = decision(DialectDecisionEngine.Status.RESOLVED, "MySQL",
            DialectDecisionEngine.Source.BOUND_DATASOURCE)
        assertEquals(resolved, DialectStatusRefreshPolicy.pickStronger(ambiguous, resolved))
    }

    @Test
    fun `weaker or equal decision never replaces`() {
        val resolved = decision(DialectDecisionEngine.Status.RESOLVED, "MySQL")
        assertNull(DialectStatusRefreshPolicy.pickStronger(resolved,
            decision(DialectDecisionEngine.Status.AMBIGUOUS)))
        assertNull(DialectStatusRefreshPolicy.pickStronger(resolved,
            decision(DialectDecisionEngine.Status.UNAVAILABLE)))
        assertNull(DialectStatusRefreshPolicy.pickStronger(
            decision(DialectDecisionEngine.Status.RESOLVED, "MySQL",
                DialectDecisionEngine.Source.LEARNED_MODULE),
            decision(DialectDecisionEngine.Status.RESOLVED, "MySQL",
                DialectDecisionEngine.Source.LEARNED_MODULE)))
    }

    @Test
    fun `manual and conflict decisions are terminal`() {
        val manual = decision(DialectDecisionEngine.Status.MANUAL, "Oracle",
            DialectDecisionEngine.Source.MANUAL)
        val conflict = decision(DialectDecisionEngine.Status.CONFLICT)
        assertNull(DialectStatusRefreshPolicy.pickStronger(manual,
            decision(DialectDecisionEngine.Status.RESOLVED, "MySQL",
                DialectDecisionEngine.Source.BOUND_DATASOURCE)))
        assertNull(DialectStatusRefreshPolicy.pickStronger(conflict,
            decision(DialectDecisionEngine.Status.RESOLVED, "MySQL",
                DialectDecisionEngine.Source.BOUND_DATASOURCE)))
    }

    @Test
    fun `refresh never produces manual or conflict status`() {
        // 重算发生在用户没有再次手动指定的场景，把手动/冲突当升级会伪造用户选择或安全阻断。
        assertNull(DialectStatusRefreshPolicy.pickStronger(
            decision(DialectDecisionEngine.Status.UNAVAILABLE),
            decision(DialectDecisionEngine.Status.MANUAL, "Oracle",
                DialectDecisionEngine.Source.MANUAL)))
        assertNull(DialectStatusRefreshPolicy.pickStronger(
            decision(DialectDecisionEngine.Status.UNAVAILABLE),
            decision(DialectDecisionEngine.Status.CONFLICT)))
    }

    @Test
    fun `same rank with different evidence refreshes`() {
        val old = decision(DialectDecisionEngine.Status.RESOLVED, "MySQL",
            DialectDecisionEngine.Source.LEARNED_MODULE)
        val fresh = decision(DialectDecisionEngine.Status.RESOLVED, "MySQL",
            DialectDecisionEngine.Source.BOUND_DATASOURCE)
        assertEquals(fresh, DialectStatusRefreshPolicy.pickStronger(old, fresh))
    }

    @Test
    fun `null old accepts any non terminal decision`() {
        val fresh = decision(DialectDecisionEngine.Status.RESOLVED, "MySQL",
            DialectDecisionEngine.Source.BOUND_DATASOURCE)
        assertEquals(fresh, DialectStatusRefreshPolicy.pickStronger(null, fresh))
        assertNull(DialectStatusRefreshPolicy.pickStronger(null,
            decision(DialectDecisionEngine.Status.CONFLICT)))
    }
}
