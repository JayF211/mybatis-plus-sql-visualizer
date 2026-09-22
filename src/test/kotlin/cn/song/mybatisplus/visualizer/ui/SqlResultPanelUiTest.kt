package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.awt.Component
import java.awt.Container
import javax.swing.JButton

/**
 * SQL 执行结果面板 UI 测试（F-44-6 续 · 补用例）：
 * 此前 0 测试，正是用户点名的「SqlPreviewPanel 错误态」薄弱点。覆盖：
 *   - 状态机 EMPTY/LOADING/ERROR/SUCCESS 正确切换（stateForTest 缝）；
 *   - showError：HTML 转义（防 < > & 破坏布局）、tooltip 存完整错误、errorCopyButton 显隐；
 *   - showIdle 保留 ERROR（防"一闪而过"）但清 LOADING/SUCCESS；
 *   - 自动可见性：有内容显示、空态隐藏（不占高度）。
 *
 * T-ELU-05（T-Round10-03）追加——错误定位按钮的**可用性判定**（先解析、后显隐）：
 *   - 无锚点 ⇒ 按钮不出现（**先红用例**：改前富错误投递链恒传非 null 动作，按钮恒可见）；
 *   - 锚点可解析 ⇒ 按钮出现；
 *   - 旧通道（onError）不得在富通道之后再投一次（`showError` 会抹掉定位态）。
 */
class SqlResultPanelUiTest : BasePlatformTestCase() {

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    private fun allComponents(c: Component): List<Component> {
        val list = mutableListOf(c)
        if (c is Container) for (child in c.components) list += allComponents(child)
        return list
    }

    /** 错误复制按钮：JButton 且 text == bundle panel.copyError。 */
    private fun copyButton(panel: SqlResultPanel): JButton? =
        allComponents(panel).filterIsInstance<JButton>().firstOrNull { it.text == MpSqlVisualizerBundle.message("panel.copyError") }

    /** T-ELU-05：错误定位按钮：JButton 且 text == bundle execute.error.locate。 */
    private fun locateButton(panel: SqlResultPanel): JButton? =
        allComponents(panel).filterIsInstance<JButton>().firstOrNull { it.text == MpSqlVisualizerBundle.message("execute.error.locate") }

    /** 信息标签：唯一非空 JBLabel（CENTER infoLabel）。 */
    private fun infoLabel(panel: SqlResultPanel): JBLabel? =
        allComponents(panel).filterIsInstance<JBLabel>().firstOrNull { it.text.isNotEmpty() }

    fun testShowError_状态与复制按钮与可见性() {
        val panel = SqlResultPanel()
        try {
            panel.showError("boom: connection refused")
            waitForEdt()
            assertEquals("showError 应进入 ERROR 态", "ERROR", panel.stateForTest())
            assertEquals("currentError 应保留原始错误文本", "boom: connection refused", panel.currentErrorForTest())
            assertTrue("ERROR 态面板应可见", panel.isVisible)
            val btn = copyButton(panel)
            assertNotNull("错误复制按钮应存在", btn)
            assertTrue("错误复制按钮应可见", btn!!.isVisible)
        } finally {
            panel.clear()
        }
    }

    fun testShowError_HTML转义防布局破坏() {
        val panel = SqlResultPanel()
        try {
            // 含 < > & 与注入式序列——必须被转义，否则会破坏 infoLabel HTML 渲染
            val raw = "a < b & c > d </span><script>alert(1)</script>"
            panel.showError(raw)
            waitForEdt()
            val text = infoLabel(panel)?.text ?: ""
            assertTrue("错误标签文本应包含 HTML 包裹", text.startsWith("<html>"))
            assertTrue("'<' 应被转义为 &lt;", text.contains("&lt;"))
            assertTrue("'>' 应被转义为 &gt;", text.contains("&gt;"))
            assertTrue("'&' 应被转义为 &amp;", text.contains("&amp;"))
            assertFalse("原始 '<' 不应直接出现（防注入/布局破坏）", text.contains("a < b"))
            // tooltip 保留完整原始错误（hover 可读）
            assertEquals("tooltip 应保留完整原始错误", raw, infoLabel(panel)?.toolTipText)
        } finally {
            panel.clear()
        }
    }

    fun testShowIdle_保留错误态防一闪而过() {
        val panel = SqlResultPanel()
        try {
            panel.showError("boom")
            waitForEdt()
            panel.showIdle()
            waitForEdt()
            assertEquals("showIdle 在 ERROR 态下应保留 ERROR", "ERROR", panel.stateForTest())
            assertEquals("showIdle 应保留错误文本（可复制/可读）", "boom", panel.currentErrorForTest())
            assertTrue("showIdle 在 ERROR 态下面板仍可见", panel.isVisible)
            assertTrue("showIdle 在 ERROR 态下复制按钮仍可见", copyButton(panel)!!.isVisible)
        } finally {
            panel.clear()
        }
    }

    fun testShowIdle_清除非错误态() {
        val panel = SqlResultPanel()
        try {
            panel.showLoading("ds1")
            waitForEdt()
            assertEquals("showLoading 应为 LOADING 态", "LOADING", panel.stateForTest())
            panel.showIdle()
            waitForEdt()
            assertEquals("showIdle 在 LOADING 态应回落 EMPTY", "EMPTY", panel.stateForTest())
            assertFalse("showIdle 后面板应隐藏（不占高度）", panel.isVisible)
            assertNull("showIdle 后 currentError 应为 null", panel.currentErrorForTest())
        } finally {
            panel.clear()
        }
    }

