package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 日志 SQL 条目管理（T-M31-03）：project 级轻服务——平台壳。
 *
 * 职责：持有纯逻辑缓冲 [SqlLogEntryBuffer]（条目/暂停/监听，可纯 JUnit 测试），
 * 经 [SqlLogConsoleBridge] 反射挂载运行控制台（execution API 不在编译 classpath，见桥 KDoc），
 * 并把缓冲变更 [notifyUi] 调度到 EDT 供面板刷新。
 */
@Service(Service.Level.PROJECT)
class SqlLogManager(private val project: Project) : Disposable {

    private val buffer = SqlLogEntryBuffer()
    private val bridge = SqlLogConsoleBridge(buffer)
    private val uiListeners = CopyOnWriteArrayList<() -> Unit>()

    /** 条目缓冲（面板与测试访问）。 */
    val entries: SqlLogEntryBuffer get() = buffer

    /** 捕捉状态（默认关——用户点击「开始捕捉」后才挂载监听，对齐 MybatisCodeHelper-Pro 手动监听）。 */
    @Volatile
    var capturing: Boolean = false
        private set

    /** 开始捕捉：挂载控制台监听（幂等）。 */
    fun startCapture() {
        if (capturing) return
        capturing = true
        bridge.attach(project)
    }

    /** 停止捕捉：移除挂载，保留已捕获条目。 */
    fun stopCapture() {
        if (!capturing) return
        capturing = false
        bridge.detach()
    }

    /** UI 订阅刷新回调（回调在 EDT 执行；首个订阅者开始桥接缓冲变更）。 */
    fun addUiListener(listener: () -> Unit) {
        if (uiListeners.isEmpty()) buffer.addListener(::notifyUi)
        uiListeners += listener
    }

    fun removeUiListener(listener: () -> Unit) {
        uiListeners -= listener
        if (uiListeners.isEmpty()) buffer.removeListener(::notifyUi)
    }

    private fun notifyUi() {
        ApplicationManager.getApplication().invokeLater {
            uiListeners.forEach { it() }
        }
    }

    /** 面板销毁/项目关闭时移除挂载。 */
    override fun dispose() {
        bridge.detach()
        buffer.removeListener(::notifyUi)
        uiListeners.clear()
    }
}
