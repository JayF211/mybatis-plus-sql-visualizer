package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;

import java.awt.Component;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.swing.Timer;

import kotlin.Pair;

/**
 * 运行控制台反射桥（T-M31-03）：`com.intellij.execution.*`（RunContentManager/ConsoleView）
 * 不在插件编译 classpath（IPGP SDK 发行版裁剪 execution 模块），运行期由真实 IDE 提供——
 * 与既有 `execute/` 数据库链路同一隔离策略（build.gradle.kts 注释约定）。
 *
 * 全部调用经反射 + Proxy，任一步失败即优雅降级（返回 false / 静默跳过），不抛异常。
 *
 * T-xx（设置生效性审计）：`open` 开放子类化——[SqlLogManager] 内部测试缝（installBridgeForTest）
 * 注入受控桥（attach 恒成功/恒失败）验证 capturing 状态机，生产不受影响。
 */
public class SqlLogConsoleBridge {

    private final SqlLogEntryBuffer buffer;
    private final Supplier<ParserConfig> parserConfig;
    private final Runnable syncBufferConfig;
    private final Runnable onConsoleAppeared;
    private final BooleanSupplier isCapturing;

    private Object runContentManager;
    /**
     * 已挂载的控制台 → Filter 代理映射。
     * 值从 Boolean 改为 Pair<filter, ifaceClass>，供 detachAllFilters 移除 Filter 使用。
     *
     * T-Perf-22：synchronized 包装保留 IdentityHashMap 的同一性语义——EDT 轮询/事件写
     * （scanComponent/contentAdded）与 Filter 回调读（任意控制台出线程）并发访问普通
     * IdentityHashMap 构成并发修改（死循环/异常风险，窗口窄但真实）。
     */
    public final Map<Object, Pair<Object, Class<?>>> attachedConsoles =
            java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    /** 每个已挂载控制台 → 解析器上下文（含暂存源码位置，F-44-6）。 */
    private final Map<Object, BridgeLineContext> consoleContexts =
            java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    /** descriptor → 已解析 console 缓存（T-LogPerf-03：命中且已挂载时跳过组件树遍历）。 */
    private final IdentityHashMap<Object, Object> descriptorConsoleCache = new IdentityHashMap<>();
    /** 捕捉中逐行使用的 timePrefix 缓存（T-LogPerf-04：仅设置变更/挂载时刷新）。 */
    private volatile String timePrefixCache = null;

    private final ContentManagerListener contentListener = new ContentManagerListener() {
        @Override
        public void contentAdded(ContentManagerEvent event) {
            Component component = event.getContent().getComponent();
            LOG.info("contentAdded: " + component.getClass().getName());
            scanComponent(component);
            onConsoleAppeared.run();
        }

        @Override
        public void contentRemoved(ContentManagerEvent event) {
            LOG.info("contentRemoved: " + event.getContent().getComponent().getClass().getName());
            onConsoleRemoved.run();
        }
    };

    private Timer pollTimer;
    /** 高频补捞定时器（attach 后短暂运行，捕获第一条 SQL）。 */
    private Timer catchUpTimer;
    /** 当前快速轮询间隔（T-LogPerf-01：500ms 起步指数退避，上限=常规间隔）。 */
    private int currentFastDelayMs = FAST_POLL_INTERVAL_MS;
    /** 新控制台出现回调（由 manager 设置）。 */
    public Runnable onConsoleRemoved = () -> {
    };

    /** 上一轮是否检测到主运行控制台（ConsoleViewImpl——Spring Boot 等 run 进程）。 */
    private boolean lastHadMainConsole = false;

    /** ContentManagerListener 是否注册成功（243/253=成功，262+=失败→需轮询兜底）。 */
    private boolean contentListenerRegistered = false;

    public SqlLogConsoleBridge(SqlLogEntryBuffer buffer) {
        this(buffer,
                () -> new ParserConfig("Preparing:", "Parameters:", true, 20000),
                () -> {
                },
                () -> {
                },
                () -> true);
    }

    public SqlLogConsoleBridge(SqlLogEntryBuffer buffer, Supplier<ParserConfig> parserConfig,
                               Runnable syncBufferConfig, Runnable onConsoleAppeared, BooleanSupplier isCapturing) {
        this.buffer = buffer;
        this.parserConfig = parserConfig;
        this.syncBufferConfig = syncBufferConfig;
        this.onConsoleAppeared = onConsoleAppeared;
        this.isCapturing = isCapturing;
    }

