package cn.song.mybatisplus.visualizer.util

import com.intellij.openapi.editor.Editor
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.KeyStroke

/**
 * 编辑器「键盘复制」统一接线（T-SqlCopyShortcuts O-122）。
 *
 * 为什么必须在这个层位（T-ClickPreviewCopyRestore 二轮源码实证）：编辑器的 Ctrl+C 走 keymap
 * `$Copy`，而它要先从 DataContext 取 `PlatformDataKeys.COPY_PROVIDER`——编辑器组件
 * （`EditorComponentImpl.uiDataSnapshot`）**只提供 EDITOR/CARET、不提供 COPY_PROVIDER**，
 * 于是该 action 在编辑器里不可用、事件落回 Swing 分派；编辑器组件是 `JTextComponent`，
 * 自带的无选中 copy 是 **no-op** ⇒ 任何未接线的编辑器里「无选中按 Ctrl+C」都毫无反应。
 * 解法＝在**焦点组件自身**的 WHEN_FOCUSED InputMap 上覆盖 Ctrl+C / Ctrl+Insert（同 key `put`
 * 覆盖 JTextComponent 默认绑定，无优先级问题）。
 *
 * 语义契约（全站统一）：**无选区 → 复制全文**（含用户编辑后文本）；**有选区 → 只复制选区**。
 * 不接管键盘外的任何入口（各界面自带按钮/菜单复制照旧），也不改变编辑器本身的编辑行为。
 *
 * @param copyText 复制落点，生产传 [UiTextClipboard]::copy，测试直录断言
 * @param onCopied 复制后回调（用于「图标换对号」之类反馈）；null = 无反馈
 */
internal object EditorCopyShortcuts {

    /** ActionMap / InputMap 共用的动作键（put 到焦点组件自身）。 */
    private const val COPY_ACTION_KEY = "mpsv.editor.copyShortcut"

    /** 在 [editor] 的焦点组件上接管 Ctrl+C / Ctrl+Insert。 */
    fun install(editor: Editor, copyText: (String) -> Unit, onCopied: (() -> Unit)? = null) {
        val component = editor.component
        component.actionMap.put(
            COPY_ACTION_KEY,
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    val text = if (editor.selectionModel.hasSelection()) {
                        editor.selectionModel.selectedText ?: ""
                    } else {
                        editor.document.text
                    }
                    copyText(text)
                    onCopied?.invoke()
                }
            },
        )
        component.inputMap.put(KeyStroke.getKeyStroke("control C"), COPY_ACTION_KEY)
        component.inputMap.put(KeyStroke.getKeyStroke("control INSERT"), COPY_ACTION_KEY)
    }
}
