package cn.song.mybatisplus.visualizer.feedback;

import cn.song.mybatisplus.visualizer.ui.CopyFeedback;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.MpvsUiColors;
import cn.song.mybatisplus.visualizer.util.SafeBrowser;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import kotlin.Pair;
import org.jetbrains.annotations.Nullable;

/**
 * 需求池报告确认框（T-ErrReport-03）：错误形态（预填标题/描述/分类/堆栈附件）与
 * 手动形态（全空、类型默认疑问咨询、无附件）共用。
 *
 * <p>UI 只做编排：请求组装在 {@link #collectRequest()}（组合 ErrorReportComposer 纯函数），
 * 提交交回调用方回调（后台线程）；提交结果经通知展示（{@link FeedbackReporting#notifyOutcome}），
 * 不在对话框内等网络。普通 Swing 组件布局，不走 HTML 渲染（EDT 冻结教训）。
 *
 * <p>T-FeedbackDlg（2026-09-14 真机反馈三缺陷）：
 * - 宽度：标题框列数曾误用 TITLE_MAX=200（JTextField 列数是首选宽度语义）致弹窗 1272px，
 *   收敛为 30 列 + 描述/堆栈滚动面板首选宽 620，整体 ~660px 量级；
 * - 占位：标题/描述经 getEmptyText 显示灰色提示（文案对齐官网表单），并补 {@link #doValidate()}
 *   客户端校验（title 5~200 / description ≥10），短输入拦截在触网前；
 * - 拖拽：isResizable=true + {@link #getDimensionServiceKey()} 记忆尺寸，描述/堆栈行
 *   weighty=1 + BOTH 随拖拽纵向伸缩。
 *
 * <p>T-FeedbackDlg2（2026-09-14 真机复查两缺陷）：勾选跳动——堆栈行以 weighty=1
 * 参与 GridBag 纵向分配、窗口高度不变，堆栈出现即压扁上方描述行。
 * {@link #toggleStackPreview} 勾选时给窗口做等量高度补偿（补偿公式见 {@link #toggleResizeDelta}），
 * 取消时按堆栈行实际高对称缩回。
 *
 * <p>T-FeedbackDlg3（2026-09-14 真机反馈两缺陷）：手动形态堆栈自主输入——勾选行恒显示、
 * 勾选后堆栈区<b>可编辑</b>（用户自行粘贴；曾提供「从 IDE 错误池导入」菜单，T-MarketInternal
 * 起摘除：其数据源 MessagePool 系在 262 标 @ApiStatus.Internal，Marketplace 审核阻断，
 * 平台无公开替代；堆栈来源引导移入占位文案）；附件内容取文本区实际内容
 * （{@link #collectStackAttachment()}）；描述框默认高收敛（滚动面板首选 140→90，文本区 6→4 行）。
 * 错误形态维持原语义：event 堆栈预填只读预览、附件用全文、无堆栈隐藏勾选行。
 *
 * <p>T-FeedbackErr（2026-09-14 真机：报告失败只有一个「Server returned HTTP response code:
 * 400」toast，用户既看不到原因也没有重试入口）——提交状态机落进对话框本身：
 * 点提交后对话框<b>不关</b>，按钮转「提交中…」并禁用，状态行就地显示进度；失败时按钮恢复
 * 「提交」、状态行给红字原因 + 转浏览器兜底，用户改完内容可直接再提交，输入不会随关窗丢失；
 * 成功才自动关闭，通知路径（FR 编号 + 查看详情）保持不变；附件上传失败降级
 * （warning 非空）时正文照发、状态行提示堆栈没带上。结果文案为纯函数，headless 可测。
 *
 * <p>T-FeedbackBatch（用户定案「不能被轻易限流」）：本波全部错误事件进入同一对话框。
 * 服务端限流为每 IP 每小时 5 次提交，逐条提交必然撞 429，因此：批次条目 ≥2 条时对话框底部
 * 出现「本批共 N 条异常」分节（条目含头部主预填自己），逐条复选框默认全勾（用户看得见
 * 「这批会一起报上去」，不是被静默合并）；提交按条数分流（BatchSubmitter）：≤5 条合并成
 * 一条帖子，更多则首发带合并附件、其余错峰补发；标题/描述由 {@link #composeSubmission}
 * 纯函数派生，条目全不勾时退化为普通单条报告。
 *
 * <p>T-FeedbackBatch2（2026-09-15）：262 平台对「报告全部」逐簇调用 submit、每次只带 1 条
 * 事件（合并收口在 {@link IdeErrorReportSubmitter}），本对话框随之修两处显示语义：
 * 批次条目只剩 1 条（= 主预填自己）时<b>不出分节</b>——旧文案「其他 1 条异常」列的却是
 * 主预填那条，自相矛盾；头部条目的堆栈开关收敛到主表单「附带异常堆栈」单一事实源：
 * 分节里头部行复选框禁用仅镜像显示，收集提交条目时以主勾选态回写（此前两处开关各写各的，
 * 合并附件可能丢主堆栈）。
 *
 * <p>T-ErrorReportRedact（2026-09-19，D1=B 定案）——<b>预填分级</b>：闸门（用户确认）管「发不发」，
 * 分级管「默认带什么」。错误形态的默认预填不再含 message：
 * <ul>
 *  <li>标题 = 异常类简单名、描述 = 异常类全名 + 栈帧摘要 + cause 链（{@link ErrorReportComposer#prefill}）；</li>
 *  <li>message 全文降为「附带异常消息全文」勾选行（默认不勾，无 message 时整行隐藏），勾选后
 *    标题/描述回填含 message 的旧形态——与改前<b>逐字节一致</b>（{@link #applyMessagePrefill}）；</li>
 *  <li>完整堆栈附件同步改为<b>默认不勾</b>：附件首行就是 {@code 异常类: message}，默认勾选等于绕开脱敏；</li>
 *  <li>批次条目清单里的逐条消息随同一开关去留（{@link #composeSubmission}）。</li>
 * </ul>
 * 用户手工改过的标题/描述不被勾选切换覆盖（只回填仍等于上次预填写入值的字段）。
 *
 * <p>T-ErrReportSubmitNoOp（2026-10-08 真机 P0）——<b>单条上报点「提交」无反应</b>：
 * 单条事件不出批次分节（T-FeedbackBatch2）⇒ {@link ReportFormFields#batchList} 0 组件，而
 * {@link #selectedBatchItemsFor} 仍裸索引 {@code getComponent(0)} ⇒ {@code ArrayIndexOutOfBoundsException}
 * 从 {@link #doOKAction()} 外溢到 EDT，平台只记 SEVERE 日志、用户零反馈、按钮状态不变。
 * 两处一并修：
 * <ul>
 * <li>{@link #selectedBatchItemsFor} 容器访问改 {@link java.awt.Container#getComponents()} + {@code getOrNull}（带界）；</li>
 * <li>{@link #doOKAction()} 组装段整体纳入 {@code runCatching}，异常落状态行（{@link #finishSubmitting} 失败态）
 *     + LOG.warn，补上 T-FeedbackErr「失败必须可见」在组装段的漏网处。</li>
 * </ul>
 */
