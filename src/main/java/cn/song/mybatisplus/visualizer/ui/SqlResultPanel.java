package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/**
 * SQL 执行结果面板（M36）：在 SQL 预览面板下方展示执行状态与结果摘要。
 */
public class SqlResultPanel extends JBPanel<SqlResultPanel> {

    private enum State { EMPTY, LOADING, ERROR, SUCCESS }

    private final JBLabel statusLabel = new JBLabel();
    private final JBLabel infoLabel = new JBLabel();

    /**
     * T-UiNative T-UN-14：加载态转圈接原生 {@link AnimatedIcon.Default}（243 javap 核实），
     * 与既有文案并排（WEST 位，仅 LOADING 态可见）；原纯文字 JBLabel 加载态的
     * 文案键（"execute.result.loading"）沿用不变，测试断言文字仍成立。
     * 文字用空串（而非 JBLabel(Icon) 构造器）——保持 text 非 null，组件遍历类
     * 测试（SqlResultPanelUiTest/SqlPreviewPanelUiTest 的非空标签断言）零适配。
     */
    private final JBLabel loadingIconLabel;

    private final JButton errorCopyButton;
    private String currentError = null;
    private State state = State.EMPTY;

    public SqlResultPanel() {
        infoLabel.setForeground(JBColor.GRAY);
        infoLabel.setBorder(JBUI.Borders.empty(1, 4));

        loadingIconLabel = new JBLabel("", new AnimatedIcon.Default(), SwingConstants.CENTER);
        loadingIconLabel.setVisible(false);
        loadingIconLabel.setBorder(JBUI.Borders.empty(0, 4, 0, 0));

        errorCopyButton = new JButton(MpSqlVisualizerBundle.message("panel.copyError"));
        errorCopyButton.setVisible(false);
        errorCopyButton.setOpaque(false);
        errorCopyButton.setContentAreaFilled(false);
        errorCopyButton.setBorder(JBUI.Borders.empty(1, 6));
        errorCopyButton.setFont(errorCopyButton.getFont().deriveFont(11f));
        errorCopyButton.setForeground(JBColor.namedColor("Link.activeForeground", JBColor.GRAY));
        errorCopyButton.setMargin(JBUI.insets(0, 6, 0, 6));
        errorCopyButton.addActionListener(e -> {
            if (currentError != null) {
                copyToClipboard(currentError);
                // T-ExecCopyFix：复制成功反馈（对齐 SqlClickPreview.flashCopied 对号语义）
                CopyFeedback.flashCopied(errorCopyButton);
            }
        });

        setBorder(JBUI.Borders.empty());
        setOpaque(false);
        // T-UiNative T-UN-14：加载转圈放左侧（CENTER 是 infoLabel 文案，EAST 是错误复制按钮）
        add(loadingIconLabel, BorderLayout.WEST);
        add(infoLabel, BorderLayout.CENTER);
        JBPanel<JBPanel<?>> east = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        east.setOpaque(false);
        east.setBorder(JBUI.Borders.empty());
        east.add(errorCopyButton);
        add(east, BorderLayout.EAST);
        setVisible(false);
    }

    private void showContent() {
        setVisible(true);
    }

    /** T-UiNative T-UN-14：转圈图标随状态显隐（仅 showLoading 打开，其余状态统一关闭）。 */
    private void setLoadingIconVisible(boolean visible) {
        loadingIconLabel.setVisible(visible);
    }

    public void showTempFileOpened(String dataSourceName) {
        state = State.EMPTY;
        currentError = null;
        errorCopyButton.setVisible(false);
        setLoadingIconVisible(false);
        String dsInfo = dataSourceName != null && !dataSourceName.isEmpty()
                ? " | " + MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName) : "";
        infoLabel.setText("<html>" + MpSqlVisualizerBundle.message("execute.fileOpened") + dsInfo + "</html>");
        infoLabel.setForeground(JBColor.GRAY);
        showContent();
        revalidate();
        repaint();
    }

    /**
     * 显示执行中状态（T-UiNative T-UN-14：转圈为原生 AnimatedIcon.Default，文字沿用既有键）。
     */
    public void showLoading(String dataSourceName) {
        state = State.LOADING;
        currentError = null;
        errorCopyButton.setVisible(false);
        setLoadingIconVisible(true);
        String dsInfo = dataSourceName != null && !dataSourceName.isEmpty()
                ? " | " + MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName) : "";
        infoLabel.setText(dataSourceName != null && !dataSourceName.isEmpty()
                ? "<html>" + MpSqlVisualizerBundle.message("execute.result.loading") + " | "
                + MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName) + "</html>"
                : MpSqlVisualizerBundle.message("execute.result.loading"));
        infoLabel.setForeground(JBColor.GRAY);
        showContent();
        revalidate();
        repaint();
    }

    public void showUpdateResult(int affectedRows, long elapsedMs, String dataSourceName) {
        state = State.SUCCESS;
        currentError = null;
        errorCopyButton.setVisible(false);
        setLoadingIconVisible(false);
        String dsInfo = dataSourceName != null && !dataSourceName.isEmpty()
                ? " | " + MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName) : "";
        infoLabel.setText("<html>" + MpSqlVisualizerBundle.message("execute.result.affected", affectedRows, elapsedMs) + dsInfo + "</html>");
        infoLabel.setForeground(JBColor.namedColor("Label.successForeground", java.awt.Color.BLACK));
        showContent();
        revalidate();
        repaint();
    }

    public void showQueryResult(int rowCount, long elapsedMs, String dataSourceName) {
        state = State.SUCCESS;
        currentError = null;
        errorCopyButton.setVisible(false);
        setLoadingIconVisible(false);
        String dsInfo = dataSourceName != null && !dataSourceName.isEmpty()
                ? " | " + MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName) : "";
        infoLabel.setText("<html>" + MpSqlVisualizerBundle.message("execute.result.rows", rowCount, elapsedMs) + dsInfo + "</html>");
        infoLabel.setForeground(JBColor.namedColor("Label.successForeground", java.awt.Color.BLACK));
        showContent();
        revalidate();
        repaint();
    }

    public void showError(String error) {
        state = State.ERROR;
        currentError = error;
        setLoadingIconVisible(false);
        String esc = error.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        infoLabel.setText("<html>" + MpSqlVisualizerBundle.message("execute.result.error", esc) + "</html>");
        infoLabel.setForeground(JBColor.namedColor("ErrorForeground", JBColor.RED));
        infoLabel.setToolTipText(error);
        errorCopyButton.setVisible(true);
        errorCopyButton.revalidate();
        errorCopyButton.repaint();
        showContent();
        revalidate();
        repaint();
    }

    public void showEmpty() {
        state = State.EMPTY;
        currentError = null;
        errorCopyButton.setVisible(false);
        setLoadingIconVisible(false);
        infoLabel.setText("");
        setVisible(false);
        revalidate();
        repaint();
    }

    public void clear() {
        showEmpty();
    }

    /**
     * 执行完成后的"空闲收尾"——清 loading/成功态，但**保留错误**（ERROR 态不清）。
     */
    public void showIdle() {
        if (state == State.ERROR) {
            revalidate();
            repaint();
            return;
        }
        showEmpty();
    }

    private void copyToClipboard(String text) {
        UiTextClipboard.copy(text);
    }

    /** M36.5.48：当前错误文本（测试断言用）。 */
    public String currentErrorForTest() {
        return currentError;
    }

    /** M36.5.49：当前状态（测试断言用）。 */
    public String stateForTest() {
        return state.name();
    }
}