    /**
     * 反射取 RunContentManager 实例（双包名候选）并挂载现有 console；环境缺 execution API 时返回 false。
     * T-xx：`open` 供测试受控桥覆写（attach 恒成功/恒失败）。
     */
    public boolean attach(Project project) {
        LOG.info("attach: 开始（project=" + project.getName() + ", runContentManager=" +
                (runContentManager != null ? runContentManager.getClass().getName() : "null") + "）");
        if (runContentManager != null) {
            LOG.info("attach: already attached");
            return true;
        }
        Object rcm = runContentManagerOrNull(project);
        if (rcm == null) {
            LOG.warn("attach: no RunContentManager candidate available (execution API missing in this environment)");
            return false;
        }
        runContentManager = rcm;
        attachContentListener(rcm);
        scanAll();
        LOG.info("attach: 成功 RCM=" + rcm.getClass().getName() + ", consolesAttached=" + attachedConsoles.size());
        return true;
    }

    /** 停止快速轮询定时器。 */
    private void stopCatchUpPolling() {
        if (catchUpTimer != null) {
            catchUpTimer.stop();
            catchUpTimer = null;
        }
    }

    /**
     * 启动轮询：无控制台时快速轮询（500ms 起步，每拍无主控制台间隔翻倍至 2s 封顶）
     * → 检测到新控制台后切换常规轮询（2s）。
     */
    public void startPolling() {
        if (catchUpTimer != null || pollTimer != null) {
            return;
        }
        startFastPolling();
        LOG.debug("startPolling: 快速轮询已启动（" + FAST_POLL_INTERVAL_MS + "ms，指数退避至 " + POLL_INTERVAL_MS + "ms）");
    }

    public void stopPolling() {
        if (pollTimer != null) {
            pollTimer.stop();
            pollTimer = null;
        }
        stopCatchUpPolling();
    }

    /** 轮询是否活跃（测试断言用：验证 stop 后轮询已停止）。 */
    public boolean isPollingActive() {
        return pollTimer != null || catchUpTimer != null;
    }

    /** 快速轮询（500ms 起步）—— 项目运行中但未检测到控制台时，尽快捕获第一条 SQL。 */
    private void startFastPolling() {
        stopCatchUpPolling();
        currentFastDelayMs = FAST_POLL_INTERVAL_MS;
        catchUpTimer = new Timer(currentFastDelayMs, e -> onCatchUpTick());
        catchUpTimer.setRepeats(true);
        catchUpTimer.start();
    }

    /** 快速轮询回调：先扫描（可能切走常规轮询），仍在等待且无主控制台则退避放宽下一拍。 */
    private void onCatchUpTick() {
        scanAll();
        if (catchUpTimer != null && !lastHadMainConsole) {
            currentFastDelayMs = nextFastDelay(currentFastDelayMs, true);
            catchUpTimer.setDelay(currentFastDelayMs);
        }
    }

    /** 常规轮询（2s）—— 已检测到控制台后，低频兜底。 */
    private void startNormalPolling() {
        stopCatchUpPolling();
        pollTimer = new Timer(POLL_INTERVAL_MS, e -> scanAll());
        pollTimer.setRepeats(true);
        pollTimer.start();
    }

    /** 移除挂载（项目关闭/面板销毁时）。 */
    public void detach() {
        stopPolling();
        detachAllFilters();
        Object rcm = runContentManager;
        if (rcm == null) {
            return;
        }
        try {
            rcm.getClass().getMethod("removeContentManagerListener", CONTENT_LISTENER_CLASS).invoke(rcm, contentListener);
        } catch (Throwable t) {
            LOG.debug("detach: removeContentManagerListener failed", t);
        }
        runContentManager = null;
    }

    /** ContentManagerListener 是否注册成功。 */
    public boolean isContentListenerRegistered() {
        return contentListenerRegistered;
    }

    private void attachContentListener(Object rcm) {
        try {
            rcm.getClass().getMethod("addContentManagerListener", CONTENT_LISTENER_CLASS).invoke(rcm, contentListener);
            contentListenerRegistered = true;
            LOG.info("attach: ContentManagerListener 注册成功（" + rcm.getClass().getName() + "）");
        } catch (Throwable t) {
            // 2026.2 接口无此方法（不 extends ContentManager）→ 轮询兜底
            contentListenerRegistered = false;
            LOG.warn("attach: ContentManagerListener 不可用（" + rcm.getClass().getName() + "）— 轮询兜底");
        }
    }

