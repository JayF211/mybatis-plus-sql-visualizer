package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.EnhancedCaptureConsent
import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.LogTimeCorrelator
import cn.song.mybatisplus.visualizer.log.SqlCaptureGate
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.log.logTimeKey
import cn.song.mybatisplus.visualizer.log.logTimeParamSignature
import cn.song.mybatisplus.visualizer.log.stampLogTime
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
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
/**
 * T-SettingsTruth P2：增强捕获状态行广播（app 级 Topic，设置页面板订阅后刷新状态行）。
 */
interface AgentStatusListener {
    fun onStatusChanged()
}

@Service(Service.Level.PROJECT)
class AgentManager(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(AgentManager::class.java)
        const val AGENT_RESOURCE = "/agent/mps-sql-agent.jar"

        /**
         * 拒绝冷却默认窗（T-PS4-01）：24h。冷却只作用于确认弹窗频率、不改变同意门槛（保守口径——
         * 冷却过期后再弹一次，用户仍可同意）；设置页关闭再开启增强捕获时重置。
         */
        const val DEFAULT_REJECT_COOLDOWN_MS = 24L * 60 * 60 * 1000

        /** attach 排队去抖窗默认值（T-PS4-03）：同 pid 窗口内重复启动不重复排队（与挂载前静默等待解耦）。 */
        const val DEFAULT_ATTACH_DEBOUNCE_MS = 3_000L

        /** 挂载前静默等待（T-PS4-03 提常量与去抖窗解耦）：等应用初始化热点过去。 */
        private const val ATTACH_SETTLE_DELAY_MS = 3_000L

        fun getInstance(project: Project): AgentManager = project.getService(AgentManager::class.java)

        /** T-SettingsTruth P2：增强捕获状态变更 Topic（app 级；仿 PluginSettings.SETTINGS_CHANGE_TOPIC）。 */
        @JvmField
        val AGENT_STATUS_TOPIC: Topic<AgentStatusListener> = Topic.create(
            "MpSqlVisualizerAgentStatusChange",
            AgentStatusListener::class.java,
        )
    }

    /** 在线态：任一会话活跃且最近窗口内有心跳/事件。UI 状态行/接管切换依据。 */
    @Volatile
    var online: Boolean = false
        private set

    /** 会话表。internal 为同模块守护测试缝（T-PS4-03 sweepOffline 清表断言）；生产链路勿直接读写。 */
    internal val sessions = CopyOnWriteArrayList<AgentSession>()

    /** 幂等表：pid → attach 时间戳（同一 pid 不二次 attach）。internal 为同模块守护测试缝（T-PS4-03）。 */
    internal val attachedPids = ConcurrentHashMap<Long, Long>()

    /**
     * 惰性挂载去抖（T-PS4-03 落地）：pid → 上次排队时刻——同 pid 在 [attachDebounceMs] 窗口内
     * 重复启动不重复排队（声明多年未用，本次兑现为真实去抖表；attach 任务完成时摘除条目，
     * 失败后的同 pid 后续启动可再排队，成功则由 attachedPids 幂等表兜底去重）。
     * internal 为同模块守护测试缝（T-PS4-01 静默跳过断言 / T-PS4-03 去抖落地）；生产链路勿直接读写。
     */
    internal val pendingPids = ConcurrentHashMap<Long, Long>()

    // ---------- T-PS4-01/03 测试缝：冷却窗、去抖窗与时间源可注入（生产链路勿改默认值） ----------

    /** 拒绝冷却窗（默认 [DEFAULT_REJECT_COOLDOWN_MS]；测试注入短窗驱动冷却语义）。 */
    internal var rejectCooldownMs: Long = DEFAULT_REJECT_COOLDOWN_MS

    /** attach 排队去抖窗（默认 [DEFAULT_ATTACH_DEBOUNCE_MS]；测试注入短窗驱动去抖语义）。 */
    internal var attachDebounceMs: Long = DEFAULT_ATTACH_DEBOUNCE_MS

    /** 时间源（冷却判断与 attach 排队去抖共用；默认系统时钟，测试注入可控时钟）。 */
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    /**
     * T-ReachAudit T-RA-01：心跳窗口（默认 [AgentProtocol.ONLINE_WINDOW_MS]；测试注入短窗驱动过期语义）。
     *
     * 此前该窗口只存在于协议常量与 KDoc（[AgentProtocol.ONLINE_WINDOW_MS] 全仓零消费、[AgentSession.lastSeen]
     * 有写无读），在线态只看 socket 是否断开——agent 侧停摆但连接仍在时 `online` 恒 true，控制台桥被
     * `SqlCaptureGate.paused` 一直封着，两条通道同时零产出且无超时恢复。现按协议既有语义落地。
     */
    internal var heartbeatWindowMs: Long = AgentProtocol.ONLINE_WINDOW_MS

    /**
     * T-ReachAudit T-RA-01：会话健康判定——socket 未断**且**窗口内有过心跳/事件。
     * 纯判定（可单测）：`now - lastSeen` 用注入时钟，故测试可推进时间而不真等 30s。
     */
    private fun isSessionHealthy(session: AgentSession): Boolean =
        session.alive && (clockMs() - session.lastSeen) <= heartbeatWindowMs

    /** T-ReachAudit T-RA-01：最近一次摘除是否因心跳过期（区分「连接断开」与「应用侧无响应」两类回落）。 */
    private val heartbeatDropDetected = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 「关闭 → 再开启增强捕获」边沿检测（SETTINGS_CHANGE 监听重置拒绝冷却用）。 */
    private var lastKnownEnabled = PluginSettings.getInstance().state.enhancedCaptureEnabled

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
                // T-PS4-01：关闭 → 再开启的上升沿重置拒绝冷却（保留用户改主意的入口；
                // 仅此边沿重置，其余设置变更不动冷却，防改无关设置白涮 24h 冷却窗）
                if (state.enhancedCaptureEnabled && !lastKnownEnabled) {
                    state.enhancedCaptureRejectedAtMs = 0L
                    LOG.info("设置变更：增强捕获重新开启——拒绝冷却已重置")
                }
                lastKnownEnabled = state.enhancedCaptureEnabled
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
     * 进程出现回调（RunConfigurationProcessListener.processStarted 转接；增强未开启时空操作）。
     * 后台线程：进程就绪等待 + 3s 去抖 + attach——全程不进 EDT。
     *
     * 默认开（2026-09-16 定案）后的确认闸门：enabled 默认 true 而 confirmed 默认 false——
     * 若沿用「仅设置页勾选时弹确认」，默认勾上的开关将永不弹框、永不 attach（死开关）。
     * 故未确认时在**首次挂载请求**处征求一次知情同意（EDT 弹框），同意后继续挂载。
     *
     * T-PS4-01 触发面收窄：未确认态的确认弹窗仅对主配置启动（[isMainConfigLaunch]）触发；
     * HTTP Client/测试/profiler 等非主配置进程在未确认态**静默跳过**（不弹窗、不入 attach 队列），
     * 杜绝「拒绝注入的用户每进程启动挨一次 EDT 模态弹窗」。已 confirmed 的 attach 链路维持对
     * 所有进程触发不变（增强捕获主通道语义零变化）。
     *
     * @param isMainConfigLaunch 是否主业务配置启动（ModuleBasedConfiguration 子类，与
     *   RunConfigurationProcessListener.isModuleBasedLaunch 同源判定）。默认 true =
     *   保守按主配置处理（未知调用方保留既有弹窗语义，知情同意不静默吞）；生产唯一调用点显式传参。
     */
    fun onProcessAppeared(processHandler: Any?, isMainConfigLaunch: Boolean = true) {
        if (!isEnhancedEnabled()) {
            LOG.info("onProcessAppeared: 增强未开启（enabled=${PluginSettings.getInstance().state.enhancedCaptureEnabled}, " +
                "disposed=${project.isDisposed}）——跳过挂载")
            return
        }
        if (!isEnhancedConfirmed()) {
            if (!isMainConfigLaunch) {
                LOG.info("onProcessAppeared: 增强已开启但未确认且非主配置进程——静默跳过（不弹窗不入队，T-PS4-01）")
                return
            }
            if (isInRejectCooldown()) {
                LOG.info("onProcessAppeared: 拒绝冷却期内（${remainingRejectCooldownMs()}ms）——不弹窗不排队（T-PS4-01）")
                return
            }
            LOG.info("onProcessAppeared: 增强已开启但未确认注入——请求确认后挂载")
            requestConsentThenAttach(processHandler)
            return
        }
        doAttachAsync(processHandler)
    }

    /**
     * 拒绝冷却判断（T-PS4-01）：自持久化的「拒绝时刻」（[PluginSettings.State.enhancedCaptureRejectedAtMs]）
     * 起 [rejectCooldownMs] 窗口内为 true。冷却只作用于弹窗频率、不改变同意门槛（保守口径——
     * 冷却过期后再弹一次）；总开关关闭时本判断不会被走到（enabled 门在前，关闭增强 > 冷却）。
     */
    internal fun isInRejectCooldown(): Boolean {
        val rejectedAt = PluginSettings.getInstance().state.enhancedCaptureRejectedAtMs
        if (rejectedAt <= 0L) return false
        return clockMs() - rejectedAt < rejectCooldownMs
    }

    internal fun remainingRejectCooldownMs(): Long =
        (rejectCooldownMs - (clockMs() - PluginSettings.getInstance().state.enhancedCaptureRejectedAtMs)).coerceAtLeast(0)

    /**
     * T-CaptureTruth T-CT-02：重新授权入口（设置页「重新授权」按钮 / 面板链路诊断行的恢复动作）。
     *
     * 语义 = 清零拒绝冷却（与「关闭→再开启」上升沿同一条重置语义，抽为单一入口）后立即重走确认流：
     * 同意 → 置 confirmed 并（有进程句柄时）挂载；拒绝 → 重新计时冷却。此前用户被拒后唯一恢复路径是
     * 「设置页取消勾选→应用→重新勾选→应用」，而复选框本就显示勾选态，实际无从下手（T-CaptureTruth 根因之二）。
     *
     * 无进程句柄：本入口不携带 processStarted 事件，故只恢复授权、不尝试挂载——挂载由下一次
     * 应用进程启动承接（状态行随即转「已授权，等待应用进程启动后挂载」）。
     */
    fun requestReauthorization() {
        if (!isEnhancedEnabled()) {
            LOG.info("requestReauthorization: 增强未开启——忽略")
            return
        }
        PluginSettings.getInstance().state.enhancedCaptureRejectedAtMs = 0L
        LOG.info("requestReauthorization: 拒绝冷却已清零，重走注入确认流")
        publishStatusChanged()
        requestConsentThenAttach(null)
    }

    /**
     * 首次挂载确认（EDT 弹一次）。T-PS4-01 三态语义：
     * - 同意 → 置 confirmed 持久化并继续挂载（此后任何进程都不再弹）；
     * - 拒绝 → 持久化「拒绝时刻」进入 [DEFAULT_REJECT_COOLDOWN_MS] 冷却（冷却期内不重复弹，
     *   冷却过期后再弹一次；冷却只作用于弹窗频率、不改变同意门槛——保守口径）；
     * - 关闭增强 → 总开关 enhancedCaptureEnabled 置 false 持久化（语义优先级：关闭增强 > 冷却，
     *   总开关关闭后冷却无意义），此后任何进程启动都不弹窗、不 attach，直到用户在设置页重新开启
     *   （重开的上升沿由 SETTINGS_CHANGE 监听重置冷却）。
     * 确认结果持久化（重开不再弹）；headless 由 [consentProvider] 默认实现直接拒绝。
     */
    private fun requestConsentThenAttach(processHandler: Any?) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || !isEnhancedEnabled()) return@invokeLater
            val outcome = try {
                consentProvider()
            } catch (t: Throwable) {
                LOG.warn("requestConsentThenAttach: 确认框异常——按拒绝处理（进冷却）", t)
                EnhancedCaptureConsent.REJECTED
            }
            val state = PluginSettings.getInstance().state
            when (outcome) {
                EnhancedCaptureConsent.ACCEPTED -> {
                    state.enhancedCaptureConfirmed = true
                    // T-CaptureTruth T-CT-01：三态结果都广播——否则设置页状态行要等下一次
                    // attach/掉线才刷新，用户点完同意/拒绝后看到的是过期文案。
                    publishStatusChanged()
                    if (processHandler == null) {
                        // T-CaptureTruth T-CT-02：重新授权入口无进程句柄——只恢复授权，
                        // 挂载由下一次应用进程启动承接（避免 resolvePid(null) 的误导性 WARN）。
                        LOG.info("requestConsentThenAttach: 用户同意注入——已授权，等待应用进程启动后挂载")
                        return@invokeLater
                    }
                    LOG.info("requestConsentThenAttach: 用户同意注入——继续挂载")
                    doAttachAsync(processHandler)
                }
                EnhancedCaptureConsent.DISABLE_CAPTURE -> {
                    // 「关闭增强捕获并不再询问」：总开关持久化关闭（与设置页 Apply 同通道，
                    // XmlSerializer 随平台自动保存落盘）。enabled 门在 onProcessAppeared 入口，
                    // 后续任何进程启动都不再进入本确认流，也不 attach。
                    state.enhancedCaptureEnabled = false
                    LOG.info("requestConsentThenAttach: 用户选择关闭增强捕获并不再询问——总开关已持久化关闭")
                    publishStatusChanged()
                }
                EnhancedCaptureConsent.REJECTED -> {
                    state.enhancedCaptureRejectedAtMs = clockMs()
                    LOG.info("requestConsentThenAttach: 用户拒绝注入——进入 ${rejectCooldownMs}ms 冷却（冷却期内不重复弹，不改同意门槛）")
                    publishStatusChanged()
                }
            }
        }
    }

    /**
     * 确认提供者（默认弹注入确认框三态结果，headless 恒拒绝；测试替换以驱动同意/关闭/拒绝分支）。
     * T-PS4-01 三态：ACCEPTED 同意 / DISABLE_CAPTURE 关闭增强并不再询问 / REJECTED 拒绝（进冷却）。
     */
    internal var consentProvider: () -> EnhancedCaptureConsent = {
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isHeadlessEnvironment) {
            EnhancedCaptureConsent.REJECTED
        } else {
            cn.song.mybatisplus.visualizer.config.EnhancedCaptureConfirmDialog.confirmOutcome(null)
        }
    }

    /** 实际挂载（阻塞等待与 attach 全链在单线程 executor；T-PS4-03：pid 解析与排队去抖前移到请求线程）。 */
    private fun doAttachAsync(processHandler: Any?) {
        // T-PS4-03：去抖必须在「排队时刻」判定——若迟到 executor 任务起跑才查表，静默等待已把
        // 窗口耗尽、判定失真。pid 解析是轻反射（getNativePid/getProcess().pid()，无阻塞等待），
        // 前移到请求线程安全；重活（静默等待 + attach）仍留在 executor，不进调用线程。
        val pid = resolvePid(processHandler)
        if (pid == null) {
            LOG.warn("onProcessAppeared: resolvePid 失败（handler=${processHandler?.javaClass?.name}）——跳过挂载")
            return
        }
        val now = clockMs()
        val lastQueued = pendingPids[pid]
        if (lastQueued != null && now - lastQueued < attachDebounceMs) {
            LOG.info("onProcessAppeared: pid=$pid 去抖窗内重复启动（距上次排队 ${now - lastQueued}ms < ${attachDebounceMs}ms）——不重复排队（T-PS4-03）")
            return
        }
        pendingPids[pid] = now
        executor.execute {
            try {
                LOG.info("onProcessAppeared: 目标 pid=$pid，${ATTACH_SETTLE_DELAY_MS}ms 静默等待后挂载")
                // 静默等待：等应用初始化热点过去（驱动已加载场景 retransform 直达；未加载场景 transformer 兜底）。
                // 单线程池串行性质：executor 仅此一条工作线程，跨 pid 的静默等待天然串行排队——
                // 多进程先后启动时后者在前者等待结束后才进入 attach，不会并发压目标 JVM，维持既有语义。
                Thread.sleep(ATTACH_SETTLE_DELAY_MS)
                attachToPid(pid)
            } catch (t: Throwable) {
                LOG.warn("onProcessAppeared: 惰性 attach 失败（非致命，桥继续捕获）", t)
            } finally {
                pendingPids.remove(pid)
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
        // T-SettingsTruth P2：attach 成功 → 状态行「已挂载 pid=x」
        everAttached.set(true)
        // T-ReachAudit T-RA-01：新会话挂上即清「心跳过期」标记——回落文案只描述最近一次摘除原因
        heartbeatDropDetected.set(false)
        publishStatusChanged()
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
        // T-ReachAudit T-RA-01：会话与管理器共用同一时间源（心跳窗口判定跨「记 lastSeen」与
        // 「算过期」两侧，不同源则测试无法推进时间）
        return AgentSession(project, pid, socket, onStateChange = { updateOnline() }, clockMs = clockMs)
    }

    /** agent jar 提取一次缓存（T-PS4-03）：解压 + deleteOnExit 登记只做一次；失败不缓存（下次 attach 重试）。 */
    @Volatile
    private var cachedAgentJar: File? = null

    /**
     * 解压 agent jar 到临时目录（T-PS4-03：提取一次缓存到服务字段复用，deleteOnExit 仅登记一次；
     * 原「每次 createSession 解压一个新临时文件」会随 attach 次数累积 deleteOnExit 登记与磁盘文件，
     * 直到 IDE 退出才统一回收。失败 null 由调用方显式上报——禁静默吞）。
     */
    private fun extractAgentJar(): File? {
        cachedAgentJar?.let { return it }
        val extracted = runCatching {
            val stream = javaClass.getResourceAsStream(AGENT_RESOURCE)
                ?: error("资源 $AGENT_RESOURCE 缺失（构建未内嵌 agent jar）")
            stream.use { input ->
                val out = File.createTempFile("mps-sql-agent", ".jar")
                out.deleteOnExit()
                out.outputStream().use { input.copyTo(it) }
                out
            }
        }.getOrNull()
        if (extracted != null) cachedAgentJar = extracted
        return extracted
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
        // T-ReachAudit T-RA-01：在线态 = 存在**健康**会话（socket 未断 + 心跳窗口内），不再只看 socket
        online = sessions.any { isSessionHealthy(it) }
        if (online != wasOnline) {
            LOG.info("增强捕获在线态切换: $wasOnline -> $online")
        }
        // 接管/回落切换：桥暂停挂载 ↔ 恢复（§4.3；桥链路本身零改动）
        SqlCaptureGate.paused.set(online)
        // T-AgentDialectLoop：回落/关闭时清空日志时间相关器，防跨运行陈旧匹配
        if (!online) LogTimeCorrelator.clear()
    }

    /**
     * T-SettingsTruth P2 / T-CaptureTruth T-CT-01：状态行文案（设置页增强捕获状态行展示）。
     * 本函数只取数，文案由纯函数 [agentStatusText] 产出（五态全分支可单测）。
     * 只读内存集合（CopyOnWriteArrayList/ConcurrentHashMap），任意线程安全。
     */
    internal fun statusSummary(): String? = agentStatusText(
        AgentStatusSnapshot(
            enabled = isEnhancedEnabled(),
            confirmed = isEnhancedConfirmed(),
            rejectCooldownRemainingMs = remainingRejectCooldownMs(),
            // T-ReachAudit T-RA-01：只把**健康**会话算作「已挂载」——半死会话（socket 在、心跳停）
            // 若仍显示「已挂载 pid=…」，用户会以为捕获正常，实际两条通道都已零产出
            livePids = sessions.filter { isSessionHealthy(it) }.map { it.pid },
            everAttached = everAttached.get(),
            heartbeatExpired = heartbeatDropDetected.get(),
        ),
    )

    /** T-SettingsTruth P2：是否发生过成功挂载（区分「从未挂载」与「挂载后中断回落」）。 */
    private val everAttached = java.util.concurrent.atomic.AtomicBoolean(false)

    /** T-SettingsTruth P2：状态迁移点广播（attach 成功 / sweepOffline 摘除 / shutdownAll）。 */
    private fun publishStatusChanged() {
        runCatching {
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .messageBus.syncPublisher(AGENT_STATUS_TOPIC).onStatusChanged()
        }.onFailure { LOG.warn("publishStatusChanged 广播失败（非致命）", it) }
    }

    /**
     * 在线态巡检（5s）：心跳窗口过期/连接死亡 → 会话摘除 → 桥回落。
     * internal 为同模块守护测试缝（T-PS4-03）。
     *
     * T-ReachAudit T-RA-01：摘除面由 `!alive` 扩为 `!isSessionHealthy`——半死会话（socket 在、心跳停）
     * 此前永远不会被摘除，控制台桥也就永远回不来；现按协议语义在窗口过期后摘除并回落。
     */
    internal fun sweepOffline() {
        val dead = sessions.filter { !isSessionHealthy(it) }
        if (dead.isNotEmpty()) {
            // 仍 alive 却过期 = 心跳停摆（应用侧卡死/织入异常），与 socket 断开区分开供状态行叙述
            heartbeatDropDetected.set(dead.any { it.alive })
            dead.forEach { it.close() }
            sessions.removeAll(dead.toSet())
            // T-PS4-03：死会话顺带清幂等表——pid 对应进程已消亡，残留会让该 pid 复用/重启后
            // 无法重挂；[shutdownAll] 的全表清空语义保留不动。
            dead.forEach { attachedPids.remove(it.pid) }
            updateOnline()
            // T-SettingsTruth P2：掉线摘除 → 状态行「连接中断，已回落控制台捕获」
            publishStatusChanged()
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

    /**
     * T-CaptureTruth T-CT-02：是否处于「开关开着但未授权」（含拒绝冷却期）——设置页据此露出
     * 重新授权按钮。区别于 [isEnhancedActive]：未授权态下增强功能实际不工作，状态行与按钮
     * 就是为了把这一真相摆到用户面前。
     */
    internal fun needsReauthorization(): Boolean = isEnhancedEnabled() && !isEnhancedConfirmed()

    /** 关闭开关：CONFIG 下发停采集（禁用不卸载语义）→ 会话关闭 → 桥回落。 */
    fun shutdownAll() {
        sessions.forEach { it.sendConfig(disabled = true) }
        sessions.forEach { it.close() }
        sessions.clear()
        attachedPids.clear()
        // T-PS4-03：关闭开关同时清排队去抖表——关后不应残留排队记忆（3s 窗内的重复启动照常重判）
        pendingPids.clear()
        updateOnline()
        // T-SettingsTruth P2：回落链完成 → 状态行「连接中断，已回落控制台捕获」
        publishStatusChanged()
    }

    /** 脱敏开关下发（设置变更）。disabled 跟随当前监听状态（B2：不写死 false——监听关闭时改设置不得重置为采集）。 */
    fun pushDesensitize(desensitize: Boolean) {
        val active = isListenActive()
        sessions.forEach { it.sendConfig(disabled = !active, desensitize = desensitize) }
    }

    // T-PS4-14③：pushFrameSkipPrefixes 死代码已删除（全仓库零调用）——帧过滤前缀的真实下发链为
    // SETTINGS_CHANGE_TOPIC 监听（本类 init）→ executor → pushDesensitize/sendConfig（sendConfig 读设置现拼 skipPrefixes）。

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
            // T-PS4-03：服务随 project 生命周期，去抖/幂等表一并清空（不引入跨项目残留）
            pendingPids.clear()
            attachedPids.clear()
            executor.shutdownNow()
            monitor.shutdownNow()
            SqlCaptureGate.paused.set(false)
        }
    }
}

/** T-CaptureTruth T-CT-01：增强捕获状态行输入快照（纯数据，[agentStatusText] 入参，零平台依赖）。 */
internal data class AgentStatusSnapshot(
    val enabled: Boolean,
    val confirmed: Boolean,
    val rejectCooldownRemainingMs: Long,
    val livePids: List<Long>,
    val everAttached: Boolean,
    /** T-ReachAudit T-RA-01：最近一次摘除是否因心跳过期（应用侧无响应，而非连接断开）。 */
    val heartbeatExpired: Boolean = false,
)

/**
 * T-CaptureTruth T-CT-01：增强捕获状态行文案（纯函数，五态全分支可单测）。
 *
 * 立项动因：原实现只有「有活会话 / 曾挂载 / 其余 null」三态——**「开关开着但从未授权」落进 null
 * 分支**，设置页状态行渲染成空白而复选框仍显示勾选，用户读作「开着且没事」，实际一条都捕不到
 * （T-CaptureTruth 根因之三；旧守卫断言「无会话应返回 null」把该行为钉死，本次一并改）。
 *
 * 五态：
 *  1. 关闭（!enabled）→ null（复选框未勾选，无需叙事）；
 *  2. 有活会话 →「已挂载 pid=…」；
 *  3. 未授权 + 冷却中 → 含剩余小时与「重新授权」指引（冷却原本静默，用户无从得知被拒过）；
 *  4. 未授权 + 无冷却 → 下次启动主配置时会询问；
 *  5. 已授权：曾挂载过 → 回落文案（T-RA-01 起区分「连接断开」与「心跳过期/应用无响应」两因）；
 *    从未挂载 → 等待应用进程启动后挂载。
 */
internal fun agentStatusText(s: AgentStatusSnapshot): String? {
    if (!s.enabled) return null
    if (s.livePids.isNotEmpty()) {
        return MpSqlVisualizerBundle.message(
            "settings.enhancedCapture.status.attached",
            s.livePids.joinToString(",") { "pid=$it" },
        )
    }
    if (!s.confirmed) {
        if (s.rejectCooldownRemainingMs > 0) {
            // 小时向上取整（不足 1 小时也显示 1，避免「剩 0 小时」的歧义）
            val hours = ((s.rejectCooldownRemainingMs + 3_599_999L) / 3_600_000L).coerceAtLeast(1L)
            return MpSqlVisualizerBundle.message("settings.enhancedCapture.status.unauthorizedCooldown", hours)
        }
        return MpSqlVisualizerBundle.message("settings.enhancedCapture.status.unauthorizedPending")
    }
    if (s.everAttached) {
        // T-ReachAudit T-RA-01：心跳过期 = 应用侧停摆（socket 未断），与真断连的处置不同——
        // 前者用户该去看应用进程，后者只是连接掉了，故分开叙述
        return MpSqlVisualizerBundle.message(
            if (s.heartbeatExpired) {
                "settings.enhancedCapture.status.heartbeatLost"
            } else {
                "settings.enhancedCapture.status.fellBack"
            },
        )
    }
    return MpSqlVisualizerBundle.message("settings.enhancedCapture.status.authorizedWaiting")
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
    /**
     * T-ReachAudit T-RA-01：时间源（默认系统时钟）。与 [AgentManager.clockMs] 同源注入——心跳窗口判定
     * 跨「会话记 lastSeen」与「管理器算过期」两侧，两侧时钟不同源则测试无法推进时间（假时钟一注入，
     * 真实时间戳的 lastSeen 会让会话永远健康），真机也无差异。
     */
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {

    private val LOG = Logger.getInstance(AgentSession::class.java)

    @Volatile
    var alive: Boolean = true
        private set

    @Volatile
    var lastSeen: Long = clockMs()
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
                    lastSeen = clockMs()
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
            // T-SourceJumpFunnel P1-3：随 CONFIG 附带用户登记的帧过滤前缀（热下发；旧 agent 忽略该键）
            val prefixes = PluginSettings.getInstance().state.sourceFrameSkipPrefixes
                .lineSequence().filter { it.isNotBlank() }.toList()
            val body = AgentProtocol.configBody(disabled, desensitize, prefixes).toByteArray(Charsets.UTF_8)
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
