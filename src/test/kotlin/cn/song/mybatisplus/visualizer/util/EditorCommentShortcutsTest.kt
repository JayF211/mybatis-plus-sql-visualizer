package cn.song.mybatisplus.visualizer.util

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * T-PreviewLineCommentDash O-134/D136：预览行注释统一 `--` 接管件的行为契约。
 *
 * 驱动方式＝**全局 handler 注册表解析落点**（`EditorActionManager.getActionHandler` 后直调 execute，
 * 与 keymap Ctrl+/ 的取用路径同源），不依赖 AnAction 分发（照 EditorCopyShortcutsTest 的落点原则）。
 */
class EditorCommentShortcutsTest : BasePlatformTestCase() {

    private lateinit var realHandler: EditorActionHandler

    override fun setUp() {
        super.setUp()
        realHandler = EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_COMMENT_LINE)
    }

    override fun tearDown() {
        try {
            // 兜底还原注册表（各用例 finally 已还原，此处防断言失败路径残留）
            EditorActionManager.getInstance().setActionHandler(IdeActions.ACTION_COMMENT_LINE, realHandler)
        } finally {
            super.tearDown()
        }
    }

    private fun editor(text: String): Editor =
        EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(text), project)

    /** 与 keymap Ctrl+/ 同源：从全局注册表解析当前 handler 后执行。 */
    private fun triggerLineComment(ed: Editor) {
        val handler = EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_COMMENT_LINE)
        handler.execute(ed, ed.caretModel.currentCaret, DataContext.EMPTY_CONTEXT)
    }

    /** 记名假 handler：未打标直通断言的被调探针（mock 前一 handler）。 */
    private class RecordingHandler(val name: String) : EditorActionHandler() {
        val executedOn: MutableList<String> = mutableListOf()

        override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
            executedOn.add(name)
        }

        override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext): Boolean = true
    }

    /** 全选当前文档（行粒度切换的最直接驱动）。 */
    private fun selectAll(ed: Editor) {
        WriteCommandAction.runWriteCommandAction(project) {
            ed.selectionModel.setSelection(0, ed.document.textLength)
        }
    }

    // ------------------------------------------------------------------
    // 纯逻辑：toggleLines 行级切换
    // ------------------------------------------------------------------

    fun test纯逻辑_补前缀保缩进() {
        assertEquals(
            listOf("-- SELECT 1;", "-- FROM t"),
            EditorCommentShortcuts.toggleLines(listOf("SELECT 1;", "    FROM t")),
        )
    }

    fun test纯逻辑_已注释行幂等不叠加() {
        assertEquals(
            listOf("-- SELECT 1;", "-- FROM t"),
            EditorCommentShortcuts.toggleLines(listOf("-- SELECT 1;", "FROM t")),
        )
    }

    fun test纯逻辑_全已注释则整段剥除_双横线与井号双认() {
        assertEquals(
            listOf("SELECT 1;", "FROM t", "WHERE 1 = 1"),
            EditorCommentShortcuts.toggleLines(listOf("-- SELECT 1;", "# FROM t", "-- WHERE 1 = 1")),
        )
    }

    fun test纯逻辑_空白行双态原样保留() {
        assertEquals(
            listOf("-- a", "", "-- b"),
            EditorCommentShortcuts.toggleLines(listOf("a", "", "b")),
        )
        assertEquals(
            listOf("a", "", "b"),
            EditorCommentShortcuts.toggleLines(listOf("-- a", "", "-- b")),
        )
    }

    fun test纯逻辑_剥除只吞前缀后一个空格() {
        assertEquals("  x", strip("--   x"))
        assertEquals("x", strip("--x"))
        assertEquals("x", strip("# x"))
    }

    private fun strip(line: String): String = EditorCommentShortcuts.toggleLines(listOf(line)).single()

    // ------------------------------------------------------------------
    // 全局 handler 注册表链路：打标编辑器接管 / 未打标直通
    // ------------------------------------------------------------------

    fun test打标编辑器_无选区注释光标行插双横线() {
        val ed = editor("SELECT 1;")
        try {
            EditorCommentShortcuts.install(ed)
            triggerLineComment(ed)
            assertEquals("-- SELECT 1;", ed.document.text)
        } finally {
            EditorCommentShortcuts.uninstall(ed)
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    fun test再按一次对称剥除() {
        val ed = editor("SELECT 1;")
        try {
            EditorCommentShortcuts.install(ed)
            triggerLineComment(ed)
            triggerLineComment(ed)
            assertEquals("SELECT 1;", ed.document.text)
        } finally {
            EditorCommentShortcuts.uninstall(ed)
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    fun test遗留井号残留行可剥除() {
        // 修复前 MySQL commenter 产出的 `#` 行（残留）+ 修复后的 `--` 行——整段剥除时一并剥
        val ed = editor("# old comment\n-- SELECT 1;")
        try {
            EditorCommentShortcuts.install(ed)
            selectAll(ed)
            triggerLineComment(ed)
            assertEquals("old comment\nSELECT 1;", ed.document.text)
        } finally {
            EditorCommentShortcuts.uninstall(ed)
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    fun test多行选区按行粒度整段切换_再按对称还原() {
        val ed = editor("a\nb\nc")
        try {
            EditorCommentShortcuts.install(ed)
            // 选区跨第 1~2 行（偏移 0..3 落在前两行的行界内）→ 行粒度只波及前两行
            WriteCommandAction.runWriteCommandAction(project) {
                ed.selectionModel.setSelection(0, 3)
            }
            triggerLineComment(ed)
            assertEquals("-- a\n-- b\nc", ed.document.text)
            // 前一次触发已按新范围回设选区：两行全已注释 → 再按对称剥除
            triggerLineComment(ed)
            assertEquals("a\nb\nc", ed.document.text)
        } finally {
            EditorCommentShortcuts.uninstall(ed)
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    fun test混合选区_已注释行幂等未注释行补前缀() {
        val ed = editor("-- a\nb")
        try {
            EditorCommentShortcuts.install(ed)
            selectAll(ed)
            triggerLineComment(ed)
            assertEquals("-- a\n-- b", ed.document.text)
        } finally {
            EditorCommentShortcuts.uninstall(ed)
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    fun test未打标编辑器直通原handler_零行为差异() {
        val manager = EditorActionManager.getInstance()
        val fake = RecordingHandler("previous")
        val saved = manager.setActionHandler(IdeActions.ACTION_COMMENT_LINE, fake)
        val marked = editor("SELECT 1;")
        val unmarked = editor("SELECT 2;")
        try {
            EditorCommentShortcuts.install(marked)
            // 未打标编辑器：直通前一 handler，本接管件不动它的文档
            triggerLineComment(unmarked)
            assertEquals(listOf("previous"), fake.executedOn)
            assertEquals("SELECT 2;", unmarked.document.text)
            // 打标编辑器：走自有切换，前一 handler 不被调
            triggerLineComment(marked)
            assertEquals("-- SELECT 1;", marked.document.text)
            assertEquals(1, fake.executedOn.size)
        } finally {
            EditorCommentShortcuts.uninstall(marked)
            EditorFactory.getInstance().releaseEditor(marked)
            EditorFactory.getInstance().releaseEditor(unmarked)
            manager.setActionHandler(IdeActions.ACTION_COMMENT_LINE, saved)
        }
    }

    fun test引用计数_最后一个打标编辑器卸载才恢复原handler() {
        val manager = EditorActionManager.getInstance()
        val fake = RecordingHandler("previous")
        val saved = manager.setActionHandler(IdeActions.ACTION_COMMENT_LINE, fake)
        val e1 = editor("a")
        val e2 = editor("b")
        try {
            EditorCommentShortcuts.install(e1)
            EditorCommentShortcuts.install(e2)
            EditorCommentShortcuts.uninstall(e1)
            // 还剩 e2 在场 → 在位的仍是自有 handler
            val current = manager.getActionHandler(IdeActions.ACTION_COMMENT_LINE)
            assertTrue("仍应是在位自有 handler", current is EditorCommentShortcuts.DashLineCommentHandler)
            EditorCommentShortcuts.uninstall(e2)
            // 最后一个卸载 → 恢复到注册时捕获的原 handler
            assertSame("应恢复原 handler", fake, manager.getActionHandler(IdeActions.ACTION_COMMENT_LINE))
            triggerLineComment(e2)
            assertEquals(listOf("previous"), fake.executedOn)
        } finally {
            EditorCommentShortcuts.uninstall(e1)
            EditorCommentShortcuts.uninstall(e2)
            EditorFactory.getInstance().releaseEditor(e1)
            EditorFactory.getInstance().releaseEditor(e2)
            manager.setActionHandler(IdeActions.ACTION_COMMENT_LINE, saved)
        }
    }

    fun test重复打标幂等_计数不虚增() {
        val manager = EditorActionManager.getInstance()
        val fake = RecordingHandler("previous")
        val saved = manager.setActionHandler(IdeActions.ACTION_COMMENT_LINE, fake)
        val ed = editor("SELECT 1;")
        try {
            EditorCommentShortcuts.install(ed)
            EditorCommentShortcuts.install(ed) // 幂等：不重复计数
            EditorCommentShortcuts.uninstall(ed)
            // 若重复 install 虚增了计数，这次卸载后 refCount 仍 >0、handler 未恢复
            assertSame("重复打标不应虚增计数——卸载一次即恢复", fake, manager.getActionHandler(IdeActions.ACTION_COMMENT_LINE))
        } finally {
            EditorCommentShortcuts.uninstall(ed)
            EditorFactory.getInstance().releaseEditor(ed)
            manager.setActionHandler(IdeActions.ACTION_COMMENT_LINE, saved)
        }
    }
}
