package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialectPreviewStatusTextTest {
    @Test
    fun `every evidence and confidence has localized readable text`() {
        for (source in DialectDecisionEngine.Source.entries) {
            for (confidence in DialectDecisionEngine.Confidence.entries) {
                val decision = DialectDecisionEngine.Decision(
                    DialectDecisionEngine.Status.RESOLVED, "PostgreSQL", confidence, source,
                    dataSourceIds = listOf("ds-id"),
                )
                val text = DialectPreviewStatusText.describe(decision)
                assertTrue("$source/$confidence 缺方言：${text.visible}", text.visible.contains("PostgreSQL"))
                assertTrue("$source/$confidence 缺候选身份：${text.details}", text.details.contains("ds-id"))
                assertFalse("$source/$confidence 命中缺失 bundle key：${text.details}",
                    text.details.contains("panel.meta.dialectStatus."))
            }
        }
    }

    @Test
    fun `ambiguous conflict and unavailable never claim MySQL as proven`() {
        for (status in listOf(DialectDecisionEngine.Status.AMBIGUOUS,
            DialectDecisionEngine.Status.CONFLICT, DialectDecisionEngine.Status.UNAVAILABLE)) {
            val decision = DialectDecisionEngine.Decision(status, null,
                DialectDecisionEngine.Confidence.FALLBACK, DialectDecisionEngine.Source.IDEA_SCOPE,
                dataSourceIds = listOf("candidate-a", "candidate-b"))
            val text = DialectPreviewStatusText.describe(decision)
            assertTrue("$status 缺兼容回退提示", text.visible.contains("MySQL"))
            assertTrue(text.details.contains("candidate-a"))
            assertTrue(text.details.contains("candidate-b"))
        }
    }
}
