package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.LogTimeCorrelator
import cn.song.mybatisplus.visualizer.log.SqlCaptureGate
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.log.logTimeKey
import cn.song.mybatisplus.visualizer.log.logTimeParamSignature
import cn.song.mybatisplus.visualizer.log.stampLogTime
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

        /** T-PS4-01：拒绝冷却窗（24h——拒绝后冷却期内不再弹确认；冷却只作用于弹窗频率不改变同意门槛）。 */
        const val CONSENT_COOLDOWN_MS = 24 * 60 * 60 * 1000L

        /** T-PS4-03：挂载去抖窗（等应用初始化热点；同 pid 窗内重复排队跳过）。 */
        const val DEBOUNCE_MS = 3_000L

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
                    // T-PS4-01：off→on 沿重置拒绝冷却——用户显式重新表达意愿，下次启动即可再问
                    if (!lastKnownEnabled) {
                        state.enhancedCaptureConsentCooldownUntil = 0
                        LOG.info("[T-PS4-01] 增强捕获重新开启——拒绝冷却已重置")
                    }
                    executor.execute { pushDesensitize(state.enhancedCaptureDesensitize) }
                }
                lastKnownEnabled = state.enhancedCaptureEnabled
            },
        )
    }

    /** 设置沿检测：上次设置事件时的增强捕获开关（T-PS4-01 冷却重置的 off→on 沿判定）。 */
    @Volatile
    private var lastKnownEnabled: Boolean = PluginSettings.getInstance().state.enhancedCaptureEnabled

    // ---------- 挂载入口 ----------

    /**
     * 进程出现回调（RunConfigurationProcessListener.processStarted 转接；增强未开启时空操作）。
     * 后台线程：进程就绪等待 + 3s 去抖 + attach——全程不进 EDT。
     *
     * 默认开（2026-09-16 定案）后的确认闸门：enabled 默认 true 而 confirmed 默认 false——
     * 若沿用「仅设置页勾选时弹确认」，默认勾上的开关将永不弹框、永不 attach（死开关）。
     * 故未确认时在**首次挂载请求**处征求一次知情同意（EDT 弹框），同意后继续挂载。
     *
     * T-PS4-01（性能第六轮「高」级发现）：①触发面收窄——确认弹窗仅对主配置启动触发
     * （[mainLaunch]，复用 isModuleBasedLaunch 判定），HTTP 请求/测试/profiler 等非模块型
     * 进程静默跳过（此前每进程启动挨一次模态弹窗）；②拒绝冷却——拒绝后 24h 内不再弹
     * （[CONSENT_COOLDOWN_MS]），设置页关闭再开启增强捕获时重置（保留改主意入口）。
     * 已 confirmed 的 attach 链路维持现状（增强捕获主通道语义不变）。
     */
    fun onProcessAppeared(processHandler: Any?, mainLaunch: Boolean = true) {
        if (!isEnhancedEnabled()) {
            LOG.info("onProcessAppeared: 增强未开启（enabled=${PluginSettings.getInstance().state.enhancedCaptureEnabled}, " +
                "disposed=${project.isDisposed}）——跳过挂载")
            return
        }
        if (!isEnhancedConfirmed()) {
            if (!mainLaunch) {
                LOG.info("onProcessAppeared: 增强未确认且非主配置启动（HTTP/测试/profiler 等）——静默跳过确认弹窗")
                return
            }
            if (System.currentTimeMillis() < consentCooldownUntil()) {
                LOG.info("onProcessAppeared: 拒绝冷却期内（剩余 ${consentCooldownUntil() - System.currentTimeMillis()}ms）——不再弹确认")
                return
            }
            LOG.info("onProcessAppeared: 增强已开启但未确认注入——请求确认后挂载")
            requestConsentThenAttach(processHandler)
            return
        }
        doAttachAsync(processHandler)
    }

    /** 拒绝冷却截止时间戳（0=无冷却；设置字段持久化，重开 IDE 冷却仍有效）。 */
    internal fun consentCooldownUntil(): Long =
        PluginSettings.getInstance().state.enhancedCaptureConsentCooldownUntil

    /**
     * 首次挂载确认（EDT 弹一次；同意→置 confirmed 并继续挂载，拒绝→进 24h 冷却本次不注入）。
     * 确认结果持久化（重开不再弹）；headless 由 [consentProvider] 默认实现直接拒绝。
     */
    private fun requestConsentThenAttach(processHandler: Any?) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || !isEnhancedEnabled()) return@invokeLater
            val accepted = try {
                consentProvider()
            } catch (t: Throwable) {
                LOG.warn("requestConsentThenAttach: 确认框异常——按不同意处理", t)
                false
            }
            val state = PluginSettings.getInstance().state
            if (accepted) {
                state.enhancedCaptureConfirmed = true
                state.enhancedCaptureConsentCooldownUntil = 0
                LOG.info("requestConsentThenAttach: 用户同意注入——继续挂载")
                doAttachAsync(processHandler)
            } else {
                // T-PS4-01：拒绝进冷却——冷却只影响弹窗频率，下次主配置启动过了冷却窗仍会再问
                state.enhancedCaptureConsentCooldownUntil = System.currentTimeMillis() + CONSENT_COOLDOWN_MS
                LOG.info("requestConsentThenAttach: 用户未同意注入——本次不挂载，${CONSENT_COOLDOWN_MS / 3_600_000}h 内不再询问")
            }
        }
    }

    /** 确认提供者（默认弹注入确认框，headless 恒拒绝；测试替换以驱动同意/拒绝分支）。 */
    internal var consentProvider: () -> Boolean = {
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isHeadlessEnvironment) {
            false
        } else {
            cn.song.mybatisplus.visualizer.config.EnhancedCaptureConfirmDialog.confirm(null)
        }
    }

    /**
     * 实际挂载（后台线程：resolvePid → 3s 去抖 → attach）。
     *
     * T-PS4-03：pendingPids 去抖真实化——同 pid 3s 内重复排队（快速重启/重复 Run）直接跳过
     * （兑现 [pendingPids] 声明处注释；此前声明未用，同 pid 每次都在单线程池里串行等待 3s）。
     * 单线程池串行性质维持：跨 pid 的 3s sleep 依次排队（等应用初始化的合理去抖，accept
     * 超时 10s 亦串行不放大）；pendingPids 条目按 pid 累积（进程数量级，可忽略）。
     */
    private fun doAttachAsync(processHandler: Any?) {
        executor.execute {
            try {
                val pid = resolvePid(processHandler)
                if (pid == null) {
                    LOG.warn("onProcessAppeared: resolvePid 失败（handler=${processHandler?.javaClass?.name}）——跳过挂载")
                    return@execute
                }
                // 去抖真实化：3s 内同 pid 已排队/尝试过 → 跳过（put 返回旧值即上次尝试时刻）
                val now = System.currentTimeMillis()
                val lastAttempt = pendingPids.put(pid, now)
                if (lastAttempt != null && now - lastAttempt < DEBOUNCE_MS) {
                    LOG.info("onProcessAppeared: pid=$pid ${now - lastAttempt}ms 内已尝试——去抖跳过")
                    return@execute
                }
                LOG.info("onProcessAppeared: 目标 pid=$pid，3s 去抖后挂载")
                // 去抖 3s：等应用初始化热点过去（驱动已加载场景 retransform 直达；未加载场景 transformer 兜底）
                Thread.sleep(DEBOUNCE_MS)
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
        // B1：新 agent 默认 disabled=false（静态字段初始值），须按当前监听状态立即校正——
        // 否则「监听关闭状态启动项目」场景新 agent 又开始白采集
        val active = isListenActive()
        runCatching { session.sendConfig(disabled = !active) }
            .onFailure { LOG.warn("attachToPid: 初始 CONFIG 下发失败（pid=$pid）", it) }
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

    /**
     * 解压 agent jar 到临时目录（T-PS4-03：提取一次缓存到服务字段复用——每进程 attach 不再
     * 重复解压，deleteOnExit 仅首次提取登记一次；失败 null 由调用方显式上报——禁静默吞）。
     */
    @Volatile
    private var cachedAgentJar: File? = null

    private fun extractAgentJar(): File? {
        cachedAgentJar?.takeIf { it.isFile }?.let { return it }
        return runCatching {
            val stream = javaClass.getResourceAsStream(AGENT_RESOURCE)
                ?: error("资源 $AGENT_RESOURCE 缺失（构建未内嵌 agent jar）")
            stream.use { input ->
                val out = File.createTempFile("mps-sql-agent", ".jar")
                out.deleteOnExit()
                out.outputStream().use { output -> input.copyTo(output) }
                cachedAgentJar = out
                out
            }
        }.getOrNull()
    }

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
            dead.forEach {
                it.close()
                // T-PS4-03：死会话顺带清 pid 表——进程已死，pid 表残留会让「OS 复用同 pid 的
                // 新进程」被幂等表误判已挂载而漏挂
                attachedPids.remove(it.pid)
            }
            sessions.removeAll(dead.toSet())
            updateOnline()
        }
    }

    private fun isEnhancedActive(): Boolean = isEnhancedEnabled() && isEnhancedConfirmed()

    /** 开关已开且项目未销毁（不含确认态）。 */
    internal fun isEnhancedEnabled(): Boolean {
        val state = PluginSettings.getInstance().state
        return state.enhancedCaptureEnabled && !project.isDisposed
    }

    /** 注入确认已达成（同意过一次即持久为 true）。 */
    internal fun isEnhancedConfirmed(): Boolean =
        PluginSettings.getInstance().state.enhancedCaptureConfirmed

    /** 关闭开关：CONFIG 下发停采集（禁用不卸载语义）→ 会话关闭 → 桥回落。 */
    fun shutdownAll() {
        sessions.forEach { it.sendConfig(disabled = true) }
        sessions.forEach { it.close() }
        sessions.clear()
        attachedPids.clear()
        updateOnline()
    }

    /** 脱敏开关下发（设置变更）。disabled 跟随当前监听状态（B2：不写死 false——监听关闭时改设置不得重置为采集）。 */
    fun pushDesensitize(desensitize: Boolean) {
        val active = isListenActive()
        sessions.forEach { it.sendConfig(disabled = !active, desensitize = desensitize) }
    }

    /**
     * 监听开关联动（T-AgentListenSync）：停止监听 → agent 停采（真停，非 IDE 侧丢弃）；
     * 开启监听 → 恢复采集。由 SqlLogManager.startCapture/stopCapture 调用。
     * agent 侧 CONFIG 即时生效（volatile 标志），织入点常驻，恢复后下一条 SQL 完整采集。
     */
    fun pushCaptureActive(active: Boolean) {
        if (!isEnhancedActive()) return
        val desensitize = PluginSettings.getInstance().state.enhancedCaptureDesensitize
        sessions.forEach { it.sendConfig(disabled = !active, desensitize = desensitize) }
        LOG.info("pushCaptureActive: active=$active → disabled=${!active}（sessions=${sessions.size}）")
    }

    /** 当前 SQL 监听是否开启（SqlLogManager.capturing；服务缺失/异常按 false——宁停采勿白采）。 */
    internal fun isListenActive(): Boolean = runCatching {
        project.getService(SqlLogManager::class.java).capturing
    }.getOrDefault(false)

    // ---------- pid 解析（复用桥反射骨架；getNativePid 能力探测缺失回退 getProcess().pid()） ----------

    internal fun resolvePid(processHandler: Any?): Long? = runCatching {
        if (processHandler == null) return null
        // 1) KillableProcessHandler.getNativePid()（262+ 平台；243 实测无此方法）
        runCatching {
            val m = processHandler.javaClass.methods.firstOrNull { it.name == "getNativePid" && it.parameterCount == 0 }
            (m?.invoke(processHandler) as? Number)?.toLong()
        }.getOrNull()
            // 2) getProcess() 直接接口调用 pid()（Java 9+）——禁反射 invoke：ProcessImpl 是 JDK
            //    package-private 类，getMethod("pid").invoke 会抛 IllegalAccessException
            //    （测试匿名子类可访问故测试通过、真实环境失败的根因）
            ?: runCatching {
                val process = processHandler.javaClass.getMethod("getProcess").invoke(processHandler) as java.lang.Process
                process.pid().toLong()
            }.getOrNull()
            // 3) OSProcessUtil.getProcessID(Process)（243 平台稳定 API，util.jar）
            ?: runCatching {
                val process = processHandler.javaClass.getMethod("getProcess").invoke(processHandler) as java.lang.Process
                com.intellij.execution.process.OSProcessUtil.getProcessID(process).toLong()
            }.getOrNull()
            ?: run {
                val (processRepr, procErr) = try {
                    val p = processHandler.javaClass.getMethod("getProcess").invoke(processHandler)
                    (p?.javaClass?.name ?: "null") to null
                } catch (t: Throwable) {
                    "N/A" to t.toString()
                }
                LOG.warn("resolvePid: 三步回退全失败（handler=${processHandler.javaClass.name}, process=$processRepr, procErr=$procErr）")
                null
            }
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

    /** T-AgentLogTime：日志时间未命中计数（诊断——键不等 vs 时序赶不上，真机排障一眼分辨）。 */
    private val timeMiss = AtomicLong()

    init {
        socket.soTimeout = 0
        Thread({
            try {
                val input = DataInputStream(socket.getInputStream())
                while (true) {
                    val len = input.readInt()
                    if (len < 0 || len > AgentProtocol.MAX_FRAME_LEN) {
                        // 超限帧容错：读掉 body 丢弃继续，不杀会话（一次大事件不应永久掉线）
                        LOG.warn("frame len=$len 超限（MAX=${AgentProtocol.MAX_FRAME_LEN}）——丢弃该帧继续")
                        if (len > 0) input.skipNBytes(len.toLong())
                        continue
                    }
                    val cmd = input.readUnsignedByte()
                    val body = ByteArray(len)
                    input.readFully(body)
                    lastSeen = System.currentTimeMillis()
                    handleFrame(cmd, String(body, Charsets.UTF_8))
                }
            } catch (t: Throwable) {
                LOG.warn("会话读循环退出（pid=$pid, alive 前=$alive）——掉线原因如下", t)
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
                    LOG.warn("EVENT 帧解析失败丢弃（pid=$pid, dropped=${eventDropped.incrementAndGet()}, bodyLen=${body.length}, head=${body.take(120)}）")
                    return
                }
                try {
                    val entry = AgentEventAdapter.toEntry(event)
                    // T-AgentDialectLoop / T-AgentLogTime：接管期间控制台嗅探暂存的「日志行原始时间」
                    // 按 [logTimeKey]（占位符版 SQL 指纹）取回，参数值序列作次级判别（[stampLogTime]）。
                    // 未命中回落捕获时间（= execute 开始时刻，与日志行只差毫秒级）并留痕 [logTimeMiss]。
                    project.getService(SqlLogManager::class.java).entries.addEntry(stampLogTime(entry) ?: logTimeMiss(entry))
                } catch (t: Throwable) {
                    LOG.warn("EVENT 入队失败（不斩断流）", t)
                }
            }
            // HEARTBEAT/HELLO：lastSeen 已刷新；未知命令忽略（协议向前兼容）
            else -> Unit
        }
    }

    /**
     * T-AgentLogTime：日志时间未命中留痕——前 [MISS_LOG_LIMIT] 条逐条打印键与 SQL 头部
     * （判「两侧 rawSql 不等」），其后每 [MISS_LOG_STEP] 条打一次累计（判「时序赶不上」）。
     * 未命中只影响时间字段（回落 execute 开始时刻），不影响条目本身，故只记日志不动流程。
     */
    private fun logTimeMiss(entry: LogSqlEntry): LogSqlEntry {
        val n = timeMiss.incrementAndGet()
        if (n <= MISS_LOG_LIMIT) {
            LOG.info(
                "日志时间未命中 #$n（回落捕获时间）：key=${logTimeKey(entry)} " +
                    "params=${logTimeParamSignature(entry.params).take(60)} sql=${entry.rawSql.take(80)}",
            )
        } else if (n % MISS_LOG_STEP == 0L) {
            LOG.info("日志时间未命中累计 $n 条（最近：key=${logTimeKey(entry)} 相关器存量=${LogTimeCorrelator.size()}）")
        }
        return entry
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

    private companion object {
        /** 未命中逐条打印上限（前 N 条够看清两侧 rawSql 差异）。 */
        const val MISS_LOG_LIMIT = 3L

        /** 逐条打印用尽后的累计打印步长。 */
        const val MISS_LOG_STEP = 200L
    }
}
