package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull

/**
 * 缺陷②修复回归守卫：**拦截器注入开关切换不得改变参数历史缓存键**。
 *
 * 缺陷形态（2026-10-12 演示录制中发现，诊断报告 `docs/调研报告/20261012-0900-*`）：
 * 历史键 = `表名:SHA-256(SQL 全文)`，而逻辑删除 / 多租户等拦截器模拟在渲染前改写 SQL 文本
 * （追加 `AND deleted = 0` / `AND tenant_id = ?`）——用户一开这些开关，键就变，此前回填好的
 * 参数全部召回 miss、退回占位符（原片 `record/perf/a23_interceptor.mp4` 的 `status=41` → `?`）。
 *
 * 修复：`PreviewResult.historyFingerprintSeed` 承载「注入前」语句文本，面板算键时优先用它。
 * 本用例钉两件事：
 *  ① 同一语句、注入开关开/关（SQL 文本不同）但种子相同 → **键必须相同**（修复前为红）；
 *  ② 种子缺席（存量构造点）→ 仍按 SQL 文本算键，不同 SQL 得不同键（存量行为不许回归）。
 */
class SqlPreviewPanelHistoryKeySeedTest : BasePlatformTestCase() {

    private fun result(sql: String, seed: String?): PreviewResult = PreviewResult(
        sql = sql,
        compactSql = sql,
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = "user",
        columns = listOf("id"),
        historyFingerprintSeed = seed,
    )

    /** ① 修复主用例：注入前后 SQL 文本不同、种子相同 → 键一致。 */
    fun testHistoryKeyStableAcrossInjection() {
        val seed = "SELECT id, status FROM user WHERE status = ?"
        val panel = SqlPreviewPanel(project)
        // 未开拦截器注入
        panel.showResult(result(sql = seed, seed = seed))
        val keyOff = panel.historyCacheKey()
        // 开了多租户注入（模拟：最终 SQL 多出 AND tenant_id = ?），种子仍取注入前文本
        panel.showResult(result(sql = "$seed\n    AND tenant_id = ?", seed = seed))
        val keyOn = panel.historyCacheKey()
        assertNotNull("键不应为 null", keyOff)
        assertNotNull("键不应为 null", keyOn)
        assertEquals("注入开关切换后参数历史键必须不变（缺陷②修复点）", keyOff, keyOn)
        panel.clear()
    }

    /** ② 存量回归：种子缺席时仍按 SQL 文本算键，不同 SQL → 不同键。 */
    fun testHistoryKeyFallsBackToSqlWhenSeedAbsent() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(sql = "SELECT id FROM user WHERE status = ?", seed = null))
        val keyA = panel.historyCacheKey()
        panel.showResult(result(sql = "SELECT id FROM user WHERE status = ? AND deleted = 0", seed = null))
        val keyB = panel.historyCacheKey()
        assertNotNull(keyA)
        assertNotNull(keyB)
        assertNotEquals("种子缺席时应维持按 SQL 文本算键的存量语义", keyA, keyB)
        panel.clear()
    }
}