public class ErrorReportDialog extends DialogWrapper {

    /**
     * 用户点击提交后回调（T-FeedbackErr 三参形态）：携带最终请求/附件与勾选后的批次条目，
     * 以及提交结束回调；调用方拿到结果后必须回调它（成功/失败都要），对话框据此恢复按钮、
     * 渲染状态行、成功时自行关闭。
     */
    @FunctionalInterface
    public interface OnSubmit {
        void onSubmit(FeedbackApi.Submission submission,
                      List<BatchSubmitter.BatchItem> selectedItems,
                      Consumer<FeedbackApi.SubmissionOutcome> onResult);
    }

    /** 错误形态预填；null = 手动形态。 */
    private final @Nullable ErrorReportComposer.Prefill prefill;
    /** 异常堆栈全文（仅错误形态=event 堆栈）；错误形态 null 时隐藏勾选行。 */
    private final @Nullable String stackText;
    /** 栈指纹（写入描述尾部，管理员合并重复帖依据）；手动形态 null。 */
    private final @Nullable String fingerprint;
    private final @Nullable String ideVersion;
    private final @Nullable String pluginVersion;
    private final OnSubmit onSubmit;

    /** 对话框全部输入控件（T-FeedbackDlg 可测试性抽取，headless 测试直接构造）。 */
    public final ReportFormFields fields = new ReportFormFields();

    /**
     * 上一次由预填分级写入的标题/描述（T-ErrorReportRedact）：勾选切换只在字段内容仍等于
     * 这两个值时才覆盖，用户手工改过的字段不动（免得勾一下抹掉刚写的内容）。
     */
    private String lastInjectedTitle = null;
    private String lastInjectedDescription = null;

    /**
     * 本次会话附带的批次条目（错误形态由 {@link IdeErrorReportSubmitter} 按勾选的 events 注入；
     * 手动形态为空）。条目级勾选状态在提交时读取（{@link #selectedBatchItems()}）。
     */
    private List<BatchSubmitter.BatchItem> batchItems = List.of();

    /** 提交进行中标记：重复点提交直接忽略（T-FeedbackErr 状态机）。 */
    private boolean isSubmitting = false;

    public ErrorReportDialog(@Nullable ErrorReportComposer.Prefill prefill,
                             @Nullable String stackText,
                             @Nullable String fingerprint,
                             @Nullable String ideVersion,
                             @Nullable String pluginVersion,
                             OnSubmit onSubmit) {
        super(true);
        this.prefill = prefill;
        this.stackText = stackText;
        this.fingerprint = fingerprint;
        this.ideVersion = ideVersion;
        this.pluginVersion = pluginVersion;
        this.onSubmit = onSubmit;

        setTitle(MpSqlVisualizerBundle.message("error.report.dialog.title"));
        setOKButtonText(MpSqlVisualizerBundle.message("error.report.dialog.submit"));
        // 平台组件文案不跟插件语言（DialogWrapper cancelAction 坑）：显式覆盖 Cancel 文案
        setCancelButtonText(MpSqlVisualizerBundle.message("error.report.dialog.cancel"));
        // T-FeedbackErr：提交不再一按就关窗——状态行在窗内，失败可原地改内容重试
        setOKButtonTooltip(MpSqlVisualizerBundle.message("error.report.dialog.submit.tip"));
        setOKActionEnabled(true);

        fields.browserFallbackHandler = this::browserFallback;

        // T-FeedbackErr 真机第二轮：状态行复制按钮（对齐 SQL 预览窗口失败信息交互）——
        // 复制当前状态行全文（含服务端原因/诊断片段），成功后按钮短暂变「已复制」
        fields.statusCopyHandler = () -> {
            String text = fields.statusLabel.getText();
            if (text != null && !text.isBlank()) {
                UiTextClipboard.copy(text);
                CopyFeedback.flashCopied(fields.statusCopyButton);
            }
        };
        fields.statusCopyButton.addActionListener(e -> fields.statusCopyHandler.run());

        if (prefill != null) {
            fields.titleField.setText(prefill.getTitle());
            fields.descriptionArea.setText(prefill.getDescription());
            lastInjectedTitle = prefill.getTitle();
            lastInjectedDescription = prefill.getDescription();
            selectCategory(prefill.getCategory());
        }
        // 类型：错误形态默认 Bug、手动形态默认疑问咨询
        fields.typeCombo.setSelectedIndex(
                (prefill != null ? ErrorReportComposer.TYPE_BUG : ErrorReportComposer.TYPE_QUESTION) - 1);
        if (prefill == null) {
            selectCategory(ErrorReportComposer.CATEGORY_OTHER);
        }

        if (prefill == null) {
            // T-FeedbackDlg3：手动形态堆栈自主输入——勾选恒显示，堆栈区可编辑（自行粘贴）。
            // T-MarketInternal：原「从 IDE 错误池导入」按钮摘除（数据源 MessagePool 系
            // 262 Internal），来源引导并入占位文案（见 stackPreview.placeholder 键）
            fields.stackPreview.setEditable(true);
            fields.stackPreview.getEmptyText().setText(
                    MpSqlVisualizerBundle.message("error.report.dialog.stackPreview.placeholder"));
            fields.attachStackCheck.setVisible(true);
        } else {
            // 错误形态维持原语义：event 堆栈预填只读预览；无堆栈时整行隐藏不留死勾选框
            if (stackText != null) {
                fields.stackPreview.setText(headLines(stackText, 60));
            }
            fields.attachStackCheck.setVisible(stackText != null);
        }
        // 勾选默认关（T-ErrorReportRedact D1=B 改了错误形态的默认）：完整堆栈首行就是
        // `异常类: message`，默认勾选等于把 message 全文随附件送出去，与「默认不外传业务文本」
        // 相悖；默认预填的诊断价值由描述里的栈帧摘要 + cause 链承担。手动形态原本就默认关。
        fields.attachStackCheck.setSelected(false);
        fields.stackPreviewContainer.setVisible(fields.attachStackCheck.isSelected());
        // 监听在初始状态落定后注册：初始可见性直调，不触发窗口高度补偿
        fields.attachStackCheck.addItemListener(e -> toggleStackPreview(fields.attachStackCheck.isSelected()));

        // T-ErrorReportRedact（D1=B）：异常消息全文默认不预填，改为「可勾选附加」——
        // 有 message 才出勾选行（无消息不出死勾选框，同堆栈行语义）；默认不勾。
        fields.attachMessageCheck.setVisible(prefill != null && prefill.getMessage() != null);
        fields.attachMessageCheck.setSelected(false);
        fields.attachMessageCheck.addItemListener(e -> applyMessagePrefill(fields.attachMessageCheck.isSelected()));

        fields.nameField.setText(ReportSubmitterStore.loadName());
        fields.emailField.setText(ReportSubmitterStore.loadEmail());
        // 匿名默认开（与 web 表单一致）；已记忆昵称则默认实名展示，用户可自行勾回
        fields.anonymousCheck.setSelected(fields.nameField.getText().isBlank());

        fields.browserLink.setText(MpSqlVisualizerBundle.message("error.report.dialog.browser"));

        init();
    }

