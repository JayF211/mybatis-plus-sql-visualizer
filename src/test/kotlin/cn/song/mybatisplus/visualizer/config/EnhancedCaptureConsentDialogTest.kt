package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

/**
 * T-ConsentTruth T-CN-02：注入确认框三态结果运行期测试（本缺陷的正面守卫）。
 *
 * 背景：T-PS4-01（`0fbd147`）三态化改造把 `showAndGet()` 换成 `show()` + 读 `outcome`，却漏接 OK
 * 路径——`EnhancedCaptureConsent.ACCEPTED` 全仓无写入点，`confirm()` 恒 false。后果是增强捕获经 UI
 * 完全不可达：设置页勾选被自身弹回、应用启动弹窗把「同意」记成「拒绝」并写 24h 冷却。
 *
 * 为什么此前测试没抓住：`AgentConsentFlowTest` 注入的是假 `consentProvider`（绕开真对话框），
 * `DialogWrapperInitOrderGuardTest` 只查声明顺序——**真实对话框的 OK 路径此前零覆盖**。
 *
 * headless 直调 `doOKAction()` 的惯例同 `EntitySelectionDialogTest` / `ParamImportDialogTest` /
 * `SqlPreviewDialogTest`（故该方法在对话框里声明为 public override）。
 */
class EnhancedCaptureConsentDialogTest : BasePlatformTestCase() {

    fun testOK动作产出ACCEPTED() {
        val dialog = EnhancedCaptureConfirmDialog(null)
        dialog.doOKAction()
        assertEquals(
            "点「同意并启用」必须产出 ACCEPTED——否则 confirm() 恒 false：设置页勾选会被自身弹回、" +
                "启动弹窗会把同意记成拒绝并写 24h 冷却（T-ConsentTruth 缺陷现场）",
            EnhancedCaptureConsent.ACCEPTED,
            dialog.outcome,
        )
    }

    fun test未决态默认拒绝() {
        val dialog = EnhancedCaptureConfirmDialog(null)
        assertEquals(
            "初值须为 REJECTED（右上角 X 关闭/取消按拒绝的既有语义）",
            EnhancedCaptureConsent.REJECTED,
            dialog.outcome,
        )
    }
}
