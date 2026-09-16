package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 首次捕获成功指路（T-UxOnboard T-UX-03）：
 *
 * 会话内首次捕获到 SQL 时经预览组发一条通知，指路 SQL 日志工具窗（带「打开面板」动作）——
 * 新用户从「代码侧引导」（T-UX-01）自然过渡到「日志侧能力」。防打扰三重约束：
 *  - 会话级一次性（object 记忆，IDE 重启自然重置）；
 *  - 历史非空跳过——首个入列条目之外缓冲里已有条目（更早会话捕获残留），说明用户已在用；
 *  - headless（isUnitTestMode）直接静默（平台通知 API 测试进程降级路径，不入）。
 *
 * 挂载点：[SqlLogManager] 把本入口与自动展开同挂缓冲 onEntryAccepted 出口（每次成功入队
 * 即时回调），零新增监听链；决策矩阵抽 [shouldGuide] 纯函数供裸 JUnit 覆盖。
 */
object FirstCaptureGuide {

    private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

    /** 会话级一次性记忆（@Volatile——入列回调可能来自控制台输出线程）。 */
    @Volatile
    var alreadyGuided: Boolean = false
        private set

    /** 每次成功入队回调（[SqlLogManager] 挂载；headless 下恒静默）。 */
    fun onEntryAccepted(project: Project, buffer: SqlLogEntryBuffer) {
        val headless = ApplicationManager.getApplication()?.isUnitTestMode == true
        // O(1) size 访问器——本回调在控制台流式灌入热路径上，禁用 snapshot() 全量拷贝
        if (!shouldGuide(alreadyGuided, buffer.size(), headless)) return
        alreadyGuided = true
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            runCatching {
                val notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(
                        MpSqlVisualizerBundle.message("onboarding.captureGuide.title"),
                        MpSqlVisualizerBundle.message("onboarding.captureGuide.content"),
                        NotificationType.INFORMATION,
                    )
                notification.addAction(
                    NotificationAction.createSimpleExpiring(
                        MpSqlVisualizerBundle.message("onboarding.captureGuide.openPanel"),
                    ) {
                        if (!project.isDisposed) {
                            ToolWindowManager.getInstance(project)
                                .getToolWindow(SqlLogManager.TOOL_WINDOW_ID)?.show()
                        }
                    },
                )
                notification.notify(project)
            }
        }
    }

    /**
     * 指路决策（纯函数，测试缝）：未指路过 + 缓冲恰为 1 条（本条即会话首条，历史非空则跳过）
     * + 非 headless。入列回调触发时条目已入缓冲，size==1 即「首次捕获」。
     */
    internal fun shouldGuide(alreadyGuided: Boolean, bufferCount: Int, unitTestMode: Boolean): Boolean =
        !alreadyGuided && bufferCount == 1 && !unitTestMode

    /** 测试重置（会话记忆清零）。 */
    internal fun resetForTest() {
        alreadyGuided = false
    }
}
