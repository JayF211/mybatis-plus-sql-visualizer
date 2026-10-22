package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.allowsSharedConsoleReuse
import cn.song.mybatisplus.visualizer.execute.allowsSilentLastUsed
import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-DialectGateExit：两条纯判据（强制选择器 / 绑定后重渲染）。
 *
 * 行为链（弹窗按钮、池线程刷新）由 `RollbackSqlDialogTest` 与 `DialectStatusRefreshWiringGuardTest` 覆盖，
 * 这里钉的是判据本身——把任一条件放宽，这里必红。
 */
class DialectGateExitPolicyTest {

    @Test
    fun `forceChoose 跳过共享控制台复用`() {
        assertTrue("默认允许复用共享控制台", allowsSharedConsoleReuse(forceChoose = false))
        assertFalse("强制选择时必须不复用（否则点了等于没点）", allowsSharedConsoleReuse(forceChoose = true))
    }

    @Test
    fun `last-used 静默命中仅预览且非强制`() {
        assertTrue("预览准备链默认静默命中", allowsSilentLastUsed(executeAfterPrepare = false, forceChoose = false))
        assertFalse("显式执行本就不静默", allowsSilentLastUsed(executeAfterPrepare = true, forceChoose = false))
        assertFalse("强制选择必须不静默", allowsSilentLastUsed(executeAfterPrepare = false, forceChoose = true))
    }

    @Test
    fun `绑定方言与渲染不同且文本未编辑才重渲染`() {
        val bound = DialectDecisionEngine.Source.BOUND_DATASOURCE
        assertTrue(
            "绑定 MySQL 而 SQL 是 PostgreSQL：应重渲染",
            shouldRerenderForBoundDialect(bound, "MySQL", "PostgreSQL", textUntouched = true),
        )
        assertFalse(
            "同方言不需要重渲染",
            shouldRerenderForBoundDialect(bound, "MySQL", "MySQL", textUntouched = true),
        )
        assertFalse(
            "用户改过文本时不得重渲染（会吞掉编辑）",
            shouldRerenderForBoundDialect(bound, "MySQL", "PostgreSQL", textUntouched = false),
        )
        assertFalse(
            "非绑定源（模块学习/项目共识）不是执行目标，不重渲染",
            shouldRerenderForBoundDialect(
                DialectDecisionEngine.Source.LEARNED_MODULE, "MySQL", "PostgreSQL", textUntouched = true,
            ),
        )
        assertFalse(
            "目标方言缺失不重渲染",
            shouldRerenderForBoundDialect(bound, null, "PostgreSQL", textUntouched = true),
        )
    }
}
