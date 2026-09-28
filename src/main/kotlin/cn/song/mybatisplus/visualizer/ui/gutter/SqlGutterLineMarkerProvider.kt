package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.PsiSnapshot
import cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.daemon.LineMarkerInfo.LineMarkerGutterIconRenderer
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.SwingUtilities

/**
 * 代码左侧预览 SQL 图标（M10-02，F-04/05/07 新增 gutter 入口）。
 *
 * 在 Wrapper 调用链锚点行显示预览图标。
 *
 * **交互约定（M38.2 调整为更贴合调试直觉）**：
 *  - 左键（单击）→ 直接预览 SQL（一键路径最短，对齐原行为）；
 *  - 右键 → 弹出菜单：预览 SQL / 最近执行记录。
 *
 * **性能红线（M10 开发计划 §2.4，硬性约束）**：
 *  - 快速路径 [getLineMarkerInfo] 逐元素回调，只做 [WrapperAnchorDetector] 的 O(1) 纯语法判定，
 *    零 resolve、零遍历、零转换；负例立即返回 null；
 *  - tooltip 返回静态文案，不计算 SQL 内容；
 *  - 转换一律经 [SqlPreviewService.previewAsync] 后台执行（EDT 禁止 PSI 慢操作）；
 *  - [PluginSettings.state.showGutterIcon] 可一键关闭（性能/视觉兜底）。
 */
class SqlGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // T-Perf-23：开关判断前置——用户关闭图标后不再对每个 leaf 跑锚点判定
        if (!gutterEnabled()) return null
        if (!GutterGroups.enabledFor(javaClass, runCatching { PluginSettings.getInstance().state.showGutterIcon }.getOrDefault(true))) return null // R2 分组显隐
        val anchor = WrapperAnchorDetector.anchorElement(element) ?: return null
        // 七参构造器（2020.3+）：末参 accessibleNameProvider 供屏幕阅读器使用。
        // T-MergeFollowup-02：覆写 createGutterRenderer——243 平台 LineMarkerGutterIconRenderer 不覆写
        // getRightButtonClickAction（基类字节码恒 aconst_null），GutterIconNavigationHandler 只接左键 click，
        // M37.1 的「navigate 里判右键」永远收不到右键事件（真机右键无反应根因）——右键经平台正解 API 接线。
        return object : LineMarkerInfo<PsiElement>(
            anchor,
            anchor.textRange,
            SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.tooltip") },
            SqlGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.accessible.name") },
        ) {
            override fun createGutterRenderer(): GutterIconRenderer =
                object : LineMarkerGutterIconRenderer<PsiElement>(this) {
                    override fun getRightButtonClickAction(): AnAction = SqlGutterRightClickAction(anchor)
                }
        }
    }

    /** 读取 gutter 图标开关（测试环境 service 未注册时按默认 true 处理）。 */
    private fun gutterEnabled(): Boolean = runCatching {
        PluginSettings.getInstance().state.showGutterIcon
    }.getOrDefault(true)

    /**
     * 点击处理（M38.2 重构：左键直接预览 + 右键弹菜单）。
     *
     * 防抖（200ms 全局窗口）：连续点击同一 / 不同 gutter 图标时，仅首次触发，避免多后台线程并发 / 多弹窗叠加。
     *
     * 左右键语义：
     *  - 左键 → 直接预览 SQL（一键路径最短）；
     *  - 右键 → 弹出两选项菜单（预览 SQL / 最近执行记录）。
     *
     * NPE 修复：M37.1 方案在 BaseListPopupStep.onChosen 的 finalChoice=false（悬停预览）分支也执行动作并返回
     * FINAL_CHOICE，导致 ListPopupImpl 抛 NPE("FINAL_CHOICE must not be null")。现仅在 finalChoice=true 时执行。
     */
    private object SqlGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {

        /** 防抖窗口（毫秒）。 */
        private const val DEBOUNCE_MS = 200L

        /** 最近一次成功导航的时间戳（volatile 保证多线程可见性）。 */
        @Volatile
        private var lastNavigateTime = 0L

        /** 菜单项。 */
        private sealed class GutterAction(val labelKey: String) {
            data object Preview : GutterAction("gutter.popup.preview")
            data object Recent : GutterAction("gutter.popup.recent")
        }

        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val now = System.currentTimeMillis()
            // 防抖：200ms 内重复点击丢弃
            if (!shouldNavigate(lastNavigateTime, now)) return
            lastNavigateTime = now

            val project: Project = elt.project

            // 右键（或 popupTrigger）→ 弹出两选项菜单。
            if (e != null && (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger)) {
                showMenuPopup(project, elt, e)
                return
            }

            // 左键 → 直接预览 SQL（一键路径最短）。
            previewSource(project, elt)
        }

        /**
         * 右键入口（T-MergeFollowup-02）：[SqlGutterRightClickAction] 经平台 `getRightButtonClickAction`
         * 分发到此处——与 navigate 共享 200ms 防抖窗口（同 [lastNavigateTime]），菜单逻辑复用。
         */
        fun onRightClick(project: Project, elt: PsiElement, e: MouseEvent) {
            val now = System.currentTimeMillis()
            if (!shouldNavigate(lastNavigateTime, now)) return
            lastNavigateTime = now
            showMenuPopup(project, elt, e)
        }

        /** 右键菜单：预览 SQL / 最近执行记录。 */
        private fun showMenuPopup(project: Project, elt: PsiElement, e: MouseEvent) {            val actions = listOf<GutterAction>(GutterAction.Preview, GutterAction.Recent)
            val step = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<GutterAction>(
                null,
                actions,
            ) {
                override fun getTextFor(value: GutterAction): String =
                    MpSqlVisualizerBundle.message(value.labelKey)

                override fun onChosen(
                    selectedValue: GutterAction?,
                    finalChoice: Boolean,
                ): com.intellij.openapi.ui.popup.PopupStep<*>? {
                    // 仅 finalChoice=true 时执行；finalChoice=false 是悬停预览，不响应（避免 M37.1 的 NPE）。
                    if (!finalChoice) return null
                    when (selectedValue) {
                        is GutterAction.Recent -> showRecentEntriesPopup(project, elt)
                        else -> previewSource(project, elt)
                    }
                    return com.intellij.openapi.ui.popup.PopupStep.FINAL_CHOICE
                }
            }
            val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createListPopup(step)
            popup.show(com.intellij.ui.awt.RelativePoint(e))
        }

        /** 左键原行为：锚点转 SQL 并弹预览（后台转换，F-16 展示配置）。 */
        private fun previewSource(project: Project, elt: PsiElement) {
            SqlPreviewService.previewAsync(project, {
                project.getService(SqlPreviewService::class.java).preview(elt)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }

        /** 反查该锚点最近的日志执行记录并弹二级列表（匹配在后台：PSI 读包 ReadAction + 快照校验）。 */
        private fun showRecentEntriesPopup(project: Project, elt: PsiElement) {
            // T-PCP-02：EDT 侧捕获锚点快照（文件 + textRange + 文档 modificationStamp）；
            // 后台读取前由 [validatedRecentAnchor] 校验，失效即按「无记录」降级（不跳到错位置）
            val snapshot = PsiSnapshot.capture(elt)
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                val anchor = runCatching {
                    com.intellij.openapi.application.ReadAction.compute<Pair<String, Int>?, Throwable> {
                        validatedRecentAnchor(project.basePath, snapshot)
                    }
                }.getOrNull()
                val manager = project.getService(cn.song.mybatisplus.visualizer.log.SqlLogManager::class.java)
                val matched = cn.song.mybatisplus.visualizer.log.EntrySourceMatcher.match(
                    manager.entries.snapshot(),
                    anchor?.first,
                    anchor?.second ?: 0,
                )
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (matched.isEmpty()) {
                        // T-UiNative T-UN-02：无记录提示改轻通知（原模态弹窗——纯告知无需打断）；
                        // 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）
                        if (!com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode) {
                            runCatching {
                                com.intellij.notification.Notification(
                                    NOTIFICATION_GROUP_ID,
                                    MpSqlVisualizerBundle.message("gutter.popup.recent"),
                                    MpSqlVisualizerBundle.message("gutter.popup.recent.empty"),
                                    com.intellij.notification.NotificationType.INFORMATION,
                                ).notify(project)
                            }
                        }
                        return@invokeLater
                    }
                    val entryStep = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<cn.song.mybatisplus.visualizer.log.LogSqlEntry>(
                        MpSqlVisualizerBundle.message("gutter.popup.recent"),
                        matched,
                    ) {
                        override fun getTextFor(value: cn.song.mybatisplus.visualizer.log.LogSqlEntry): String =
                            value.summary

                        override fun onChosen(
                            selectedValue: cn.song.mybatisplus.visualizer.log.LogSqlEntry?,
                            finalChoice: Boolean,
                        ): com.intellij.openapi.ui.popup.PopupStep<*>? {
                            // 仅 finalChoice=true 时执行；避免悬停预览分支误触发 NPE。
                            if (!finalChoice) return null
                            // 20260907-1500：open 会同步 show SqlPreviewDialog，须挂 doFinalStep
                            //（popup 选中路径内同步弹 dialog 触发平台焦点告警，与生成测试资产同根因）。
                            selectedValue?.let {
                                return doFinalStep {
                                    cn.song.mybatisplus.visualizer.log.LogEntryPreviewOpener.open(project, it)
                                }
                            }
                            return com.intellij.openapi.ui.popup.PopupStep.FINAL_CHOICE
                        }
                    }
                    val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createListPopup(entryStep)
                    popup.showInFocusCenter()
                }
            }
        }

        // 绝对路径 → 项目根相对路径：T-PCP-02 起收口到文件级 [relativeToProjectPath]（快照校验链共用）
    }

    /**
     * 右键动作（T-MergeFollowup-02）：挂在自定义 renderer 的 `getRightButtonClickAction()` 上——
     * 243 平台对 gutter 图标右键只认该 API（`LineMarkerGutterIconRenderer` 未覆写、基类恒 null，
     * 平台事件分发层直接分流，`GutterIconNavigationHandler` 只接左键 click）。
     * 防抖与菜单逻辑收口 [SqlGutterNavigationHandler.onRightClick]。
     */
    private class SqlGutterRightClickAction(private val elt: PsiElement) : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val mouseEvent = e.inputEvent as? MouseEvent ?: return
            SqlGutterNavigationHandler.onRightClick(elt.project, elt, mouseEvent)
        }
    }

    companion object {
        private val SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", SqlGutterLineMarkerProvider::class.java)

        /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"
    }
}

