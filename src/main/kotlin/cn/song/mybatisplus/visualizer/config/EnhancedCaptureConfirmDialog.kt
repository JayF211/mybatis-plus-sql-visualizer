package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 增强捕获注入确认结果（T-PS4-01 三态）。
 *
 * - [ACCEPTED]：同意注入——AgentManager 置 confirmed 持久化并继续挂载（此后永不弹）。
 * - [DISABLE_CAPTURE]：关闭增强捕获并不再询问——AgentManager 置 enhancedCaptureEnabled=false
 *   持久化，此后任何进程启动都不弹窗、不 attach，直到用户在设置页重新开启。
 * - [REJECTED]：拒绝（含右上角 X 关闭）——AgentManager 持久化「拒绝时刻」进入 24h 冷却，
 *   冷却期内不重复弹；冷却只作用于弹窗频率、不改变同意门槛（保守口径）。
 */
enum class EnhancedCaptureConsent {
    ACCEPTED,
    DISABLE_CAPTURE,
    REJECTED,
}

/**
 * M56 T-M56-08：增强捕获注入确认对话框（首次启用弹一次，确认记忆持久化）。
 *
 * 话术四件套（§4.5）：目的 / 采集范围 / 数据留本机不回传 + 回落路径 /
 * JDK21+ 目标控制台会出现动态挂载告警属预期（JEP 451，仅告警未禁止；未来硬禁止走
 * 回落路径并提示 `-XX:+EnableDynamicAgentLoading`，插件不自动改用户配置）。
 *
 * T-PS4-01：动作三态化——同意（OK）/ 关闭增强捕获并不再询问（第三动作，用户明确新增需求）/
 * 取消。对话框只产出 [EnhancedCaptureConsent] 结果，持久化语义由调用方承担（agent 挂载链的
 * AgentManager 逐态落盘；设置页 [PluginSettingsPanel] 的首次启用确认仅消费 ACCEPTED 与否）。
 */
class EnhancedCaptureConfirmDialog(parent: Project?) : DialogWrapper(parent, true) {

    companion object {
        /** 「关闭增强捕获」动作的退出码（非 OK/CANCEL 平台保留区，仅用于 close 闭环；结果以 [EnhancedCaptureConfirmDialog.outcome] 为准）。 */
        private const val DISABLE_EXIT_CODE = 4

        /** 模态确认；true = 用户同意注入（DISABLE_CAPTURE / REJECTED 均为 false）。 */
        fun confirm(parent: Project?): Boolean =
            confirmOutcome(parent) == EnhancedCaptureConsent.ACCEPTED

        /** 模态确认三态结果（T-PS4-01）：agent 挂载链确认流的标准入口。 */
        fun confirmOutcome(parent: Project?): EnhancedCaptureConsent {
            val dialog = EnhancedCaptureConfirmDialog(parent)
            dialog.show()
            return dialog.outcome
        }
    }

    /** 对话框结果（默认 [EnhancedCaptureConsent.REJECTED]：右上角 X 关闭等未决路径按拒绝处理）。 */
    var outcome: EnhancedCaptureConsent = EnhancedCaptureConsent.REJECTED
        private set

    /**
     * 第三动作：关闭增强捕获并不再询问（T-PS4-01 用户明确新增需求；照 DialogWrapperExitAction 惯例）。
     *
     * **声明位置是硬约束，不得下移到 [init] 块之后**：Kotlin 属性初始化按文本顺序执行，而平台的
     * `DialogWrapper.init()`（在下方 init 块里调用）会回调 [createActions]——此刻本字段尚未赋值，
     * 传进动作数组的是 null，平台按「无此按钮」处理（T-CaptureTruth 实证：243 平台
     * `DialogWrapper.init()` 字节码直调 `createActions()`；沙盒日志无「确认框异常」= 平台容忍 null
     * 而非抛错，第三按钮静默消失）。守卫测试 `DialogWrapperActionOrderGuardTest` 防回潮。
     */
    private val disableCaptureAction: DialogWrapperExitAction =
        object : DialogWrapperExitAction(
            MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.disable"),
            DISABLE_EXIT_CODE,
        ) {
            override fun doAction(e: java.awt.event.ActionEvent) {
                outcome = EnhancedCaptureConsent.DISABLE_CAPTURE
                super.doAction(e)
            }
        }

    init {
        title = MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.title")
        init()
        setCancelButtonText(MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.cancel"))
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = JBUI.size(520, 240)
        val label = JBLabel()
        label.setText(
            "<html><body style='width:480px'>"
                + MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.body")
                + "</body></html>",
        )
        panel.add(label, BorderLayout.CENTER)
        return panel
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, disableCaptureAction, cancelAction)

    override fun getOKAction(): Action = super.getOKAction().apply {
        putValue(Action.NAME, MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.ok"))
    }

    /**
     * T-ConsentTruth T-CN-01：OK（「同意并启用」）的结果写入——**三态里唯一必须显式接线的一态**。
     *
     * 缺陷现场（T-PS4-01 `0fbd147` 引入，2026-09-18）：三态化改造把
     * `showAndGet()`（布尔值本身即「是否按了 OK」）换成 `show()` + 读 [outcome]，却只接上了
     * [EnhancedCaptureConsent.DISABLE_CAPTURE]——本方法缺失时 `outcome` 停在初值 REJECTED，
     * 于是 `confirm()` 恒 false：设置页勾选被自身弹回、应用启动弹窗把「同意」记成「拒绝」并写 24h
     * 冷却，**增强捕获经 UI 完全不可达**（用户报障「点确认但开启标识没变且不生效」）。
     *
     * public（平台原为 protected）是运行期测试缝：headless 下直调本方法断言三态可达，惯例同
     * `EntitySelectionDialog.doOKAction()` / `ParamImportDialogTest` / `SqlPreviewDialogTest`。
     * 另由 `DialogWrapperInitOrderGuardTest` 的状态可达性守卫遍历枚举防「加状态忘接线」回潮。
     */
    public override fun doOKAction() {
        outcome = EnhancedCaptureConsent.ACCEPTED
        super.doOKAction()
    }
}
