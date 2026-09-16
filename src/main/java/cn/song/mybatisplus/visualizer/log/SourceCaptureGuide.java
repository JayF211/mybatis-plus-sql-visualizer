package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.SqlLogConfigurable;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * T-JumpCapture：源码位置缺失时的引导编排（实时日志 / 历史面板「跳转到源码」共用）。
 *
 * 跳转源码完全依赖 {@code SqlSourceInterceptor} 捕获的真实调用位置（F-44-6/7）；
 * 条目无捕获位置时不再按表名搜索 Wrapper（同表名 ≠ 同调用点，命中即误导，已整体移除），
 * 而是弹框说明原因并给两个出口：打开「SQL 日志」设置页（源码捕获开关所在页），
 * 或立即在项目中注入拦截器代码。注入结果四类弹窗亦收敛于此（原设置页硬编码文案 i18n 化迁入）。
 *
 * <p>master 的 {@code object SourceCaptureGuide} 的 Java 承载（全 static 形态）。</p>
 */
public final class SourceCaptureGuide {

    private SourceCaptureGuide() {
    }

    /** 引导弹框用户选择（纯函数映射，测试驱动不触 UI）。 */
    public enum GuideChoice { OpenSettings, InjectNow, Cancel }

    /** 注入防重入（弹框与设置页共用本编排，检测期间不重复触发）。 */
    private static final AtomicBoolean injectionInProgress = new AtomicBoolean(false);

    /** showDialog 返回码 → 选择（数组序 0=打开设置 1=立即注入 2=取消；关闭/ESC 视同取消）。 */
    public static GuideChoice resolveChoice(int returnCode) {
        if (returnCode == 0) {
            return GuideChoice.OpenSettings;
        }
        if (returnCode == 1) {
            return GuideChoice.InjectNow;
        }
        return GuideChoice.Cancel;
    }

    /** 无捕获位置时的引导弹框（实时日志 sourceFile==null / 历史面板 NoSource 共用入口）。 */
    public static void showMissingSourceDialog(Project project) {
        int rc = Messages.showDialog(
                project,
                MpSqlVisualizerBundle.message("log.jump.guide.message"),
                MpSqlVisualizerBundle.message("log.jump.guide.title"),
                new String[]{
                        MpSqlVisualizerBundle.message("log.jump.guide.openSettings"),
                        MpSqlVisualizerBundle.message("log.jump.guide.injectNow"),
                        MpSqlVisualizerBundle.message("log.jump.guide.cancel"),
                },
                0,
                Messages.getQuestionIcon());
        GuideChoice choice = resolveChoice(rc);
        if (choice == GuideChoice.OpenSettings) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, SqlLogConfigurable.class);
        } else if (choice == GuideChoice.InjectNow) {
            runInjection(project);
        }
        // GuideChoice.Cancel -> Unit：无操作
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
            Messages.showWarningDialog(
                    project,
                    MpSqlVisualizerBundle.message("log.inject.unsupported"),
                    title);
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
}
