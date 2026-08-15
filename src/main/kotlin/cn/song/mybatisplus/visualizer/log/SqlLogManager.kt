package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 日志 SQL 条目管理（T-M31-03）：project 级轻服务——平台壳。
 *
 * 职责：持有纯逻辑缓冲 [SqlLogEntryBuffer]（条目/暂停/去重/过滤，可纯 JUnit 测试），
 * 经 [SqlLogConsoleBridge] 反射挂载运行控制台（execution API 不在编译 classpath，见桥 KDoc），
 * 并把缓冲变更 [notifyUi] 调度到 EDT 供面板刷新。
 *
 * T-M31.14：配置装配——parser 前缀/混合/最大长度、去重窗口、过滤关键字均来自 [PluginSettings]；
 * 发现 SQL 自动打开日志窗口（[TOOL_WINDOW_ID]）。
 */
@Service(Service.Level.PROJECT)
class SqlLogManager(private val project: Project) : Disposable {

    private val buffer = SqlLogEntryBuffer()
    private val bridge = SqlLogConsoleBridge(buffer, ::parserConfig, ::applySettings)
    private val uiListeners = CopyOnWriteArrayList<() -> Unit>()
    private var attached = false

    /** 条目缓冲（面板与测试访问）。 */
    val entries: SqlLogEntryBuffer get() = buffer

    /** 捕捉状态（默认关——用户点击「开始捕捉」后才挂载监听，对齐 MybatisCodeHelper-Pro 手动监听）。 */
    @Volatile
    var capturing: Boolean = false
        private set

    /** 开始捕捉：挂载控制台监听 + 轮询兜底（幂等）。 */
    fun startCapture() {
        if (capturing) return
        capturing = true
        applySettings()
        if (bridge.attach(project)) {
            // 2026.2 无 contentAdded 监听接口（T-M31.10）→ 2s 轮询扫描新 console
            bridge.startPolling()
        }
    }

    /** 停止捕捉：移除挂载并停止轮询，保留已捕获条目。 */
    fun stopCapture() {
        if (!capturing) return
        capturing = false
        bridge.stopPolling()
        bridge.detach()
    }

    /** 应用设置（前缀/去重窗口/过滤——捕捉中修改设置后重新装配生效）。 */
    fun applySettings() {
        val state = PluginSettings.getInstance().state
        buffer.dedupeWindowMs = state.logDedupeWindowMs
        buffer.filterKeywords = state.logFilterKeywords
            .split(';').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** UI 订阅刷新回调（回调在 EDT 执行；首个订阅者开始桥接缓冲变更）。 */
    fun addUiListener(listener: () -> Unit) {
        if (uiListeners.isEmpty()) {
            buffer.addListener {
                notifyUi()
                maybeAutoOpenWindow()
            }
        }
        uiListeners += listener
    }

    fun removeUiListener(listener: () -> Unit) {
        uiListeners -= listener
        if (uiListeners.isEmpty()) buffer.removeListener(::notifyUi)
    }

    /** 发现 SQL 且设置开启时自动打开日志工具窗口（仅首次捕获触发一次）。 */
    private fun maybeAutoOpenWindow() {
        if (autoOpenFired) return
        if (!PluginSettings.getInstance().state.logAutoOpenOnCapture) return
        if (buffer.snapshot().isEmpty()) return
        autoOpenFired = true
        ApplicationManager.getApplication().invokeLater {
            ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
        }
    }

    private fun notifyUi() {
        ApplicationManager.getApplication().invokeLater {
            uiListeners.forEach { it() }
        }
    }

    private fun parserConfig(): ParserConfig {
        val state = PluginSettings.getInstance().state
        return ParserConfig(
            sqlPrefix = state.logSqlPrefix,
            paramPrefix = state.logParamPrefix,
            mixedPrefix = state.logMixedPrefix,
            maxParseLength = state.logMaxParseLength,
        )
    }

    /** 面板销毁/项目关闭时移除挂载。 */
    override fun dispose() {
        bridge.detach()
        buffer.removeListener(::notifyUi)
        uiListeners.clear()
    }

    companion object {
        /** plugin.xml `<toolWindow id>`（自动打开用）。 */
        const val TOOL_WINDOW_ID = "cn.song.mybatisplus.visualizer.log"
    }

    private var autoOpenFired = false
}

/** 解析器配置（桥创建 parser 用；纯数据）。 */
data class ParserConfig(
    val sqlPrefix: String,
    val paramPrefix: String,
    val mixedPrefix: Boolean,
    val maxParseLength: Int,
)