// 保留供可能外部引用的防抖纯函数。
internal fun shouldNavigate(lastTimeMs: Long, nowMs: Long, windowMs: Long = 200L): Boolean =
    nowMs - lastTimeMs >= windowMs

/** gutter 侧诊断日志（快照失效按 debug 留痕——标记/记录缺失无害，不打扰用户）。 */
private val GUTTER_LOG: Logger = Logger.getInstance(SqlGutterLineMarkerProvider::class.java)

/** 绝对路径 → 相对项目根路径（项目外文件返回 null）。 */
internal fun relativeToProjectPath(base: String?, absolutePath: String): String? {
    if (base.isNullOrBlank()) return null
    val normAbs = absolutePath.replace('\\', '/')
    val normBase = base.replace('\\', '/').trimEnd('/')
    return if (normAbs.startsWith("$normBase/")) normAbs.removePrefix("$normBase/") else null
}

/**
 * T-PCP-02：锚点快照的**后台校验 + 行号解析**收口（须在读权限内调用，生产侧由 `ReadAction.compute` 包裹）。
 *
 * 捕获与读取之间的写操作会让元素失效——校验不过即返回 null，调用方按「无最近记录」如实降级
 * （原实现直接读 `elt.textRange`，文档已被替换时会算出错行号或抛失效异常）。
 *
 * 单独成函数（而非内联进池化块）是为了让失效路径可被单测直接驱动：合成「捕获 → 改文档 → 解析」
 * 三态无需真点 gutter 图标。守卫规则 `psiCapture`（convention/BannedApiSourceScanTest）要求
 * 跨线程读 PSI 的池化块出现本收口（或 `PsiSnapshot` 判定）。
 *
 * @param projectBasePath 项目根绝对路径（生产侧传 `project.basePath`；显式入参便于 fixture 用例驱动）
 */
internal fun validatedRecentAnchor(projectBasePath: String?, snapshot: PsiSnapshot?): Pair<String, Int>? {
    val snap = snapshot ?: return null
    val validity = snap.validate()
    if (validity is PsiSnapshot.Validity.Invalid) {
        GUTTER_LOG.debug("[gutter] 锚点快照已失效（${validity.reason.description}），跳过最近执行记录反查：${snap.filePath}")
        return null
    }
    val relative = relativeToProjectPath(projectBasePath, snap.filePath) ?: return null
    return relative to snap.lineNumber()
}
