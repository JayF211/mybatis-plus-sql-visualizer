package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * 代码左侧预览 SQL 图标（M10-02，F-04/05/07 新增 gutter 入口）。
 *
 * 在 Wrapper 调用链锚点行显示预览图标，点击弹出 SQL 预览（复用 [SqlPreviewService] 链路）。
 *
 * **性能红线（M10 开发计划 §2.4，硬性约束）**：
 *  - 快速路径 [getLineMarkerInfo] 逐元素回调，只做 [WrapperAnchorDetector] 的 O(1) 纯语法判定，
 *    零 resolve、零遍历、零转换；负例立即返回 null；
 *  - tooltip 返回静态文案，不计算 SQL 内容；
 *  - 点击（EDT 回调）只捕获锚点元素，转换一律经 [SqlPreviewService.previewAsync] 后台执行；
 *  - [PluginSettings.state.showGutterIcon] 可一键关闭（性能/视觉兜底）。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="JAVA" .../>`。
 */
class SqlGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val anchor = WrapperAnchorDetector.anchorElement(element) ?: return null
        if (!gutterEnabled()) return null
        // 七参构造器（2020.3+）：末参 accessibleNameProvider 供屏幕阅读器使用
        return LineMarkerInfo(
            anchor,
            anchor.textRange,
            SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.tooltip") },
            SqlGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.accessible.name") },
        )
    }

    /** 读取 gutter 图标开关（测试环境 service 未注册时按默认 true 处理）。 */
    private fun gutterEnabled(): Boolean = runCatching {
        PluginSettings.getInstance().state.showGutterIcon
    }.getOrDefault(true)

    /**
     * 点击处理：在 EDT 捕获锚点元素，转换走后台线程 + ReadAction（EDT 禁止 PSI 慢操作），
     * 结果按显示方式配置（F-16）展示，与 ConvertToSqlAction 完全同链路。
     *
     * 防抖（200ms 全局窗口）：连续点击同一 / 不同 gutter 图标时，仅首次触发 PSI resolve + 弹窗，
     * 窗口内后续点击静默丢弃，避免多后台线程并发执行同一解析 / 多弹窗叠加。
     */
    private object SqlGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {

        /** 防抖窗口（毫秒）。 */
        private const val DEBOUNCE_MS = 200L

        /** 最近一次成功导航的时间戳（volatile 保证多线程可见性）。 */
        @Volatile
        private var lastNavigateTime = 0L

        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val now = System.currentTimeMillis()
            // 防抖：200ms 内重复点击丢弃
            if (!shouldNavigate(lastNavigateTime, now)) return
            lastNavigateTime = now

            val project: Project = elt.project
            SqlPreviewService.previewAsync(project, {
                project.getService(SqlPreviewService::class.java).preview(elt)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", SqlGutterLineMarkerProvider::class.java)
    }
}

/**
 * 防抖判定（纯函数，便于单测）。
 *
 * @param lastTimeMs 上次导航时间戳（毫秒）
 * @param nowMs     当前时间戳（毫秒）
 * @param windowMs  防抖窗口（毫秒）
 * @return 是否应执行导航（true = 执行，false = 丢弃）
 */
internal fun shouldNavigate(lastTimeMs: Long, nowMs: Long, windowMs: Long = 200L): Boolean =
    nowMs - lastTimeMs >= windowMs
