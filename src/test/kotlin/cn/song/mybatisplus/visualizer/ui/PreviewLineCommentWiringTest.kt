package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.util.EditorCommentShortcuts
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * T-PreviewLineCommentDash O-134/D136：**面板接线覆盖**——预览面板真实构造链上的编辑器
 * 必须已打标（否则接管件全绿而用户按 Ctrl+/ 仍插 `#`；S4 反向验证的红点就是本合同）。
 *
 * 与 [cn.song.mybatisplus.visualizer.util.EditorCommentShortcutsTest] 的分工：那边验接管件本体
 * （打标/委托/计数），这边验「面板确实把编辑器交进接管件」+「释放链确实复原原动作」。
 * 面板生命周期照 [EditorWhitespaceSettingsTest] 既有形态（单面板 + 收尾 `clear()`，不 `dispose()`；
 * 建两个面板各 dispose 会撞 `SqlExecutor.releaseForPanel()` 单例重复释放）。
 */
class PreviewLineCommentWiringTest : BasePlatformTestCase() {

    private lateinit var originalAction: AnAction

    override fun setUp() {
        super.setUp()
        originalAction = ActionManager.getInstance().getAction(IdeActions.ACTION_COMMENT_LINE)!!
    }

    override fun tearDown() {
        try {
            ActionManager.getInstance().replaceAction(IdeActions.ACTION_COMMENT_LINE, originalAction)
        } finally {
            super.tearDown()
        }
    }

    private fun previewResult() = PreviewResult(
        sql = "SELECT id, name\nFROM user\nWHERE age > 1",
        compactSql = "SELECT id, name FROM user WHERE age > 1",
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = "user",
    )

    private fun contextOf(ed: Editor): DataContext = object : DataContext {
        override fun getData(dataId: String): Any? =
            if (CommonDataKeys.EDITOR.`is`(dataId)) ed else null
    }

    /** 与 keymap 同源：从动作注册表解析当前动作后执行。 */
    private fun triggerLineComment(ed: Editor) {
        val action = ActionManager.getInstance().getAction(IdeActions.ACTION_COMMENT_LINE)!!
        action.actionPerformed(AnActionEvent.createFromAnAction(action, null, "test", contextOf(ed)))
    }

    fun test预览自建编辑器已打标_端到端插双横线且再按对称还原() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(previewResult())
            val ed = panel.editorForTest()
            assertNotNull("预览面板应挂载编辑器", ed)
            ed!!
            assertTrue(
                "assemblePreviewEditor 装配的预览编辑器必须已打标——未打标时 Ctrl+/ 落到平台 MySQL commenter 插 `#`（本轮缺陷）",
                EditorCommentShortcuts.isMarked(ed),
            )
            assertTrue(
                "面板装配后该 id 应已换入自有动作",
                ActionManager.getInstance().getAction(IdeActions.ACTION_COMMENT_LINE)
                    is EditorCommentShortcuts.DashLineCommentAction,
            )
            val before = ed.document.text
            assertTrue("前置：预览文本非空", before.isNotBlank())
            // 全选后触发：行粒度整段切换 → 每个非空行插 `-- `
            WriteCommandAction.runWriteCommandAction(project) {
                ed.selectionModel.setSelection(0, ed.document.textLength)
            }
            triggerLineComment(ed)
            val after = ed.document.text
            val commented = after.split('\n').filter { it.isNotBlank() }
            assertTrue("触发后应有非空行", commented.isNotEmpty())
            assertTrue(
                "打标编辑器经动作注册表必须逐行插 `-- `（不得留 `#`）——实测：\n$after",
                commented.all { it.trimStart().startsWith("-- ") },
            )
            assertFalse("不得产出 MySQL `#` 前缀", after.contains('#'))
            // 再按一次：前一次已回设选区，两态对称还原
            triggerLineComment(ed)
            assertEquals("再按一次应逐字节还原", before, ed.document.text)
        } finally {
            panel.clear()
        }
    }

    fun test面板释放链摘除打标并复原原动作() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(previewResult())
        val ed = panel.editorForTest()
        assertNotNull("预览面板应挂载编辑器", ed)
        assertTrue("前置：面板编辑器应已打标", EditorCommentShortcuts.isMarked(ed!!))
        panel.clear()
        // clear → releaseEditor → disposeCurrentEditorOnly → uninstall：计数归零须复原平台原动作
        assertSame(
            "面板 clear/释放后应复原平台原动作（引用计数未归零即泄漏接管）",
            originalAction,
            ActionManager.getInstance().getAction(IdeActions.ACTION_COMMENT_LINE),
        )
    }
}
