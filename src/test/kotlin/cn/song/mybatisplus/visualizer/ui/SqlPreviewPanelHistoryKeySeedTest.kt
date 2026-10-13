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
 * 修复：PreviewResult.historyFingerprintSeed 承载「注入前」语句文本，面板算键时优先用它。
 * 本用例钉两件事：
 *  ① 同一语句、注入开关开/关（SQL 文本不同）但种子相同 → **键必须相同**（修复前为红）；
 *  ② 种子缺席（存量构造点）→ 仍按 SQL 文本算键，不同 SQL 得不同键（存量行为不许回归）。
 *
 * k2j 互操作：PreviewResult 为 Java 类（29 参规范构造，historyFingerprintSeed 第 29 位），
 * 具名实参→位置实参，缺省槽按 Kotlin 数据类默认值补齐；面板扩展函数 showResult/clear 落在
 * Java 门面（SqlPreviewPanelResultViewKt / SqlPreviewPanelSqlViewKt），键取数走 historyCacheKeyForTest。
 */
class SqlPreviewPanelHistoryKeySeedTest : BasePlatformTestCase() {

    private fun result(sql: String, seed: String?): PreviewResult = PreviewResult(
        sql,
        sql,
        emptyList(),
        null,
        WrapperType.LAMBDA_QUERY,
        "user",
        null,
        listOf("id"),
        emptyMap(),
        null,
        null,
        emptyList(),
        emptyList(),
        null,
        null,
        emptyMap(),
        emptyList(),
        emptyList(),
        null,
        emptyList(),
        emptyMap(),
        null,
        false,
        null,
        null,
        emptyList(),
        emptySet(),
        null,
        seed,
    )

    /** ① 修复主用例：注入前后 SQL 文本不同、种子相同 → 键一致。 */
    fun testHistoryKeyStableAcrossInjection() {
        val seed = "SELECT id, status FROM user WHERE status = ?"
        val panel = SqlPreviewPanel(project)
        // 未开拦截器注入
        SqlPreviewPanelResultViewKt.showResult(panel, result(seed, seed))
        val keyOff = panel.historyCacheKeyForTest()
        // 开了多租户注入（模拟：最终 SQL 多出 AND tenant_id = ?），种子仍取注入前文本
        SqlPreviewPanelResultViewKt.showResult(panel, result("$seed\n    AND tenant_id = ?", seed))
        val keyOn = panel.historyCacheKeyForTest()
        assertNotNull("键不应为 null", keyOff)
        assertNotNull("键不应为 null", keyOn)
        assertEquals("注入开关切换后参数历史键必须不变（缺陷②修复点）", keyOff, keyOn)
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    /** ② 存量回归：种子缺席时仍按 SQL 文本算键，不同 SQL → 不同键。 */
    fun testHistoryKeyFallsBackToSqlWhenSeedAbsent() {
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, result("SELECT id FROM user WHERE status = ?", null))
        val keyA = panel.historyCacheKeyForTest()
        SqlPreviewPanelResultViewKt.showResult(panel, result("SELECT id FROM user WHERE status = ? AND deleted = 0", null))
        val keyB = panel.historyCacheKeyForTest()
        assertNotNull(keyA)
        assertNotNull(keyB)
        assertNotEquals("种子缺席时应维持按 SQL 文本算键的存量语义", keyA, keyB)
        SqlPreviewPanelSqlViewKt.clear(panel)
    }
}
