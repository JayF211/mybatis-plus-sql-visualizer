package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;

/**
 * M56 T-M56-08：增强捕获注入确认对话框（首次启用弹一次，确认记忆持久化）。
 *
 * 话术四件套（§4.5）：目的 / 采集范围 / 数据留本机不回传 + 回落路径 /
 * JDK21+ 目标控制台会出现动态挂载告警属预期（JEP 451，仅告警未禁止；未来硬禁止走
 * 回落路径并提示 {@code -XX:+EnableDynamicAgentLoading}，插件不自动改用户配置）。
 *
 * T-PS4-01：动作三态化——同意（OK）/ 关闭增强捕获并不再询问（第三动作，用户明确新增需求）/
 * 取消。对话框只产出 {@link EnhancedCaptureConsent} 结果，持久化语义由调用方承担（agent 挂载链的
 * AgentManager 逐态落盘；设置页 {@link PluginSettingsPanel} 的首次启用确认仅消费 ACCEPTED 与否）。
 */
public class EnhancedCaptureConfirmDialog extends DialogWrapper {

    /** 「关闭增强捕获」动作的退出码（非 OK/CANCEL 平台保留区，仅用于 close 闭环；结果以 {@link #getOutcome()} 为准）。 */
    private static final int DISABLE_EXIT_CODE = 4;

    /** 模态确认；true = 用户同意注入（DISABLE_CAPTURE / REJECTED 均为 false）。 */
    public static boolean confirm(Project parent) {
        return confirmOutcome(parent) == EnhancedCaptureConsent.ACCEPTED;
    }

    /** 模态确认三态结果（T-PS4-01）：agent 挂载链确认流的标准入口。 */
    public static EnhancedCaptureConsent confirmOutcome(Project parent) {
        EnhancedCaptureConfirmDialog dialog = new EnhancedCaptureConfirmDialog(parent);
        dialog.show();
        return dialog.getOutcome();
    }

    /** 对话框结果（默认 {@link EnhancedCaptureConsent#REJECTED}：右上角 X 关闭等未决路径按拒绝处理）。 */
    private EnhancedCaptureConsent outcome = EnhancedCaptureConsent.REJECTED;

    public EnhancedCaptureConsent getOutcome() {
        return outcome;
    }

    /**
     * 第三动作：关闭增强捕获并不再询问（T-PS4-01 用户明确新增需求；照 DialogWrapperExitAction 惯例）。
     *
     * <b>声明位置是硬约束，不得下移到构造器 {@code init()} 调用之后</b>：Java 字段初始化按文本顺序执行，
     * 而平台的 {@code DialogWrapper.init()}（在下方构造器里调用）会回调 {@link #createActions()}——此刻本字段
     * 尚未赋值的话，传进动作数组的是 null，平台按「无此按钮」处理（T-CaptureTruth 实证：243 平台
     * {@code DialogWrapper.init()} 字节码直调 {@code createActions()}；沙盒日志无「确认框异常」= 平台容忍 null
     * 而非抛错，第三按钮静默消失）。守卫测试 {@code DialogWrapperActionOrderGuardTest} 防回潮。
     */
    private final DialogWrapperExitAction disableCaptureAction = new DialogWrapperExitAction(
        MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.disable"),
        DISABLE_EXIT_CODE
    ) {
        @Override
        protected void doAction(ActionEvent e) {
            outcome = EnhancedCaptureConsent.DISABLE_CAPTURE;
            super.doAction(e);
        }
    };

    public EnhancedCaptureConfirmDialog(Project parent) {
        super(parent, true);
        setTitle(MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.title"));
        init();
        // Cancel 文案须在 init() 之后显式覆盖（createActions 默认文案先落地）
        setCancelButtonText(MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.cancel"));
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(JBUI.size(520, 240));
        JBLabel label = new JBLabel();
        label.setText(
            "<html><body style='width:480px'>"
                + MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.body")
                + "</body></html>");
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction(), disableCaptureAction, getCancelAction()};
    }

    @Override
    protected Action getOKAction() {
        Action action = super.getOKAction();
        action.putValue(Action.NAME, MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.ok"));
        return action;
    }

    /**
     * T-ConsentTruth T-CN-01：OK（「同意并启用」）的结果写入——<b>三态里唯一必须显式接线的一态</b>。
     *
     * 缺陷现场（T-PS4-01 {@code 0fbd147} 引入，2026-09-18）：三态化改造把
     * {@code showAndGet()}（布尔值本身即「是否按了 OK」）换成 {@code show()} + 读 {@link #getOutcome()}，却只接上了
     * {@link EnhancedCaptureConsent#DISABLE_CAPTURE}——本方法缺失时 {@code outcome} 停在初值 REJECTED，
     * 于是 {@code confirm()} 恒 false：设置页勾选被自身弹回、应用启动弹窗把「同意」记成「拒绝」并写 24h
     * 冷却，<b>增强捕获经 UI 完全不可达</b>（用户报障「点确认但开启标识没变且不生效」）。
     *
     * public（平台原为 protected）是运行期测试缝：headless 下直调本方法断言三态可达，惯例同
     * {@code EntitySelectionDialog.doOKAction()} / {@code ParamImportDialogTest} / {@code SqlPreviewDialogTest}。
     * 另由 {@code DialogWrapperInitOrderGuardTest} 的状态可达性守卫遍历枚举防「加状态忘接线」回潮。
     */
    @Override
    public void doOKAction() {
        outcome = EnhancedCaptureConsent.ACCEPTED;
        super.doOKAction();
    }
}
