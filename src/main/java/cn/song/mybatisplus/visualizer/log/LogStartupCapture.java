package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;

/**
 * 项目启动自动监听（T-M31.14）：设置「项目启动时自动开始监听」开启时，
 * 项目打开且已有运行中的主控制台时挂载监听（等价自动点击「捕捉」）；
 * 无运行控制台时 {@link SqlLogManager#startCapture} 守卫跳过——项目真正启动运行时由
 * onConsoleAppeared 自动捕捉。
 *
 * T-M51-04-DEL：删除 ProjectManagerListener 兜底（projectOpened 已 for-removal），
 * 仅保留 postStartupActivity 触发。startCapture() 幂等，onConsoleAppeared 兜底捕捉。
 */
public class LogStartupCapture implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(LogStartupCapture.class);
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 5000L;

    @Override
    public Object execute(Project project, Continuation<? super Unit> continuation) {
        doExecute(project);
        return Unit.INSTANCE;
    }

    private void doExecute(Project project) {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        LOG.info("LogStartupCapture.execute: 触发（project=" + project.getName()
                + ", logAutoCaptureOnStart=" + state.getLogAutoCaptureOnStart()
                + ", suppressed=" + state.getLogAutoCaptureSuppressed() + "）");

        // 自动启动条件：设置开启 且 用户未显式停止
        if (!shouldStartCapture(state.getLogAutoCaptureSuppressed(), state.getLogAutoCaptureOnStart(), true)) {
            return;
        }
        SqlLogManager manager = project.getService(SqlLogManager.class);
        if (state.getLogAutoCaptureSuppressed()) {
            // 用户已显式停止：仍挂载桥+轮询（检测项目启动），但不进入 armed 态
            LOG.info("LogStartupCapture: 用户已显式停止，仅挂载桥+轮询（project=" + project.getName() + "）");
            manager.startListeningOnly();
            return;
        }
        tryStartCapture(manager, project, 0);
    }

    private void tryStartCapture(SqlLogManager manager, Project project, int attempt) {
        LOG.info("LogStartupCapture.tryStartCapture: 第 " + (attempt + 1) + " 次尝试（project=" + project.getName() + "）");
        manager.startCapture();
        if (!manager.isCapturing() && attempt < MAX_RETRIES && !project.isDisposed()) {
            LOG.info("LogStartupCapture: 未生效，" + RETRY_DELAY_MS + "ms 后重试 (" + (attempt + 1) + "/" + MAX_RETRIES + ")");
            final int nextAttempt = attempt + 1;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                tryStartCapture(manager, project, nextAttempt);
            });
        } else if (manager.isCapturing()) {
            LOG.info("LogStartupCapture: 已成功进入捕捉态");
        }
    }

    /**
     * 判断是否应启动自动捕捉（测试缝）：纯函数，从 {@link #doExecute(Project)} 抽取的条件判断逻辑，
     * 供测试直接调用（无需 PSI 环境、无需 PluginSettings/SqlLogManager 服务）。
     *
     * 条件矩阵：
     *  - suppressed=true → 不启动（用户已显式停止）
     *  - suppressed=false, autoStart=true → 启动
     *  - suppressed=false, autoStart=false, hasOpenProject=true → 不启动（设置未开启）
     *  - suppressed=false, autoStart=false, hasOpenProject=false → 不启动（无打开项目）
     *
     * @param suppressed     用户是否已显式停止自动启动
     * @param autoStart      设置「项目启动时自动开始监听」是否开启
     * @param hasOpenProject 当前是否有打开的项目
     * @return true 应启动自动捕捉
     */
    public static boolean shouldStartCapture(boolean suppressed, boolean autoStart, boolean hasOpenProject) {
        if (!autoStart) {
            return false;
        }
        if (suppressed) {
            return false;
        }
        if (!hasOpenProject) {
            return false;
        }
        return true;
    }
}
