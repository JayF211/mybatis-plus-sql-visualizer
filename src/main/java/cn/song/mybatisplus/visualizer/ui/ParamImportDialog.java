// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ParamImportDialog.kt 移植（N3 监听父 disposable + P13 去空输入守卫 + O-44a 空白符接线）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.ui.highlight.JsonSyntaxHighlighter;
import cn.song.mybatisplus.visualizer.util.IconScaler;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Map;

/**
 * P-05.1：参数导入手动输入弹窗。
 *
 * 布局：顶部工具栏（格式化 / 删除）+ 中部 JSON 语法高亮编辑器 + 空态占位提示。
 */
public class ParamImportDialog extends DialogWrapper implements Disposable {

    /** 确认回调（输入文本）。 */
    public interface OnConfirm {
        void confirm(String text);
    }

    private final OnConfirm onConfirm;
    private final Project project;
    private Editor editor = null;

    private final JBLabel placeholderLabel = new JBLabel(
            MpSqlVisualizerBundle.message("panel.param.import.dialog.placeholder"));
    private static final float ICON_SCALE = 14f / 16f;

    public ParamImportDialog(@NotNull Project project, @NotNull OnConfirm onConfirm) {
        this(project, null, onConfirm);
    }

    public ParamImportDialog(@NotNull Project project, @Nullable String preFill, @NotNull OnConfirm onConfirm) {
        super(project, true);
        this.project = project;
        this.onConfirm = onConfirm;
        this.preFill = preFill;
        setTitle(MpSqlVisualizerBundle.message("panel.param.import.result.title"));
        init();
    }

    private final String preFill;

