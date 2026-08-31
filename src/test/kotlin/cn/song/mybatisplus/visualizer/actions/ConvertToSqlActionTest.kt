package cn.song.mybatisplus.visualizer.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 转换 Action（正向 Wrapper→SQL）update() 可见性测试（F-44 续 · 补用例大波次）：
 * 此前 0 测试。update 读取 PSI_FILE 决定启用范围（Java 文件 / XML mapper 可用，其它文件不可用）。
 * 用 MapDataContext 构造 AnActionEvent，确定性校验可见性逻辑，不触发 actionPerformed。
 */
class ConvertToSqlActionTest : BasePlatformTestCase() {

    private fun eventFor(file: com.intellij.psi.PsiFile?, editor: com.intellij.openapi.editor.Editor?): AnActionEvent {
        val dc = MapDataContext()
        if (file != null) dc.put(CommonDataKeys.PSI_FILE.name, file)
        if (editor != null) dc.put(CommonDataKeys.EDITOR.name, editor)
        return AnActionEvent(null, dc, ActionPlaces.UNKNOWN, Presentation(), ActionManager.getInstance(), 0)
    }

    fun testUpdate_Java文件可见可用() {
        myFixture.configureByText("A.java", "class A {}")
        val e = eventFor(myFixture.file, myFixture.editor)
        ConvertToSqlAction().update(e)
        assertTrue("Java 文件应启用", e.presentation.isEnabledAndVisible)
    }

    fun testUpdate_Xml文件可见可用() {
        myFixture.configureByText("a.xml", "<root/>")
        val e = eventFor(myFixture.file, myFixture.editor)
        ConvertToSqlAction().update(e)
        assertTrue("XML mapper 文件应启用", e.presentation.isEnabledAndVisible)
    }

    fun testUpdate_非Java非Xml文件不可见() {
        myFixture.configureByText("a.txt", "x")
        val e = eventFor(myFixture.file, myFixture.editor)
        ConvertToSqlAction().update(e)
        assertFalse("普通文本文件不应启用", e.presentation.isEnabledAndVisible)
    }
}
