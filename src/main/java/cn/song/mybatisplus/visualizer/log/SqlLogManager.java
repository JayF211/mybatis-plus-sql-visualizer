package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import kotlin.Pair;

/**
 * 日志 SQL 条目管理（T-M31-03）：project 级轻服务——平台壳。
 *
 * 职责：持有纯逻辑缓冲 [SqlLogEntryBuffer]（条目/暂停/去重/过滤，可纯 JUnit 测试），
 * 经 [SqlLogConsoleBridge] 反射挂载运行控制台（execution API 不在编译 classpath，见桥 KDoc），
 * 并把缓冲变更 [notifyUi] 调度到 EDT 供面板刷新。
 *
 * T-M31.14：配置装配——parser 前缀/混合/最大长度、去重窗口、过滤关键字均来自 [PluginSettings]。
 *
 * F-44：不再自动打开独立窗口（SQL 日志已合并到主侧边栏 tab 中）；实时捕获仍运行，
 * 驱动 F-43 自动保存 + 主侧边栏 Tab 0 实时展示。
 */
@Service(Service.Level.PROJECT)
public class SqlLogManager implements Disposable {

    private final SqlLogEntryBuffer buffer = new SqlLogEntryBuffer(
            PluginSettings.getInstance().getState().getLogMaxCount());
    private SqlLogConsoleBridge bridge = new SqlLogConsoleBridge(
            buffer, this::parserConfig, this::applySettings, this::onConsoleAppeared, this::isCapturing);
    private final CopyOnWriteArrayList<Runnable> uiListeners = new CopyOnWriteArrayList<>();

    /** 自动保存历史 SQL（F-43）：buffer 淘汰回调的写入目标。 */
    private final SqlLogAutoSaver autoSaver = new SqlLogAutoSaver();

    /** 缓冲变更 → 面板刷新（EDT 调度）；仅面板订阅时登记（命名引用——可被 removeListener 精确移除）。 */
    private final Runnable uiNotifyListener = this::notifyUi;

    private final Project project;

    /** 捕捉状态（默认关——用户点击「开始捕捉」后才挂载监听）。 */
    private volatile boolean capturing;

    /** 用户主动开启 SQL 监听标志——点击「捕捉」后置 true，点击「停止」或 dispose 时清零。 */
    private volatile boolean userArmed;

    /** 是否处于「等待主控制台出现」中间态（测试断言）。 */
    private volatile boolean isWaitingForConsole;

    /** capturing 变化回调（面板注册 → 刷新工具栏按钮图标/文案）。 */
    private final CopyOnWriteArrayList<Runnable> capturingListeners = new CopyOnWriteArrayList<>();

    public SqlLogManager(Project project) {
        this.project = project;
        this.bridge.onConsoleRemoved = this::onConsoleRemoved;
    }

    /** T-xx：内部测试缝——注入受控桥（attach 恒成功/恒失败）验证 capturing 状态机；生产路径不调用。 */
    void installBridgeForTest(SqlLogConsoleBridge bridge) {
        this.bridge = bridge;
    }

    /** 仅挂载桥+轮询（不进入 armed 态）——suppressed=true 时检测项目启动用。 */
    public void startListeningOnly() {
        if (bridge.attach(project)) {
            bridge.startPolling();
            LOG.info("startListeningOnly: 桥挂载+轮询已启动（suppressed 模式，不进入 armed 态）");
        }
    }

    public void startCapture() {
        startCapture(false);
    }