    /**
     * 实时查询 RunContentManager 当前是否有**进程存活的**主运行控制台。
     */
    public boolean hasMainConsole() {
        Object rcm = runContentManager;
        if (rcm == null) {
            return false;
        }
        Object[] descriptors = descriptorsOf(rcm);
        if (descriptors == null) {
            return false;
        }
        for (Object descriptor : descriptors) {
            Component component = componentOf(descriptor);
            if (component == null) {
                continue;
            }
            boolean isMain = component.getClass().getName().contains("ConsoleViewImpl") ||
                    !component.getClass().getName().contains("HttpSingleResponseConsole");
            if (!isMain) {
                continue;
            }
            if (isProcessAlive(descriptor)) {
                return true;
            }
        }
        return false;
    }

    /** 反射检查 RunContentDescriptor 关联的进程是否存活。 */
    private boolean isProcessAlive(Object descriptor) {
        if (descriptor == null) {
            return false;
        }
        try {
            Object processHandler = cachedMethod(descriptor.getClass(), "getProcessHandler").invoke(descriptor);
            if (processHandler == null) {
                return false;
            }
            Boolean terminated = (Boolean) cachedMethod(processHandler.getClass(), "isProcessTerminated").invoke(processHandler);
            Boolean terminating = (Boolean) cachedMethod(processHandler.getClass(), "isProcessTerminating").invoke(processHandler);
            boolean alive = (terminated == null || !terminated) && (terminating == null || !terminating);
            LOG.debug("isProcessAlive: alive=" + alive + ", descriptor=" + descriptor.getClass().getSimpleName());
            return alive;
        } catch (Throwable t) {
            LOG.debug("isProcessAlive: 反射失败（" + t.getClass().getSimpleName() + "），默认视为存活");
            return true;
        }
    }

    /**
     * 遍历 RunContentManager 全部运行描述符，对每个 console 挂载 filter（幂等）。
     */
    public void scanAll() {
        boolean hasMain = hasMainConsole();
        LOG.debug("scanAll: hasMainConsole=" + hasMain + ", lastHadMainConsole=" + lastHadMainConsole);
        if (lastHadMainConsole && !hasMain) {
            LOG.info("scanAll: 主控制台已移除（上轮有、当前无），触发 onConsoleRemoved");
            onConsoleRemoved.run();
        }
        if (!lastHadMainConsole && hasMain) {
            LOG.info("scanAll: 主控制台出现，触发 onConsoleAppeared");
            onConsoleAppeared.run();
            if (catchUpTimer != null) {
                LOG.info("scanAll: 检测到控制台，快速轮询 → 常规轮询（2s）");
                startNormalPolling();
            }
        }
        Object rcm = runContentManager;
        if (rcm == null) {
            return;
        }
        Object[] descriptors = descriptorsOf(rcm);
        if (descriptors == null) {
            return;
        }
        // 挂载 filter（幂等）；已挂载的 console 经缓存短路，不再遍历组件树（T-LogPerf-03）
        for (Object descriptor : descriptors) {
            if (descriptor == null) {
                continue;
            }
            Object cachedConsole = descriptorConsoleCache.get(descriptor);
            // T-Perf-22：keys 视图遍历须持 map 监视器——快照后判定
            List<Object> keysSnapshot;
            synchronized (attachedConsoles) {
                keysSnapshot = new java.util.ArrayList<>(attachedConsoles.keySet());
            }
            if (shouldSkipRescan(cachedConsole, keysSnapshot)) {
                continue;
            }
            Component component = componentOf(descriptor);
            if (component == null) {
                continue;
            }
            scanComponent(component);
            descriptorConsoleCache.put(descriptor, SqlLogConsoleBridgeKt.findConsoleView(component));
            if (descriptorConsoleCache.size() > DESCRIPTOR_CACHE_MAX) {
                descriptorConsoleCache.clear();
            }
        }
        lastHadMainConsole = hasMain;
    }

    /** 组件树中查找 ConsoleView 实现并挂载（供 contentAdded 与扫描复用）。 */
    public void scanComponent(Component component) {
        Object console = SqlLogConsoleBridgeKt.findConsoleView(component);
        if (console == null) {
            LOG.debug("scanComponent: no ConsoleView found under " + (component != null ? component.getClass().getName() : "null"));
            return;
        }
        attachFilter(console);
    }