    fun test成功与加载态_隐藏复制按钮且可见() {
        val panel = SqlResultPanel()
        try {
            panel.showQueryResult(10, 25, "mysql")
            waitForEdt()
            assertEquals("showQueryResult 应为 SUCCESS 态", "SUCCESS", panel.stateForTest())
            assertTrue("结果态面板应可见", panel.isVisible)
            assertFalse("成功态不应显示错误复制按钮", copyButton(panel)!!.isVisible)

            panel.showUpdateResult(3, 12, "mysql")
            waitForEdt()
            assertEquals("showUpdateResult 应为 SUCCESS 态", "SUCCESS", panel.stateForTest())

            panel.showLoading()
            waitForEdt()
            assertEquals("showLoading 应为 LOADING 态", "LOADING", panel.stateForTest())
            assertFalse("加载态不应显示错误复制按钮", copyButton(panel)!!.isVisible)
        } finally {
            panel.clear()
        }
    }

    fun testShowEmpty_隐藏面板且清空错误() {
        val panel = SqlResultPanel()
        try {
            panel.showError("boom")
            waitForEdt()
            panel.showEmpty()
            waitForEdt()
            assertFalse("空态面板应隐藏", panel.isVisible)
            assertEquals("空态应为 EMPTY", "EMPTY", panel.stateForTest())
            assertNull("空态 currentError 应清空", panel.currentErrorForTest())
            assertFalse("空态复制按钮应隐藏", copyButton(panel)!!.isVisible)
        } finally {
            panel.clear()
        }
    }

    // ------------------------------------------------------------------
    // T-ELU-05（T-Round10-03）：错误定位按钮显隐判据
    // ------------------------------------------------------------------

    private fun errorInfo(msg: String) =
        cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo(message = msg)

    /**
     * **本任务的先红用例**（改前必红）：无锚点 ⇒ 定位按钮不出现。
     *
     * 判据必须走 [SqlPreviewPanel] 的富错误投递链（[SqlPreviewPanel.showErrorRichWithLocateForTest]）——
     * 直接调 [SqlResultPanel.showErrorRich] 传 null 在改前改后都绿（`SqlResultPanel.kt:252` 本就按
     * null 判定），证不了「投递点是否先解析」。改前该链恒传非 null 动作 ⇒ 按钮可见 ⇒ 本用例红。
     */
    fun test错误定位按钮_无锚点时不可见() {
        val preview = SqlPreviewPanel(project)
        try {
            // 本会话未登记过锚点（SqlPreviewService.lastPreviewSourceAnchor 默认 null）
            preview.showErrorRichWithLocateForTest(errorInfo("boom: syntax error near 'FORM'"))
            waitForEdt()
            val btn = locateButton(preview.resultPanelForTest())
            assertNotNull("定位按钮应存在于结果面板", btn)
            assertFalse("锚点不可解析 ⇒ 定位按钮不出现（不得可见而点击无反应）", btn!!.isVisible)
        } finally {
            preview.clear()
            preview.dispose()
        }
    }

    /** 锚点可解析 ⇒ 定位按钮出现（对照半边；与上例共同构成「先解析后显隐」的双向判据）。 */
    fun test错误定位按钮_锚点可解析时可见() {
        val preview = SqlPreviewPanel(project)
        try {
            val vf = myFixture.addFileToProject("AnchorHost.java", "class AnchorHost {}").virtualFile
            project.getService(SqlPreviewService::class.java).lastPreviewSourceAnchor =
                SqlPreviewService.SourceAnchor(vf, 2)
            preview.showErrorRichWithLocateForTest(errorInfo("boom: syntax error near 'FORM'"))
            waitForEdt()
            val btn = locateButton(preview.resultPanelForTest())
            assertNotNull("定位按钮应存在于结果面板", btn)
            assertTrue("锚点可解析 ⇒ 定位按钮出现", btn!!.isVisible)
        } finally {
            project.getService(SqlPreviewService::class.java).lastPreviewSourceAnchor = null
            preview.clear()
            preview.dispose()
        }
    }

    /**
     * T-ELU-01 抹除链回归：富通道投递后旧通道（`onError`）**不得**再投一次——`showError` 会清掉
     * 定位动作与按钮可见性（`SqlResultPanel.kt:231-232`），两条都投则按钮恒不可见。
     * 本用例钉住 `DataSourceSelector.deliverError` 的互斥契约（生产投递点共用同一函数）。
     */
    fun test错误定位按钮_旧通道不得抹掉富通道的定位态() {
        val preview = SqlPreviewPanel(project)
        try {
            val vf = myFixture.addFileToProject("AnchorHost2.java", "class AnchorHost2 {}").virtualFile
            project.getService(SqlPreviewService::class.java).lastPreviewSourceAnchor =
                SqlPreviewService.SourceAnchor(vf, 0)
            val panel = preview.resultPanelForTest()
            var richSeen = false
            var plainSeen = false
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.deliverError(
                onErrorRich = { info -> richSeen = true; panel.showErrorRich(info) { } },
                onError = { plainSeen = true; panel.showError(it) },
                cause = null,
                message = "boom",
            )
            waitForEdt()
            assertTrue("富通道应被投递", richSeen)
            assertFalse("富通道在场时旧通道不得再投（否则抹掉定位按钮）", plainSeen)
            assertTrue("富通道置的定位按钮应保持可见", locateButton(panel)!!.isVisible)
        } finally {
            project.getService(SqlPreviewService::class.java).lastPreviewSourceAnchor = null
            preview.clear()
            preview.dispose()
        }
    }
}
