package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.log.insight.*
import cn.song.mybatisplus.visualizer.ui.insight.*
import org.junit.Assert.*
import org.junit.Test
import javax.swing.JCheckBox
import javax.swing.SwingUtilities

class RuntimeInsightViewTest {
    @Test fun `opening and refreshing never opt in and close disables once`() {
        SwingUtilities.invokeAndWait {
            val changes = mutableListOf<Boolean>()
            val source = object : RuntimeInsightSource {
                override fun enable(enabled: Boolean) { changes += enabled }
                override fun snapshot() = RuntimeInsightSnapshot(emptyList(), emptyList(), emptyList())
            }
            val view = RuntimeInsightView({ it }, source, {}, {}, {})
            view.present(source.snapshot())
            assertTrue(changes.isEmpty())
            view.workload.actions.components.filterIsInstance<JCheckBox>().single().doClick()
            assertEquals(listOf(true), changes)
            view.dispose(); view.dispose()
            assertEquals(listOf(true, false), changes)
        }
    }
    @Test fun `toggling analysis keeps user choice until agent confirms and then reflects state`() {
        SwingUtilities.invokeAndWait {
            var sessionEnabled = false
            val source = object : RuntimeInsightSource {
                override fun enable(enabled: Boolean) { sessionEnabled = enabled }
                override fun snapshot() =
                    RuntimeInsightSnapshot(listOf(AnalysisSessionSnapshot(1, sessionEnabled, emptyList(), emptyList(), setOf("coverage_not_proven"), 0)), emptyList(), emptyList())
            }
            val view = RuntimeInsightView({ it }, source, {}, {}, {})
            view.present(source.snapshot())
            val box = view.workload.actions.components.filterIsInstance<JCheckBox>().single()
            assertFalse(box.isSelected)                      // 打开时按实际状态（未启用）
            // 勾选后，agent 尚未确认的滞后快照不得把勾选态弹回（T-RuntimeEnableLatency）
            sessionEnabled = false                            // 模拟 agent 未确认（快照 enabled=false）
            box.doClick()
            assertTrue(box.isSelected)                       // 勾选态保留
            view.present(source.snapshot())                  // 滞后快照再 present 一次也不弹回
            assertTrue(box.isSelected)
            // 状态落地（agent 确认）后，下次 present 不再覆盖用户选择，但状态栏反映实际
            sessionEnabled = true
            view.present(source.snapshot())
            assertTrue(box.isSelected)
            view.dispose()
        }
    }
    @Test fun `request without terminal or cross thread proof remains inconclusive`() {
        val evidence = SqlRequestEvidence("request", "run", true, false, null, null, null, null, false, true, emptyList())
        val request = ObservedRequest("request", null, null, null, evidence, 0L, emptyList())
        val snapshot = RuntimeInsightSnapshot(emptyList(), listOf(request), emptyList())
        val row = RuntimeInsightPresentation.requests(snapshot, SqlRequestBudget(100, 1_000_000)).single()
        assertEquals("INCONCLUSIVE", row.cells[2])
        assertTrue(row.detail.contains("cross_thread_coverage_unknown"))
    }
    @Test fun `page baseline does not invent producer totals for empty capture`() {
        val snapshot = RuntimeInsightSnapshot(emptyList(), emptyList(), emptyList())
        val baseline = RuntimeInsightPresentation.baseline(snapshot)
        assertNull(baseline.generatedCount)
        assertEquals(SqlBudgetStatus.INCONCLUSIVE, SqlWorkloadBaselineComparator.compare(baseline, baseline).status)
    }
}