    /**
     * T-LogPerf-04：设置变更事件驱动刷新——[SqlLogManager] 订阅 SETTINGS_CHANGE_TOPIC 后调用，
     * 一次同步全部已挂载上下文的解析器配置与 timePrefix 缓存，取代逐行 configProvider() 同步。
     */
    void refreshConsoleContexts() {
        ParserConfig cfg = parserConfig.get();
        // T-Perf-22：values 遍历持监视器（快照后应用，避免与 Filter 回调并发修改）
        List<BridgeLineContext> snapshot;
        synchronized (consoleContexts) {
            snapshot = new java.util.ArrayList<>(consoleContexts.values());
        }
        for (BridgeLineContext ctx : snapshot) {
            applyConfig(ctx, cfg);
        }
        String raw = PluginSettings.getInstance().getState().getLogTimePrefix();
        timePrefixCache = (raw != null && !raw.isBlank()) ? raw : null;
    }

    /** 把 [ParserConfig] 快照同步进 [BridgeLineContext]（挂载与设置变更共用）。 */
    private void applyConfig(BridgeLineContext ctx, ParserConfig cfg) {
        ctx.myBatisParser.setSqlPrefix(cfg.sqlPrefix());
        ctx.myBatisParser.setParamPrefix(cfg.paramPrefix());
        ctx.myBatisParser.setMixedPrefix(cfg.mixedPrefix());
        ctx.myBatisParser.setMaxParseLength(cfg.maxParseLength());
        ctx.hibernateParser.setSqlPrefix(cfg.hibernateSqlPrefix());
        ctx.hibernateParser.setParamPrefix(cfg.hibernateParamPrefix());
        ctx.hibernateParser.setMixedPrefix(cfg.mixedPrefix());
        ctx.hibernateParser.setMaxParseLength(cfg.maxParseLength());
        ctx.jdbcParser.setEnabled(cfg.jdbcEnabled());
        ctx.jdbcParser.setMaxParseLength(cfg.maxParseLength());
        ctx.hibernateEnabled = cfg.hibernateEnabled();
    }

