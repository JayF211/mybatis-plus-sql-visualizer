package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-ExecSlowOps：方言两段式映射判定纯函数回归（[DialectPreMapping.shouldSkip]）。
 *
 * 真机双 SEVERE 的线程编排核心判定——BGT 读探测后「要不要派 EDT 做 setMapping」：
 *  - 进程内同 vf 已设过 → 跳过（T-PreviewFlash 幂等短路语义保留）；
 *  - workspace.xml 持久映射幸存（getConfiguredMapping 已 MySQL）→ 跳过（IDE 重启稳态零 EDT 占用）；
 *  - 探测失败 null 保守不跳过——宁可多设一次同值映射，不冒 GenericSQL PSI 静默失败的险（M36.5.3）。
 */
class ExecSlowOpsRoutingTest {

    @Test
    fun `进程内已映射直接跳过`() {
        assertTrue(DialectPreMapping.shouldSkip(true, null))
        assertTrue(DialectPreMapping.shouldSkip(true, false))
        assertTrue(DialectPreMapping.shouldSkip(true, true))
    }

    @Test
    fun `读探测确认已配置MySQL跳过`() {
        assertTrue(DialectPreMapping.shouldSkip(false, true))
    }

    @Test
    fun `未映射且探测为否或未决时派EDT`() {
        assertFalse(DialectPreMapping.shouldSkip(false, false))
        assertFalse(DialectPreMapping.shouldSkip(false, null))
    }
}
