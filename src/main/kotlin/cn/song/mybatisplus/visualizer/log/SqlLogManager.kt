package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
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
 *
 * T-xx（设置生效性审计）：
 *  - 自动打开监听与面板解耦——窗口从未打开（启动自动监听旗舰场景）时同样触发 [maybeAutoOpenWindow]；
 *  - capturing 仅在桥挂载成功后置位——attach 失败不进入「已捕捉但无监听」的假状态（可重试）；
 *  - 启动自动监听（logAutoCaptureOnStart）仅由 [LogStartupCapture]（postStartupActivity）在项目打开时触发，
 *    项目关闭后 [dispose] 自动停止——语义为「随项目生命周期」，设置页勾选后不立即监听。
 */
@Service(Service.Level.PROJECT)
class SqlLogManager(private val project: Project) : Disposable {

    private val buffer = SqlLogEntryBuffer()
    private var bridge: SqlLogConsoleBridge = SqlLogConsoleBridge(buffer, ::parserConfig, ::applySettings) { onConsoleAppeared() }.apply {
        onConsoleRemoved = { this@SqlLogManager.onConsoleRemoved() }
    }
    private val uiListeners = CopyOnWriteArrayList<() -> Unit>()

    /** 缓冲变更 → 面板刷新（EDT 调度）；仅面板订阅时登记（命名引用——可被 removeListener 精确移除）。 */
    private val uiNotifyListener: () -> Unit = { notifyUi() }

    /** 缓冲变更 → 自动打开窗口检查（T-xx：与面板创建解耦——窗口从未打开时监听已接线）。 */
    private val autoOpenListener: () -> Unit = { maybeAutoOpenWindow() }

    /** 条目缓冲（面板与测试访问）。 */
    val entries: SqlLogEntryBuffer get() = buffer

    /** 捕捉状态（默认关——用户点击「开始捕捉」后才挂载监听，对齐 MybatisCodeHelper-Pro 手动监听）。 */
    @Volatile
    var capturing: Boolean = false
        private set

    init {
        // T-xx：自动打开决策监听随 manager 生命周期常驻——面板懒加载（工具窗口首次打开才创建
        // SqlLogPanel → addUiListener），若监听只在 addUiListener 时接线，窗口从未打开的场景
        // （启动自动监听 + logAutoOpenOnCapture）自动打开永不触发（该设置形同虚设）
        buffer.addListener(autoOpenListener)
    }

    /** T-xx：内部测试缝——注入受控桥（attach 恒成功/恒失败）验证 capturing 状态机；生产路径不调用。
     * （不用次级构造函数——轻服务容器按构造函数参数解析，避免多公开构造函数的歧义风险。） */
    internal fun installBridgeForTest(bridge: SqlLogConsoleBridge) {
        this.bridge = bridge
    }

    /** 开始捕捉：挂载控制台监听 + 轮询兜底（幂等）。 */
    fun startCapture() {
        LOG.info("startCapture: 调用开始（capturing=$capturing, project=${project.name}）")
        if (capturing) return
        applySettings()
        // T-xx（设置生效性审计）：**先挂载成功再置 capturing**——attach 失败（环境缺 execution API /
        // 反射目标不可用）时保持「未捕捉」态并 warn（可诊断），工具栏仍显示「捕捉」可手动重试；
        // 原实现先置 true 再 attach，失败后进入「已捕捉但无任何监听」的假状态（静默失败，无感知无重试）
        if (bridge.attach(project)) {
            capturing = true
            bridge.startPolling()
            LOG.info("startCapture: 已进入捕捉态（capturing=true, consoles=${attachedConsoles.size}）")
        } else {
            LOG.warn("startCapture: 控制台监听挂载失败（execution API 不可用）——未进入捕捉态，可稍后手动重试")
        }
    }

    /** 是否有活跃控制台（用于 apply() 判断是否立即启动监听）。 */
    fun hasConsoles(): Boolean = attachedConsoles.isNotEmpty() || SqlLogConsoleBridge.runContentManagerOrNull(project).let { rcm ->
        if (rcm == null) return@let false
        try {
            val raw = rcm.javaClass.getMethod("getAllDescriptors").invoke(rcm)
            val count = when (raw) {
                is Array<*> -> raw.size
                is Collection<*> -> raw.size
                else -> 0
            }
            count > 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 停止捕捉：重置 capturing 标志 + 清空已挂载 console 记录。
     * 性能优化：
     *  - 243/253（ContentManagerListener 可用）→ 停止轮询，改用事件回调检测新控制台（零开销）
     *  - 262+（无 ContentManagerListener）→ 保持轮询兜底（2s 间隔，轻量 reflection）
     */
    fun stopCapture() {
        if (!capturing) return
        capturing = false
        attachedConsoles.clear()
        if (bridge.contentListenerRegistered) {
            bridge.stopPolling() // 243/253：事件回调替代轮询
        }
        // 262+：保持轮询兜底（contentAdded 不可用）
    }

    /** 新控制台出现（contentAdded / 轮询检测）时回调——设置开启且未捕捉则自动重启。 */
    fun onConsoleAppeared() {
        if (capturing) return
        if (!PluginSettings.getInstance().state.logAutoCaptureOnStart) return
        LOG.info("onConsoleAppeared: 检测到新控制台 + 设置开启，自动重启捕捉（project=${project.name}）")
        startCapture()
    }

    /** 已挂载的 console 集合（bridge 访问）。 */
    val attachedConsoles: MutableMap<Any, Boolean> get() = bridge.attachedConsoles

    /** 控制台被移除（项目停止 / 用户关闭 tab）时回调——停止捕捉。 */
    fun onConsoleRemoved() {
        if (!capturing) {
            LOG.debug("onConsoleRemoved: 已非捕捉态（capturing=false），跳过停止")
            return
        }
        LOG.info("onConsoleRemoved: 控制台被移除，停止捕捉（project=${project.name}）")
        stopCapture()
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
            buffer.addListener(uiNotifyListener)
        }
        uiListeners += listener
    }

    fun removeUiListener(listener: () -> Unit) {
        uiListeners -= listener
        if (uiListeners.isEmpty()) buffer.removeListener(uiNotifyListener)
    }

    /** 发现 SQL 且设置开启时自动打开日志工具窗口（仅首次捕获触发一次；与面板是否已创建无关）。 */
    private fun maybeAutoOpenWindow() {
        if (autoOpenFired) return
        if (!PluginSettings.getInstance().state.logAutoOpenOnCapture) return
        if (buffer.snapshot().isEmpty()) return
        autoOpenFired = true
        ApplicationManager.getApplication().invokeLater {
            // runCatching 兜底：项目已关闭/工具窗口不可用等边缘场景不得抛出（显示失败静默即可）
            runCatching {
                ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
            }
        }
    }

    /** T-xx：测试访问器——验证窗口未打开时首条捕获也能触发自动打开决策（见 [maybeAutoOpenWindow]）。 */
    internal fun autoOpenFiredForTest(): Boolean = autoOpenFired

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

    /** 面板销毁/项目关闭时移除挂载（capturing 同步重置——状态机闭环）。 */
    override fun dispose() {
        capturing = false
        bridge.detach()
        buffer.removeListener(uiNotifyListener)
        buffer.removeListener(autoOpenListener)
        uiListeners.clear()
    }

    companion object {
        private val LOG = Logger.getInstance(SqlLogManager::class.java)

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
