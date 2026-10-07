package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * 日志条目跳转的共享原语（T-RecentExecMenu O-119）：解析 / 导航 / 轻提示 / 缺位置引导。
 *
 * **为什么只有原语、没有统一策略**：实时页与历史页的**分支策略本就不同**（搬迁前实测）——
 * 实时页 `MissingSource` 走轻通知（文案键 `log.panel.jumpToSource.*`），历史页把它并入
 * `NoSource` 走引导弹窗（文案键 `log.history.jumpToSource.*`）。共享件只收口两面板逐字相同的
 * 部分（[EntryJumpResolver] 转接、[MapperXmlLocator] 定位、`OpenFileDescriptor.navigate(true)`、
 * `isUnitTestMode` 跳过通知），**策略分支留在各自调用方**——避免「提取时取一侧丢另一侧」。
 *
 * 三个调用方：`SqlLogPanel`（实时页）、`HistorySqlLogPanel`（历史页）、`RecentExecutionsDialog`
 * （弹框右键菜单；策略取实时页口径，与菜单文案同源）。两面板的 `LogJumpTarget` /
 * `HistoryJumpTarget` 类型与 `resolveXxx` 签名**保持不动**（既有测试按其断言分支），内部改为
 * 由本对象转接。
 */
object LogEntryJumpOps {

    /** 轻提示通知组（实时/历史/弹框共用；plugin.xml 已注册）。 */
    const val NOTIFICATION_GROUP_ID: String = "cn.song.mybatisplus.visualizer.preview"

    /**
     * 源码跳转目标（[EntryJumpResolver.JumpTarget] 的同名转接；`MissingSource` 与 `NoSource`
     * **保持区分**，合并与否由调用方策略定）。
     */
    sealed class SourceTarget {
        data class Precise(val virtualFile: VirtualFile, val line: Int) : SourceTarget()
        data object NoSource : SourceTarget()
        data class NotFound(val sourceFile: String) : SourceTarget()
        data object MissingSource : SourceTarget()
    }

    /** mapper XML 语句节点目标。 */
    data class XmlTarget(val virtualFile: VirtualFile, val line: Int)

    /** 源码目标解析（仅依赖捕获位置，零启发式）。 */
    fun resolveSource(project: Project, entry: LogSqlEntry): SourceTarget =
        when (val target = EntryJumpResolver.resolve(project, entry)) {
            is EntryJumpResolver.JumpTarget.Precise -> SourceTarget.Precise(target.virtualFile, target.line)
            EntryJumpResolver.JumpTarget.NoSource -> SourceTarget.NoSource
            is EntryJumpResolver.JumpTarget.NotFound -> SourceTarget.NotFound(target.sourceFile)
            EntryJumpResolver.JumpTarget.MissingSource -> SourceTarget.MissingSource
        }

    /**
     * mapper XML 目标解析：mapperStatement 非空且 [MapperXmlLocator] 定位成功才返回；
     * 缺失 / MP 内置语句无 XML / 多模块定位失败一律 null（调用方回退 Java 跳转，不劣化）。
     * 定位自带 ReadAction，EDT 直调安全。
     */
    fun resolveXml(project: Project, entry: LogSqlEntry): XmlTarget? {
        val statementId = entry.mapperStatement?.takeIf { it.isNotBlank() } ?: return null
        val location = MapperXmlLocator.locate(project, statementId) ?: return null
        return XmlTarget(location.virtualFile, location.line)
    }

    /** 打开文件并定位（`navigate(true)`：跳转后聚焦编辑器）。 */
    fun navigate(project: Project, virtualFile: VirtualFile, line: Int) {
        OpenFileDescriptor(project, virtualFile, line, 0).navigate(true)
    }

    /**
     * 轻提示（T-UiNative T-UN-02 形态：纯告知不打断）。
     * [titleKey] 由调用方给——实时/历史两页面板标题文案键不同，属各自的展示口径。
     * 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）。
     */
    fun showToast(project: Project, titleKey: String, content: String) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            Notification(
                NOTIFICATION_GROUP_ID,
                MpSqlVisualizerBundle.message(titleKey),
                content,
                NotificationType.INFORMATION,
            ).notify(project)
        }
    }

    /** 缺源码位置引导（T-JumpCapture：引导开启源码捕获拦截器）。 */
    fun showMissingSourceGuide(project: Project) {
        SourceCaptureGuide.showMissingSourceDialog(project)
    }
}