    /** 批次条目注入（错误形态）：注入即渲染进对话框分节（T-FeedbackBatch）。 */
    public void setBatchItems(List<BatchSubmitter.BatchItem> value) {
        this.batchItems = value == null ? List.of() : value;
        if (prefill != null) {
            applyBatchItemsToFields(fields, this.batchItems);
        }
    }

    public List<BatchSubmitter.BatchItem> getBatchItems() {
        return batchItems;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return buildCenterPanel(fields);
    }

    @Override
    public boolean isResizable() {
        return true;
    }

    @Override
    public String getDimensionServiceKey() {
        return DIMENSION_SERVICE_KEY;
    }

    /**
     * 堆栈行显隐 + 窗口高度补偿（T-FeedbackDlg2-01）。真机跳动根因：堆栈行 weighty=1
     * 参与 GridBag 纵向分配，勾选后窗口高度不变、堆栈行与描述行争抢纵向空间，
     * 描述被压扁、勾选框整体「跳上去」。补偿公式见 {@link #toggleResizeDelta}——勾选撑高、
     * 取消对称缩回，描述行高度分毫不差。preferred 增长量在翻可见性前后各测一次
     * （GridBagLayout 的 preferred 只统计可见组件），不依赖行高假设。
     * peer 未建（headless/异常路径）时静默跳过补偿，显隐本身照常。
     */
    public void toggleStackPreview(boolean show) {
        Container container = fields.stackPreviewContainer;
        Container pane;
        try {
            pane = getContentPane();
        } catch (Throwable ignored) {
            pane = null;
        }
        if (pane == null) {
            container.setVisible(show);
            return;
        }
        int prefBefore = pane.getPreferredSize().height;
        int stackRowHeight = container.getHeight();
        container.setVisible(show);
        int delta = toggleResizeDelta(
                show,
                pane.getHeight(),
                prefBefore,
                pane.getPreferredSize().height - prefBefore,
                stackRowHeight);
        if (delta != 0) {
            try {
                Window window = SwingUtilities.getWindowAncestor(pane);
                if (window != null) {
                    window.setSize(window.getWidth(), Math.max(window.getHeight() + delta, MIN_WINDOW_HEIGHT));
                }
            } catch (Throwable ignored) {
                // headless/异常路径静默跳过补偿
            }
        }
        pane.invalidate();
        pane.validate();
    }

    /**
     * 预填分级切换（T-ErrorReportRedact D1=B，纯逻辑 internal 供 headless 测试）：
     * 勾选「附带异常消息全文」→ 标题/描述回填<b>含 message 的旧形态</b>（与改前逐字节一致，
     * 兼容既有提交链路与官网需求池字段）；取消 → 回到脱敏形态（类名 + 栈帧摘要）。
     *
     * <p>只覆盖「内容仍等于上一次由预填写入的值」的字段：用户手工改过的字段一律不动，
     * 免得勾一下把刚写的内容抹掉（改过的字段视为用户接管，预填分级不再管它）。</p>
     */
    public void applyMessagePrefill(boolean attach) {
        if (prefill == null) {
            return;
        }
        Pair<String, String> targets = messagePrefillTargets(
                attach,
                prefill,
                fields.titleField.getText(),
                fields.descriptionArea.getText(),
                lastInjectedTitle,
                lastInjectedDescription);
        String title = targets.getFirst();
        String description = targets.getSecond();
        if (!title.equals(fields.titleField.getText())) {
            fields.titleField.setText(title);
            lastInjectedTitle = title;
        }
        if (!description.equals(fields.descriptionArea.getText())) {
            fields.descriptionArea.setText(description);
            lastInjectedDescription = description;
        }
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String key = validateForm(fields.titleField.getText(), fields.descriptionArea.getText());
        if (key == null) {
            return null;
        }
        JComponent component = VALIDATION_TITLE_KEY.equals(key) ? fields.titleField : fields.descriptionArea;
        return new ValidationInfo(MpSqlVisualizerBundle.message(key), component);
    }

