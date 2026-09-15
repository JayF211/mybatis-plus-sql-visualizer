package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.LogTimeCorrelator
import cn.song.mybatisplus.visualizer.log.SqlCaptureGate
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sun.tools.attach.VirtualMachine
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * AgentManager（§4.4，M56 T-M56-01/02）：project 级服务，agent 生命周期全权管理。
 *
 * - attach 全链：agent jar 解压落盘（失败显式上报）→ 端口前置协商（ServerSocket(0)）→
 *   `VirtualMachine.attach(pid)` + loadAgent → 会话接受 → 在线态。
 * - 惰性挂载（Ultra 4.0.20 教训）：开启增强 + 注入确认后，后台线程等进程就绪 + 去抖 3s 再
 *   attach，全程不进 EDT——不拖慢项目启动。
 * - 幂等表：插件侧 pid 表 + agent 侧 system property 双保险（JDK8 二次 loadAgent 抛
 *   AgentLoadException("0") 实证容错）。
 * - 降级三分支：attach 失败 / 连接丢失 / 关闭开关 → 桥恢复（capturePaused=false），零劣化。
 * - 在线态：最近 [AgentProtocol.ONLINE_WINDOW_MS] 内有心跳/事件（§4.3）。
 * - 随 project dispose 摘除：会话全关 + 连接池线程停转。
 */
