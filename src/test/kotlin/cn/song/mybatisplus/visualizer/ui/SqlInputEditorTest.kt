package cn.song.mybatisplus.visualizer.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/**
 * SQL 可编辑输入编辑器测试（F-44 续 · 补用例大波次）：此前 0 测试。
 * 覆盖：构造持有初始 SQL；setSql 程序性替换全文（且相同文本幂等不重复写入）；
 * dispose 释放编辑器且无异常、二次释放幂等。无需新增生产缝（editor/text/dispose 均为 public）。
 */
class SqlInputEditorTest : BasePlatformTestCase() {

    fun test构造_编辑器持有初始SQL() {
        val editor = SqlInputEditor(myFixture.project, "select 1")
        try {
            assertNotNull(editor.editor)
            assertEquals("select 1", editor.text)
        } finally {
            editor.dispose()
        }
    }

    fun testSetSql_替换全文且释放无异常() {
        val editor = SqlInputEditor(myFixture.project, "select 1")
        try {
            editor.setSql("select 2")
            assertEquals("setSql 应替换全文", "select 2", editor.text)
            editor.setSql("select 2") // 相同文本：setSql 早退，不重复写入
            assertEquals("select 2", editor.text)
        } finally {
            editor.dispose()
            editor.dispose() // 二次释放幂等
        }
    }

    // ---------- T-AutoFold F4-d：程序性写入（setSql）重算折叠 ----------

    private fun withAutoFold(enabled: Boolean, block: () -> Unit) {
        val state = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        val oldEnabled = state.autoFoldEnabled
        val oldThreshold = state.autoFoldFieldThreshold
        try {
            state.autoFoldEnabled = enabled
            state.autoFoldFieldThreshold = 3
            block()
        } finally {
            state.autoFoldEnabled = oldEnabled
            state.autoFoldFieldThreshold = oldThreshold
        }
    }

    private fun ownFoldRegions(editor: com.intellij.openapi.editor.Editor) =
        editor.foldingModel.allFoldRegions.filter { it.placeholderText.startsWith("…") }

    fun testSetSql_开启折叠后区域存在() {
        withAutoFold(enabled = true) {
            val editor = SqlInputEditor(myFixture.project, "select 1")
            try {
                editor.setSql("SELECT\n    a1,\n    a2,\n    a3,\n    a4\nFROM t")
                val regions = ownFoldRegions(editor.editor)
                assertEquals("setSql 程序性写入后应有折叠区域", 1, regions.size)
            } finally {
                editor.dispose()
            }
        }
    }

    fun test构造_关闭折叠零区域() {
        withAutoFold(enabled = false) {
            val editor = SqlInputEditor(myFixture.project, "SELECT\n    a1,\n    a2,\n    a3,\n    a4\nFROM t")
            try {
                assertEquals("关闭态构造后零折叠区域", 0, ownFoldRegions(editor.editor).size)
            } finally {
                editor.dispose()
            }
        }
    }
}
