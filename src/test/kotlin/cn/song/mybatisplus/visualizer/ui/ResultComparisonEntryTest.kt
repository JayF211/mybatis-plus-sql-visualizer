package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.*
import cn.song.mybatisplus.visualizer.ui.insight.ComparisonExecutionPort
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.*

class ResultComparisonEntryTest : BasePlatformTestCase() {
    private fun all(root: Component): List<Component> = listOf(root) + if (root is Container) root.components.flatMap(::all) else emptyList()
    fun testExplicitPairApprovalsInvokeProductionPortOnceAndEditRevokesReview() {
        val panel = SqlPreviewPanel(project)
        var calls = 0
        try {
            val option = ComparisonTargetOption("target", "local test", "PostgreSQL")
            val target = ComparisonTargetSession { listOf(ComparisonTargetSession.Record(option, this, "config")) }
            target.select("target")
            panel.comparisonTargetForTest = target
            panel.comparisonExecutionForTest = ComparisonExecutionPort { request, safety, session, _, _ ->
                calls++
                assertEquals(request.targetGeneration, session.snapshot()!!.generation)
                assertTrue(safety.selectFunctionSideEffectsApproved)
                assertTrue(safety.parameterBindingsResolved)
            }
            panel.showResultComparison()
            assertEquals(0, calls)
            val host = panel.previewTabs.component("result-comparison")!!
            val components = all(host)
            val inputs = components.filterIsInstance<JTextArea>().filter { it.isEditable }
            assertEquals(2, inputs.size)
            inputs[0].text = "SELECT 1"; inputs[1].text = "SELECT 2"
            fun click(key: String) = components.filterIsInstance<JButton>().single { it.text == insightText(key) }.doClick()
            click("comparison.prepare")
            click("comparison.execute")
            assertEquals(0, calls)
            components.filterIsInstance<JCheckBox>().forEach { it.isSelected = true }
            click("comparison.execute")
            assertEquals(1, calls)
            click("comparison.execute")
            assertEquals(1, calls)
            click("comparison.prepare")
            components.filterIsInstance<JCheckBox>().forEach { it.isSelected = true }
            inputs[1].append(" + 1")
            click("comparison.execute")
            assertEquals(1, calls)
            panel.previewTabs.remove("result-comparison")
            assertNull(target.snapshot())
        } finally { panel.clear(); Disposer.dispose(panel) }
    }
}