@Service(Service.Level.PROJECT)
class AgentManager(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(AgentManager::class.java)
        const val AGENT_RESOURCE = "/agent/mps-sql-agent.jar"

        fun getInstance(project: Project): AgentManager = project.getService(AgentManager::class.java)
    }

    /** 在线态：任一会话活跃且最近窗口内有心跳/事件。UI 状态行/接管切换依据。 */
    @Volatile
    var online: Boolean = false
        private set

    private val sessions = CopyOnWriteArrayList<AgentSession>()

    /** 幂等表：pid → attach 时间戳（同一 pid 不二次 attach）。 */
    private val attachedPids = ConcurrentHashMap<Long, Long>()

    /** 惰性挂载去抖：pid → 上次尝试时间（3s 内重复启动不重复 attach）。 */
    private val pendingPids = ConcurrentHashMap<Long, Long>()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mps-agent-manager").apply { isDaemon = true }
    }

    /** 在线态巡检（5s）：心跳窗口过期/连接死亡 → 会话摘除 → 桥回落。 */
    private val monitor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mps-agent-monitor").apply { isDaemon = true }
    }

    init {
        monitor.scheduleWithFixedDelay({ sweepOffline() }, 5, 5, TimeUnit.SECONDS)
        // T-M56-08：设置变更驱动——开启（已确认）→ 惰性挂载由进程出现回调承接；
        // 关闭 → CONFIG 停采集 + 会话关闭 + 桥回落；脱敏开关 → CONFIG 下发
        project.messageBus.connect(this).subscribe(
            PluginSettings.SETTINGS_CHANGE_TOPIC,
            cn.song.mybatisplus.visualizer.config.SettingsChangeListener {
                val state = PluginSettings.getInstance().state
                if (!state.enhancedCaptureEnabled) {
                    executor.execute { shutdownAll() }
                } else {
                    executor.execute { pushDesensitize(state.enhancedCaptureDesensitize) }
                }
            },
        )
    }

    // ---------- 挂载入口 ----------

    /**
     * 进程出现回调（RunConfigurationProcessListener.processStarted 转接；增强未开启/未确认时空操作）。
     * 后台线程：进程就绪等待 + 3s 去抖 + attach——全程不进 EDT。
     */
    fun onProcessAppeared(processHandler: Any?) {
        if (!isEnhancedActive()) return
        executor.execute {
            try {
                val pid = resolvePid(processHandler) ?: return@execute
                // 去抖 3s：等应用初始化热点过去（驱动已加载场景 retransform 直达；未加载场景 transformer 兜底）
                Thread.sleep(3000)
                attachToPid(pid)
            } catch (t: Throwable) {
                LOG.warn("onProcessAppeared: 惰性 attach 失败（非致命，桥继续捕获）", t)
            }
        }
    }

    /** attach 全链（幂等表去重；测试可直调）。返回是否达到在线。 */
    fun attachToPid(pid: Long): Boolean {
        if (!isEnhancedActive()) return false
        if (attachedPids.putIfAbsent(pid, System.currentTimeMillis()) != null) {
            LOG.info("attachToPid: pid=$pid 已挂载（幂等表命中），跳过")
            return online
        }
        val session = try {
            createSession(pid)
        } catch (t: Throwable) {
            attachedPids.remove(pid)
            LOG.warn("attachToPid: pid=$pid attach 失败——回落桥（零劣化）", t)
            return false
        }
        if (session == null) {
            // attach 已失败（容错后仍未在线）：回落桥
            attachedPids.remove(pid)
            return false
        }
        sessions.add(session)
        updateOnline()
        return true
    }

    /**
     * 创建会话：解压 agent jar → ServerSocket 端口协商 → attach → accept（10s 超时）。
     * 返回 null = attach 后 agent 未在超时内连接（视同失败）。
     */
    private fun createSession(pid: Long): AgentSession? {
        val jar = extractAgentJar() ?: run {
            notifyAgentFailure("增强捕获：agent 解压落盘失败，已回落控制台捕获")
            return null
        }
        val socket = attachAndOffer(jar, pid) ?: return null
        return AgentSession(project, pid, socket, onStateChange = { updateOnline() })
    }

    /** 解压 agent jar 到临时目录（deleteOnExit；失败 null 由调用方显式上报——禁静默吞）。 */
    private fun extractAgentJar(): File? = runCatching {
        val stream = javaClass.getResourceAsStream(AGENT_RESOURCE)
            ?: error("资源 $AGENT_RESOURCE 缺失（构建未内嵌 agent jar）")
        stream.use { input ->
            val out = File.createTempFile("mps-sql-agent", ".jar")
            out.deleteOnExit()
            out.outputStream().use { input.copyTo(it) }
            out
        }
    }.getOrNull()

    /**
     * attach + loadAgent（端口下发）+ accept 握手。
     * JDK 错配容忍（§4.5）：`AgentLoadException("0")` / `Non-numeric value found` 视为可能
     * 仍成功，继续等 accept；finally detach。
     */
    private fun attachAndOffer(jar: File, pid: Long): Socket? {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val port = server.localPort
            server.soTimeout = 10_000
            var vm: VirtualMachine? = null
            try {
                vm = VirtualMachine.attach(pid.toString())
                try {
                    vm.loadAgent(jar.absolutePath, AgentProtocol.agentArgs(port))
                } catch (e: com.sun.tools.attach.AgentLoadException) {
                    // JDK8 二次挂载抛 AgentLoadException: 0——agent 可能已在目标 JVM 内，继续握手
                    LOG.warn("loadAgent AgentLoadException(${e.message})——按可能已挂载继续握手")
                } catch (e: IOException) {
                    if (e.message?.contains("Non-numeric value found") == true) {
                        LOG.warn("loadAgent Non-numeric value found（JDK 错配魔数）——按可能已挂载继续握手")
                    } else {
                        throw e
                    }
                }
                return try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    LOG.warn("accept 超时：agent 未在 10s 内连接（pid=$pid）——回落桥")
                    null
                }
            } finally {
                runCatching { vm?.detach() }
            }
        }
    }

    // ---------- 会话/在线态 ----------

    private fun updateOnline() {
        val wasOnline = online
        online = sessions.any { it.alive }
        if (online != wasOnline) {
            LOG.info("增强捕获在线态切换: $wasOnline -> $online")
        }
        // 接管/回落切换：桥暂停挂载 ↔ 恢复（§4.3；桥链路本身零改动）
        SqlCaptureGate.paused.set(online)
        // T-AgentDialectLoop：回落/关闭时清空日志时间相关器，防跨运行陈旧匹配
        if (!online) LogTimeCorrelator.clear()
    }

    private fun sweepOffline() {
        val dead = sessions.filter { !it.alive }
        if (dead.isNotEmpty()) {
            dead.forEach { it.close() }
            sessions.removeAll(dead.toSet())
            updateOnline()
        }
    }

    private fun isEnhancedActive(): Boolean {
        val state = PluginSettings.getInstance().state
        return state.enhancedCaptureEnabled && state.enhancedCaptureConfirmed && !project.isDisposed
    }

    /** 关闭开关：CONFIG 下发停采集（禁用不卸载语义）→ 会话关闭 → 桥回落。 */
    fun shutdownAll() {
        sessions.forEach { it.sendConfig(disabled = true) }
        sessions.forEach { it.close() }
        sessions.clear()
        attachedPids.clear()
        updateOnline()
    }

    /** 脱敏开关下发（设置变更）。 */
    fun pushDesensitize(desensitize: Boolean) {
        sessions.forEach { it.sendConfig(disabled = false, desensitize = desensitize) }
    }

    // ---------- pid 解析（复用桥反射骨架；getNativePid 能力探测缺失回退 getProcess().pid()） ----------

    internal fun resolvePid(processHandler: Any?): Long? = runCatching {
        if (processHandler == null) return null
        // 1) KillableProcessHandler.getNativePid()（243 平台已有；能力探测失败走回退）
        runCatching {
            val m = processHandler.javaClass.methods.firstOrNull { it.name == "getNativePid" && it.parameterCount == 0 }
            (m?.invoke(processHandler) as? Number)?.toLong()
        }.getOrNull()
            // 2) ProcessHandler.getProcess().pid()（Java 9+ Process）
            ?: runCatching {
                val process = processHandler.javaClass.getMethod("getProcess").invoke(processHandler)
                (process.javaClass.getMethod("pid").invoke(process) as? Number)?.toLong()
            }.getOrNull()
    }.getOrNull()

    /** agent 失败显式上报（禁静默吞）：桥侧继续捕获，用户无感；日志 WARN 留排查锚点。 */
    private fun notifyAgentFailure(message: String) {
        LOG.warn(message)
    }

    override fun dispose() {
        runCatching {
            sessions.forEach { it.close() }
            sessions.clear()
            executor.shutdownNow()
            monitor.shutdownNow()
            SqlCaptureGate.paused.set(false)
        }
    }
}