    private void attachFilter(Object console) {
        // BUG FIX（停止后仍捕获）：非捕捉态时不挂载 Filter——守卫轮询/事件回调触发的 scanAll。
        if (!isCapturing.getAsBoolean()) {
            LOG.debug("attachFilter: 非捕捉态，跳过挂载 " + console.getClass().getName());
            return;
        }
        if (attachedConsoles.containsKey(console)) {
            LOG.debug("attachFilter: already attached " + console.getClass().getName());
            return;
        }
        for (String filterIfaceName : FILTER_IFACE_CANDIDATES) {
            try {
                Class<?> filterIface = Class.forName(filterIfaceName, true, platformClassLoader);
                ParserConfig config = parserConfig.get();
                // 三解析器：MyBatis + Hibernate + JdbcTemplate 并列运行，互不干扰（M47）
                MyBatisLogParser myBatisParser = new MyBatisLogParser(
                        new LogSqlRestorer(), config.sqlPrefix(), config.paramPrefix(), config.mixedPrefix(), config.maxParseLength());
                HibernateLogParser hibernateParser = new HibernateLogParser(
                        new LogSqlRestorer(), config.hibernateSqlPrefix(), config.hibernateParamPrefix(),
                        config.mixedPrefix(), config.maxParseLength(), true);
                JdbcTemplateLogParser jdbcParser = new JdbcTemplateLogParser(
                        new LogSqlRestorer(), config.jdbcEnabled(), config.maxParseLength());
                ParserConfig cfg0 = parserConfig.get();
                BridgeLineContext ctx = new BridgeLineContext(
                        new MyBatisLogParser(new LogSqlRestorer(), cfg0.sqlPrefix(), cfg0.paramPrefix(),
                                cfg0.mixedPrefix(), cfg0.maxParseLength()),
                        new HibernateLogParser(new LogSqlRestorer(), cfg0.hibernateSqlPrefix(), cfg0.hibernateParamPrefix(),
                                cfg0.mixedPrefix(), cfg0.maxParseLength(), true),
                        new JdbcTemplateLogParser(new LogSqlRestorer(), cfg0.jdbcEnabled(), cfg0.maxParseLength()),
                        parserConfig,
                        cfg0.hibernateEnabled());
                consoleContexts.put(console, ctx);
                // T-PreviewFlash：反射缓存 console.getEditor()（ConsoleView.getEditor 为 public）。
                // 取不到则守卫降级停用（editor 为 null → decide 恒 Process＝现状语义），绝不阻塞捕获。
                attachReplayGuard(console, ctx);
                // T-LogPerf-04：timePrefix 缓存化——设置变更走 refreshConsoleContexts，逐行不再读 PluginSettings
                String rawTimePrefix = PluginSettings.getInstance().getState().getLogTimePrefix();
                timePrefixCache = (rawTimePrefix != null && !rawTimePrefix.isBlank()) ? rawTimePrefix : null;
                Object filter = Proxy.newProxyInstance(
                        filterIface.getClassLoader(),
                        new Class<?>[]{filterIface},
                        new LogFilterProxyHandler((line, endOffset) -> {
                            BridgeLineContext context = consoleContexts.get(console);
                            if (context == null) {
                                return;
                            }
                            // T-PreviewFlash：重放守卫——rehighlight 全文档重跑 filter 时丢弃历史行（平台契约要求幂等）。
                            // 守卫依赖反射取到的编辑器文档（stamp/行0）；取不到（非真 ConsoleViewImpl，如测试桩）
                            // 则降级停用，直连现状语义——绝不能因守卫误丢正常流式行。
                            Supplier<Long> stampReader = context.docStampReader;
                            if (stampReader != null) {
                                if (context.replayGuard.decide(
                                        endOffset,
                                        stampReader.get(),
                                        line,
                                        // T-PerfSweep F1：行 0 提供者惰性传递——只有混合态分支才求值，
                                        // 流式/纯重放路径不再付 O(文档大小) 的全文档拷贝
                                        context.line0Reader)
                                        == ConsoleReplayGuard.Decision.Replay) {
                                    context.replayDropCount++;
                                } else {
                                    SqlLogConsoleBridgeKt.processConsoleLine(line, context, timePrefixCache, syncBufferConfig)
                                            .forEach(buffer::addEntry);
                                }
                            } else {
                                SqlLogConsoleBridgeKt.processConsoleLine(line, context, timePrefixCache, syncBufferConfig)
                                        .forEach(buffer::addEntry);
                            }
                        }));
                console.getClass().getMethod("addMessageFilter", filterIface).invoke(console, filter);
                attachedConsoles.put(console, new Pair<>(filter, filterIface));
                LOG.info("attachFilter: filter(" + filterIfaceName + ") attached to " + console.getClass().getName());
                return;
            } catch (Throwable t) {
                LOG.debug("attachFilter: candidate " + filterIfaceName + " failed on " + console.getClass().getName(), t);
            }
        }
        LOG.warn("attachFilter: all filter candidates failed on " + console.getClass().getName());
    }

    /**
     * T-PreviewFlash：为重放守卫解析编辑器/文档反射句柄。全部 try/catch 包裹——
     * 任一环失败即守卫降级停用（editor null），行为等同现状语义，绝不阻塞 Filter 挂载。
     * {@code getEditor} 实现类缺失该方法时 docStamp/line0 读取器为 null，守卫 decide 恒 Process。
     * T-PerfSweep F1：反射 Method 在挂载时解析一次（此前逐行 getMethod）；行 0 文本按
     * stamp 失效缓存——重放风暴中同 stamp 连续行只付一次 O(文档大小) 拷贝（缓存为
     * 尽力而为：Filter 回调线程间可见性无同步，最坏退化为多算一次拷贝，无正确性影响）。
     */
    private void attachReplayGuard(Object console, BridgeLineContext ctx) {
        try {
            java.lang.reflect.Method getEditor = console.getClass().getMethod("getEditor");
            Object editor = getEditor.invoke(console);
            if (editor == null) {
                return;
            }
            ctx.replayGuardEditor = editor;
            java.lang.reflect.Method getDoc = editor.getClass().getMethod("getDocument");
            final Object doc = getDoc.invoke(editor);
            if (doc == null) {
                return;
            }
            final java.lang.reflect.Method getStamp = doc.getClass().getMethod("getModificationStamp");
            final java.lang.reflect.Method getText = doc.getClass().getMethod("getText");
            ctx.docStampReader = () -> {
                try {
                    Object stamp = getStamp.invoke(doc);
                    return stamp instanceof Number n ? n.longValue() : Long.MIN_VALUE;
                } catch (Throwable t) {
                    return Long.MIN_VALUE;
                }
            };
            final long[] cachedStamp = {Long.MIN_VALUE};
            final String[] cachedLine0 = {null};
            ctx.line0Reader = () -> {
                try {
                    Object stampObj = getStamp.invoke(doc);
                    long stamp = stampObj instanceof Number n ? n.longValue() : Long.MIN_VALUE;
                    if (stamp != cachedStamp[0]) {
                        Object text = getText.invoke(doc);
                        String s = (text instanceof String str) ? str : "";
                        // 对齐 Kotlin lineSequence().firstOrNull()：空文档无首行返回 null；\r\n/\r/\n 任一换行
                        cachedLine0[0] = s.isEmpty() ? null : s.split("\r\n|\r|\n", 2)[0];
                        cachedStamp[0] = stamp;
                    }
                    return cachedLine0[0];
                } catch (Throwable t) {
                    return null;
                }
            };
        } catch (Throwable t) {
            // 守卫降级：句柄不可得时 docStampReader 保持 null → decide 恒 Process（现状语义）
        }
    }

