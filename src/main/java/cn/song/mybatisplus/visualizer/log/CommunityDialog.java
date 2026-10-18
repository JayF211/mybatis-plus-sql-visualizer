package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.SafeBrowser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.*;

public final class CommunityDialog extends DialogWrapper {
    public static final String ABOUT_URL = "https://sql-visualizer.jvtools.cn/about/";
    public static final String WECHAT_QR_URL = "https://sql-visualizer.jvtools.cn/wechat-qr.png";
    public static final String GROUP_QR_URL = "https://sql-visualizer.jvtools.cn/group-qr.png";
    @FunctionalInterface public interface ImageLoader { BufferedImage load(String url) throws Exception; }
    private final ImageLoader loadImage;
    private final JLabel authorQr = qrPlaceholder();
    private final JLabel groupQr = qrPlaceholder();
    private final JPanel authorCard = card("log.community.author", "log.community.author.desc", authorQr);
    private final JPanel groupCard = card("log.community.group", "log.community.group.desc", groupQr);
    private final JButton openSiteButton = new JButton(MpSqlVisualizerBundle.message("log.community.openSite"));
    private boolean loadingStarted;

    public CommunityDialog(Project project) { this(project, CommunityQrImages::fetch); }
    public CommunityDialog(Project project, ImageLoader loadImage) {
        super(project, true);
        this.loadImage = loadImage;
        openSiteButton.addActionListener(e -> SafeBrowser.browse(ABOUT_URL));
        setTitle(MpSqlVisualizerBundle.message("log.community.title"));
        init();
        setOKButtonText(MpSqlVisualizerBundle.message("log.community.close"));
        setResizable(false);
    }

    public List<JLabel> getQrLabelsForTest() { return List.of(authorQr, groupQr); }
    public List<JPanel> getCardsForTest() { return List.of(authorCard, groupCard); }
    public JButton getOpenSiteButtonForTest() { return openSiteButton; }

    @Override protected Action[] createActions() { return new Action[]{getOKAction()}; }
    @Override protected JComponent createCenterPanel() {
        boolean compact = !GraphicsEnvironment.isHeadless()
                && GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds().width < JBUI.scale(540);
        JPanel cards = new JPanel(new GridLayout(compact ? 2 : 1, compact ? 1 : 2, JBUI.scale(12), JBUI.scale(12)));
        cards.add(authorCard);
        cards.add(groupCard);
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(JBUI.Borders.empty(8));
        content.add(cards, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        actions.setBorder(JBUI.Borders.emptyTop(8));
        actions.add(openSiteButton);
        content.add(actions, BorderLayout.SOUTH);
        return content;
    }

    public void startLoading() {
        if (loadingStarted || isDisposed()) return;
        loadingStarted = true;
        load(WECHAT_QR_URL, authorQr);
        load(GROUP_QR_URL, groupQr);
    }

    private void load(String url, JLabel label) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            BufferedImage image = null;
            Throwable failure = null;
            try { image = CommunityQrImages.scaled(loadImage.load(url)); }
            catch (Throwable error) { failure = error; }
            BufferedImage loaded = image;
            Throwable error = failure;
            ApplicationManager.getApplication().invokeLater(() -> applyImageResult(label, loaded, error), ModalityState.any());
        });
    }

    /** Java 显式成功值/失败值替代 Kotlin Result 内联类型。 */
    public void applyImageResult(JLabel label, BufferedImage image, Throwable failure) {
        if (isDisposed()) return;
        if (failure == null && image != null) {
            label.setIcon(new ImageIcon(image));
            label.setText(null);
        } else {
            label.setIcon(null);
            label.setText(MpSqlVisualizerBundle.message("log.community.loadFailed"));
        }
    }

    private static JLabel qrPlaceholder() {
        JLabel label = new JLabel(MpSqlVisualizerBundle.message("log.community.loading"), SwingConstants.CENTER);
        label.setPreferredSize(JBUI.size(180, 180));
        label.setMinimumSize(label.getPreferredSize());
        label.getAccessibleContext().setAccessibleName(MpSqlVisualizerBundle.message("log.community.loading"));
        return label;
    }

    private static JPanel card(String titleKey, String descriptionKey, JLabel image) {
        String title = MpSqlVisualizerBundle.message(titleKey);
        String description = MpSqlVisualizerBundle.message(descriptionKey);
        JLabel titleLabel = new JLabel(title, SwingConstants.CENTER);
        titleLabel.setAlignmentX(0.5f);
        titleLabel.setFont(titleLabel.getFont().deriveFont(java.awt.Font.BOLD));
        JLabel descriptionLabel = new JLabel("<html><div style='width:" + JBUI.scale(188)
                + "px;text-align:center'>" + escapeHtml(description) + "</div></html>", SwingConstants.CENTER);
        descriptionLabel.setAlignmentX(0.5f);
        descriptionLabel.setPreferredSize(JBUI.size(196, 76));
        image.getAccessibleContext().setAccessibleName(title);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(JBColor.border()), JBUI.Borders.empty(12)));
        image.setAlignmentX(0.5f);
        panel.add(image);
        panel.add(Box.createVerticalStrut(JBUI.scale(10)));
        panel.add(titleLabel);
        panel.add(Box.createVerticalStrut(JBUI.scale(5)));
        panel.add(descriptionLabel);
        return panel;
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
