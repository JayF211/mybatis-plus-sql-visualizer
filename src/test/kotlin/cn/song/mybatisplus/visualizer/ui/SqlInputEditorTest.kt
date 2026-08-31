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
}