    /**
     * BUG FIX（停止后仍捕获）：移除所有已挂载控制台上的 Filter。
     */
    public void detachAllFilters() {
        // T-Perf-22：entries 遍历持监视器快照（迭代体内 remove consoleContexts，不能在遍历
        // 同一 map 时持锁直改——快照后操作）
        List<Map.Entry<Object, Pair<Object, Class<?>>>> entriesSnapshot;
        synchronized (attachedConsoles) {
            entriesSnapshot = new java.util.ArrayList<>(attachedConsoles.entrySet());
        }
        for (Map.Entry<Object, Pair<Object, Class<?>>> e : entriesSnapshot) {
            Object console = e.getKey();
            consoleContexts.remove(console);
            Pair<Object, Class<?>> filterPair = e.getValue();
            Object filter = filterPair.getFirst();
            Class<?> filterIface = filterPair.getSecond();
            boolean removed = false;
            try {
                console.getClass().getMethod("removeMessageFilter", filterIface).invoke(console, filter);
                removed = true;
                LOG.info("detachAllFilters: filter removed from " + console.getClass().getName());
            } catch (Throwable t) {
                for (String candidateName : FILTER_IFACE_CANDIDATES) {
                    try {
                        Class<?> candidate = Class.forName(candidateName, true, platformClassLoader);
                        console.getClass().getMethod("removeMessageFilter", candidate).invoke(console, filter);
                        removed = true;
                        LOG.info("detachAllFilters: filter removed via fallback " + candidateName +
                                " from " + console.getClass().getName());
                        break;
                    } catch (Throwable ignored) {
                        // continue trying
                    }
                }
            }
            if (!removed) {
                LOG.warn("detachAllFilters: FAILED to remove filter from " + console.getClass().getName() +
                        " (filter will remain active!)");
            }
        }
        attachedConsoles.clear();
        // T-LogPerf-03：挂载记录清空后缓存全部失效，下一轮重新全量扫描
        descriptorConsoleCache.clear();
    }

    /**
     * BUG FIX（停止后仍捕获）：移除 contentManagerListener（243/253 平台）。
     */
    public void detachContentListener() {
        if (!contentListenerRegistered) {
            return;
        }
        Object rcm = runContentManager;
        if (rcm == null) {
            return;
        }
        try {
            rcm.getClass().getMethod("removeContentManagerListener", CONTENT_LISTENER_CLASS).invoke(rcm, contentListener);
            LOG.info("detachContentListener: contentManagerListener removed");
        } catch (Throwable t) {
            LOG.debug("detachContentListener: removeContentManagerListener failed", t);
        }
        contentListenerRegistered = false;
    }

    private static final Logger LOG = Logger.getInstance(SqlLogConsoleBridge.class);

