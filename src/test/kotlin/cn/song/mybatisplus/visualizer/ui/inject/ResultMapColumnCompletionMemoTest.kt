package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-PerfSweep16 F40：补全链全文重扫单槽 memo——同一文件（url+modificationStamp 未变）
 * 二次提取命中 memo，零重扫（探针缝注入计数 lambda；先红=无 memo 旁路 count 2）。
 */
class ResultMapColumnCompletionMemoTest : BasePlatformTestCase() {

    @Test
    fun testF40_同文件二次提取_命中memo零重扫() {
        val file = myFixture.configureByText(
            "m.xml",
            "<mapper namespace=\"n\"><select id=\"a\">SELECT order_id FROM t</select></mapper>",
        ) as XmlFile
        ResultMapColumnCompletionMemo.resetForTest()
        val original = ResultMapColumnCompletionMemo.extractor
        var count = 0
        ResultMapColumnCompletionMemo.extractor = { _ -> count++; listOf("order_id") }
        try {
            val first = ResultMapColumnCompletionMemo.columnsFor(file)
            val second = ResultMapColumnCompletionMemo.columnsFor(file)
            assertEquals("同文件二次提取只应触发一次全文重扫", 1, count)
            assertEquals("memo 命中返回同一候选清单", first, second)
        } finally {
            ResultMapColumnCompletionMemo.extractor = original
            ResultMapColumnCompletionMemo.resetForTest()
        }
    }

    @Test
    fun testF40_stamp变化_memo失效重扫() {
        val file = myFixture.configureByText(
            "m.xml",
            "<mapper namespace=\"n\"><select id=\"a\">SELECT order_id FROM t</select></mapper>",
        ) as XmlFile
        ResultMapColumnCompletionMemo.resetForTest()
        val original = ResultMapColumnCompletionMemo.extractor
        var count = 0
        ResultMapColumnCompletionMemo.extractor = { _ -> count++; listOf("c$count") }
        try {
            val first = ResultMapColumnCompletionMemo.columnsFor(file)
            // 文档变更 → modificationStamp 变化 → memo 必须失效回源（改文档须包 write action）
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.setText(
                    "<mapper namespace=\"n\"><select id=\"a\">SELECT order_id, user_id FROM t</select></mapper>",
                )
            }
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
            val second = ResultMapColumnCompletionMemo.columnsFor(file as XmlFile)
            assertEquals("stamp 变化后应回源重扫", 2, count)
            assertEquals("新文本新候选", listOf("c2"), second)
            org.junit.Assert.assertNotEquals(first, second)
        } finally {
            ResultMapColumnCompletionMemo.extractor = original
            ResultMapColumnCompletionMemo.resetForTest()
        }
    }
}
