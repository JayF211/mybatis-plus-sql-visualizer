package cn.song.mybatisplus.visualizer.util

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.DefaultListModel

/** T-SqlCopyShortcuts O-122：列表复制基类的委派契约（复制文本 = 条目正文，不是渲染文本）。 */
class TextCopyListTest : BasePlatformTestCase() {

    private class Demo(element: String) {
        val text = element
    }

    private class DemoList(model: DefaultListModel<Demo>) : TextCopyList<Demo>(model) {
        override fun copyTextFor(element: Demo): String = element.text
        fun itemToTextForTest(index: Int): String = itemToText(index, model.getElementAt(index))
    }

    fun test复制文本委派给条目正文() {
        val model = DefaultListModel<Demo>()
        model.addElement(Demo("SELECT 1;"))
        model.addElement(Demo("SELECT 2;"))
        val list = DemoList(model)
        assertEquals("SELECT 1;", list.itemToTextForTest(0))
        assertEquals("SELECT 2;", list.itemToTextForTest(1))
    }

    fun test平台copy动作按条目正文逐行拼接() {
        val model = DefaultListModel<Demo>()
        model.addElement(Demo("SELECT 1;"))
        model.addElement(Demo("SELECT 2;"))
        val list = DemoList(model)
        list.selectedIndices = intArrayOf(0, 1)
        com.intellij.openapi.ide.CopyPasteManager.getInstance()
            .setContents(java.awt.datatransfer.StringSelection("__SENTINEL__"))
        val actionKey = list.inputMap.get(javax.swing.KeyStroke.getKeyStroke("control C"))
        assertNotNull("列表 Ctrl+C 绑定应由平台 LAF 提供", actionKey)
        list.actionMap.get(actionKey)
            .actionPerformed(java.awt.event.ActionEvent(list, java.awt.event.ActionEvent.ACTION_PERFORMED, actionKey.toString()))
        val clip: String? = com.intellij.openapi.ide.CopyPasteManager.getInstance()
            .getContents(java.awt.datatransfer.DataFlavor.stringFlavor)
        assertEquals("多选应逐行拼接条目正文", "SELECT 1;\nSELECT 2;", clip)
    }
}