    /** 双包名候选：2024.3/2025.3 在 execution 包，2026.2 移到 execution.ui 包（实证）。 */
    private static final List<String> RCM_CLASS_CANDIDATES = List.of(
            "com.intellij.execution.RunContentManager",
            "com.intellij.execution.ui.RunContentManager");
    /** Filter 接口双候选：243/253 为 ConsoleView 嵌套接口；2026.2 移除嵌套接口，addMessageFilter 参数改为独立接口。 */
    private static final List<String> FILTER_IFACE_CANDIDATES = List.of(
            "com.intellij.execution.ui.ConsoleView$Filter",
            "com.intellij.execution.filters.Filter");
    private static final Class<?> CONTENT_LISTENER_CLASS = ContentManagerListener.class;
    private static final int POLL_INTERVAL_MS = 2000; // 常规轮询间隔
    private static final int FAST_POLL_INTERVAL_MS = 500; // 快速轮询起步间隔——T-LogPerf-01：指数退避至常规间隔，不再 100ms 常驻
    private static final int DESCRIPTOR_CACHE_MAX = 64; // descriptor→console 缓存上限（超限整体失效防死引用滞留）

    /** 平台 classloader（execution API 在此 classloader 中，不在插件 classloader 中）。 */
    private static final ClassLoader platformClassLoader =
            com.intellij.openapi.project.ProjectManager.class.getClassLoader();

    /** 执行体系 API 在当前环境是否可用（测试环境/SDK 发行版为 false）。 */
    public static boolean isConsoleApiAvailable() {
        for (String it : RCM_CLASS_CANDIDATES) {
            try {
                Class.forName(it, true, platformClassLoader);
                return true;
            } catch (Throwable t) {
                // continue
            }
        }
        return false;
    }

    /** T-LogPerf-01：快速轮询指数退避（纯函数便于单测）——无主控制台每拍翻倍，上限=常规间隔；重置回落起步间隔。 */
    static int nextFastDelay(int currentMs, boolean backoff) {
        if (!backoff) {
            return FAST_POLL_INTERVAL_MS;
        }
        return Math.min(currentMs * 2, POLL_INTERVAL_MS);
    }

    /** T-LogPerf-03：短路判定（纯函数便于单测）——已知 console 且已挂载时跳过重新扫描。 */
    static boolean shouldSkipRescan(Object cachedConsole, Collection<?> attachedConsoles) {
        if (cachedConsole == null) {
            return false;
        }
        for (Object c : attachedConsoles) {
            if (c == cachedConsole) {
                return true;
            }
        }
        return false;
    }

    /** T-LogPerf-02：反射 Method 缓存（热路径每拍轮询不再重复 getMethod 查找；找不到也缓存 null）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method> methodCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static java.lang.reflect.Method cachedMethod(Class<?> clazz, String name) {
        String key = clazz.getName() + "#" + name;
        java.lang.reflect.Method cached = methodCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (methodCache.containsKey(key)) {
            return null; // 已缓存的 null
        }
        java.lang.reflect.Method m;
        try {
            m = clazz.getMethod(name);
        } catch (Throwable t) {
            m = null;
        }
        methodCache.put(key, m);
        return m;
    }

    private static Object[] descriptorsOf(Object rcm) {
        try {
            Object raw = cachedMethod(rcm.getClass(), "getAllDescriptors").invoke(rcm);
            if (raw instanceof Object[]) {
                return (Object[]) raw;
            }
            if (raw instanceof Collection) {
                return ((Collection<?>) raw).toArray();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Component componentOf(Object descriptor) {
        if (descriptor == null) {
            return null;
        }
        try {
            Object c = cachedMethod(descriptor.getClass(), "getComponent").invoke(descriptor);
            if (c instanceof Component) {
                return (Component) c;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object runContentManagerOrNull(Project project) {
        LOG.info("runContentManagerOrNull: 尝试解析 RCM（candidates=" + RCM_CLASS_CANDIDATES +
                ", cl=" + platformClassLoader.getClass().getSimpleName() + "）");
        for (String className : RCM_CLASS_CANDIDATES) {
            try {
                Class<?> clazz = Class.forName(className, true, platformClassLoader);
                LOG.info("runContentManagerOrNull: " + className + " 已加载，调用 getInstance");
                Object rcm = clazz.getMethod("getInstance", Project.class).invoke(null, project);
                if (rcm != null) {
                    LOG.info("attach: RCM resolved via " + className);
                    return rcm;
                }
                LOG.warn("runContentManagerOrNull: " + className + ".getInstance 返回 null");
            } catch (Throwable t) {
                LOG.warn("runContentManagerOrNull: " + className + " 不可用（" +
                        t.getClass().getSimpleName() + ": " + t.getMessage() + "）");
            }
        }
        return null;
    }
}
