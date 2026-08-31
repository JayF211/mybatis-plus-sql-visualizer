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
 * SQL→Java 转换 Action update() 可见性测试（F-44 续 · 补用例大波次）：此前 0 测试。
 * update 读取编辑器选择区（UI 状态）决定是否启用——有非空选择区时可用，否则不可用。
 * 用 MapDataContext + 编辑器选择态构造 AnActionEvent，确定性校验，不触发 actionPerformed。
 */
class ConvertSqlToJavaActionTest : BasePlatformTestCase() {

    private fun eventFor(file: com.intellij.psi.PsiFile?, editor: com.intellij.openapi.editor.Editor?): AnActionEvent {
        val dc = MapDataContext()
        if (file != null) dc.put(CommonDataKeys.PSI_FILE.name, file)
        if (editor != null) dc.put(CommonDataKeys.EDITOR.name, editor)
        return AnActionEvent(null, dc, ActionPlaces.UNKNOWN, Presentation(), ActionManager.getInstance(), 0)
    }

    fun testUpdate_有选择区可见可用() {
        myFixture.configureByText("a.java", "select * from t")
        val editor = myFixture.editor
        editor.selectionModel.setSelection(0, editor.document.textLength)
        val e = eventFor(myFixture.file, editor)
        ConvertSqlToJavaAction().update(e)
        assertTrue("有选择区时应启用", e.presentation.isEnabledAndVisible)
    }

    fun testUpdate_无选择区不可见() {
        myFixture.configureByText("a.java", "select * from t")
        val e = eventFor(myFixture.file, myFixture.editor)
        ConvertSqlToJavaAction().update(e)
        assertFalse("无选择区时不应启用", e.presentation.isEnabledAndVisible)
    }
}
