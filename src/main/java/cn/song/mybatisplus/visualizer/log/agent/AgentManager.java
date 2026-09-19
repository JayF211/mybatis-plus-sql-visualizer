package cn.song.mybatisplus.visualizer.log.agent;

import cn.song.mybatisplus.visualizer.config.EnhancedCaptureConfirmDialog;
import cn.song.mybatisplus.visualizer.config.EnhancedCaptureConsent;
import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener;
import cn.song.mybatisplus.visualizer.log.LogTimeCorrelator;
import cn.song.mybatisplus.visualizer.log.SqlCaptureGate;
import cn.song.mybatisplus.visualizer.log.SqlLogManager;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.execution.process.OSProcessUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * AgentManager（§4.4，M56 T-M56-01/02）：project 级服务，agent 生命周期全权管理。
 *
 * - attach 全链：agent jar 解压落盘（失败显式上报）→ 端口前置协商（ServerSocket(0)）→
 *   {@code VirtualMachine.attach(pid)} + loadAgent → 会话接受 → 在线态。
 * - 惰性挂载（Ultra 4.0.20 教训）：开启增强 + 注入确认后，后台线程等进程就绪 + 去抖 3s 再
 *   attach，全程不进 EDT——不拖慢项目启动。
 * - 幂等表：插件侧 pid 表 + agent 侧 system property 双保险（JDK8 二次 loadAgent 抛
 *   AgentLoadException("0") 实证容错）。
 * - 降级三分支：attach 失败 / 连接丢失 / 关闭开关 → 桥恢复（capturePaused=false），零劣化。
 * - 在线态：最近 {@link AgentProtocol#ONLINE_WINDOW_MS} 内有心跳/事件（§4.3）。
 * - 随 project dispose 摘除：会话全关 + 连接池线程停转。
 */
@Service(Service.Level.PROJECT)
public final class AgentManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(AgentManager.class);

    /** agent jar 类路径资源（构建产物内嵌）。 */
    public static final String AGENT_RESOURCE = "/agent/mps-sql-agent.jar";

    /**
     * 拒绝冷却默认窗（T-PS4-01）：24h。冷却只作用于确认弹窗频率、不改变同意门槛（保守口径——
     * 冷却过期后再弹一次，用户仍可同意）；设置页关闭再开启增强捕获时重置。
     */
    public static final long DEFAULT_REJECT_COOLDOWN_MS = 24L * 60 * 60 * 1000;

    /** attach 排队去抖窗默认值（T-PS4-03）：同 pid 窗口内重复启动不重复排队（与挂载前静默等待解耦）。 */
    public static final long DEFAULT_ATTACH_DEBOUNCE_MS = 3_000L;

    /** 挂载前静默等待（T-PS4-03 提常量与去抖窗解耦）：等应用初始化热点过去。 */
    private static final long ATTACH_SETTLE_DELAY_MS = 3_000L;

    // ---------- T-M56Phase2-Premain 常量 ----------

    /**
     * premain 通道等待 agent 回连的时长：agent 侧 {@code SenderLoop} 连接失败按 1s→5s 退避<b>无限重连</b>
     * （SenderLoop.java:87），故本窗口只决定「IDE 等多久后放弃这个端口」，不是连接窗口。
     */
    public static final long PREMAIN_ACCEPT_TIMEOUT_MS = 120_000L;

    /**
     * premain 通道在场时跳过 attach 的窗口（T-M56P2-04）：远小于 {@link #PREMAIN_ACCEPT_TIMEOUT_MS}——
     * 窗口内认为「本次启动的 agent 会走 premain」，窗口外回归 attach 主路径。收窄的理由是
     * premain 失效（jar 被外部删除/目标 JVM 拒载）时不能让 attach 被长期压制（零劣化）。
     */
    public static final long PREMAIN_SKIP_ATTACH_WINDOW_MS = 30_000L;

    /** 同时挂起的 premain 通道上限：{@code updateJavaParameters} 每次 Run 都会触发，防重复准备泄漏 ServerSocket。 */
    public static final int MAX_PREMAIN_CHANNELS = 4;

    /** HELLO 首帧读取超时（agent 连上后立即发 HELLO，秒级足够；超时按「pid 未知」处理不丢会话）。 */
    private static final int HELLO_READ_TIMEOUT_MS = 5_000;

    /** T-SettingsTruth P2：增强捕获状态变更 Topic（app 级；仿 PluginSettings.SETTINGS_CHANGE_TOPIC）。 */
    public static final Topic<AgentStatusListener> AGENT_STATUS_TOPIC = Topic.create(
        "MpSqlVisualizerAgentStatusChange", AgentStatusListener.class);

    /** 获取 project 级服务实例。 */
    public static AgentManager getInstance(Project project) {
        return project.getService(AgentManager.class);
    }

    private final Project project;

    /** 在线态：任一会话活跃且最近窗口内有心跳/事件。UI 状态行/接管切换依据。 */
    public volatile boolean online = false;

    /**
     * 会话表。public 为同模块守护测试缝（T-PS4-03 sweepOffline 清表断言）；生产链路勿直接读写。
     */
    public final CopyOnWriteArrayList<AgentSession> sessions = new CopyOnWriteArrayList<>();

    /** 幂等表：pid → attach 时间戳（同一 pid 不二次 attach）。public 为同模块守护测试缝（T-PS4-03）。 */
    public final ConcurrentHashMap<Long, Long> attachedPids = new ConcurrentHashMap<>();

    /**
     * 惰性挂载去抖（T-PS4-03 落地）：pid → 上次排队时刻——同 pid 在 {@link #attachDebounceMs} 窗口内
     * 重复启动不重复排队（声明多年未用，本次兑现为真实去抖表；attach 任务完成时摘除条目，
     * 失败后的同 pid 后续启动可再排队，成功则由 attachedPids 幂等表兜底去重）。
     * public 为同模块守护测试缝（T-PS4-01 静默跳过断言 / T-PS4-03 去抖落地）；生产链路勿直接读写。
     */
    public final ConcurrentHashMap<Long, Long> pendingPids = new ConcurrentHashMap<>();

    /**
     * T-M56Phase2-Premain：待回连的 premain 通道表（启动前协商的端口 → 监听 socket）。
     * public 为同模块守护测试缝；生产链路勿直接读写。
     */
    public final CopyOnWriteArrayList<PremainChannel> premainChannels = new CopyOnWriteArrayList<>();

    // ---------- T-PS4-01/03 测试缝：冷却窗、去抖窗与时间源可注入（生产链路勿改默认值） ----------

    /** 拒绝冷却窗（默认 {@link #DEFAULT_REJECT_COOLDOWN_MS}；测试注入短窗驱动冷却语义）。 */
    public long rejectCooldownMs = DEFAULT_REJECT_COOLDOWN_MS;

    /** attach 排队去抖窗（默认 {@link #DEFAULT_ATTACH_DEBOUNCE_MS}；测试注入短窗驱动去抖语义）。 */
    public long attachDebounceMs = DEFAULT_ATTACH_DEBOUNCE_MS;

    /** 时间源（冷却判断与 attach 排队去抖共用；默认系统时钟，测试注入可控时钟）。 */
    public LongSupplier clockMs = System::currentTimeMillis;

    /**
     * T-ReachAudit T-RA-01：心跳窗口（默认 {@link AgentProtocol#ONLINE_WINDOW_MS}；测试注入短窗驱动过期语义）。
     *
     * 此前该窗口只存在于协议常量与 KDoc（{@link AgentProtocol#ONLINE_WINDOW_MS} 全仓零消费、
     * {@code AgentSession.lastSeen} 有写无读），在线态只看 socket 是否断开——agent 侧停摆但连接仍在时
     * {@code online} 恒 true，控制台桥被 {@code SqlCaptureGate.paused} 一直封着，两条通道同时零产出且无超时恢复。
     * 现按协议既有语义落地。
     */
    public long heartbeatWindowMs = AgentProtocol.ONLINE_WINDOW_MS;

    /**
     * T-ReachAudit T-RA-01：会话健康判定——socket 未断<b>且</b>窗口内有过心跳/事件。
     * 纯判定（可单测）：{@code now - lastSeen} 用注入时钟，故测试可推进时间而不真等 30s。
     */
    private boolean isSessionHealthy(AgentSession session) {
        return session.isAlive() && (clockMs.getAsLong() - session.getLastSeen()) <= heartbeatWindowMs;
    }

    /** T-ReachAudit T-RA-01：最近一次摘除是否因心跳过期（区分「连接断开」与「应用侧无响应」两类回落）。 */
    private final AtomicBoolean heartbeatDropDetected = new AtomicBoolean(false);

    /** 「关闭 → 再开启增强捕获」边沿检测（SETTINGS_CHANGE 监听重置拒绝冷却用）。 */
    private boolean lastKnownEnabled = PluginSettings.getInstance().getState().getEnhancedCaptureEnabled();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mps-agent-manager");
        t.setDaemon(true);
        return t;
    });

    /** 在线态巡检（5s）：心跳窗口过期/连接死亡 → 会话摘除 → 桥回落。 */
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mps-agent-monitor");
        t.setDaemon(true);
        return t;
    });

    /** T-SettingsTruth P2：是否发生过成功挂载（区分「从未挂载」与「挂载后中断回落」）。 */
    private final AtomicBoolean everAttached = new AtomicBoolean(false);

    /** agent jar 提取一次缓存（T-PS4-03）：解压 + deleteOnExit 登记只做一次；失败不缓存（下次 attach 重试）。 */
    private volatile File cachedAgentJar;

    /** premain 专用 jar 落盘路径缓存（与 attach 的 {@link #cachedAgentJar} 分开，语义不同见 {@link #extractPremainAgentJar}）。 */
    private volatile File cachedPremainAgentJar;

    /**
     * T-PS5-04：巡检懒启标志——{@code monitor} 的 5s 定时任务由<b>首条会话建立</b>才挂上
     * （{@link #ensureMonitorStarted}）。解耦前服务一实例化就排定时器：默认开智能推断的项目
     * 「预览一次即驻留」一条守护线程 + 5s 空转，即便增强捕获从未使用。
     */
    private volatile boolean monitorStarted = false;

    private final Object monitorLock = new Object();

    public AgentManager(Project project) {
        this.project = project;
        // T-PS5-04：monitor 定时任务不再在此排定（见 ensureMonitorStarted）。
        // T-M56-08：设置变更驱动——开启（已确认）→ 惰性挂载由进程出现回调承接；
        // 关闭 → CONFIG 停采集 + 会话关闭 + 桥回落；脱敏开关 → CONFIG 下发
        project.getMessageBus().connect(this).subscribe(
            PluginSettings.SETTINGS_CHANGE_TOPIC,
            (SettingsChangeListener) () -> {
                PluginSettings.State state = PluginSettings.getInstance().getState();
                // T-PS4-01：关闭 → 再开启的上升沿重置拒绝冷却（保留用户改主意的入口；
                // 仅此边沿重置，其余设置变更不动冷却，防改无关设置白涮 24h 冷却窗）
                if (state.getEnhancedCaptureEnabled() && !lastKnownEnabled) {
                    state.setEnhancedCaptureRejectedAtMs(0L);
                    LOG.info("设置变更：增强捕获重新开启——拒绝冷却已重置");
                }
                lastKnownEnabled = state.getEnhancedCaptureEnabled();
                if (!state.getEnhancedCaptureEnabled()) {
                    // T-PS5-07：关闭下降沿复位 EP 短路标志——[SqlCaptureGate.epActive] 是「EP 在本会话
                    // 实证产出过条目」的**会话级粘滞**标志（置位点 SqlConsoleBridgeFilterProvider），
                    // 解耦前无任何复位点：关掉增强捕获再新建控制台时桥侧仍被粘滞标志短路 = 两条通道
                    // 同时零产出（静默零捕获的复发形态）。与上方上升沿重置拒绝冷却对称；EP 链路失效的
                    // 极端场景由短路计数 + debug 日志（epActiveShortCircuitCount）可观测兜底。
                    SqlCaptureGate.epActive = false;
                    executor.execute(this::shutdownAll);
                } else {
                    executor.execute(() -> pushDesensitize(state.getEnhancedCaptureDesensitize()));
                }
            });
    }

    public boolean isOnline() {
        return online;
    }

    // ---------- 挂载入口 ----------

    /**
     * T-PS5-04：巡检懒启 + 建立兜底清扫——首条会话建立时排定 5s 巡检，并<b>先兜底扫一次</b>
     * （巡检未启动的窗口期内可能已积压死会话，不能等首个 5s 拍）。新会话刚建（心跳新鲜）
     * 不会被本次清扫摘除。
     *
     * <p>internal 为同模块守护测试缝（{@link #sweepOffline} 同款：测试直连会话不经 attachToPid）。
     */
    public void ensureMonitorStarted() {
        synchronized (monitorLock) {
            if (!monitorStarted) {
                monitorStarted = true;
                monitor.scheduleWithFixedDelay(this::sweepOffline, 5, 5, TimeUnit.SECONDS);
            }
        }
        sweepOffline(); // 兜底清扫（幂等；无死会话时零动作）
    }

    /** T-PS5-04 测试缝：巡检是否已懒启（生产链路无调用点）。 */
    public boolean monitorStartedForTest() {
        return monitorStarted;
    }

    /**
     * T-PS5-04：把当前 agentUrl 回写 {@link AgentUrlRegistry}（推断层读该持有者，不再经
     * {@code getInstance} 触发服务实例化）。值取 {@link #lastConnectionUrl}，语义与解耦前逐字一致；
     * 无会话/全离线 → null（清条目，推断回落数据源 URL）。
     *
     * <p>internal 为同模块守护测试缝（测试直连会话驱动 URL 捕获回调）。
     */
    public void syncInferenceUrl() {
        AgentUrlRegistry.set(project, lastConnectionUrl());
    }

    /**
     * T-PS5-03/04：<b>会话生命周期点收口</b>——推断 URL 回写 + 数据源 URL 探测缓存失效。
     *
     * <p>探测缓存（{@link cn.song.mybatisplus.visualizer.sql.DataSourceUrlProbe}）失效双保险之一：
     * 会话建立/断开是「方言推断依据可能整体换了一套」的事件点（agent URL 出现/消失），
     * 顺带清掉数据源 URL 探测槽；另一重保险是 TTL 上界（数据源编辑后至多一个窗口滞后）。
     */
    public void onSessionLifecycleChanged() {
        syncInferenceUrl();
        cn.song.mybatisplus.visualizer.sql.DataSourceUrlProbe.invalidate(project);
    }

    /**
     * T-DialectAutoInfer：最近一次 agent 捕获的连接 URL（活跃会话优先，按会话加入序取最后值；
     * 全部离线/无会话 → null——推断层回落数据源探测）。纯读、无锁（CopyOnWriteArrayList 快照遍历）。
     *
     * <p>T-PS5-04：本函数仍是唯一取数口径，但<b>推断层不再直调</b>——{@link #syncInferenceUrl} 在会话
     * 生命周期点把结果写入 {@link AgentUrlRegistry}，推断层读持有者（零服务实例化）。
     */
    public String lastConnectionUrl() {
        for (int i = sessions.size() - 1; i >= 0; i--) {
            AgentSession s = sessions.get(i);
            if (s.isAlive() && s.getLastConnectionUrl() != null) {
                return s.getLastConnectionUrl();
            }
        }
        for (int i = sessions.size() - 1; i >= 0; i--) {
            AgentSession s = sessions.get(i);
            if (s.getLastConnectionUrl() != null) {
                return s.getLastConnectionUrl();
            }
        }
        return null;
    }

    /**
     * 进程出现回调（RunConfigurationProcessListener.processStarted 转接；增强未开启时空操作）。
     * 后台线程：进程就绪等待 + 3s 去抖 + attach——全程不进 EDT。
     *
     * 默认开（2026-09-16 定案）后的确认闸门：enabled 默认 true 而 confirmed 默认 false——
     * 若沿用「仅设置页勾选时弹确认」，默认勾上的开关将永不弹框、永不 attach（死开关）。
     * 故未确认时在<b>首次挂载请求</b>处征求一次知情同意（EDT 弹框），同意后继续挂载。
     *
     * T-PS4-01 触发面收窄：未确认态的确认弹窗仅对主配置启动（{@code isMainConfigLaunch}）触发；
     * HTTP Client/测试/profiler 等非主配置进程在未确认态<b>静默跳过</b>（不弹窗、不入 attach 队列），
     * 杜绝「拒绝注入的用户每进程启动挨一次 EDT 模态弹窗」。已 confirmed 的 attach 链路维持对
     * 所有进程触发不变（增强捕获主通道语义零变化）。
     *
     * @param isMainConfigLaunch 是否主业务配置启动（ModuleBasedConfiguration 子类，与
     *   RunConfigurationProcessListener.isModuleBasedLaunch 同源判定）。默认 true =
     *   保守按主配置处理（未知调用方保留既有弹窗语义，知情同意不静默吞）；生产唯一调用点显式传参。
     */
    public void onProcessAppeared(Object processHandler) {
        onProcessAppeared(processHandler, true);
    }

    /** {@code isMainConfigLaunch} 全参形态（Kotlin 默认参数 isMainConfigLaunch=true 的显式入口）。 */
    public void onProcessAppeared(Object processHandler, boolean isMainConfigLaunch) {
        if (!isEnhancedEnabled()) {
            LOG.info("onProcessAppeared: 增强未开启（enabled="
                + PluginSettings.getInstance().getState().getEnhancedCaptureEnabled()
                + ", disposed=" + project.isDisposed() + "）——跳过挂载");
            return;
        }
        if (!isEnhancedConfirmed()) {
            if (!isMainConfigLaunch) {
                LOG.info("onProcessAppeared: 增强已开启但未确认且非主配置进程——静默跳过（不弹窗不入队，T-PS4-01）");
                return;
            }
            if (isInRejectCooldown()) {
                LOG.info("onProcessAppeared: 拒绝冷却期内（" + remainingRejectCooldownMs() + "ms）——不弹窗不排队（T-PS4-01）");
                return;
            }
            LOG.info("onProcessAppeared: 增强已开启但未确认注入——请求确认后挂载");
            requestConsentThenAttach(processHandler);
            return;
        }
        doAttachAsync(processHandler);
    }

    /**
     * 拒绝冷却判断（T-PS4-01）：自持久化的「拒绝时刻」（{@code PluginSettings.State.enhancedCaptureRejectedAtMs}）
     * 起 {@link #rejectCooldownMs} 窗口内为 true。冷却只作用于弹窗频率、不改变同意门槛（保守口径——
     * 冷却过期后再弹一次）；总开关关闭时本判断不会被走到（enabled 门在前，关闭增强 > 冷却）。
     */
    public boolean isInRejectCooldown() {
        long rejectedAt = PluginSettings.getInstance().getState().getEnhancedCaptureRejectedAtMs();
        if (rejectedAt <= 0L) {
            return false;
        }
        return clockMs.getAsLong() - rejectedAt < rejectCooldownMs;
    }

    public long remainingRejectCooldownMs() {
        return Math.max(0L, rejectCooldownMs
            - (clockMs.getAsLong() - PluginSettings.getInstance().getState().getEnhancedCaptureRejectedAtMs()));
    }

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
    public void requestReauthorization() {
        if (!isEnhancedEnabled()) {
            LOG.info("requestReauthorization: 增强未开启——忽略");
            return;
        }
        PluginSettings.getInstance().getState().setEnhancedCaptureRejectedAtMs(0L);
        LOG.info("requestReauthorization: 拒绝冷却已清零，重走注入确认流");
        publishStatusChanged();
        requestConsentThenAttach(null);
    }

    /**
     * 首次挂载确认（EDT 弹一次）。T-PS4-01 三态语义：
     * - 同意 → 置 confirmed 持久化并继续挂载（此后任何进程都不再弹）；
     * - 拒绝 → 持久化「拒绝时刻」进入 {@link #DEFAULT_REJECT_COOLDOWN_MS} 冷却（冷却期内不重复弹，
     *   冷却过期后再弹一次；冷却只作用于弹窗频率、不改变同意门槛——保守口径）；
     * - 关闭增强 → 总开关 enhancedCaptureEnabled 置 false 持久化（语义优先级：关闭增强 > 冷却，
     *   总开关关闭后冷却无意义），此后任何进程启动都不弹窗、不 attach，直到用户在设置页重新开启
     *   （重开的上升沿由 SETTINGS_CHANGE 监听重置冷却）。
     * 确认结果持久化（重开不再弹）；headless 由 {@link #consentProvider} 默认实现直接拒绝。
     */
    private void requestConsentThenAttach(Object processHandler) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed() || !isEnhancedEnabled()) {
                return;
            }
            EnhancedCaptureConsent outcome;
            try {
                outcome = consentProvider.get();
            } catch (Throwable t) {
                // T-FatalPassthrough T-FP-02：JVM 级致命错误透传（见 util/FatalErrors.kt）——本文件
                // 是长驻链路（attach / premain / 会话读线程 / CONFIG 下发），捕获面覆盖 Error：
                // 普通异常（含 ClassNotFoundException 这类可选能力缺失）仍走既有降级，
                // VirtualMachineError/LinkageError 不得被吞成一条 warn 后让流程带病继续。
                // Java 形态：isFatal 蕴含 Error，显式收窄后 throw（裸 `throw t` 在 try 含受检异常时
                // 因精确重抛分析要求方法声明该受检异常而不可编译；语义与原对象逐字一致，不包装）。
                if (FatalErrors.isFatal(t)) throw (Error) t;
                LOG.warn("requestConsentThenAttach: 确认框异常——按拒绝处理（进冷却）", t);
                outcome = EnhancedCaptureConsent.REJECTED;
            }
            PluginSettings.State state = PluginSettings.getInstance().getState();
            switch (outcome) {
                case ACCEPTED -> {
                    state.setEnhancedCaptureConfirmed(true);
                    // T-CaptureTruth T-CT-01：三态结果都广播——否则设置页状态行要等下一次
                    // attach/掉线才刷新，用户点完同意/拒绝后看到的是过期文案。
                    publishStatusChanged();
                    if (processHandler == null) {
                        // T-CaptureTruth T-CT-02：重新授权入口无进程句柄——只恢复授权，
                        // 挂载由下一次应用进程启动承接（避免 resolvePid(null) 的误导性 WARN）。
                        LOG.info("requestConsentThenAttach: 用户同意注入——已授权，等待应用进程启动后挂载");
                        return;
                    }
                    LOG.info("requestConsentThenAttach: 用户同意注入——继续挂载");
                    doAttachAsync(processHandler);
                }
                case DISABLE_CAPTURE -> {
                    // 「关闭增强捕获并不再询问」：总开关持久化关闭（与设置页 Apply 同通道，
                    // XmlSerializer 随平台自动保存落盘）。enabled 门在 onProcessAppeared 入口，
                    // 后续任何进程启动都不再进入本确认流，也不 attach。
                    state.setEnhancedCaptureEnabled(false);
                    LOG.info("requestConsentThenAttach: 用户选择关闭增强捕获并不再询问——总开关已持久化关闭");
                    publishStatusChanged();
                }
                case REJECTED -> {
                    state.setEnhancedCaptureRejectedAtMs(clockMs.getAsLong());
                    LOG.info("requestConsentThenAttach: 用户拒绝注入——进入 " + rejectCooldownMs
                        + "ms 冷却（冷却期内不重复弹，不改同意门槛）");
                    publishStatusChanged();
                }
            }
        });
    }

    /**
     * 确认提供者（默认弹注入确认框三态结果，headless 恒拒绝；测试替换以驱动同意/关闭/拒绝分支）。
     * T-PS4-01 三态：ACCEPTED 同意 / DISABLE_CAPTURE 关闭增强并不再询问 / REJECTED 拒绝（进冷却）。
     */
    public Supplier<EnhancedCaptureConsent> consentProvider = () -> {
        if (ApplicationManager.getApplication().isHeadlessEnvironment()) {
            return EnhancedCaptureConsent.REJECTED;
        }
        return EnhancedCaptureConfirmDialog.confirmOutcome(null);
    };

    /** 实际挂载（阻塞等待与 attach 全链在单线程 executor；T-PS4-03：pid 解析与排队去抖前移到请求线程）。 */
    private void doAttachAsync(Object processHandler) {
        // T-M56Phase2-Premain（T-M56P2-04）：premain 通道在场 → 本次启动已注入 -javaagent，跳过 attach。
        // 反方向（premain 回连先到）由 attachedPids 兜底：回连时已写表，attachToPid 的 putIfAbsent 挡下。
        if (hasPendingPremainChannel()) {
            LOG.info("onProcessAppeared: premain 通道在场（启动即捕获已注入）——跳过 attach（T-M56Phase2）");
            return;
        }
        // T-PS4-03：去抖必须在「排队时刻」判定——若迟到 executor 任务起跑才查表，静默等待已把
        // 窗口耗尽、判定失真。pid 解析是轻反射（getNativePid/getProcess().pid()，无阻塞等待），
        // 前移到请求线程安全；重活（静默等待 + attach）仍留在 executor，不进调用线程。
        Long pid = resolvePid(processHandler);
        if (pid == null) {
            LOG.warn("onProcessAppeared: resolvePid 失败（handler="
                + (processHandler != null ? processHandler.getClass().getName() : "null") + "）——跳过挂载");
            return;
        }
        long now = clockMs.getAsLong();
        Long lastQueued = pendingPids.get(pid);
        if (lastQueued != null && now - lastQueued < attachDebounceMs) {
            LOG.info("onProcessAppeared: pid=" + pid + " 去抖窗内重复启动（距上次排队 " + (now - lastQueued)
                + "ms < " + attachDebounceMs + "ms）——不重复排队（T-PS4-03）");
            return;
        }
        pendingPids.put(pid, now);
        executor.execute(() -> {
            try {
                LOG.info("onProcessAppeared: 目标 pid=" + pid + "，" + ATTACH_SETTLE_DELAY_MS + "ms 静默等待后挂载");
                // 静默等待：等应用初始化热点过去（驱动已加载场景 retransform 直达；未加载场景 transformer 兜底）。
                // 单线程池串行性质：executor 仅此一条工作线程，跨 pid 的静默等待天然串行排队——
                // 多进程先后启动时后者在前者等待结束后才进入 attach，不会并发压目标 JVM，维持既有语义。
                Thread.sleep(ATTACH_SETTLE_DELAY_MS);
                attachToPid(pid);
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) throw (Error) t; // T-FP-02：fatal 透传
                LOG.warn("onProcessAppeared: 惰性 attach 失败（非致命，桥继续捕获）", t);
            } finally {
                pendingPids.remove(pid);
            }
        });
    }

    /** attach 全链（幂等表去重；测试可直调）。返回是否达到在线。 */
    public boolean attachToPid(long pid) {
        if (!isEnhancedActive()) {
            return false;
        }
        if (attachedPids.putIfAbsent(pid, System.currentTimeMillis()) != null) {
            LOG.info("attachToPid: pid=" + pid + " 已挂载（幂等表命中），跳过");
            return online;
        }
        AgentSession session;
        try {
            session = createSession(pid);
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t; // T-FP-02：fatal 透传
            attachedPids.remove(pid);
            LOG.warn("attachToPid: pid=" + pid + " attach 失败——回落桥（零劣化）", t);
            return false;
        }
        if (session == null) {
            // attach 已失败（容错后仍未在线）：回落桥
            attachedPids.remove(pid);
            return false;
        }
        sessions.add(session);
        // B1：新 agent 默认 disabled=false（静态字段初始值），须按当前监听状态立即校正——
        // 否则「监听关闭状态启动项目」场景新 agent 又开始白采集
        boolean active = isListenActive();
        try {
            session.sendConfig(!active);
        } catch (Throwable t) {
            LOG.warn("attachToPid: 初始 CONFIG 下发失败（pid=" + pid + "）", t);
        }
        updateOnline();
        // T-PS5-04：首条会话建立 → 巡检懒启（含兜底清扫）+ 推断 URL 回写持有者（含探测缓存失效）
        ensureMonitorStarted();
        onSessionLifecycleChanged();
        // T-SettingsTruth P2：attach 成功 → 状态行「已挂载 pid=x」
        everAttached.set(true);
        // T-ReachAudit T-RA-01：新会话挂上即清「心跳过期」标记——回落文案只描述最近一次摘除原因
        heartbeatDropDetected.set(false);
        publishStatusChanged();
        return true;
    }

    /**
     * 创建会话：解压 agent jar → ServerSocket 端口协商 → attach → accept（10s 超时）。
     * 返回 null = attach 后 agent 未在超时内连接（视同失败）。
     */
    private AgentSession createSession(long pid) throws IOException, AttachNotSupportedException, AgentInitializationException {
        File jar = extractAgentJar();
        if (jar == null) {
            notifyAgentFailure("增强捕获：agent 解压落盘失败，已回落控制台捕获");
            return null;
        }
        Socket socket = attachAndOffer(jar, pid);
        if (socket == null) {
            return null;
        }
        // T-ReachAudit T-RA-01：会话与管理器共用同一时间源（心跳窗口判定跨「记 lastSeen」与
        // 「算过期」两侧，不同源则测试无法推进时间）
        return new AgentSession(
            project, pid, socket,
            this::updateOnline,
            clockMs,
            // T-PS5-04：URL 捕获即回写推断持有者（会话建立时 URL 尚不可知，事件到达才拿到）
            this::syncInferenceUrl);
    }

    /**
     * 解压 agent jar 到临时目录（T-PS4-03：提取一次缓存到服务字段复用，deleteOnExit 仅登记一次；
     * 原「每次 createSession 解压一个新临时文件」会随 attach 次数累积 deleteOnExit 登记与磁盘文件，
     * 直到 IDE 退出才统一回收。失败 null 由调用方显式上报——禁静默吞）。
     */
    private File extractAgentJar() {
        File cached = cachedAgentJar;
        if (cached != null) {
            return cached;
        }
        File extracted = null;
        try {
            InputStream input = getClass().getResourceAsStream(AGENT_RESOURCE);
            if (input == null) {
                throw new IllegalStateException("资源 " + AGENT_RESOURCE + " 缺失（构建未内嵌 agent jar）");
            }
            try (InputStream in = input) {
                File out = File.createTempFile("mps-sql-agent", ".jar");
                out.deleteOnExit();
                try (OutputStream os = new FileOutputStream(out)) {
                    in.transferTo(os);
                }
                extracted = out;
            }
        } catch (Throwable e) {
            extracted = null;
        }
        if (extracted != null) {
            cachedAgentJar = extracted;
        }
        return extracted;
    }

    /**
     * attach + loadAgent（端口下发）+ accept 握手。
     * JDK 错配容忍（§4.5）：{@code AgentLoadException("0")} / {@code Non-numeric value found} 视为可能
     * 仍成功，继续等 accept；finally detach。
     */
    private Socket attachAndOffer(File jar, long pid) throws IOException, AttachNotSupportedException, AgentInitializationException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            server.setSoTimeout(10_000);
            VirtualMachine vm = null;
            try {
                vm = VirtualMachine.attach(String.valueOf(pid));
                try {
                    vm.loadAgent(jar.getAbsolutePath(), AgentProtocol.agentArgs(port));
                } catch (com.sun.tools.attach.AgentLoadException e) {
                    // JDK8 二次挂载抛 AgentLoadException: 0——agent 可能已在目标 JVM 内，继续握手
                    LOG.warn("loadAgent AgentLoadException(" + e.getMessage() + ")——按可能已挂载继续握手");
                } catch (IOException e) {
                    if (e.getMessage() != null && e.getMessage().contains("Non-numeric value found")) {
                        LOG.warn("loadAgent Non-numeric value found（JDK 错配魔数）——按可能已挂载继续握手");
                    } else {
                        throw e;
                    }
                }
                try {
                    return server.accept();
                } catch (SocketTimeoutException e) {
                    LOG.warn("accept 超时：agent 未在 10s 内连接（pid=" + pid + "）——回落桥");
                    return null;
                }
            } finally {
                try {
                    if (vm != null) {
                        vm.detach();
                    }
                } catch (Throwable ignored) {
                    // 对齐 Kotlin runCatching：detach 失败不影响握手结果
                }
            }
        }
    }

    // ---------- T-M56Phase2-Premain：启动即捕获补位 ----------

    /**
     * premain 注入准备（{@code PremainRunConfigurationExtension.updateJavaParameters} 的唯一入口）：
     * 过四道闸门 → 产出 {@code -javaagent:…} VM 选项字符串，null = 本次不注入。
     *
     * 闸门顺序与副作用解耦（零劣化的关键）：开关/确认两道为假时<b>不提取 jar、不协商端口</b>——
     * 这两步在未开启态纯属浪费（且端口协商会开 ServerSocket）。判定本身走纯函数
     * {@link PremainInjection#decide}，此处只负责按序求值事实与打日志。
     *
     * 注意确认闸门：未确认态不注入，首次运行的知情同意仍由既有 attach 链在进程启动后弹一次
     * （{@link #onProcessAppeared}）——确认后的后续运行才吃到 premain，该语义本任务不改。
     */
    public String preparePremainVmOption() {
        boolean enabled = isEnhancedEnabled();
        boolean confirmed = isEnhancedConfirmed();
        if (!enabled || !confirmed) {
            String reason = ((PremainInjection.Decision.Skip) PremainInjection.decide(enabled, confirmed)).reason();
            LOG.info("premain 注入跳过：" + reason + "（不提取 jar、不协商端口）");
            return null;
        }
        File jar = extractPremainAgentJar();
        Integer port = jar != null ? openPremainChannel() : null;
        PremainInjection.Decision decision = PremainInjection.decide(true, true, jar != null, port);
        if (decision instanceof PremainInjection.Decision.Inject inject) {
            LOG.info("premain 注入：端口 " + inject.port() + " 已前置协商（agent 回连前不阻塞用户 Run）");
            return PremainInjection.vmOption(java.util.Objects.requireNonNull(jar).getAbsolutePath(), inject.port());
        }
        String reason = ((PremainInjection.Decision.Skip) decision).reason();
        LOG.warn("premain 注入跳过：" + reason + "——回落 attach/桥（零劣化）");
        return null;
    }

    /**
     * 端口前置协商（T-M56P2-01）：开 loopback 端口 + 起守护 accept 线程，立即返回端口。
     * 与 attach 链的 {@link #attachAndOffer} 同一套「{@code ServerSocket(0, 1, loopback)} 取端口」思路，
     * 差别只在<b>端口生命周期前移到进程启动之前</b>——agent 侧 {@code EventBus.start(port)} 起
     * {@code SenderLoop} 后按 1s→5s 退避无限重连，端口先开不丢连接。
     *
     * 返回 null 的两条路径（绑定失败 / 挂起通道超上限）都只记日志：调用方回落 attach/桥。
     */
    public Integer openPremainChannel() {
        prunePremainChannels();
        if (premainChannels.size() >= MAX_PREMAIN_CHANNELS) {
            LOG.warn("openPremainChannel: 挂起通道已达上限 " + MAX_PREMAIN_CHANNELS + "——本次不注入（回落 attach/桥）");
            return null;
        }
        ServerSocket server;
        try {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t; // T-FP-02：fatal 透传
            LOG.warn("openPremainChannel: 端口前置协商失败——本次不注入（回落 attach/桥）", t);
            return null;
        }
        PremainChannel channel = new PremainChannel(server.getLocalPort(), server, clockMs.getAsLong());
        premainChannels.add(channel);
        Thread acceptThread = new Thread(() -> acceptPremain(channel), "mps-agent-premain-" + channel.port);
        acceptThread.setDaemon(true);
        acceptThread.start();
        LOG.info("openPremainChannel: 端口 " + channel.port + " 已前置协商（挂起 " + premainChannels.size() + " 个）");
        return channel.port;
    }

    /** premain 通道是否在场（{@link #doAttachAsync} 跳过判据）：未认领且准备时刻在跳过窗内。 */
    public boolean hasPendingPremainChannel() {
        long now = clockMs.getAsLong();
        for (PremainChannel channel : premainChannels) {
            if (!channel.claimed && now - channel.preparedAtMs <= PREMAIN_SKIP_ATTACH_WINDOW_MS) {
                return true;
            }
        }
        return false;
    }

    /** 清理过期/已认领通道（关 socket 幂等——accept 线程 finally 也会关一次）。 */
    private void prunePremainChannels() {
        long now = clockMs.getAsLong();
        premainChannels.removeIf(channel -> {
            boolean stale = channel.claimed || now - channel.preparedAtMs > PREMAIN_ACCEPT_TIMEOUT_MS;
            if (stale) {
                try {
                    channel.server.close();
                } catch (Throwable ignored) {
                    // 对齐 Kotlin runCatching：已关/关失败都按清理完成处理
                }
            }
            return stale;
        });
    }

    /** premain accept 线程体：等到回连（或超时）后交 {@link #onPremainConnected}，无论如何都摘除通道。 */
    private void acceptPremain(PremainChannel channel) {
        try {
            channel.server.setSoTimeout((int) PREMAIN_ACCEPT_TIMEOUT_MS);
            Socket socket = channel.server.accept();
            channel.claimed = true;
            onPremainConnected(socket);
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t; // T-FP-02：fatal 透传（accept 线程：透传即线程终止，平台记录现场）
            LOG.info("premain 通道 " + channel.port + " 未收到回连（" + t.getClass().getSimpleName() + ": "
                + t.getMessage() + "）——该进程回落 attach/桥（零劣化）");
        } finally {
            try {
                channel.server.close();
            } catch (Throwable ignored) {
                // 对齐 Kotlin runCatching：关闭失败不影响摘除
            }
            premainChannels.remove(channel);
        }
    }

    /**
     * premain 回连处理（T-M56P2-04 幂等）：读 HELLO 取 pid → 建会话 → 写幂等表。
     *
     * 去重判据是「同 pid 是否有<b>活动会话</b>」而非查 {@link #attachedPids}：premain 是更强通道（启动期
     * 就能织入，无 3s 静默等待），先到者优先，写表时覆盖——否则 attach 先占表会让 premain 连接
     * 被误丢，反而丢掉唯一一条可用连接。
     */
    private void onPremainConnected(Socket socket) {
        try {
            if (!isEnhancedActive()) {
                LOG.info("premain 回连到达但增强捕获已关闭/未确认——丢弃连接");
                try {
                    socket.close();
                } catch (Throwable ignored) {
                    // 对齐 Kotlin runCatching
                }
                return;
            }
            long pid = readHelloPid(socket);
            boolean duplicate = false;
            if (pid > 0L) {
                for (AgentSession session : sessions) {
                    if (session.isAlive() && session.getPid() == pid) {
                        duplicate = true;
                        break;
                    }
                }
            }
            if (pid > 0L && duplicate) {
                LOG.info("premain 回连 pid=" + pid + " 已有活动会话——丢弃重复连接（幂等）");
                try {
                    socket.close();
                } catch (Throwable ignored) {
                    // 对齐 Kotlin runCatching
                }
                return;
            }
            // T-ReachAudit T-RA-01 合流补位：会话时间源须与管理器同源注入（createSession 同款）——
            // 缺省真实时钟时，假时钟驱动的心跳窗口判定两侧不同源（manager 算出的「已过期」被真实
            // 时间戳的 lastSeen 抵消），premain 通道建立的半死会话不会被巡检摘除，封口在此复活
            AgentSession session = new AgentSession(
                project, pid, socket,
                this::updateOnline,
                clockMs,
                // T-PS5-04：premain 会话同 attach 链——URL 捕获回写推断持有者
                this::syncInferenceUrl);
            sessions.add(session);
            if (pid > 0L) {
                attachedPids.put(pid, clockMs.getAsLong());
            }
            // B1 同款：新 agent 默认 disabled=false，须按当前监听状态立即校正
            boolean active = isListenActive();
            try {
                session.sendConfig(!active);
            } catch (Throwable t) {
                LOG.warn("premain: 初始 CONFIG 下发失败（pid=" + pid + "）", t);
            }
            everAttached.set(true);
            updateOnline();
            // T-PS5-04：premain 会话建立同 attach 链——巡检懒启 + 推断 URL 回写
            ensureMonitorStarted();
            onSessionLifecycleChanged();
            publishStatusChanged();
            LOG.info("premain 会话建立：pid=" + pid + "（启动即捕获，attach 链已跳过）");
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t; // T-FP-02：fatal 透传
            LOG.warn("premain 回连处理失败——丢弃连接（回落桥）", t);
            try {
                socket.close();
            } catch (Throwable ignored) {
                // 对齐 Kotlin runCatching
            }
        }
    }

    /** HELLO 帧 pid 提取（{@code "pid":<n>}；SenderLoop.java:39）。 */
    private static final java.util.regex.Pattern HELLO_PID_PATTERN =
        java.util.regex.Pattern.compile("\"pid\"\\s*:\\s*(\\d+)");

    /**
     * 读首帧 HELLO（{@code {"pid":<n>}}，SenderLoop.java:39）取 agent 自报 pid；非 HELLO/超时/畸形返回 0。
     *
     * 用裸 {@code DataInputStream} 读且只读一帧：它不做预读缓冲，剩余字节原样留给随后构造的
     * {@link AgentSession}（其内部另起 {@code DataInputStream} 走同一 socket 输入流），不会吞掉后续帧。
     */
    public long readHelloPid(Socket socket) {
        try {
            socket.setSoTimeout(HELLO_READ_TIMEOUT_MS);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            int len = input.readInt();
            if (len <= 0 || len > AgentProtocol.MAX_FRAME_LEN) {
                return 0L;
            }
            int cmd = input.readUnsignedByte();
            byte[] body = new byte[len];
            input.readFully(body);
            if (cmd != AgentProtocol.CMD_HELLO) {
                return 0L;
            }
            java.util.regex.Matcher m = HELLO_PID_PATTERN.matcher(new String(body, StandardCharsets.UTF_8));
            if (m.find()) {
                try {
                    return Long.parseLong(m.group(1));
                } catch (NumberFormatException e) {
                    return 0L;
                }
            }
            return 0L;
        } catch (Throwable t) {
            LOG.info("readHelloPid: HELLO 未取到（" + t.getClass().getSimpleName() + ": " + t.getMessage() + "）——按 pid 未知继续");
            return 0L;
        }
    }

    /**
     * premain 专用 agent jar 落盘（T-M56P2-02）。与 attach 的 {@link #extractAgentJar} 分开的理由是
     * {@code -javaagent} 与 {@code loadAgent} 对文件的要求根本不同：
     *
     * - {@code loadAgent} 在调用当次读文件 → 随机临时名 + {@code deleteOnExit} 足够；
     * - {@code -javaagent} 由<b>目标 JVM 在启动时</b>读，且打不开 agent jar 时 JVM 会在初始化阶段直接终止
     *   （{@code Error occurred during initialization of VM}）——{@code deleteOnExit} 与「IDE 退出 / 子 JVM 启动」
     *   之间存在删除竞态，随机名又会让同会话重复 Run 每次新路径、旧文件堆积。
     *
     * 故落盘策略：插件临时目录（{@code PathManager.getPluginTempPath}，不可用回落 {@code java.io.tmpdir}）+
     * 按内容 CRC32 定名（同一构建产物永远同一路径，重复 Run 复用）+ 「临时文件 + ATOMIC_MOVE 覆盖改名」
     * （并发/多项目同时准备只收敛到同一份完整文件）+ <b>不登记 deleteOnExit</b>。残留单文件约 500KB
     * 落在插件临时目录，由 OS 临时目录自行回收，换「进程启动时文件必然在位」。
     */
    private File extractPremainAgentJar() {
        File cached = cachedPremainAgentJar;
        if (cached != null && cached.isFile() && cached.length() > 0L) {
            return cached;
        }
        File extracted = null;
        try {
            InputStream stream = getClass().getResourceAsStream(AGENT_RESOURCE);
            if (stream == null) {
                throw new IllegalStateException("资源 " + AGENT_RESOURCE + " 缺失（构建未内嵌 agent jar）");
            }
            byte[] bytes;
            try (InputStream in = stream) {
                bytes = in.readAllBytes();
            }
            File dir = new File(premainAgentDir(), "mpsv-agent");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IllegalStateException("premain agent 目录不可建：" + dir);
            }
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(bytes);
            long crcValue = crc.getValue();
            File target = new File(dir, "mps-sql-agent-" + crcValue + ".jar");
            if (target.isFile() && target.length() == bytes.length) {
                return target;
            }
            File tmp = new File(dir, "mps-sql-agent-" + crcValue + ".jar.tmp");
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(bytes);
            }
            try {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            extracted = target;
        } catch (Throwable e) {
            extracted = null;
        }
        if (extracted != null) {
            cachedPremainAgentJar = extracted;
        }
        return extracted;
    }

    /** premain jar 目录根：插件临时目录优先（平台 API 在 headless 测试环境可能未初始化，故带回退）。 */
    private File premainAgentDir() {
        try {
            return new File(com.intellij.openapi.application.PathManager.getPluginTempPath());
        } catch (Throwable e) {
            String tmp = System.getProperty("java.io.tmpdir");
            return new File(tmp != null ? tmp : ".");
        }
    }

    // ---------- 会话/在线态 ----------

    private void updateOnline() {
        boolean wasOnline = online;
        // T-ReachAudit T-RA-01：在线态 = 存在<b>健康</b>会话（socket 未断 + 心跳窗口内），不再只看 socket
        boolean anyHealthy = false;
        for (AgentSession session : sessions) {
            if (isSessionHealthy(session)) {
                anyHealthy = true;
                break;
            }
        }
        online = anyHealthy;
        if (online != wasOnline) {
            LOG.info("增强捕获在线态切换: " + wasOnline + " -> " + online);
        }
        // 接管/回落切换：桥暂停挂载 ↔ 恢复（§4.3；桥链路本身零改动）
        SqlCaptureGate.paused.set(online);
        // T-AgentDialectLoop：回落/关闭时清空日志时间相关器，防跨运行陈旧匹配
        if (!online) {
            LogTimeCorrelator.clear();
        }
    }

    /**
     * T-SettingsTruth P2 / T-CaptureTruth T-CT-01：状态行文案（设置页增强捕获状态行展示）。
     * 本函数只取数，文案由纯函数 {@link AgentManagerKt#agentStatusText} 产出（五态全分支可单测）。
     * 只读内存集合（CopyOnWriteArrayList/ConcurrentHashMap），任意线程安全。
     */
    public String statusSummary() {
        java.util.List<Long> livePids = new java.util.ArrayList<>();
        for (AgentSession session : sessions) {
            // T-ReachAudit T-RA-01：只把<b>健康</b>会话算作「已挂载」——半死会话（socket 在、心跳停）
            // 若仍显示「已挂载 pid=…」，用户会以为捕获正常，实际两条通道都已零产出
            if (isSessionHealthy(session)) {
                livePids.add(session.getPid());
            }
        }
        return AgentManagerKt.agentStatusText(new AgentStatusSnapshot(
            isEnhancedEnabled(),
            isEnhancedConfirmed(),
            remainingRejectCooldownMs(),
            livePids,
            everAttached.get(),
            heartbeatDropDetected.get()));
    }

    /** T-SettingsTruth P2：状态迁移点广播（attach 成功 / sweepOffline 摘除 / shutdownAll）。 */
    private void publishStatusChanged() {
        try {
            ApplicationManager.getApplication().getMessageBus().syncPublisher(AGENT_STATUS_TOPIC).onStatusChanged();
        } catch (Throwable t) {
            LOG.warn("publishStatusChanged 广播失败（非致命）", t);
        }
    }

    /**
     * 在线态巡检（5s）：心跳窗口过期/连接死亡 → 会话摘除 → 桥回落。
     * public 为同模块守护测试缝（T-PS4-03）。
     *
     * T-ReachAudit T-RA-01：摘除面由 {@code !alive} 扩为 {@code !isSessionHealthy}——半死会话（socket 在、心跳停）
     * 此前永远不会被摘除，控制台桥也就永远回不来；现按协议语义在窗口过期后摘除并回落。
     */
    public void sweepOffline() {
        java.util.List<AgentSession> dead = new java.util.ArrayList<>();
        for (AgentSession session : sessions) {
            if (!isSessionHealthy(session)) {
                dead.add(session);
            }
        }
        if (!dead.isEmpty()) {
            // 仍 alive 却过期 = 心跳停摆（应用侧卡死/织入异常），与 socket 断开区分开供状态行叙述
            boolean anyAlive = false;
            for (AgentSession session : dead) {
                if (session.isAlive()) {
                    anyAlive = true;
                    break;
                }
            }
            heartbeatDropDetected.set(anyAlive);
            for (AgentSession session : dead) {
                session.close();
            }
            sessions.removeAll(new java.util.HashSet<>(dead));
            // T-PS4-03：死会话顺带清幂等表——pid 对应进程已消亡，残留会让该 pid 复用/重启后
            // 无法重挂；shutdownAll 的全表清空语义保留不动。
            for (AgentSession session : dead) {
                attachedPids.remove(session.getPid());
            }
            updateOnline();
            // T-PS5-04：会话摘除后推断 URL 同步（全摘完 → 清条目，推断回落数据源 URL）
            onSessionLifecycleChanged();
            // T-SettingsTruth P2：掉线摘除 → 状态行「连接中断，已回落控制台捕获」
            publishStatusChanged();
        }
    }

    private boolean isEnhancedActive() {
        return isEnhancedEnabled() && isEnhancedConfirmed();
    }

    /** 开关已开且项目未销毁（不含确认态）。 */
    public boolean isEnhancedEnabled() {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        return state.getEnhancedCaptureEnabled() && !project.isDisposed();
    }

    /** 注入确认已达成（同意过一次即持久为 true）。 */
    public boolean isEnhancedConfirmed() {
        return PluginSettings.getInstance().getState().getEnhancedCaptureConfirmed();
    }

    /** 关闭全部待回连通道（shutdownAll/dispose 共用）。accept 线程会因 socket 关闭而退出。 */
    private void closePremainChannels() {
        for (PremainChannel channel : premainChannels) {
            try {
                channel.server.close();
            } catch (Throwable ignored) {
                // 对齐 Kotlin runCatching：关闭失败不影响清空
            }
        }
        premainChannels.clear();
    }

    /**
     * T-CaptureTruth T-CT-02：是否处于「开关开着但未授权」（含拒绝冷却期）——设置页据此露出
     * 重新授权按钮。区别于 {@link #isEnhancedActive}：未授权态下增强功能实际不工作，状态行与按钮
     * 就是为了把这一真相摆到用户面前。
     */
    public boolean needsReauthorization() {
        return isEnhancedEnabled() && !isEnhancedConfirmed();
    }

    /** 关闭开关：CONFIG 下发停采集（禁用不卸载语义）→ 会话关闭 → 桥回落。 */
    public void shutdownAll() {
        for (AgentSession session : sessions) {
            session.sendConfig(true);
        }
        for (AgentSession session : sessions) {
            session.close();
        }
        sessions.clear();
        attachedPids.clear();
        // T-PS4-03：关闭开关同时清排队去抖表——关后不应残留排队记忆（3s 窗内的重复启动照常重判）
        pendingPids.clear();
        // T-M56Phase2-Premain：待回连通道一并关掉（关后即便 agent 回连也会被 isEnhancedActive 挡下，
        // 此处提前关是防 socket 挂到超时才回收）
        closePremainChannels();
        updateOnline();
        // T-PS5-04：会话全清 → 推断 URL 清条目（方言推断回落数据源 URL）
        onSessionLifecycleChanged();
        // T-SettingsTruth P2：回落链完成 → 状态行「连接中断，已回落控制台捕获」
        publishStatusChanged();
    }

    /** 脱敏开关下发（设置变更）。disabled 跟随当前监听状态（B2：不写死 false——监听关闭时改设置不得重置为采集）。 */
    public void pushDesensitize(boolean desensitize) {
        boolean active = isListenActive();
        for (AgentSession session : sessions) {
            session.sendConfig(!active, desensitize);
        }
    }

    // T-PS4-14③：pushFrameSkipPrefixes 死代码已删除（全仓库零调用）——帧过滤前缀的真实下发链为
    // SETTINGS_CHANGE_TOPIC 监听（本类 init）→ executor → pushDesensitize/sendConfig（sendConfig 读设置现拼 skipPrefixes）。

    /**
     * 监听开关联动（T-AgentListenSync）：停止监听 → agent 停采（真停，非 IDE 侧丢弃）；
     * 开启监听 → 恢复采集。由 SqlLogManager.startCapture/stopCapture 调用。
     * agent 侧 CONFIG 即时生效（volatile 标志），织入点常驻，恢复后下一条 SQL 完整采集。
     */
    public void pushCaptureActive(boolean active) {
        if (!isEnhancedActive()) {
            return;
        }
        boolean desensitize = PluginSettings.getInstance().getState().getEnhancedCaptureDesensitize();
        for (AgentSession session : sessions) {
            session.sendConfig(!active, desensitize);
        }
        LOG.info("pushCaptureActive: active=" + active + " → disabled=" + !active + "（sessions=" + sessions.size() + "）");
    }

    /** 当前 SQL 监听是否开启（SqlLogManager.capturing；服务缺失/异常按 false——宁停采勿白采）。 */
    public boolean isListenActive() {
        try {
            return project.getService(SqlLogManager.class).isCapturing();
        } catch (Throwable e) {
            return false;
        }
    }

    // ---------- pid 解析（复用桥反射骨架；getNativePid 能力探测缺失回退 getProcess().pid()） ----------

    public Long resolvePid(Object processHandler) {
        try {
            if (processHandler == null) {
                return null;
            }
            // 1) KillableProcessHandler.getNativePid()（262+ 平台；243 实测无此方法）
            Long nativePid = null;
            try {
                Method m = null;
                for (Method candidate : processHandler.getClass().getMethods()) {
                    if ("getNativePid".equals(candidate.getName()) && candidate.getParameterCount() == 0) {
                        m = candidate;
                        break;
                    }
                }
                if (m != null) {
                    Object result = m.invoke(processHandler);
                    if (result instanceof Number) {
                        nativePid = ((Number) result).longValue();
                    }
                }
            } catch (Throwable ignored) {
                // 能力探测失败 → 落下一步回退
            }
            if (nativePid != null) {
                return nativePid;
            }
            // 2) getProcess() 直接接口调用 pid()（Java 9+）——禁反射 invoke：ProcessImpl 是 JDK
            //    package-private 类，getMethod("pid").invoke 会抛 IllegalAccessException
            //    （测试匿名子类可访问故测试通过、真实环境失败的根因）
            Long processPid = null;
            try {
                Object raw = processHandler.getClass().getMethod("getProcess").invoke(processHandler);
                if (raw instanceof Process) {
                    processPid = ((Process) raw).pid();
                }
            } catch (Throwable ignored) {
                // 落下一步回退
            }
            if (processPid != null) {
                return processPid;
            }
            // 3) OSProcessUtil.getProcessID(Process)（243 平台稳定 API，util.jar）
            Long osPid = null;
            try {
                Object raw = processHandler.getClass().getMethod("getProcess").invoke(processHandler);
                if (raw instanceof Process) {
                    osPid = (long) OSProcessUtil.getProcessID((Process) raw);
                }
            } catch (Throwable ignored) {
                // 落最后一步留痕
            }
            if (osPid != null) {
                return osPid;
            }
            String processRepr;
            String procErr;
            try {
                Object p = processHandler.getClass().getMethod("getProcess").invoke(processHandler);
                processRepr = p != null ? p.getClass().getName() : "null";
                procErr = null;
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) throw (Error) t; // T-FP-02：fatal 透传
                processRepr = "N/A";
                procErr = t.toString();
            }
            LOG.warn("resolvePid: 三步回退全失败（handler=" + processHandler.getClass().getName()
                + ", process=" + processRepr + ", procErr=" + procErr + "）");
            return null;
        } catch (Throwable e) {
            // T-FatalPassthrough T-FP-02b（独立复核同族抵消点）：本函数外层 runCatching 会接住上面捕获块的
            // fatal 透传——不在此处放行等于透传无效（改前：fatal 落成 pid=null，调用方只 warn
            // "resolvePid 失败——跳过挂载"）。非 fatal 仍返回 null（pid 解析失败语义逐字不变）；fatal 经
            // doAttachAsync → onProcessAppeared → 监听器（RunConfigurationProcessListener:135）再透传，
            // 交给平台记录现场。
            if (FatalErrors.isFatal(e)) throw (Error) e;
            return null;
        }
    }

    /** agent 失败显式上报（禁静默吞）：桥侧继续捕获，用户无感；日志 WARN 留排查锚点。 */
    private void notifyAgentFailure(String message) {
        LOG.warn(message);
    }

    @Override
    public void dispose() {
        try {
            for (AgentSession session : sessions) {
                session.close();
            }
            sessions.clear();
            // T-PS5-04：项目销毁 → 推断 URL 条目清除（不引入跨项目残留）
            onSessionLifecycleChanged();
            // T-PS4-03：服务随 project 生命周期，去抖/幂等表一并清空（不引入跨项目残留）
            pendingPids.clear();
            attachedPids.clear();
            // T-M56Phase2-Premain：待回连通道随项目销毁一并关闭
            closePremainChannels();
            executor.shutdownNow();
            monitor.shutdownNow();
            SqlCaptureGate.paused.set(false);
        } catch (Throwable ignored) {
            // 对齐 Kotlin runCatching：dispose 尽力而为
        }
    }
}
