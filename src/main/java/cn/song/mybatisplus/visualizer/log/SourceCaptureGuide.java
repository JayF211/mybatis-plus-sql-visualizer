package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.EnhancedCaptureConsent;
import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.SqlLogConfigurable;
import cn.song.mybatisplus.visualizer.log.agent.AgentManager;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * T-JumpCapture / O-66 T-GuideEnhancedFirst：源码位置缺失时的引导编排（实时日志 / 历史面板「跳转到源码」共用）。
 *
 * <p>跳转源码完全依赖捕获的真实调用位置（F-44-6/7）——两条采集通道都带：控制台通道靠
 * {@code SqlSourceInterceptor} 的标记行，增强捕获（agent 通道）靠应用 JVM 内 StackLocator 直取
 * （SqlEvent.sourceFile → AgentEventAdapter 直通面板条目）。条目无捕获位置时不再按表名搜索
 * Wrapper（同表名 ≠ 同调用点，命中即误导，已整体移除），而是弹框按<b>全局增强捕获状态</b>给出口：
 *
 * <ul>
 * <li>未开启 / 未授权（{@link GuideBranch#EnhanceFirst}）：默认按钮让位「开启增强捕获」——不改工程代码、
 *     同意一次授权 + 重启应用即生效，对用户最省事；注入拦截器降为次选。</li>
 * <li>已开启已授权（{@link GuideBranch#Legacy}）：维持原两出口（打开设置 / 注入拦截器），此时缺位置
 *     另有原因（帧过滤前缀命中 / 应用未重启 / agent 未挂载），message 末尾附一行实时状态（D1）。</li>
 * </ul>
 *
 * <p>注入结果四类弹窗亦收敛于此（原设置页硬编码文案 i18n 化迁入）；其中 UnsupportedProject 在
 * EnhanceFirst 分支复用本编排引导开启增强捕获（20260924 定案：不再指路手动配置），Legacy 维持普通警告。
 *
 * <p>master 的 {@code object SourceCaptureGuide} 的 Java 承载（全 static 形态）。</p>
 */
public final class SourceCaptureGuide {

    private SourceCaptureGuide() {
    }

    /** 引导弹框用户选择（纯函数映射，测试驱动不触 UI）。 */
    public enum GuideChoice { EnableEnhanced, OpenSettings, InjectNow, Cancel }

    /** 引导分支（{@link #decideActions} 产出）：EnhanceFirst＝增强捕获未开启/未授权，Legacy＝已开启已授权。 */
    public enum GuideBranch { EnhanceFirst, Legacy }

    /** 注入防重入（弹框与设置页共用本编排，检测期间不重复触发）。 */
    private static final AtomicBoolean injectionInProgress = new AtomicBoolean(false);

    /**
     * 全局增强捕获状态 → 引导分支（纯函数）。
     * 增强捕获未开启或未授权时，它是携带源码位置的通道里对用户最省事的一条（不注入工程代码），
     * 默认按钮让位；已开启已授权时开关不再是原因，维持原出口。
     */
    public static GuideBranch decideActions(boolean enabled, boolean confirmed) {
        return enabled && confirmed ? GuideBranch.Legacy : GuideBranch.EnhanceFirst;
    }

    /**
     * showDialog 返回码 → 选择，<b>序表随分支</b>（O-66）：EnhanceFirst 0=开启增强捕获，
     * Legacy 0=打开设置；两分支 1=立即注入。分支参数是硬约束——两条序表 0 号语义不同，
     * 无分支映射会把「开启增强捕获」错路由成「打开设置」。关闭/ESC/负值一律兜底取消。
     */
    public static GuideChoice resolveChoice(int returnCode, GuideBranch branch) {
        if (returnCode == 0 && branch == GuideBranch.EnhanceFirst) {
            return GuideChoice.EnableEnhanced;
        }
        if (returnCode == 0) {
            return GuideChoice.OpenSettings;
        }
        if (returnCode == 1) {
            return GuideChoice.InjectNow;
        }
        return GuideChoice.Cancel;
    }

    /**
     * Legacy 分支 message＝基础文案末尾附一行增强捕获实时状态（D1 定案；复用链路诊断键
     * log.diag.enhanced）。无读数（null/空白）时原样返回——分支语义已由按钮表达，不硬凑一行。
     */
    public static String legacyMessage(String baseMessage, String enhancedStatus) {
        if (enhancedStatus == null || enhancedStatus.isBlank()) {
            return baseMessage;
        }
        return baseMessage + "\n\n" + MpSqlVisualizerBundle.message("log.diag.enhanced", enhancedStatus);
    }

    /** 无捕获位置时的引导弹框（实时日志 sourceFile==null / 历史面板 NoSource 共用入口）。 */
    public static void showMissingSourceDialog(Project project) {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        GuideBranch branch = decideActions(state.getEnhancedCaptureEnabled(), state.getEnhancedCaptureConfirmed());
        String message;
        if (branch == GuideBranch.EnhanceFirst) {
            message = MpSqlVisualizerBundle.message("log.jump.guide.message.enhanceFirst");
        } else {
            String status;
            try {
                status = AgentManager.getInstance(project).statusSummary();
            } catch (Throwable t) {
                status = null; // runCatching → getOrNull 语义：读不到状态行就原样返回基础文案
            }
            message = legacyMessage(MpSqlVisualizerBundle.message("log.jump.guide.message"), status);
        }
        String[] buttons;
        if (branch == GuideBranch.EnhanceFirst) {
            buttons = new String[]{
                    MpSqlVisualizerBundle.message("log.jump.guide.enableEnhanced"),
                    MpSqlVisualizerBundle.message("log.jump.guide.injectNow"),
                    MpSqlVisualizerBundle.message("log.jump.guide.cancel"),
            };
        } else {
            buttons = new String[]{
                    MpSqlVisualizerBundle.message("log.jump.guide.openSettings"),
                    MpSqlVisualizerBundle.message("log.jump.guide.injectNow"),
                    MpSqlVisualizerBundle.message("log.jump.guide.cancel"),
            };
        }
        int rc = Messages.showDialog(
                project,
                message,
                MpSqlVisualizerBundle.message("log.jump.guide.title"),
                buttons,
                0,
                Messages.getQuestionIcon());
        GuideChoice choice = resolveChoice(rc, branch);
        if (choice == GuideChoice.EnableEnhanced) {
            enableEnhancedCapture(project);
        } else if (choice == GuideChoice.OpenSettings) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, SqlLogConfigurable.class);
        } else if (choice == GuideChoice.InjectNow) {
            runInjection(project);
        }
        // GuideChoice.Cancel -> Unit：无操作
    }

    /**
     * {@link #enableEnhancedCapture(Project, kotlin.jvm.functions.Function1)} 的默认回调形态
     * （master 默认参数 onOutcome：同意 → 展示生效提示）。
     */
    public static void enableEnhancedCapture(Project project) {
        enableEnhancedCapture(project, consent -> {
            if (consent == EnhancedCaptureConsent.ACCEPTED) {
                showEnhanceDone(project);
            }
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * 「开启增强捕获」动作（O-66，EnhanceFirst 分支默认按钮触发）。
     *
     * <p>总开关关闭时先置 enabled=true 并发布 {@link PluginSettings#SETTINGS_CHANGE_TOPIC}——与设置页
     * Apply 同通道，AgentManager 的「关闭→再开启」上升沿据此清零拒绝冷却、CONFIG 下发链跟随；
     * 已开启时不得重复发布（设置页面板的 isModified 对比读同一 state，乱发布会制造脏态假象）。
     * 随后复用 {@link AgentManager#requestReauthorization} 确认流（清冷却 + 弹确认框 + 三态持久化全部
     * 单点收在 AgentManager，本编排只经 onOutcome 消费结果，不复写状态机）：
     * 同意 → 展示「重启应用后生效」提示（无句柄不挂载，挂载由下一次应用进程启动承接——本动作
     * 不碰已在运行的应用）；拒绝 / 关闭增强 → 静默（尊重 24h 冷却与关闭语义，不再打扰）。
     */
    public static void enableEnhancedCapture(
            Project project,
            kotlin.jvm.functions.Function1<? super EnhancedCaptureConsent, kotlin.Unit> onOutcome) {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        if (!state.getEnhancedCaptureEnabled()) {
            state.setEnhancedCaptureEnabled(true);
            ApplicationManager.getApplication().getMessageBus()
                    .syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged();
        }
        AgentManager.getInstance(project).requestReauthorization(onOutcome);
    }

    /** 同意后的生效提示（title 复用确认框标题键，语义同属「启用增强捕获」）。 */
    private static void showEnhanceDone(Project project) {
        Messages.showInfoMessage(
                project,
                MpSqlVisualizerBundle.message("log.jump.guide.enhanceDone"),
                MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.title"));
    }

    /**
     * 触发拦截器注入（EDT 调用安全：检测阶段在 {@link SqlSourceInterceptorAutoInjector#injectAsync} 后台执行）。
     * master 的 {@code onResult} 默认参数重载：结果回调缺省展示结果弹窗。
     *
     * @return true = 已受理；false = 上一次注入仍在进行
     */
    public static boolean runInjection(Project project) {
        return runInjection(project, result -> showInjectionResult(project, result));
    }

    /**
     * 触发拦截器注入（EDT 调用安全：检测阶段在 {@link SqlSourceInterceptorAutoInjector#injectAsync} 后台执行）。
     *
     * @param onResult 结果回调（EDT）；防重入期间被拒绝时不回调
     * @return true = 已受理；false = 上一次注入仍在进行
     */
    public static boolean runInjection(Project project, Consumer<InjectionResult> onResult) {
        if (!injectionInProgress.compareAndSet(false, true)) {
            return false;
        }
        SqlSourceInterceptorAutoInjector.injectAsync(project, result -> {
            injectionInProgress.set(false);
            onResult.accept(result);
        });
        return true;
    }

    /**
     * 展示注入结果弹窗（四类结果统一文案，双语 bundle）。
     *
     * @return true = 注入成功（设置页据此决定是否保留勾选态）
     */
    public static boolean showInjectionResult(Project project, InjectionResult result) {
        String title = MpSqlVisualizerBundle.message("log.inject.title");
        if (result instanceof InjectionResult.Success) {
            Messages.showInfoMessage(project, ((InjectionResult.Success) result).getMessage(), title);
            return true;
        }
        if (result instanceof InjectionResult.AlreadyExists) {
            Messages.showInfoMessage(
                    project,
                    MpSqlVisualizerBundle.message("log.inject.alreadyExists"),
                    title);
            return false;
        }
        if (result instanceof InjectionResult.UnsupportedProject) {
            PluginSettings.State state = PluginSettings.getInstance().getState();
            if (decideActions(state.getEnhancedCaptureEnabled(), state.getEnhancedCaptureConfirmed())
                    == GuideBranch.EnhanceFirst) {
                showUnsupportedWithEnhanceGuide(project, title);
            } else {
                Messages.showWarningDialog(
                        project,
                        MpSqlVisualizerBundle.message("log.inject.unsupported"),
                        title);
            }
            return false;
        }
        if (result instanceof InjectionResult.CannotDetectStructure) {
            Messages.showWarningDialog(
                    project,
                    MpSqlVisualizerBundle.message("log.inject.cannotDetect"),
                    title);
            return false;
        }
        // T-ModPick：用户在模块勾选弹框取消——静默返回，无结果弹窗（用户主动退出非异常态）
        if (result instanceof InjectionResult.Cancelled) {
            return false;
        }
        return false;
    }

    /**
     * 注入识别不了项目类型时的兜底引导（20260924 定案）：不再指路手动配置示例（对用户太重），
     * 默认按钮让位「开启增强捕获」——agent 通道不依赖项目类型、不改工程代码，与缺位置引导
     * （{@link #showMissingSourceDialog}）复用同一 {@link #enableEnhancedCapture} 编排；取消/关闭静默返回。
     * 仅 EnhanceFirst 分支进入（Legacy 已开启已授权，「开启增强捕获」不再是有效出口，维持普通警告）。
     */
    private static void showUnsupportedWithEnhanceGuide(Project project, String title) {
        int rc = Messages.showDialog(
                project,
                MpSqlVisualizerBundle.message("log.inject.unsupported.enhanceFirst"),
                title,
                new String[]{
                        MpSqlVisualizerBundle.message("log.jump.guide.enableEnhanced"),
                        MpSqlVisualizerBundle.message("log.jump.guide.cancel"),
                },
                0,
                Messages.getWarningIcon());
        if (rc == 0) {
            enableEnhancedCapture(project);
        }
    }
}
