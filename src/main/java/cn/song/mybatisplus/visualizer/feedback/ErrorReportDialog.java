package cn.song.mybatisplus.visualizer.feedback;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.SafeBrowser;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.ui.components.labels.LinkListener;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.jetbrains.annotations.Nullable;

/**
 * 需求池报告确认框（T-ErrReport-03）：错误形态（预填标题/描述/分类/堆栈附件）与
 * 手动形态（全空、类型默认疑问咨询、无附件）共用。
 *
 * <p>UI 只做编排：请求组装在 {@link #collectRequest()}（组合 ErrorReportComposer 纯函数），
 * 提交交回调用方回调（后台线程）；提交结果经通知展示（{@link FeedbackReporting#notifyResult}），
 * 不在对话框内等网络。普通 Swing 组件布局，不走 HTML 渲染（EDT 冻结教训）。
 */
public class ErrorReportDialog extends DialogWrapper {

    /** 错误形态预填；null = 手动形态。 */
    private final @Nullable ErrorReportComposer.Prefill prefill;
    /** 异常堆栈全文；null = 手动形态（无附件勾选行）。 */
    private final @Nullable String stackText;
    /** 栈指纹（写入描述尾部，管理员合并重复帖依据）；手动形态 null。 */
    private final @Nullable String fingerprint;
    private final @Nullable String ideVersion;
    private final @Nullable String pluginVersion;
    /** 用户点击提交后回调（携带最终请求与可选堆栈附件；提交在回调方后台执行）。 */
    private final Consumer<FeedbackApi.Submission> onSubmit;

    private final JTextField titleField = new JTextField(ErrorReportComposer.TITLE_MAX);
    private final ComboBox<String> typeCombo = new ComboBox<>(typeLabels());
    private final ComboBox<String> categoryCombo = new ComboBox<>(categoryLabels());
    private final JBTextArea descriptionArea = new JBTextArea(8, 46);
    private final JBTextArea stackPreview = new JBTextArea(10, 46);
    private final JCheckBox attachStackCheck = new JCheckBox();
    private final JPanel stackPreviewContainer = new JPanel(new BorderLayout(4, 2));
    private final JTextField nameField = new JTextField(20);
    private final JTextField emailField = new JTextField(20);
    private final JCheckBox anonymousCheck = new JCheckBox();
    private final LinkLabel<Object> browserLink = new LinkLabel<>("", null, (source, linkData) -> browserFallback());

    public ErrorReportDialog(@Nullable ErrorReportComposer.Prefill prefill,
                             @Nullable String stackText,
                             @Nullable String fingerprint,
                             @Nullable String ideVersion,
                             @Nullable String pluginVersion,
                             Consumer<FeedbackApi.Submission> onSubmit) {
        super(true);
        this.prefill = prefill;
        this.stackText = stackText;
        this.fingerprint = fingerprint;
        this.ideVersion = ideVersion;
        this.pluginVersion = pluginVersion;
        this.onSubmit = onSubmit;

        attachStackCheck.setText(MpSqlVisualizerBundle.message("error.report.dialog.attachStack"));
        attachStackCheck.setToolTipText(MpSqlVisualizerBundle.message("error.report.dialog.attachStack.tip"));
        attachStackCheck.addItemListener(e -> stackPreviewContainer.setVisible(attachStackCheck.isSelected()));
        stackPreviewContainer.add(
                new JBLabel(MpSqlVisualizerBundle.message("error.report.dialog.stackPreview")), BorderLayout.NORTH);
        stackPreviewContainer.add(new JBScrollPane(stackPreview), BorderLayout.CENTER);
        anonymousCheck.setText(MpSqlVisualizerBundle.message("error.report.dialog.anonymous"));

        setTitle(MpSqlVisualizerBundle.message("error.report.dialog.title"));
        setOKButtonText(MpSqlVisualizerBundle.message("error.report.dialog.submit"));
        // 平台组件文案不跟插件语言（DialogWrapper cancelAction 坑）：显式覆盖 Cancel 文案
        setCancelButtonText(MpSqlVisualizerBundle.message("error.report.dialog.cancel"));

        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        stackPreview.setEditable(false);
        stackPreview.setLineWrap(false);

        if (prefill != null) {
            titleField.setText(prefill.getTitle());
            descriptionArea.setText(prefill.getDescription());
            selectCategory(prefill.getCategory());
        }
        // 类型：错误形态默认 Bug、手动形态默认疑问咨询
        typeCombo.setSelectedIndex((prefill != null ? ErrorReportComposer.TYPE_BUG : ErrorReportComposer.TYPE_QUESTION) - 1);
        if (prefill == null) {
            selectCategory(ErrorReportComposer.CATEGORY_OTHER);
        }

        if (stackText != null) {
            List<String> head = new ArrayList<>();
            for (String line : stackText.split("\\R")) {
                head.add(line);
                if (head.size() == 60) {
                    break;
                }
            }
            stackPreview.setText(String.join("\n", head));
        }
        attachStackCheck.setSelected(stackText != null);
        stackPreviewContainer.setVisible(attachStackCheck.isSelected());

        nameField.setText(ReportSubmitterStore.loadName());
        emailField.setText(ReportSubmitterStore.loadEmail());
        // 匿名默认开（与 web 表单一致）；已记忆昵称则默认实名展示，用户可自行勾回
        anonymousCheck.setSelected(nameField.getText().isBlank());

        browserLink.setText(MpSqlVisualizerBundle.message("error.report.dialog.browser"));

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(4, 0, 4, 0);
        JPanel panel = new JPanel(new GridBagLayout());

        labelRow(panel, gbc, "error.report.dialog.subject", titleField);
        labelRow(panel, gbc, "error.report.dialog.type", typeCombo);
        labelRow(panel, gbc, "error.report.dialog.category", categoryCombo);
        labelRow(panel, gbc, "error.report.dialog.description", new JBScrollPane(descriptionArea));
        fullRow(panel, gbc, attachStackCheck);
        fullRow(panel, gbc, stackPreviewContainer);
        labelRow(panel, gbc, "error.report.dialog.name", nameField);
        labelRow(panel, gbc, "error.report.dialog.email", emailField);
        fullRow(panel, gbc, anonymousCheck);
        fullRow(panel, gbc, browserLink);
        return panel;
    }

