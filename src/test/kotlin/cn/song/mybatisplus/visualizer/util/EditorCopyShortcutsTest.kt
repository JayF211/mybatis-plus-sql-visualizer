package cn.song.mybatisplus.visualizer.util

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotEquals
import java.awt.event.ActionEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * T-SqlCopyShortcuts O-122：编辑器键盘复制共享件的行为契约。
 *
 * 断言落在**按键解析落点**（InputMap → ActionMap）而非内部调用——二轮教训：直调 handler 绿
 * 不等于真机按键绿（见 pitfall-ctrl-c-copy-chain-mechanism）。
 */
class EditorCopyShortcutsTest : BasePlatformTestCase() {

    private fun editor(text: String): Editor =
        EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(text), project)

    private fun trigger(component: JComponent, stroke: String): String {
        // 触发签名形如真实按键解析：先查 InputMap 命中的 key，再执行对应 ActionMap 动作
        val actionKey = component.inputMap.get(KeyStroke.getKeyStroke(stroke))
        assertNotNull("$stroke 应有绑定（InputMap 命中）", actionKey)
        component.actionMap.get(actionKey)
            .actionPerformed(ActionEvent(component, ActionEvent.ACTION_PERFORMED, actionKey.toString()))
        return actionKey.toString()
    }

    fun test无选中复制全文_两个同义键都在场() {
        val ed = editor("SELECT * FROM user;")
        try {
            val captured = mutableListOf<String>()
            var copiedFeedback = 0
            EditorCopyShortcuts.install(ed, { captured.add(it) }, { copiedFeedback++ })

            val keyC = trigger(ed.component, "control C")
            val keyIns = trigger(ed.component, "control INSERT")
            assertEquals("Ctrl+C 与 Ctrl+Insert 应落在同一动作键", keyC, keyIns)
            assertNotEquals("应覆盖 JTextComponent 默认 'copy' 绑定", "copy", keyC)
            assertEquals(listOf("SELECT * FROM user;", "SELECT * FROM user;"), captured)
            assertEquals("每次复制都应触发反馈回调", 2, copiedFeedback)
        } finally {
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    fun test有选中只复制选区() {
        val ed = editor("SELECT * FROM user WHERE id = 1;")
        try {
            val captured = mutableListOf<String>()
            EditorCopyShortcuts.install(ed, { captured.add(it) })
            WriteCommandAction.runWriteCommandAction(project) {
                ed.selectionModel.setSelection(0, 6) // "SELECT"
            }
            trigger(ed.component, "control C")
            assertEquals(listOf("SELECT"), captured)
        } finally {
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    fun test编辑后文本按当前文档复制() {
        val ed = editor("SELECT 1;")
        try {
            val captured = mutableListOf<String>()
            EditorCopyShortcuts.install(ed, { captured.add(it) })
            WriteCommandAction.runWriteCommandAction(project) {
                ed.document.setText("SELECT edited FROM t;")
            }
            trigger(ed.component, "control C")
            assertEquals(listOf("SELECT edited FROM t;"), captured)
        } finally {
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    fun test无反馈回调时复制仍工作() {
        val ed = editor("SELECT 2;")
        try {
            val captured = mutableListOf<String>()
            EditorCopyShortcuts.install(ed, { captured.add(it) }, null)
            trigger(ed.component, "control C")
            assertEquals(listOf("SELECT 2;"), captured)
        } finally {
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }
}
