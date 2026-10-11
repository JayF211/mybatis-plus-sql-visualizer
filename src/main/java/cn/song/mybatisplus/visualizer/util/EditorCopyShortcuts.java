// T-SyncK2JMaster482 自 master util/EditorCopyShortcuts.kt 移植（T-SqlCopyShortcuts O-122）
package cn.song.mybatisplus.visualizer.util;

import com.intellij.openapi.editor.Editor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.event.ActionEvent;
import java.util.function.Consumer;

/**
 * 编辑器「键盘复制」统一接线（T-SqlCopyShortcuts O-122）。
 *
 * <p>为什么必须在这个层位（T-ClickPreviewCopyRestore 二轮源码实证）：编辑器的 Ctrl+C 走 keymap
 * {@code $Copy}，而它要先从 DataContext 取 {@code PlatformDataKeys.COPY_PROVIDER}——编辑器组件
 * （{@code EditorComponentImpl.uiDataSnapshot}）<b>只提供 EDITOR/CARET、不提供 COPY_PROVIDER</b>，
 * 于是该 action 在编辑器里不可用、事件落回 Swing 分派；编辑器组件是 {@code JTextComponent}，
 * 自带的无选中 copy 是 <b>no-op</b> ⇒ 任何未接线的编辑器里「无选中按 Ctrl+C」都毫无反应。
 * 解法＝在<b>焦点组件自身</b>的 WHEN_FOCUSED InputMap 上覆盖 Ctrl+C / Ctrl+Insert（同 key {@code put}
 * 覆盖 JTextComponent 默认绑定，无优先级问题）。
 *
 * <p>语义契约（全站统一）：<b>无选区 → 复制全文</b>（含用户编辑后文本）；<b>有选区 → 只复制选区</b>。
 * 不接管键盘外的任何入口（各界面自带按钮/菜单复制照旧），也不改变编辑器本身的编辑行为。
 *
 * <p>Kotlin {@code object} → 全静态工具类；{@code onCopied} 默认参数（null = 无反馈）以重载表达
 * （Kotlin 调用方仍以 {@code onCopied = …} 具名实参走三参重载）。
 */
public final class EditorCopyShortcuts {

    private EditorCopyShortcuts() {}

    /** ActionMap / InputMap 共用的动作键（put 到焦点组件自身）。 */
    private static final String COPY_ACTION_KEY = "mpsv.editor.copyShortcut";

    /**
     * 在 {@code editor} 的焦点组件上接管 Ctrl+C / Ctrl+Insert（无复制后回调）。
     *
     * @param copyText 复制落点，生产传 {@link UiTextClipboard#copy}，测试直录断言
     */
    public static void install(@NotNull Editor editor, @NotNull Consumer<String> copyText) {
        install(editor, copyText, null);
    }

    /**
     * 在 {@code editor} 的焦点组件上接管 Ctrl+C / Ctrl+Insert。
     *
     * @param copyText 复制落点，生产传 {@link UiTextClipboard#copy}，测试直录断言
     * @param onCopied 复制后回调（用于「图标换对号」之类反馈）；null = 无反馈
     */
    public static void install(@NotNull Editor editor, @NotNull Consumer<String> copyText,
                               @Nullable Runnable onCopied) {
        JComponent component = editor.getComponent();
        component.getActionMap().put(COPY_ACTION_KEY, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String text;
                if (editor.getSelectionModel().hasSelection()) {
                    String selected = editor.getSelectionModel().getSelectedText();
                    text = selected != null ? selected : "";
                } else {
                    text = editor.getDocument().getText();
                }
                copyText.accept(text);
                if (onCopied != null) onCopied.run();
            }
        });
        component.getInputMap().put(KeyStroke.getKeyStroke("control C"), COPY_ACTION_KEY);
        component.getInputMap().put(KeyStroke.getKeyStroke("control INSERT"), COPY_ACTION_KEY);
    }
}