    private static void labelRow(JPanel panel, GridBagConstraints gbc, String textKey, JComponent component) {
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JBLabel(MpSqlVisualizerBundle.message(textKey)), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(component, gbc);
    }

    private static void fullRow(JPanel panel, GridBagConstraints gbc, JComponent component) {
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(component, gbc);
        gbc.gridwidth = 1;
    }

    /** 提交前组装最终请求（描述追加指纹行；昵称/邮箱记忆随提交持久化）。 */
    public FeedbackApi.PostRequest collectRequest() {
        ReportSubmitterStore.save(nameField.getText().trim(), emailField.getText().trim());
        String submitterName = nameField.getText().trim();
        String submitterEmail = emailField.getText().trim();
        return new FeedbackApi.PostRequest(
                titleField.getText().trim(),
                ErrorReportComposer.buildDescription(null, descriptionArea.getText(), fingerprint),
                typeCombo.getSelectedIndex() + 1,
                CATEGORIES[categoryCombo.getSelectedIndex()],
                submitterName.isEmpty() ? null : submitterName,
                submitterEmail.isEmpty() ? null : submitterEmail,
                anonymousCheck.isSelected(),
                FeedbackApi.SOURCE_IDE,
                ideVersion,
                pluginVersion,
                List.of());
    }

    public @Nullable FeedbackApi.StackAttachment collectStackAttachment() {
        if (stackText == null || !attachStackCheck.isSelected()) {
            return null;
        }
        String occurredAt = ZonedDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX"));
        String content = ErrorReportComposer.buildStackAttachment(
                stackText, ideVersion, pluginVersion, fingerprint, occurredAt);
        return new FeedbackApi.StackAttachment(
                FeedbackApi.STACK_ATTACHMENT_NAME, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doOKAction() {
        FeedbackApi.Submission submission = new FeedbackApi.Submission(collectRequest(), collectStackAttachment());
        super.doOKAction();
        onSubmit.accept(submission);
    }

    private void browserFallback() {
        List<String> params = new ArrayList<>();
        params.add("source=" + FeedbackApi.SOURCE_IDE);
        if (ideVersion != null) {
            params.add("ideVersion=" + URLEncoder.encode(ideVersion, StandardCharsets.UTF_8));
        }
        if (pluginVersion != null) {
            params.add("pluginVersion=" + URLEncoder.encode(pluginVersion, StandardCharsets.UTF_8));
        }
        SafeBrowser.browse(FeedbackApi.SITE_BASE + "/about/feedback?" + String.join("&", params));
    }

    private void selectCategory(String slug) {
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].equals(slug)) {
                categoryCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    private static String[] typeLabels() {
        String[] labels = new String[4];
        for (int i = 0; i < 4; i++) {
            labels[i] = MpSqlVisualizerBundle.message("feedback.type." + (i + 1));
        }
        return labels;
    }

    private static String[] categoryLabels() {
        String[] labels = new String[CATEGORIES.length];
        for (int i = 0; i < CATEGORIES.length; i++) {
            labels[i] = MpSqlVisualizerBundle.message("feedback.category." + CATEGORIES[i]);
        }
        return labels;
    }

    /** 与需求池 Categories 一致（sql-visualizer-admin constants.ts 顺序）。 */
    public static final String[] CATEGORIES = {
            ErrorReportComposer.CATEGORY_WRAPPER,
            ErrorReportComposer.CATEGORY_XML,
            ErrorReportComposer.CATEGORY_ANNOTATION,
            ErrorReportComposer.CATEGORY_SQL_TO_JAVA,
            ErrorReportComposer.CATEGORY_SQL_LOG,
            ErrorReportComposer.CATEGORY_BATCH,
            ErrorReportComposer.CATEGORY_COMPLETION,
            ErrorReportComposer.CATEGORY_OTHER,
    };
}
