// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/DegradationBanner.kt 移植
package cn.song.mybatisplus.visualizer.ui;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import java.awt.BorderLayout;

/**
 * 面板内嵌诚实降级横幅（T-PreviewUx-Pack #2，吸收 DBX「诚实降级」口径）。
 *
 * 面板内嵌的**非模态单行提示**，不算弹窗（docs/设计规范-弹窗克制红线.md 口径：面板内嵌
 * 提示不属于弹窗形态，弹窗克制零新增）。触发即显示、恢复即隐藏（不占高度）。
 * 文案三要素由各 i18n 键完整承载：哪段没还原 / 为什么 / 用户能做什么，绝不静默失败。
 *
 * 视觉照既有同类实现（LogToSqlPanel statusLabel / SqlPreviewPanel meta 警示同款）：
 * `ErrorForeground` 命名色 + JBUI 内边距，明暗主题自适应；文案自带 `⚠ ` 前缀（同 meta 行
 * 警示风格），不引入新图标形态。
 *
 * 消息类别与优先级（{@link Kind}，高优先不被低优先覆盖/清除）：
 * <pre>
 *  DEGRADED（降级链，最高）&gt; RENDER_WARNING（渲染警示）&gt; DOLLAR_INJECT（`${}` 直替
 *  信息提示，T-DollarInjectMark——措辞中性的注入面提示，**可关闭**：仅该类别带 ✕ 按钮，
 *  点击回调 {@link #onDismiss} 由调用方做会话级记忆）。
 * </pre>
 */
public class DegradationBanner extends JBPanel<DegradationBanner> {

    /** 消息类别（同类别「恢复即清除」；高优先类别不被低优先类别清除）。 */
    public enum Kind { RENDER_WARNING, DEGRADED, DOLLAR_INJECT }

    /** 类别优先级（数值大者优先——show 低优先不覆盖高优先）。 */
    private static int priority(Kind kind) {
        switch (kind) {
            case DEGRADED:
                return 2;
            case RENDER_WARNING:
                return 1;
            case DOLLAR_INJECT:
                return 0;
        }
        throw new IllegalArgumentException("unknown kind: " + kind);
    }

    /** T-DollarInjectMark：DOLLAR_INJECT 的 ✕ 关闭回调（调用方负责会话级记忆与 clear）。 */
    @Nullable
    public Runnable onDismiss = null;

    private final JBLabel label;

    /** 仅 DOLLAR_INJECT 可见的信息级关闭按钮（错误/降级类保持常驻不可关）。 */
    private final JBLabel dismissButton;

    /** 当前挂起的消息类别（null = 无提示）。 */
    @Nullable
    private Kind currentKind = null;

    public DegradationBanner() {
        super(new BorderLayout());

        label = new JBLabel();
        label.setForeground(JBColor.namedColor("ErrorForeground", JBColor.RED));
        label.setBorder(JBUI.Borders.empty(4, 8, 4, 8));
        // 详情可能含 SQL 片段（`<` 开头），禁用 HTML 解析纯文本渲染（SqlSelectionDialog 同款约定）
        label.putClientProperty("html.disable", true);

        dismissButton = new JBLabel("✕");
        dismissButton.setToolTipText(null);
        dismissButton.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        dismissButton.setBorder(JBUI.Borders.empty(4, 8, 4, 8));
        dismissButton.setForeground(JBColor.namedColor("ErrorForeground", JBColor.RED));
        dismissButton.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (onDismiss != null) onDismiss.run();
            }
        });
        dismissButton.setVisible(false);

        setOpaque(false);
        add(label, BorderLayout.CENTER);
        add(dismissButton, BorderLayout.EAST);
        setVisible(false);
    }

    /** 显示提示（文案由调用方按 i18n 键组装三要素）。 */
    public void show(Kind kind, String message) {
        // 高优先类别挂起期间，低优先提示不覆盖（DEGRADED 常驻语义的泛化——
        // RENDER_WARNING 挂起时 DOLLAR_INJECT 信息提示同样让位，警示清除后下次更新再回）
        Kind current = currentKind;
        if (current != null && priority(current) > priority(kind)) return;
        currentKind = kind;
        label.setText(message);
        dismissButton.setVisible(kind == Kind.DOLLAR_INJECT);
        dismissButton.setToolTipText(message);
        setVisible(true);
    }

    /**
     * 清除指定类别：当前挂起的是该类别才隐藏——渲染警示（低优先）清除不影响挂起的降级提示。
     */
    public void clear(Kind kind) {
        if (currentKind != kind) return;
        currentKind = null;
        label.setText("");
        dismissButton.setVisible(false);
        setVisible(false);
    }

    /** 测试访问器：当前文案（显隐断言用 isVisible）。 */
    public String textForTest() {
        return label.getText();
    }

    /** 测试访问器：当前挂起类别。 */
    @Nullable
    public Kind kindForTest() {
        return currentKind;
    }

    /** 测试访问器：✕ 关闭按钮可见性（仅 DOLLAR_INJECT）。 */
    public boolean dismissVisibleForTest() {
        return dismissButton.isVisible();
    }

    /** 测试触发器：模拟点击 ✕。 */
    public void clickDismissForTest() {
        if (dismissButton.isVisible() && onDismiss != null) onDismiss.run();
    }
}
