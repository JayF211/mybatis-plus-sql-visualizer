package cn.song.mybatisplus.visualizer.log.agent;

import cn.song.mybatisplus.visualizer.config.EnhancedCaptureConfirmDialog;
import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener;
import cn.song.mybatisplus.visualizer.log.LogTimeCorrelator;
import cn.song.mybatisplus.visualizer.log.SqlCaptureGate;
import cn.song.mybatisplus.visualizer.log.SqlLogManager;
import com.intellij.execution.process.OSProcessUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

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

    /** 获取 project 级服务实例。 */
    public static AgentManager getInstance(Project project) {
        return project.getService(AgentManager.class);
    }

    private final Project project;

    /** 在线态：任一会话活跃且最近窗口内有心跳/事件。UI 状态行/接管切换依据。 */
    private volatile boolean online = false;

    private final CopyOnWriteArrayList<AgentSession> sessions = new CopyOnWriteArrayList<>();

    /** 幂等表：pid → attach 时间戳（同一 pid 不二次 attach）。 */
    private final ConcurrentHashMap<Long, Long> attachedPids = new ConcurrentHashMap<>();

    /** 惰性挂载去抖：pid → 上次尝试时间（3s 内重复启动不重复 attach）。 */
    private final ConcurrentHashMap<Long, Long> pendingPids = new ConcurrentHashMap<>();

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

    public AgentManager(Project project) {
        this.project = project;
        monitor.scheduleWithFixedDelay(this::sweepOffline, 5, 5, TimeUnit.SECONDS);
        // T-M56-08：设置变更驱动——开启（已确认）→ 惰性挂载由进程出现回调承接；
        // 关闭 → CONFIG 停采集 + 会话关闭 + 桥回落；脱敏开关 → CONFIG 下发
        project.getMessageBus().connect(this).subscribe(
            PluginSettings.SETTINGS_CHANGE_TOPIC,
            (SettingsChangeListener) () -> {
                PluginSettings.State state = PluginSettings.getInstance().getState();
                if (!state.getEnhancedCaptureEnabled()) {
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
     * 进程出现回调（RunConfigurationProcessListener.processStarted 转接；增强未开启时空操作）。
     * 后台线程：进程就绪等待 + 3s 去抖 + attach——全程不进 EDT。
     *
     * 默认开（2026-09-16 定案）后的确认闸门：enabled 默认 true 而 confirmed 默认 false——
     * 若沿用「仅设置页勾选时弹确认」，默认勾上的开关将永不弹框、永不 attach（死开关）。
     * 故未确认时在<b>首次挂载请求</b>处征求一次知情同意（EDT 弹框），同意后继续挂载。
     */
    public void onProcessAppeared(Object processHandler) {
        if (!isEnhancedEnabled()) {
            LOG.info("onProcessAppeared: 增强未开启（enabled="
                + PluginSettings.getInstance().getState().getEnhancedCaptureEnabled()
                + ", disposed=" + project.isDisposed() + "）——跳过挂载");
            return;
        }
        if (!isEnhancedConfirmed()) {
            LOG.info("onProcessAppeared: 增强已开启但未确认注入——请求确认后挂载");
            requestConsentThenAttach(processHandler);
            return;
        }
        doAttachAsync(processHandler);
    }

    /**
     * 首次挂载确认（EDT 弹一次；同意→置 confirmed 并继续挂载，拒绝→本次不注入）。
     * 确认结果持久化（重开不再弹）；headless 由 {@link #consentProvider} 默认实现直接拒绝。
     */
    private void requestConsentThenAttach(Object processHandler) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed() || !isEnhancedEnabled()) {
                return;
            }
            boolean accepted = false;
            try {
                accepted = consentProvider.getAsBoolean();
            } catch (Throwable t) {
                LOG.warn("requestConsentThenAttach: 确认框异常——按不同意处理", t);
            }
            if (accepted) {
                PluginSettings.getInstance().getState().setEnhancedCaptureConfirmed(true);
                LOG.info("requestConsentThenAttach: 用户同意注入——继续挂载");
                doAttachAsync(processHandler);
            } else {
                LOG.info("requestConsentThenAttach: 用户未同意注入——本次不挂载（下次运行再询问）");
            }
        });
    }

    /** 确认提供者（默认弹注入确认框，headless 恒拒绝；测试替换以驱动同意/拒绝分支）。 */
    public BooleanSupplier consentProvider = () -> {
        if (ApplicationManager.getApplication().isHeadlessEnvironment()) {
            return false;
        }
        return EnhancedCaptureConfirmDialog.confirm(null);
    };

    /** 实际挂载（后台线程：resolvePid → 3s 去抖 → attach）。 */
    private void doAttachAsync(Object processHandler) {
        executor.execute(() -> {
            try {
                Long pid = resolvePid(processHandler);
                if (pid == null) {
                    LOG.warn("onProcessAppeared: resolvePid 失败（handler="
                        + (processHandler != null ? processHandler.getClass().getName() : "null") + "）——跳过挂载");
                    return;
                }
                LOG.info("onProcessAppeared: 目标 pid=" + pid + "，3s 去抖后挂载");
                // 去抖 3s：等应用初始化热点过去（驱动已加载场景 retransform 直达；未加载场景 transformer 兜底）
                Thread.sleep(3000);
                attachToPid(pid);
            } catch (Throwable t) {
                LOG.warn("onProcessAppeared: 惰性 attach 失败（非致命，桥继续捕获）", t);
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
        return new AgentSession(project, pid, socket, this::updateOnline);
    }

    /** 解压 agent jar 到临时目录（deleteOnExit；失败 null 由调用方显式上报——禁静默吞）。 */
    private File extractAgentJar() {
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
                return out;
            }
        } catch (Throwable e) {
            return null;
        }
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

    // ---------- 会话/在线态 ----------

    private void updateOnline() {
        boolean wasOnline = online;
        boolean anyAlive = false;
        for (AgentSession session : sessions) {
            if (session.isAlive()) {
                anyAlive = true;
                break;
            }
        }
        online = anyAlive;
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

    private void sweepOffline() {
        java.util.List<AgentSession> dead = new java.util.ArrayList<>();
        for (AgentSession session : sessions) {
            if (!session.isAlive()) {
                dead.add(session);
            }
        }
        if (!dead.isEmpty()) {
            for (AgentSession session : dead) {
                session.close();
            }
            sessions.removeAll(new java.util.HashSet<>(dead));
            updateOnline();
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
        updateOnline();
    }

    /** 脱敏开关下发（设置变更）。disabled 跟随当前监听状态（B2：不写死 false——监听关闭时改设置不得重置为采集）。 */
    public void pushDesensitize(boolean desensitize) {
        boolean active = isListenActive();
        for (AgentSession session : sessions) {
            session.sendConfig(!active, desensitize);
        }
    }

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
                processRepr = "N/A";
                procErr = t.toString();
            }
            LOG.warn("resolvePid: 三步回退全失败（handler=" + processHandler.getClass().getName()
                + ", process=" + processRepr + ", procErr=" + procErr + "）");
            return null;
        } catch (Throwable e) {
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
            executor.shutdownNow();
            monitor.shutdownNow();
            SqlCaptureGate.paused.set(false);
        } catch (Throwable ignored) {
            // 对齐 Kotlin runCatching：dispose 尽力而为
        }
    }
}
