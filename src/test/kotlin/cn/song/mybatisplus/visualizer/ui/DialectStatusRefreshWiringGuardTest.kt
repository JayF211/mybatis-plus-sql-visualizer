package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-DialectStatusLiveRefresh 守卫：刷新链的真实挂点必须在场——纯策略测试证明不了
 * 「执行后确实会刷新」，本守卫钉住两处生产接线（embedConsole 单点钩子并列调用、
 * rerenderForExecutionTarget 决策同步），任一被摘即红。
 */
class DialectStatusRefreshWiringGuardTest {
    private fun read(path: String): String {
        val f = File(path)
        require(f.isFile) { "missing source file: $path" }
        return f.readText()
    }

    private val root = "src/main/kotlin/cn/song/mybatisplus/visualizer/ui"

    @Test
    fun `embedConsole hook calls refreshDialectStatusRow`() {
        val t = read("$root/SqlPreviewPanelExecution.kt")
        assertTrue("embedConsole 钩子丢失 refreshDialectStatusRow 调用",
            t.contains("runCatching { refreshDialectStatusRow() }"))
        // 必须与绑定行刷新同拍（同一 invokeLater 块），不能散落第二个挂点
        val bind = t.indexOf("runCatching { refreshBindStateRow() }")
        val dialect = t.indexOf("runCatching { refreshDialectStatusRow() }")
        assertTrue("方言刷新未与绑定刷新同拍", bind in 0 until dialect &&
            dialect - bind < 800)
    }

    @Test
    fun `rerenderForExecutionTarget upgrades decision before renderSql`() {
        val t = read("$root/SqlPreviewPanelExecution.kt")
        val fn = t.substringAfter("internal fun SqlPreviewPanel.rerenderForExecutionTarget")
            .substringBefore("internal fun SqlPreviewPanel.executeSql")
        assertTrue("重渲染路径未升级 currentDialectDecision",
            fn.contains("currentDialectDecision ="))
        val decide = fn.indexOf("currentDialectDecision =")
        val render = fn.indexOf("    renderSql()")
        assertTrue("决策升级必须先于 renderSql", decide in 0 until render)
        assertFalse("重渲染路径不得走异步刷新（同拍同步升级即可）", fn.contains("refreshDialectStatusRow"))
    }

    @Test
    fun `updateMeta consumes panel decision not raw result field`() {
        val t = read("$root/SqlPreviewPanelResultView.kt")
        assertTrue("updateMeta 未写入面板决策", t.contains("currentDialectDecision = result.dialectDecision"))
        assertTrue("updateMeta 未走统一重组入口", t.contains("applyDialectStatusSegment()"))
    }

    @Test
    fun `clear resets dialect decision and segments`() {
        val t = read("$root/SqlPreviewPanelSqlView.kt")
        assertTrue("clear 未复位方言决策（会跨预览残留）",
            t.contains("currentDialectDecision = null"))
        assertTrue("clear 未复位 tooltip 分段", t.contains("metaTooltipCore = \"\""))
    }
}
