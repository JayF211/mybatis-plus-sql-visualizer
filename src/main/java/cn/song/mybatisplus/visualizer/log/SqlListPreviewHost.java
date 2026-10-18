package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.util.ui.UIUtil;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.*;

/** 日志和历史共用的停靠预览宿主；单击仅选择，并按需提示迁移入口。 */
public final class SqlListPreviewHost {
    private final JList<LogSqlEntry> list;
    private final JComponent listPane;
    private final SqlDockedPreview preview;
    private final String splitterKey;
    private final Consumer<Boolean> onOpenChanged;
    private final JPanel component = new JPanel(new BorderLayout());
    private final JPanel hint = new JPanel(new BorderLayout());
    private OnePixelSplitter split;
    private LogSqlEntry pendingHintEntry;
    private final Timer hintTimer = new Timer(300, e -> showPendingHint());

    public SqlListPreviewHost(JList<LogSqlEntry> list, JComponent listPane, SqlDockedPreview preview) {
        this(list, listPane, preview, "mpvs.sqlList.preview.split", false, ignored -> {});
    }

    public SqlListPreviewHost(JList<LogSqlEntry> list, JComponent listPane, SqlDockedPreview preview,
            String splitterKey, boolean showIconHint, Consumer<Boolean> onOpenChanged) {
        this.list = list;
        this.listPane = listPane;
        this.preview = preview;
        this.splitterKey = splitterKey;
        this.onOpenChanged = onOpenChanged;
        component.add(listPane, BorderLayout.CENTER);
        JTextArea message = new JTextArea(MpSqlVisualizerBundle.message(
                showIconHint ? "log.dockedPreview.migration.icon" : "log.dockedPreview.migration"));
        message.setColumns(20);
        message.setRows(3);
        message.setMinimumSize(new Dimension(0, 0));
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setEditable(false);
        message.setFocusable(false);
        message.setOpaque(false);
        message.setBorder(BorderFactory.createEmptyBorder(4, 6, 0, 6));
        hint.add(message, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton open = new JButton(MpSqlVisualizerBundle.message("log.dockedPreview.open"));
        open.addActionListener(e -> toggle());
        actions.add(open);
        JButton acknowledge = new JButton(MpSqlVisualizerBundle.message("log.dockedPreview.acknowledge"));
        acknowledge.addActionListener(e -> hideHint());
        actions.add(acknowledge);
        hint.add(actions, BorderLayout.SOUTH);
        hint.setVisible(false);
        hintTimer.setRepeats(false);
        component.add(hint, BorderLayout.SOUTH);
    }

    private OnePixelSplitter split() {
        if (split == null) {
            split = new OnePixelSplitter(true, splitterKey, 0.65f);
            split.setFirstComponent(listPane);
            split.setSecondComponent(preview.getComponent());
            split.setBackground(UIUtil.getPanelBackground());
            split.setShowDividerControls(false);
            split.setHonorComponentsMinimumSize(true);
            listPane.setMinimumSize(new Dimension(100, 80));
            preview.getComponent().setMinimumSize(new Dimension(100, 80));
        }
        return split;
    }

    public JPanel getComponent() { return component; }
    public boolean getHintVisibleForTest() { return hint.isVisible(); }
    public boolean getHintScheduledForTest() { return hintTimer.isRunning(); }
    public boolean getPreviewOpenForTest() { return preview.isOpen(); }
    public void showPendingHintForTest() { showPendingHint(); }

    public void toggle() {
        if (preview.isOpen()) {
            preview.close();
            split().setFirstComponent(null);
            component.remove(split());
            component.add(listPane, BorderLayout.CENTER);
        } else {
            hideHint();
            preview.open();
            component.remove(listPane);
            split().setFirstComponent(listPane);
            component.add(split(), BorderLayout.CENTER);
            SwingUtilities.invokeLater(() -> { if (preview.isOpen()) list.requestFocusInWindow(); });
        }
        onOpenChanged.accept(preview.isOpen());
        component.revalidate();
        component.repaint();
    }

    public void onRowClick(MouseEvent event) {
        if (event.getClickCount() >= 2) {
            hideHint();
            return;
        }
        if (preview.isOpen() || hint.isVisible() || event.getClickCount() != 1
                || !SwingUtilities.isLeftMouseButton(event) || SqlClickPreview.isMultiSelectIntent(event.getModifiersEx())
                || list.getSelectedIndices().length != 1
                || PluginSettings.getInstance().getState().getSqlListPreviewHintSeen()) return;
        int index = list.locationToIndex(event.getPoint());
        if (index < 0 || list.getCellBounds(index, index) == null
                || !list.getCellBounds(index, index).contains(event.getPoint())) return;
        pendingHintEntry = list.getModel().getElementAt(index);
        hintTimer.restart();
    }

    private void showPendingHint() {
        hintTimer.stop();
        LogSqlEntry entry = pendingHintEntry;
        pendingHintEntry = null;
        if (entry == null || preview.isOpen() || list.getSelectedIndices().length != 1
                || list.getSelectedValue() != entry
                || PluginSettings.getInstance().getState().getSqlListPreviewHintSeen()) return;
        PluginSettings settings = PluginSettings.getInstance();
        PluginSettings.State state = settings.getState().copy();
        state.setSqlListPreviewHintSeen(true);
        settings.loadState(state);
        hint.setVisible(true);
        component.revalidate();
        component.repaint();
    }

    public void hideHint() {
        hintTimer.stop();
        pendingHintEntry = null;
        hint.setVisible(false);
        component.revalidate();
        component.repaint();
    }
}
