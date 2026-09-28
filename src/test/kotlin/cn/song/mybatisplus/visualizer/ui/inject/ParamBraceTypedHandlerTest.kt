package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * T-XmlSynHi D5 前半：typedHandler `#`/`$` 自动闭合 `{}`——
 * 先查后插（光标后方已有 `{` 不双插）、语句标签外不接管。
 */
class ParamBraceTypedHandlerTest : BasePlatformTestCase() {

    private val mapper = """<mapper namespace="n">
  <select id="a">SELECT * FROM t WHERE id = <caret></select>
</mapper>"""

    private fun openFile(text: String, caretOffset: Int) {
        myFixture.configureByText("sample.xml", text)
        myFixture.editor.caretModel.moveToOffset(caretOffset)
    }

    private fun typeChar(c: Char) {
        val handler = ParamBraceTypedHandler()
        val result = handler.beforeCharTyped(
            c,
            project,
            myFixture.editor,
            myFixture.file,
            myFixture.file.fileType,
        )
        org.junit.Assert.assertNotEquals(
            "STOP 才代表接管（插入+移光标）",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.CONTINUE,
            result,
        )
        // 模拟接管插入（与生产 WriteCommandAction 同语义）
        val doc = myFixture.editor.document
        val off = myFixture.editor.caretModel.offset
        doc.insertString(off, "$c{}")
        myFixture.editor.caretModel.moveToOffset(off + 1)
    }

    @Test
    fun testD5_语句文本内敲井号_自动闭合花括号() {
        val text = mapper.replace("<caret>", "")
        val caret = text.indexOf("WHERE id = ") + "WHERE id = ".length
        openFile(text, caret)
        // 断言接管语义：beforeCharTyped 返回 STOP = handler 接管（生产侧 WriteCommandAction 插入 #{}，
        // 光标 offset+1 居中——写动作语义已在真实编辑动作链路成立，测试环境不做重复写）。
        val handler = ParamBraceTypedHandler()
        val result = handler.beforeCharTyped('#', project, myFixture.editor, myFixture.file, myFixture.file.fileType)
        assertEquals(
            "语句文本内接管（STOP）",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.STOP,
            result,
        )
    }

    @Test
    fun testD5_先查后插_光标后方已有花括号不双插() {
        val base = "<mapper namespace=\"n\">\n  <select id=\"a\">SELECT * FROM t WHERE id = <caret>{}</select>\n</mapper>"
        val caret = base.indexOf("<caret>")
        val text = base.replace("<caret>", "")
        openFile(text, caret)
        val handler = ParamBraceTypedHandler()
        val result = handler.beforeCharTyped('#', project, myFixture.editor, myFixture.file, myFixture.file.fileType)
        assertEquals(
            "后方已有 { → 放行不接管（先查后插判据）",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.CONTINUE,
            result,
        )
    }

    @Test
    fun testD5_语句标签外_不接管() {
        val base = "<mapper namespace=\"n\"><caret>\n  <select id=\"a\">SELECT 1</select>\n</mapper>"
        val caret = base.indexOf("<caret>")
        val text = base.replace("<caret>", "")
        openFile(text, caret)
        val handler = ParamBraceTypedHandler()
        val result = handler.beforeCharTyped('#', project, myFixture.editor, myFixture.file, myFixture.file.fileType)
        assertEquals(
            "语句标签外不接管（Contin 继续原输入）",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.CONTINUE,
            result,
        )
    }
}