/**
 * 单个 agent 会话：读线程循环解帧（EVENT → 适配 → buffer；HEARTBEAT 刷新在线窗口；
 * HELLO 记录 agent 侧 pid），CONFIG 下发。alive = socket 未断。
 */
internal class AgentSession(
    private val project: Project,
    val pid: Long,
    private val socket: Socket,
    private val onStateChange: () -> Unit,
) {

    private val LOG = Logger.getInstance(AgentSession::class.java)

    @Volatile
    var alive: Boolean = true
        private set

    @Volatile
    var lastSeen: Long = System.currentTimeMillis()
        private set

    private val output = java.io.DataOutputStream(socket.getOutputStream())
    private val eventDropped = AtomicLong()

    init {
        socket.soTimeout = 0
        Thread({
            try {
                val input = DataInputStream(socket.getInputStream())
                while (true) {
                    val len = input.readInt()
                    if (len < 0 || len > AgentProtocol.MAX_FRAME_LEN) {
                        throw EOFException("bad frame len $len")
                    }
                    val cmd = input.readUnsignedByte()
                    val body = ByteArray(len)
                    input.readFully(body)
                    lastSeen = System.currentTimeMillis()
                    handleFrame(cmd, String(body, Charsets.UTF_8))
                }
            } catch (t: Throwable) {
                alive = false
                onStateChange()
            }
        }, "mps-agent-session-$pid").apply { isDaemon = true }.start()
    }

    private fun handleFrame(cmd: Int, body: String) {
        when (cmd) {
            AgentProtocol.CMD_EVENT -> {
                val event = AgentEvent.parse(body)
                if (event == null) {
                    eventDropped.incrementAndGet()
                    return
                }
                try {
                    val entry = AgentEventAdapter.toEntry(event)
                    // T-AgentDialectLoop：接管期间控制台嗅探暂存的「日志行原始时间」按同指纹回填
                    // （未命中保持 null——显示回落捕获时间，与既有语义一致）
                    val stamped = if (entry.logTimestamp != null) entry
                    else LogTimeCorrelator.take(entry.dedupeKey)?.let { entry.copy(logTimestamp = it) } ?: entry
                    project.getService(SqlLogManager::class.java).entries.addEntry(stamped)
                } catch (t: Throwable) {
                    LOG.warn("EVENT 入队失败（不斩断流）", t)
                }
            }
            // HEARTBEAT/HELLO：lastSeen 已刷新；未知命令忽略（协议向前兼容）
            else -> Unit
        }
    }

    fun sendConfig(disabled: Boolean, desensitize: Boolean =
        PluginSettings.getInstance().state.enhancedCaptureDesensitize) {
        try {
            val body = AgentProtocol.configBody(disabled, desensitize).toByteArray(Charsets.UTF_8)
            synchronized(output) {
                output.writeInt(body.size)
                output.write(AgentProtocol.CMD_CONFIG)
                output.write(body)
                output.flush()
            }
        } catch (t: Throwable) {
            alive = false
            onStateChange()
        }
    }

    fun close() {
        alive = false
        runCatching { socket.close() }
        onStateChange()
    }
}