    /**
     * 开始捕捉：挂载控制台监听 + 轮询兜底（幂等）。
     * 自动启动路径守卫：项目尚无运行中的主控制台时不进入捕捉态。
     */
    public void startCapture(boolean manual) {
        LOG.info("startCapture: 调用开始（capturing=" + capturing + ", waiting=" + isWaitingForConsole +
                ", manual=" + manual + ", project=" + project.getName() + "）");
        if (capturing && !isWaitingForConsole) {
            return; // 已在捕捉态（非等待）则幂等
        }
        if (manual) {
            PluginSettings.getInstance().getState().setLogAutoCaptureSuppressed(false);
        }
        buffer.setPaused(false);
        if (!hasMainConsole()) {
            capturing = true;
            if (manual) {
                userArmed = true;
            }
            isWaitingForConsole = true;
            LOG.info("startCapture: 无运行中的主控制台——进入 armed 态（已开启，等待项目运行, armed=" + userArmed + "）");
            if (bridge.attach(project)) {
                bridge.startPolling();
            }
            refreshToolbar();
            return;
        }
        boolean wasWaiting = isWaitingForConsole;
        isWaitingForConsole = false;
        if (!capturing) {
            capturing = true;
        }
        applySettings();
        if (bridge.attach(project)) {
            if (!wasWaiting) {
                bridge.startPolling();
            }
            bridge.scanAll();
            LOG.info("startCapture: 已开始捕捉 SQL（capturing=true, consoles=" + bridge.attachedConsoles.size() + "）");
            refreshToolbar();
        } else {
            LOG.warn("startCapture: 控制台监听挂载失败（execution API 不可用）——保持 armed 态，可稍后手动重试");
        }
    }

