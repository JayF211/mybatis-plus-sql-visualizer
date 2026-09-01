package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.Objects;

/**
 * SQL 执行结果面板（M36）：在 SQL 预览面板下方展示执行状态与结果摘要。
 */
public class SqlResultPanel extends JBPanel<SqlResultPanel> {

    private enum State { EMPTY, LOADING, ERROR, SUCCESS }

    private final JBLabel statusLabel = new JBLabel();
    private final JBLabel infoLabel = new JBLabel();
    private final JButton errorCopyButton;
    private String currentError = null;
    private State state = State.EMPTY;

    public SqlResultPanel() {
        infoLabel.setForeground(JBColor.GRAY);
        infoLabel.setBorder(JBUI.Borders.empty(1, 4));

        errorCopyButton = new JButton(MpSqlVisualizerBundle.message("panel.copyError"));
        errorCopyButton.setVisible(false);
        errorCopyButton.setOpaque(false);
        errorCopyButton.setContentAreaFilled(false);
        errorCopyButton.setBorder(JBUI.Borders.empty(1, 6));
        errorCopyButton.setFont(errorCopyButton.getFont().deriveFont(11f));
        errorCopyButton.setForeground(JBColor.namedColor("Link.activeForeground", JBColor.GRAY));
        errorCopyButton.setMargin(JBUI.insets(0, 6, 0, 6));
        errorCopyButton.addActionListener(e -> {
            if (currentError != null) copyToClipboard(currentError);
        });

        setBorder(JBUI.Borders.empty());
        setOpaque(false);
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

    public void showTempFileOpened(String dataSourceName) {
        state = State.EMPTY;
        currentError = null;
        errorCopyButton.setVisible(false);
        String dsInfo = dataSourceName != null && !dataSourceName.isEmpty()
                ? " | " + MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName) : "";
        infoLabel.setText("<html>" + MpSqlVisualizerBundle.message("execute.fileOpened") + dsInfo + "</html>");
        infoLabel.setForeground(JBColor.GRAY);
        showContent();
        revalidate();
        repaint();
    }

    public void showLoading(String dataSourceName) {
        state = State.LOADING;
        currentError = null;
        errorCopyButton.setVisible(false);
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
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
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
