package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * T-K2JWalk1 KW-1：预览编辑器双释放守卫。
 *
 * 真机形态（2026-10-02 沙盒走查）：跨工程切换残留的预览弹窗关闭时，两个面板实例先后持有
 * 同一编辑器，后到方的 releaseEditor 触发平台 TraceableDisposable「Double release of editor」
 * SEVERE（DisposalException 为 package-private，不可 catch）。修复 =
 * disposeCurrentEditorOnly 里 isDisposed 前置判断幂等跳过。
 *
 * 本测试用「先行释放 + 面板再释放」模拟双持有的后到方：
 * 摘掉守卫（直接 releaseEditor）时此处必抛 DisposalException（红）；守卫在场则幂等跳过（绿）。
 */
class SqlPreviewPanelDoubleReleaseGuardTest : BasePlatformTestCase() {

    private fun result() = PreviewResult(
        "select  id ,name  from t  where id=1",
        "select id,name from t where id=1",
        emptyList(),
        null,
        WrapperType.LAMBDA_QUERY,
        "t",
        null,
        listOf("id", "name"),
        emptyMap(),
        null,
        null,
        emptyList(),
        emptyList(),
        null,
        null,
        emptyMap(),
        null,
        null,
        null,
        null,
    )

    fun testReleaseEditorSkipsAlreadyDisposedEditor() {
        val panel = SqlPreviewPanel(project)
        try {
            SqlPreviewPanelResultViewKt.showResult(panel, result())
            val editor = panel.editorForTest()
            assertNotNull("前置：预览面板应已自建编辑器（无控制台挂载走 createEditor 分支）", editor)
            // 模拟双持有的第一次释放（另一面板/路径先行）
            EditorFactory.getInstance().releaseEditor(editor!!)
            assertTrue("前置：编辑器应处于已释放态", editor.isDisposed)
            // KW-1 守卫：已释放的编辑器再走面板释放链应幂等跳过（摘掉守卫必红）
            panel.releaseEditorForTest()
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
        }
    }
}