    /** 提交前组装最终请求（描述追加指纹行；昵称/邮箱记忆随提交持久化）。 */
    public FeedbackApi.PostRequest collectRequest() {
        ReportSubmitterStore.save(fields.nameField.getText().trim(), fields.emailField.getText().trim());
        String submitterName = fields.nameField.getText().trim();
        String submitterEmail = fields.emailField.getText().trim();
        return new FeedbackApi.PostRequest(
                fields.titleField.getText().trim(),
                ErrorReportComposer.buildDescription(null, fields.descriptionArea.getText(), fingerprint),
                fields.typeCombo.getSelectedIndex() + 1,
                CATEGORIES[fields.categoryCombo.getSelectedIndex()],
                submitterName.isEmpty() ? null : submitterName,
                submitterEmail.isEmpty() ? null : submitterEmail,
                fields.anonymousCheck.isSelected(),
                FeedbackApi.SOURCE_IDE,
                ideVersion,
                pluginVersion,
                List.of());
    }

    /**
     * 附件堆栈：错误形态用 event 全文（预览区仅为截断展示）；手动形态取堆栈区实际内容
     * （用户自行粘贴，T-FeedbackDlg3）——勾选但内容为空视同不附带。
     */
    public @Nullable FeedbackApi.StackAttachment collectStackAttachment() {
        if (!fields.attachStackCheck.isSelected()) {
            return null;
        }
        String stack = prefill != null ? stackText : blankToNull(fields.stackPreview.getText());
        if (stack == null) {
            return null;
        }
        String content = ErrorReportComposer.buildStackAttachment(
                stack, ideVersion, pluginVersion, fingerprint, FeedbackReporting.nowStamp());
        return new FeedbackApi.StackAttachment(
                FeedbackApi.STACK_ATTACHMENT_NAME, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doOKAction() {
        // 校验不过先交框架弹错（校验失败时框架不会关窗），通过才组装提交
        if (validateForm(fields.titleField.getText(), fields.descriptionArea.getText()) != null) {
            super.doOKAction();
            return;
        }
        if (isSubmitting) {
            return;
        }
        // T-ErrReportSubmitNoOp：组装段整体纳入兜底——任一步抛错都必须落到状态行，不许再出现
        // 「点了没反应」（真机：容器越界异常外溢到 EDT，平台只记 SEVERE 日志，用户零反馈）
        FeedbackApi.Submission submission;
        try {
            submission = composeSubmission(
                    new FeedbackApi.Submission(collectRequest(), collectStackAttachment()),
                    selectedBatchItems());
        } catch (Throwable error) {
            LOG.warn("报告提交前置组装失败", error);
            finishSubmitting(new FeedbackApi.SubmissionOutcome(
                    new FeedbackApi.SubmitResult.Failure(
                            MpSqlVisualizerBundle.message(
                                    "error.report.dialog.status.assembleFailed",
                                    error.getClass().getSimpleName()))));
            return;
        }
        beginSubmitting();
        // T-FeedbackErr：不再 super.doOKAction()（那会先关窗）——结果由提交结束回调决定：
        // 成功才关窗，失败留窗可改可重试
        try {
            onSubmit.onSubmit(submission, selectedBatchItems(), this::finishSubmitting);
        } catch (Throwable t) {
            finishSubmitting(new FeedbackApi.SubmissionOutcome(
                    new FeedbackApi.SubmitResult.Failure(t.getMessage() != null ? t.getMessage() : "提交失败"), null));
        }
    }

    /**
     * 勾选后参与提交的批次条目（T-FeedbackBatch；全不勾 = 普通单条报告）。
     * 头部条目的堆栈开关以主表单「附带异常堆栈」为准（T-FeedbackBatch2，纯函数收口在
     * {@link #selectedBatchItemsFor}）。
     */
    public List<BatchSubmitter.BatchItem> selectedBatchItems() {
        if (prefill == null) {
            return List.of();
        }
        return selectedBatchItemsFor(batchItems, fields.attachStackCheck.isSelected(), fields.batchList,
                fields.attachMessageCheck.isSelected());
    }

    /** 进入「提交中」：按钮禁用并改文案，状态行显示进度（T-FeedbackErr）。 */
    public void beginSubmitting() {
        isSubmitting = true;
        if (!isHeadless()) {
            try {
                setOKButtonText(MpSqlVisualizerBundle.message("error.report.dialog.submitting"));
                setOKActionEnabled(false);
            } catch (Throwable ignored) {
                // peer 异常路径静默跳过
            }
        }
        fields.statusLabel.setForeground(null);
        fields.statusLabel.setText(submitStatusText(SubmitPhase.SUBMITTING, null, null));
        // 复制按钮随错误出现（预览窗口语义）：提交中尚无错误，先收起
        fields.statusCopyButton.setVisible(false);
        repaintStatus();
    }

    /**
     * 提交结束（成功/失败都走这里，T-FeedbackErr）：成功 → 关窗并由调用方发通知；
     * 失败 → 留在窗内，按钮恢复可点、状态行给原因（服务端文案已透出）。
     */
    public void finishSubmitting(FeedbackApi.SubmissionOutcome outcome) {
        isSubmitting = false;
        FeedbackApi.SubmitResult result = outcome.getResult();
        if (result instanceof FeedbackApi.SubmitResult.Success) {
            if (!isHeadless()) {
                try {
                    close(OK_EXIT_CODE);
                } catch (Throwable ignored) {
                    // headless 无 peer，静默跳过
                }
            }
        } else if (result instanceof FeedbackApi.SubmitResult.Failure) {
            FeedbackApi.SubmitResult.Failure failure = (FeedbackApi.SubmitResult.Failure) result;
            if (!isHeadless()) {
                try {
                    setOKButtonText(MpSqlVisualizerBundle.message("error.report.dialog.submit"));
                    setOKActionEnabled(true);
                } catch (Throwable ignored) {
                    // peer 异常路径静默跳过
                }
            }
            fields.statusLabel.setForeground(MpvsUiColors.STATUS_ERROR);
            fields.statusLabel.setText(submitStatusText(SubmitPhase.FAILED, failure.getMessage(), outcome.getWarning()));
            // 复制按钮随错误出现（与 SQL 预览窗口 errorCopyButton 同款时机）
            fields.statusCopyButton.setVisible(true);
            repaintStatus();
        }
    }

    /** 状态行文案变化后标记重绘（headless 无 peer，静默跳过）。 */
    private void repaintStatus() {
        if (isHeadless()) {
            return;
        }
        try {
            fields.statusLabel.invalidate();
            Container pane = getContentPane();
            if (pane != null) {
                pane.invalidate();
                pane.repaint();
            }
        } catch (Throwable ignored) {
            // headless/异常路径静默跳过
        }
    }

    /** peer 未创建（headless/异常路径）时不碰按钮与关窗 API。 */
    private boolean isHeadless() {
        try {
            return getWindow() == null || !getWindow().isDisplayable();
        } catch (Throwable t) {
            return true;
        }
    }

    private void browserFallback() {
        List<String> params = new ArrayList<>();
        params.add("source=" + FeedbackApi.SOURCE_IDE);
        try {
            if (ideVersion != null) {
                params.add("ideVersion=" + URLEncoder.encode(ideVersion, StandardCharsets.UTF_8));
            }
            if (pluginVersion != null) {
                params.add("pluginVersion=" + URLEncoder.encode(pluginVersion, StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // URLEncoder.encode(String, Charset) 理论不抛，兜底保持参数可拼
        }
        SafeBrowser.browse(FeedbackApi.SITE_BASE + "/about/feedback?" + String.join("&", params));
    }

    private void selectCategory(String slug) {
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].equals(slug)) {
                fields.categoryCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    private static @Nullable String blankToNull(@Nullable String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /** 取前 limit 行拼接（Kotlin lineSequence().take(n).joinToString 的等价形态）。 */
    private static String headLines(String text, int limit) {
        List<String> head = new ArrayList<>();
        for (String line : text.split("\\R")) {
            head.add(line);
            if (head.size() == limit) {
                break;
            }
        }
        return String.join("\n", head);
    }

    /** 组装期异常落 idea.log 的通道（T-ErrReportSubmitNoOp：状态行给用户、日志给排障）。 */
    private static final Logger LOG = Logger.getInstance(ErrorReportDialog.class);

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

    /** DimensionService key：用户拖拽后的对话框尺寸跨会话记忆（T-FeedbackDlg-02）。 */
    public static final String DIMENSION_SERVICE_KEY = "MpSqlVisualizer.ErrorReportDialog";

    /** 取消勾选缩窗时的地板高（防把手动拖到极小的窗口缩到看不见内容）。 */
    public static final int MIN_WINDOW_HEIGHT = 400;

    /**
     * 勾选/取消「附带异常堆栈」的窗口高度补偿量（纯函数，T-FeedbackDlg2-01）。
     * GridBag 把纵向富余（contentHeight - 可见行首选高之和）在 weighty=1 行间平分，
     * 描述行（weighty=1）随堆栈行显隐被挤占/释放空间——真机即「点击勾选后整片跳上去」。
     *
     * <p>- 勾选：描述行损失 Δ = (slack + S)/2（slack=当前富余、S=堆栈行首选高，即
     * preferredGrowth）；此后两 weighty 行对分新增空间，窗口撑高 2Δ = slack + S
     * 即可让描述行分毫不差。
     * - 取消：堆栈行当前实占高 R（含行间隙近似）整块释放给唯一 weighty 描述行，
     * 窗口缩回 R 即恢复原状。
     *
     * <p>返回单位 px，正=撑高、负=缩回；0 = 无需调整。
     */
    public static int toggleResizeDelta(boolean show, int contentHeight, int contentPrefHeight,
                                        int preferredGrowth, int stackRowHeight) {
        if (show) {
            return Math.max(contentHeight - contentPrefHeight, 0) + Math.max(preferredGrowth, 0);
        }
        // 取消：堆栈行当前实占高与 preferred 缩减量取大者整块释放（后者兜底未布局态）
        return -Math.max(Math.max(stackRowHeight, -preferredGrowth), 0);
    }

    public static final String VALIDATION_TITLE_KEY = "error.report.dialog.validation.title";
    public static final String VALIDATION_DESCRIPTION_KEY = "error.report.dialog.validation.description";

    // ---------- T-ErrorReportRedact：预填分级（纯函数，headless 可测） ----------

    /**
     * 预填分级切换的目标值（纯函数，T-ErrorReportRedact）：勾选「附带异常消息全文」→
     * 标题/描述取含 message 的旧形态；取消 → 取脱敏形态。
     *
     * <p><b>只回填「内容仍等于上次预填写入值」的字段</b>——用户手工改过的字段原样返回（不覆盖），
     * 免得勾一下抹掉刚写的内容；{@code lastInjectedTitle}/{@code lastInjectedDescription} 为 null
     * （手动形态或从未预填）时同样不覆盖。</p>
     */
    public static Pair<String, String> messagePrefillTargets(
            boolean attach,
            ErrorReportComposer.Prefill prefill,
            String currentTitle,
            String currentDescription,
            @Nullable String lastInjectedTitle,
            @Nullable String lastInjectedDescription) {
        String title = attach ? prefill.getTitleWithMessage() : prefill.getTitle();
        String description = attach ? prefill.getDescriptionWithMessage() : prefill.getDescription();
        return new Pair<>(
                Objects.equals(currentTitle, lastInjectedTitle) ? title : currentTitle,
                Objects.equals(currentDescription, lastInjectedDescription) ? description : currentDescription);
    }

    /**
     * 提交前客户端校验（纯函数）：title 5~200 / description ≥10（需求池服务端契约，
     * 与 ErrorReportComposer 同源常量）。返回应提示的 i18n key；null = 通过。
     */
    public static @Nullable String validateForm(String title, String description) {
        String trimmed = title.trim();
        if (trimmed.length() < ErrorReportComposer.TITLE_MIN || trimmed.length() > ErrorReportComposer.TITLE_MAX) {
            return VALIDATION_TITLE_KEY;
        }
        if (description.trim().length() < ErrorReportComposer.DESCRIPTION_MIN) {
            return VALIDATION_DESCRIPTION_KEY;
        }
        return null;
    }

    /**
     * 布局组装（纯函数，T-FeedbackDlg-03/02）：描述与堆栈滚动面板首选宽 620 钳制弹窗宽度，
     * 两行 fill=BOTH + weighty=1 随对话框拖拽纵向伸缩（堆栈未勾选不可见时不参与分配）。
     */
    public static JComponent buildCenterPanel(ReportFormFields fields) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(4, 0, 4, 0);
        JPanel panel = new JPanel(new GridBagLayout());

        labelRow(panel, gbc, "error.report.dialog.subject", fields.titleField, false);
        labelRow(panel, gbc, "error.report.dialog.type", fields.typeCombo, false);
        labelRow(panel, gbc, "error.report.dialog.category", fields.categoryCombo, false);
        JBScrollPane descriptionScroll = new JBScrollPane(fields.descriptionArea);
        // T-FeedbackDlg3：默认首选高 140→90（用户真机反馈描述框默认过大）
        descriptionScroll.setPreferredSize(JBUI.size(620, 90));
        labelRow(panel, gbc, "error.report.dialog.description", descriptionScroll, true);
        // 消息附带开关（T-ErrorReportRedact）：紧贴描述行——它改的正是标题/描述的预填内容
        fullRow(panel, gbc, fields.attachMessageCheck, false);
        // 勾选行（T-MarketInternal：原右侧「从 IDE 错误池导入」按钮已摘除）
        fullRow(panel, gbc, fields.attachStackCheck, false);
        fullRow(panel, gbc, fields.stackPreviewContainer, true);
        labelRow(panel, gbc, "error.report.dialog.name", fields.nameField, false);
        labelRow(panel, gbc, "error.report.dialog.email", fields.emailField, false);
        fullRow(panel, gbc, fields.anonymousCheck, false);
        fullRow(panel, gbc, fields.browserLink, false);
        // T-FeedbackBatch：批次条目分节（默认隐藏，有批次条目时才显示）
        fullRow(panel, gbc, fields.batchSection, true);
        // T-FeedbackErr：状态行常驻最底部（提交中/失败原因就地展示，不依赖 toast），
        // 行内带「复制失败信息」按钮（buildStatusRow 组装）
        fullRow(panel, gbc, buildStatusRow(fields), false);
        return panel;
    }

    private static void labelRow(JPanel panel, GridBagConstraints gbc, String textKey,
                                 JComponent component, boolean stretchVertically) {
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 0.0;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JBLabel(MpSqlVisualizerBundle.message(textKey)), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        if (stretchVertically) {
            gbc.weighty = 1.0;
            gbc.fill = GridBagConstraints.BOTH;
        } else {
            gbc.fill = GridBagConstraints.HORIZONTAL;
        }
        panel.add(component, gbc);
        gbc.weighty = 0.0;
    }

    private static void fullRow(JPanel panel, GridBagConstraints gbc, JComponent component, boolean stretchVertically) {
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.weighty = stretchVertically ? 1.0 : 0.0;
        gbc.fill = stretchVertically ? GridBagConstraints.BOTH : GridBagConstraints.HORIZONTAL;
        panel.add(component, gbc);
        gbc.gridwidth = 1;
        gbc.weighty = 0.0;
    }

    /**
     * 失败状态行文案（纯函数，T-FeedbackErr）：服务端原因 + 可选附件降级说明 + 重试引导。
     * 附件行用中性表述（attachmentFailed）——状态行只在失败时出现，「帖子已提交，但…」的成功语境
     * 在失败现场自相矛盾（真机第二轮截图实证：同屏出现「提交失败」与「帖子已提交」）。
     */
    public static String failureStatusText(String message) {
        return failureStatusText(message, null);
    }

    public static String failureStatusText(String message, @Nullable String warning) {
        String head = MpSqlVisualizerBundle.message("error.report.dialog.status.failed", message);
        if (warning == null || warning.isBlank()) {
            return head;
        }
        return head + "\n" + MpSqlVisualizerBundle.message("error.report.dialog.status.attachmentFailed", warning);
    }

    /** 提交阶段（状态机；{@link #submitStatusText} 的输入）。 */
    public enum SubmitPhase { SUBMITTING, SUCCESS, FAILED }

    /**
     * 状态行组装（纯函数）：可换行文本区居中 + 「复制错误信息」按钮居东——按钮不随行拉伸，
     * 文本区吸收宽度；单独抽出的目的与 buildCenterPanel 一致（headless 可测、布局无 peer 依赖）。
     */
    public static JComponent buildStatusRow(ReportFormFields fields) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(fields.statusLabel, BorderLayout.CENTER);
        row.add(fields.statusCopyButton, BorderLayout.EAST);
        return row;
    }

    /**
     * 状态行文案（纯函数，T-FeedbackErr 唯一事实源）：对话框实例上的
     * beginSubmitting/finishSubmitting 只是把它写进状态行——DialogWrapper 构造断言 EDT
     * （ThreadingAssertions 硬抛，测试 worker 非 EDT），逻辑本身必须能脱离实例被锁定。
     * 返回 null = 该阶段无状态文案（成功即关窗，不需要写状态行）。
     */
    public static @Nullable String submitStatusText(SubmitPhase phase, @Nullable String message, @Nullable String warning) {
        switch (phase) {
            case SUBMITTING:
                return MpSqlVisualizerBundle.message("error.report.dialog.status.submitting");
            case SUCCESS:
                return null;
            case FAILED:
            default:
                return failureStatusText(message == null ? "" : message, warning);
        }
    }

    // ---------- T-FeedbackBatch：批量条目（纯函数，headless 可测） ----------

    /**
     * 把批次条目渲染进对话框（错误形态；条目 ≥2 时显示「本批共 N 条异常」分节 + 逐条勾选）。
     * T-FeedbackBatch2：单条 = 主预填自己，不出分节；头部行复选框禁用仅镜像主表单勾选态。
     * 静态纯函数：只碰 Swing 组件与 bundle，无 DialogWrapper peer 依赖，headless 可直调。
     */
    public static void applyBatchItemsToFields(ReportFormFields fields, List<BatchSubmitter.BatchItem> items) {
        fields.batchSection.removeAll();
        fields.batchSection.setVisible(items.size() >= 2);
        if (items.size() < 2) {
            return;
        }
        fields.batchSection.add(
                new JBLabel(MpSqlVisualizerBundle.message("error.report.dialog.batchHeader", items.size())),
                BorderLayout.NORTH);
        JBScrollPane batchScroll = new JBScrollPane(fields.batchList);
        batchScroll.setPreferredSize(JBUI.size(620, 90));
        fields.batchSection.add(batchScroll, BorderLayout.CENTER);
        fields.batchList.removeAll();
        for (int index = 0; index < items.size(); index++) {
            BatchSubmitter.BatchItem item = items.get(index);
            JCheckBox checkBox = new JCheckBox(batchItemLabel(item));
            checkBox.setSelected(item.getAttachStack());
            if (index == 0) {
                // T-FeedbackBatch2：头部条目的堆栈开关即主表单「附带异常堆栈」，此处仅镜像
                checkBox.setEnabled(false);
                checkBox.setToolTipText(MpSqlVisualizerBundle.message("error.report.dialog.batchItem.head.tip"));
            } else {
                checkBox.setToolTipText(MpSqlVisualizerBundle.message("error.report.dialog.batchItem.tip"));
            }
            // 勾选态即条目语义：勾上=该条连堆栈一起报（头部行被禁用，收集时由主开关回写）
            checkBox.addItemListener(e -> item.setAttachStack(checkBox.isSelected()));
            fields.batchList.add(checkBox);
        }
    }

    /** 批次条目复选框文案：异常类简单名 + 消息首行（超长截断）。 */
    public static String batchItemLabel(BatchSubmitter.BatchItem item) {
        String title = item.getTitle();
        int dot = title.lastIndexOf('.');
        String type = dot >= 0 ? title.substring(dot + 1) : title;
        if (type.isBlank()) {
            type = "PluginError";
        }
        String firstLine = "";
        if (item.getMessage() != null) {
            for (String line : item.getMessage().split("\\R")) {
                if (!line.isBlank()) {
                    firstLine = line.trim();
                    break;
                }
            }
        }
        String text = firstLine.isBlank() ? type : type + ": " + firstLine;
        return text.length() > 90 ? text.substring(0, 90) + "…" : text;
    }

    /**
     * 按勾选收集参与提交的批次条目（纯函数，T-FeedbackBatch2）：
     * 头部条目（主预填那条）的堆栈开关以主表单「附带异常堆栈」为准——回写条目状态并
     * 同步分节里被禁用的头部复选框显示；其余条目按各自复选框。无条目返回空集。
     *
     * <p>T-ErrReportSubmitNoOp：{@link ReportFormFields#batchList} 的组件数<b>不必</b>等于 {@code all}
     * 的条目数——单条上报按设计不出分节（{@link #applyBatchItemsToFields} 早退），容器此时是 0 组件的
     * 空容器，索引访问必须带界（真机 {@code ArrayIndexOutOfBoundsException: No such child: 0} 根因；
     * {@code as?}/{@code instanceof} 判的是类型不是下标，救不了裸索引）。单条因此收集为空集，落到
     * {@link BatchSubmitter} 文档化的「1 条 = 原样单帖」路径，主表单的堆栈勾选照常生效。</p>
     */
    public static List<BatchSubmitter.BatchItem> selectedBatchItemsFor(List<BatchSubmitter.BatchItem> all,
                                                                       boolean headAttachStack,
                                                                       Container batchList,
                                                                       boolean includeMessages) {
        if (all.isEmpty()) {
            return List.of();
        }
        BatchSubmitter.BatchItem head = all.get(0);
        head.setAttachStack(headAttachStack);
        // T-ErrorReportRedact：消息开关对全批条目统一生效（勾选态是对话框级的一个开关，
        // 不做逐条细分），收集时回写到每个条目供补发路径复用
        for (BatchSubmitter.BatchItem item : all) {
            item.setIncludeMessage(includeMessages);
        }
        if (batchList.getComponentCount() > 0 && batchList.getComponent(0) instanceof JCheckBox) {
            ((JCheckBox) batchList.getComponent(0)).setSelected(headAttachStack);
        }
        List<BatchSubmitter.BatchItem> selected = new ArrayList<>();
        for (int index = 0; index < all.size(); index++) {
            boolean checked = index < batchList.getComponentCount()
                    && batchList.getComponent(index) instanceof JCheckBox
                    && ((JCheckBox) batchList.getComponent(index)).isSelected();
            if (checked) {
                selected.add(all.get(index));
            }
        }
        return selected;
    }

    /** Kotlin 默认参数等价重载：includeMessages 缺省 false（脱敏形态）。 */
    public static List<BatchSubmitter.BatchItem> selectedBatchItemsFor(List<BatchSubmitter.BatchItem> all,
                                                                       boolean headAttachStack,
                                                                       Container batchList) {
        return selectedBatchItemsFor(all, headAttachStack, batchList, false);
    }

    /**
     * 按勾选重写首发载荷（纯函数，T-FeedbackBatch）：≥2 条勾选时标题/描述改为批次形态
     * （「等 N 条异常」+ 条目清单），并把堆栈附件换成多节合并附件；1 条或 0 条时原样返回。
     *
     * <p>T-ErrorReportRedact：条目清单里的逐条消息按 {@link BatchSubmitter.BatchItem#getIncludeMessage()}
     * 决定去留（收集时由「附带异常消息全文」开关统一回写）——默认脱敏形态清单只出异常类名，
     * 开关打开时逐字节等于改前形态。混态（理论上不会出现）按保守侧处理：全不带才带。</p>
     */
    public static FeedbackApi.Submission composeSubmission(FeedbackApi.Submission submission,
                                                           List<BatchSubmitter.BatchItem> selected) {
        if (selected.size() < 2) {
            return submission;
        }
        boolean includeMessages = true;
        for (BatchSubmitter.BatchItem item : selected) {
            if (!item.getIncludeMessage()) {
                includeMessages = false;
                break;
            }
        }
        List<ErrorReportComposer.BatchEntry> entries = new ArrayList<>();
        for (BatchSubmitter.BatchItem item : selected) {
            entries.add(new ErrorReportComposer.BatchEntry(
                    item.getTitle(), item.getMessage(), item.getStackTraceText()));
        }
        FeedbackApi.PostRequest old = submission.getRequest();
        FeedbackApi.PostRequest request = old.copy(
                ErrorReportComposer.buildBatchTitle(entries, includeMessages),
                ErrorReportComposer.buildBatchDescription(entries, old.getDescription(), includeMessages),
                old.getType(),
                old.getCategory(),
                old.getSubmitterName(),
                old.getSubmitterEmail(),
                old.isAnonymous(),
                old.getSource(),
                old.getIdeVersion(),
                old.getPluginVersion(),
                old.getAttachments());
        return BatchSubmitter.composeFirstSubmission(submission.copy(request, submission.getStackAttachment()), selected);
    }

    public static String typeLabel(int type) {
        return MpSqlVisualizerBundle.message("feedback.type." + type);
    }

    public static String categoryLabel(String slug) {
        return MpSqlVisualizerBundle.message("feedback.category." + slug);
    }

    /**
     * 对话框全部输入控件（T-FeedbackDlg 可测试性抽取）：构造即带占位提示与换行配置，
     * {@link #buildCenterPanel}/{@link #validateForm} 的纯函数载体，
     * headless 测试直接构造，零 DialogWrapper peer 依赖。
     */
    public static class ReportFormFields {

        /** 标题 30 列（T-FeedbackDlg-03：旧实现误用 TITLE_MAX=200 列致弹窗 1272px 过宽）。 */
        public final JBTextField titleField = new JBTextField(30);

        public final JComboBox<String> typeCombo;
        public final JComboBox<String> categoryCombo;

        public final JBTextArea descriptionArea = new JBTextArea(4, 40);

        public final JCheckBox attachStackCheck = new JCheckBox();

        /**
         * 「附带异常消息全文」勾选行（T-ErrorReportRedact D1=B）：<b>默认不勾</b>——异常消息是业务文本
         * （SQL、表名、路径），默认不外传；勾上后标题/描述回填含 message 的旧形态。无 message 时整行隐藏。
         */
        public final JCheckBox attachMessageCheck = new JCheckBox();

        /** 堆栈区：错误形态只读预览；手动形态可编辑（自主粘贴，T-FeedbackDlg3；T-MarketInternal 起无导入按钮）。 */
        public final JBTextArea stackPreview = new JBTextArea(10, 40);

        public final JPanel stackPreviewContainer = new JPanel(new BorderLayout(4, 2));

        public final JTextField nameField = new JTextField(20);
        public final JTextField emailField = new JTextField(20);

        public final JCheckBox anonymousCheck = new JCheckBox();

        /**
         * 提交状态行（T-FeedbackErr 真机第二轮）：必须用可换行文本区而非 JBLabel——失败原因可能
         * 很长（如 Gson 排障链接、非 JSON 正文片段），JBLabel 单行渲染会把 GridBag 整行撑到上千 px，
         * 整个对话框被拉宽到 ~1900px（真机截图实证）。透明背景融入面板，preferred 宽 40 列封顶。
         */
        public final JBTextArea statusLabel = new JBTextArea(2, 40);

        /** 状态行「复制错误信息」按钮（真机第三轮用户定案：与 SQL 预览窗口完全一致）——样式取其
         *  smallButton（11f 小字）、文案复用同一键 panel.copyError、<b>默认隐藏</b>，仅失败态出现
         *  （预览窗口同款语义：errorCopyButton.isVisible = currentWarning != null）。 */
        public final JButton statusCopyButton = new JButton(MpSqlVisualizerBundle.message("panel.copyError"));

        /** 状态行复制行为由对话框注入（T-FeedbackErr），字段类保持无逻辑（同 browserLink 模式）。 */
        public Runnable statusCopyHandler = () -> { };

        /** 批次条目列表（T-FeedbackBatch）：每个条目一个复选框，勾选态即该条的「附带堆栈」。 */
        public final JPanel batchList = new JPanel(new GridLayout(0, 1, 0, 2));

        /** 批次分节容器（默认隐藏；{@link #setBatchItems} 注入后显示）。 */
        public final JPanel batchSection = new JPanel(new BorderLayout(4, 2));

        /** 浏览器兜底链接行为由对话框注入，字段类保持无逻辑。 */
        public Runnable browserFallbackHandler = () -> { };
        public final LinkLabel<Object> browserLink = new LinkLabel<>("", null,
                (source, linkData) -> browserFallbackHandler.run());

        public ReportFormFields() {
            titleField.getEmptyText().setText(MpSqlVisualizerBundle.message("error.report.dialog.subject.placeholder"));

            String[] typeLabels = new String[4];
            for (int i = 0; i < 4; i++) {
                typeLabels[i] = typeLabel(i + 1);
            }
            typeCombo = new JComboBox<>(typeLabels);

            String[] categoryLabels = new String[CATEGORIES.length];
            for (int i = 0; i < CATEGORIES.length; i++) {
                categoryLabels[i] = categoryLabel(CATEGORIES[i]);
            }
            categoryCombo = new JComboBox<>(categoryLabels);

            descriptionArea.setLineWrap(true);
            descriptionArea.setWrapStyleWord(true);
            descriptionArea.getEmptyText().setText(
                    MpSqlVisualizerBundle.message("error.report.dialog.description.placeholder"));

            attachStackCheck.setText(MpSqlVisualizerBundle.message("error.report.dialog.attachStack"));
            attachStackCheck.setToolTipText(MpSqlVisualizerBundle.message("error.report.dialog.attachStack.tip"));

            attachMessageCheck.setText(MpSqlVisualizerBundle.message("error.report.dialog.attachMessage"));
            attachMessageCheck.setToolTipText(MpSqlVisualizerBundle.message("error.report.dialog.attachMessage.tip"));

            stackPreview.setEditable(false);
            stackPreview.setLineWrap(false);
            stackPreviewContainer.add(
                    new JBLabel(MpSqlVisualizerBundle.message("error.report.dialog.stackPreview")), BorderLayout.NORTH);
            JBScrollPane stackScroll = new JBScrollPane(stackPreview);
            stackScroll.setPreferredSize(JBUI.size(620, 180));
            stackPreviewContainer.add(stackScroll, BorderLayout.CENTER);

            anonymousCheck.setText(MpSqlVisualizerBundle.message("error.report.dialog.anonymous"));

            statusLabel.setEditable(false);
            statusLabel.setLineWrap(true);
            statusLabel.setWrapStyleWord(true);
            statusLabel.setOpaque(false);

            statusCopyButton.setFont(statusCopyButton.getFont().deriveFont(11f));
            statusCopyButton.setVisible(false);

            batchSection.setVisible(false);
        }
    }
}