    @Override
    protected JComponent createCenterPanel() {
        JPanel content = new JPanel(new BorderLayout());
        content.add(createToolbar(), BorderLayout.NORTH);

        JComponent editorComponent = createEditor();
        JBScrollPane scrollPane = new JBScrollPane(editorComponent);
        scrollPane.setPreferredSize(new Dimension(DIALOG_WIDTH, DIALOG_HEIGHT));

        JPanel overlayPanel = new JPanel(new BorderLayout()) {
            {
                setOpaque(false);
                add(placeholderLabel, BorderLayout.NORTH);
                add(scrollPane, BorderLayout.CENTER);
            }
        };
        content.add(overlayPanel, BorderLayout.CENTER);

        // 监听文档变化，控制占位提示显隐
        // N3 修复（2026-09-21）：父 disposable 必须是 `disposable`（= DialogWrapper.getDisposable()），
        // **不能传 `this`**。平台 close() 处置的是 `myDisposable`（其 dispose() 再回调对话框的
        // dispose()），而 `this` 是 DialogWrapper 作为 Disposable 的**另一个**节点——它从没被登记进
        // Disposer 树，于是以 ROOT_DISPOSABLE 子节点身份留到应用退出，被 ObjectTree 报泄漏：
        // `'ParamImportDialog@…' was registered in Disposer as a child of 'ROOT_DISPOSABLE' but wasn't
        // disposed`（真机复现：开 3 次该对话框 → 退出时报 3 个实例泄漏，实例数与打开次数严格相等）。
        // 抽成 [attachPlaceholderListener] 是为了让「处置后监听不再响应」这条可被单测直接断言。
        if (editor != null) {
            attachPlaceholderListener(editor.getDocument());
        }
        updatePlaceholderVisibility();

        KeyStroke formatKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK);
        content.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(formatKeyStroke, "format");
        content.getActionMap().put("format", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                formatInput();
            }
        });
        return content;
    }

    /** 创建带 JSON 语法高亮的编辑器（不依赖 JSON 插件，Community 可用）。 */
    private JComponent createEditor() {
        String initialText = preFill != null ? preFill : "";
        Document doc = EditorFactory.getInstance().createDocument(initialText);
        Editor created = EditorFactory.getInstance().createEditor(doc, project);
        EditorEx createdEx = (EditorEx) created;
        createdEx.setHighlighter(new LexerEditorHighlighter(
                new JsonSyntaxHighlighter(),
                EditorColorsManager.getInstance().getGlobalScheme()));
        createdEx.getSettings().setLineNumbersShown(true);
        createdEx.getSettings().setFoldingOutlineShown(false);
        createdEx.getSettings().setIndentGuidesShown(false);
        createdEx.getSettings().setAdditionalPageAtBottom(false);
        // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
        EditorWhitespaceSettings.applyTo(createdEx);
        editor = created;
        return created.getComponent();
    }

    private void updatePlaceholderVisibility() {
        placeholderLabel.setVisible(editor == null || editor.getDocument() == null
                || editor.getDocument().getText().isBlank());
    }

    /**
     * N3：把「文档变化 → 刷新占位提示」的监听挂到<b>对话框自己的 disposable</b> 上。
     *
     * <p><b>父节点必须是 {@code getDisposable()}</b>（= `DialogWrapper.getDisposable()`）：平台在 `close()` 时处置的
     * 就是它（`myDisposable.dispose()` 再回调对话框的 `dispose()`）。若传 `this`（对话框作为
     * `Disposable` 的另一个节点），该节点从没进过 Disposer 树 → 以 ROOT_DISPOSABLE 子节点身份留到
     * 应用退出 → ObjectTree 报 `Memory leak detected: 'ParamImportDialog@…' … wasn't disposed`。
     *
     * <p>internal 是为了让单测能用<b>外部文档</b>断言「disposable 被处置后，监听不再响应」——直接观察
     * 对话框自身的占位标签做不到：`dispose()` 会把 `editor` 置 null，断言会恒真而失去判别力。
     *
     * <p>移植注记：master 侧为 `internal fun`（Java 侧落 `public`，k2j 惯例）；master 的
     * {@code onChanged: () -> Unit = ::updatePlaceholderVisibility} 默认实参在 Java 侧落为
     * {@link #attachPlaceholderListener(Document)} 单参重载（KOTLIN_TO_JAVA_RULES 默认参数 → 重载）。
     *
     * @param onChanged 文档变化时的动作；默认即生产行为（刷新占位提示），单测注入计数器以判别
     *   「处置后是否还在响应」（同理，注入点而非断言占位标签，是为了让断言有判别力）。
     */
    public void attachPlaceholderListener(Document document, Runnable onChanged) {
        document.addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                onChanged.run();
            }
        }, getDisposable());
    }

    /** {@link #attachPlaceholderListener(Document, Runnable)} 的默认实参形态（onChanged = 刷新占位提示）。 */
    public void attachPlaceholderListener(Document document) {
        attachPlaceholderListener(document, this::updatePlaceholderVisibility);
    }

    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        toolbar.setOpaque(false);
        toolbar.add(iconButton(IconScaler.scale(AllIcons.Actions.ReformatCode, ICON_SCALE),
                MpSqlVisualizerBundle.message("panel.param.import.dialog.format"), this::formatInput));
        toolbar.add(iconButton(IconScaler.scale(AllIcons.Actions.GC, ICON_SCALE),
                MpSqlVisualizerBundle.message("panel.param.import.dialog.clear"), this::clearInput));
        return toolbar;
    }

    private JButton iconButton(Icon icon, String tooltip, Runnable action) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setMargin(JBUI.insets(2));
        button.setPreferredSize(new Dimension(24, 24));
        button.addActionListener(e -> action.run());
        return button;
    }

    private void formatInput() {
        if (editor == null || editor.getDocument() == null) return;
        Document doc = editor.getDocument();
        String text = doc.getText();
        if (text.isBlank()) return;
        String trimmed = text.trim();
        String formatted = trimmed.startsWith("{") || trimmed.startsWith("[")
                ? formatJson(trimmed) : normalizeText(trimmed);
        if (!formatted.equals(text)) {
            WriteCommandAction.runWriteCommandAction(project, () -> doc.setText(formatted));
        }
    }

    private String formatJson(String text) {
        Map<String, String> entries = ParamImportParser.INSTANCE.parseJson(text);
        if (entries.isEmpty()) return text;
        StringBuilder sb = new StringBuilder("{\n");
        int idx = 0;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String escaped = e.getValue().replace("\\", "\\\\").replace("\"", "\\\"");
            sb.append("  \"").append(e.getKey()).append("\": \"").append(escaped).append("\"");
            if (idx < entries.size() - 1) sb.append(",");
            sb.append("\n");
            idx++;
        }
        sb.append("}");
        return sb.toString();
    }

    private String normalizeText(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (!t.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(t);
            }
        }
        return sb.toString();
    }

    private void clearInput() {
        if (editor != null && editor.getDocument() != null) {
            Document doc = editor.getDocument();
            WriteCommandAction.runWriteCommandAction(project, () -> doc.setText(""));
        }
    }

    /**
     * 确定：<b>空内容也回调</b>。
     *
     * <p>P13（2026-09-20 真机走查）：此前末句是 `if (text.isNotBlank()) onConfirm(text)`，其自带注释
     * 「避免空串走解析浪费」是纯性能微优化，代价却是「留空点确定」在界面上<b>零反馈</b>——回调被吞掉，
     * 下游 `importParams(emptyMap())` 与 `showEmptyClipboardHint()` 永不执行，与 T-Perf-20
     * 「空剪贴板给行内警示」的产品口径相互矛盾。故去掉该守卫：空串照常回调，由下游把「没识别到参数」
     * 这件事说出来（行内红字 + 「复制错误信息」按钮），而不是让确定按钮点了像没点。
     *
     * <p>空串代价可忽略：`ParamImportParser.parse` 首行 `if (text.isBlank()) return emptyMap()` 即返回，
     * 不存在「空串走完整解析」的开销，原注释担心的浪费并不成立。
     */
    @Override
    protected void doOKAction() {
        String text = (editor != null && editor.getDocument() != null) ? editor.getDocument().getText() : "";
        super.doOKAction();
        onConfirm.confirm(text);
    }

    @Override
    public void dispose() {
        if (editor != null) {
            EditorFactory.getInstance().releaseEditor(editor);
            editor = null;
        }
        super.dispose();
    }

    public String textForTest() {
        return (editor != null && editor.getDocument() != null) ? editor.getDocument().getText() : "";
    }

    public void setTextForTest(String text) {
        if (editor != null && editor.getDocument() != null) {
            Document doc = editor.getDocument();
            if (!doc.getText().equals(text)) {
                WriteCommandAction.runWriteCommandAction(project, () -> doc.setText(text));
            }
        }
    }

    public void triggerFormatForTest() {
        formatInput();
    }

    public void triggerClearForTest() {
        clearInput();
    }

    public static final int DIALOG_WIDTH = 480;
    public static final int DIALOG_HEIGHT = 200;
}