    /** 是否有活跃控制台（用于 apply() 判断是否立即启动监听）。 */
    public boolean hasConsoles() {
        if (!bridge.attachedConsoles.isEmpty()) {
            return true;
        }
        Object rcm = SqlLogConsoleBridge.runContentManagerOrNull(project);
        if (rcm == null) {
            return false;
        }
        try {
            Object raw = rcm.getClass().getMethod("getAllDescriptors").invoke(rcm);
            int count;
            if (raw instanceof Object[]) {
                count = ((Object[]) raw).length;
            } else if (raw instanceof Collection) {
                count = ((Collection<?>) raw).size();
            } else {
                count = 0;
            }
            return count > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否有进程存活的**主**运行控制台（ConsoleViewImpl；含进程存活检查，见桥实现）。 */
    public boolean hasMainConsole() {
        return bridge.hasMainConsole();
    }

    /**
     * 停止捕捉：重置 capturing 标志 + 移除所有控制台上的 Filter + 停止轮询 + 清空已挂载 console 记录。
     */
    public void stopCapture() {
        LOG.info("stopCapture: 调用开始（capturing=" + capturing + ", armed=" + userArmed +
                ", waiting=" + isWaitingForConsole + ", paused=" + buffer.isPaused() + "）");
        if (!capturing && !userArmed) {
            return; // armed 态也须幂等守卫
        }
        capturing = false;
        userArmed = false;
        isWaitingForConsole = false;
        buffer.setPaused(true);
        if (PluginSettings.getInstance().getState().getLogAutoCaptureOnStart()) {
            PluginSettings.getInstance().getState().setLogAutoCaptureSuppressed(true);
        }
        bridge.detachAllFilters();
        bridge.stopPolling();
        bridge.detachContentListener();
        refreshToolbar();
    }

    /** 注册 capturing 变化监听（面板调用）。 */
    public void addCapturingListener(Runnable listener) {
        capturingListeners.add(listener);
    }

    /** 强制刷新工具栏 action 状态（capturing 变化时按钮图标/文案同步）。 */
    private void refreshToolbar() {
        LOG.info("refreshToolbar: 通知 " + capturingListeners.size() + " 个监听器");
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Runnable it : capturingListeners) {
                it.run();
            }
        });
    }

    /** 新控制台出现（contentAdded / 轮询检测）时回调——armed 态时自动附着 filter 开始捕捉 SQL。 */
    public void onConsoleAppeared() {
        if (capturing && !isWaitingForConsole) {
            return; // 已在捕捉态（非等待）则幂等
        }
        boolean autoStart = PluginSettings.getInstance().getState().getLogAutoCaptureOnStart();
        boolean suppressed = PluginSettings.getInstance().getState().getLogAutoCaptureSuppressed();
        if (!autoStart && !userArmed) {
            return;
        }
        if (suppressed && !userArmed) {
            return;
        }
        LOG.info("onConsoleAppeared: 检测到新控制台，自动开始捕捉（autoStart=" + autoStart + ", suppressed=" + suppressed +
                ", armed=" + userArmed + ", waiting=" + isWaitingForConsole + ", project=" + project.getName() + "）");
        startCapture();
    }

    /** 已挂载的 console 集合（bridge 访问）。 */
    public Map<Object, Pair<Object, Class<?>>> getAttachedConsoles() {
        return bridge.attachedConsoles;
    }

    /** 控制台被移除（项目停止 / 用户关闭 tab）时回调。 */
    public void onConsoleRemoved() {
        if (!capturing || isWaitingForConsole) {
            LOG.debug("onConsoleRemoved: 非捕捉态或仍在等待（capturing=" + capturing + ", waiting=" + isWaitingForConsole + "），跳过");
            return;
        }
        boolean autoStart = PluginSettings.getInstance().getState().getLogAutoCaptureOnStart();
        if (userArmed && !autoStart) {
            LOG.info("onConsoleRemoved: 控制台被移除，回到 armed 态（手动开启，等待项目重新运行）");
            isWaitingForConsole = true;
            bridge.detachAllFilters();
        } else {
            LOG.info("onConsoleRemoved: 控制台被移除，停止捕捉（autoStart=" + autoStart + ", armed=" + userArmed + "）");
            capturing = false;
            bridge.detachAllFilters();
        }
        refreshToolbar();
    }

    /** 应用设置（前缀/去重窗口/过滤——捕捉中修改设置后重新装配生效）。 */
    public void applySettings() {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        buffer.setDedupeWindowMs(state.getLogDedupeWindowMs());
        List<String> kws = Arrays.stream(state.getLogFilterKeywords().split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        buffer.setFilterKeywords(kws);
        buffer.setMaxEntries(state.getLogMaxCount());
        if (state.getLogAutoSaveEnabled()) {
            autoSaver.configure(state.getLogAutoSavePath(), state.getLogAutoSaveFileNamePattern(), project.getBasePath());
            buffer.setOnEvicted(autoSaver::write);
        } else {
            buffer.setOnEvicted(null);
        }
    }

    /** UI 订阅刷新回调（回调在 EDT 执行；首个订阅者开始桥接缓冲变更）。 */
    public void addUiListener(Runnable listener) {
        if (uiListeners.isEmpty()) {
            buffer.addListener(uiNotifyListener);
        }
        uiListeners.add(listener);
    }

    public void removeUiListener(Runnable listener) {
        uiListeners.remove(listener);
        if (uiListeners.isEmpty()) {
            buffer.removeListener(uiNotifyListener);
        }
    }

    /** F-43 测试访问器：当前自动保存文件。 */
    File autoSaverCurrentFile() {
        return autoSaver.currentFile();
    }

    /** F-43 测试访问器：解析文件名模板。 */
    String autoSaverResolveFileName() {
        return autoSaver.resolveFileName();
    }

    /** 条目缓冲（面板与测试访问）。 */
    public SqlLogEntryBuffer getEntries() {
        return buffer;
    }

    public boolean isCapturing() {
        return capturing;
    }

    public boolean isUserArmed() {
        return userArmed;
    }

    public boolean isWaitingForConsole() {
        return isWaitingForConsole;
    }

    private void notifyUi() {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Runnable it : uiListeners) {
                it.run();
            }
        });
    }

    private ParserConfig parserConfig() {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        return new ParserConfig(
                state.getLogSqlPrefix(),
                state.getLogParamPrefix(),
                state.getLogMixedPrefix(),
                state.getLogMaxParseLength(),
                state.getLogHibernateSqlPrefix(),
                state.getLogHibernateParamPrefix(),
                state.getLogHibernateEnabled(),
                state.getLogJdbcEnabled());
    }

    /** 面板销毁/项目关闭时移除挂载（capturing 同步重置——状态机闭环）。 */
    @Override
    public void dispose() {
        userArmed = false;
        isWaitingForConsole = false;
        capturing = false;
        buffer.setPaused(true);
        bridge.detach();
        buffer.removeListener(uiNotifyListener);
        uiListeners.clear();
        autoSaver.close();
    }

    private static final Logger LOG = Logger.getInstance(SqlLogManager.class);

    /** 原 toolWindow id（F-44 后不再注册，保留常量供其他引用）。 */
    public static final String TOOL_WINDOW_ID = "cn.song.mybatisplus.visualizer.log";
}
