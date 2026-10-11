// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/onboarding/OnboardingAnchorTip.kt 移植
package cn.song.mybatisplus.visualizer.onboarding;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.util.messages.MessageBusConnection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

/**
 * 首用锚点一次性引导（T-UxOnboard T-UX-01）：
 *
 * <p>新用户「知道有好东西」的零成本触点——项目内首次打开含可预览 SQL（Wrapper 链 / XML mapper /
 * 注解 SQL 任一）的文件时，经预览组（BALLOON 非模态）弹一次「选中后按 Alt+Shift+Q 即可查看真实 SQL」。
 *
 * <p>触发约束（gutter 快速路径性能零变化是验收项）：
 * <ul>
 * <li>探测挂<b>低频入口</b>——postStartupActivity 订阅 FileEditorManagerListener.fileOpened
 *     （编辑器打开事件，天然节流），后台线程做一次文件文本启发式扫描；13 个 gutter 标记器的
 *     O(1) 快速路径一行不改；</li>
 * <li>一次性——「已展示」持久化到 {@link PluginSettings.State#setOnboardingAnchorTipShown}
 *     （application 级），重开项目/重启 IDE 不再出现，用户全生命期至多一条；</li>
 * <li>可关闭——设置页界面节开关（{@link PluginSettings.State#setOnboardingAnchorTipEnabled}，默认开），
 *     关闭后全程静默；</li>
 * <li>headless（isUnitTestMode）下持久化语义照常、不弹通知（SqlPreviewPanel.explainSql 同款守卫）。</li>
 * </ul>
 *
 * <p>启发式为纯文本子串匹配（{@link #anchorHintHit}）：首用引导允许极少量误报（后果仅是多弹一次一次性
 * 提示），换来零 PSI resolve / 零索引依赖——超限（{@link #MAX_PROBE_BYTES}）与非目标扩展名直接跳过。
 */
public class OnboardingAnchorTip implements ProjectActivity {

    /** Kotlin suspend execute 的 Java 侧等价形态（无实际挂起点，直接同步执行）。 */
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        // 连接绑定 project（Disposable），项目关闭自动注销——SqlPreviewService 同款形态。
        // T-PS5-15：句柄留用——引导**弹过即注销**（一次性语义下监听器已无使命；此前连接残留至
        // 项目关闭，此后每次文件打开仍付 shouldProbe/isProbeTarget 两次布尔判断）。
        MessageBusConnection connection = project.getMessageBus().connect(project);
        connection.subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new FileEditorManagerListener() {
                    @Override
                    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                        onFileOpened(project, file, connection);
                    }
                });
        return Unit.INSTANCE;
    }

    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /** 超大文件跳过探测（源码文件极少超过；封顶一次扫描成本）。 */
    private static final long MAX_PROBE_BYTES = 1_000_000L;

    /** 探测进行中节流（文件连续打开时只保留一个后台扫描）。 */
    private static final AtomicBoolean PROBING = new AtomicBoolean(false);

    /** 一次性展示权领取锁（并发探测命中只弹一条）。 */
    private static final Object CLAIM_LOCK = new Object();

    /**
     * 文件打开入口（EDT 回调）：守卫全走纯函数 {@link #shouldProbe} / {@link #isProbeTarget}，
     * 命中才投一个后台扫描（低频 + 廉价，热路径零涉足）。
     *
     * <p>Kotlin 默认参数 {@code connection: MessageBusConnection? = null} 的便捷重载
     * （null = 无连接上下文，仅测试直调）。
     */
    public static void onFileOpened(Project project, VirtualFile file) {
        onFileOpened(project, file, null);
    }

    /**
     * 文件打开入口（EDT 回调）：守卫全走纯函数 {@link #shouldProbe} / {@link #isProbeTarget}，
     * 命中才投一个后台扫描（低频 + 廉价，热路径零涉足）。
     *
     * @param connection 总线连接句柄（弹过后注销；null = 无连接上下文，仅测试直调）
     */
    public static void onFileOpened(Project project, VirtualFile file, MessageBusConnection connection) {
        PluginSettings.State settings = PluginSettings.getInstance().getState();
        if (!shouldProbe(settings.getOnboardingAnchorTipEnabled(), settings.getOnboardingAnchorTipShown())) return;
        if (!isProbeTarget(file.getName())) return;
        if (!PROBING.compareAndSet(false, true)) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                probeFile(project, file, connection);
            } finally {
                PROBING.set(false);
            }
        });
    }

    /** 后台扫描：读文本（ReadAction）→ 启发式命中 → 领取一次性展示权 → 弹通知。 */
    private static void probeFile(Project project, VirtualFile file, MessageBusConnection connection) {
        String text;
        try {
            text = ReadAction.compute(() -> {
                // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
                if (file.isValid() && file.getLength() >= 1 && file.getLength() <= MAX_PROBE_BYTES) {
                    return VfsUtil.loadText(file);
                }
                return "";
            });
        } catch (Throwable t) {
            text = "";
        }
        if (!anchorHintHit(file.getName(), text)) return;
        if (project.isDisposed()) return;
        if (!tryClaimAndMark()) return;
        // T-PS5-15：一次性展示权已领取（单飞点）→ 监听器完成使命，注销（幂等；项目关闭路径
        // 由 connect(project) 的 disposable 绑定兜底，语义不变）
        if (connection != null) connection.disconnect();
        if (ApplicationManager.getApplication().isUnitTestMode()) return; // headless：持久化已置位，通知跳过
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                Notification notification = NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP_ID)
                        .createNotification(
                                MpSqlVisualizerBundle.message("onboarding.anchorTip.title"),
                                MpSqlVisualizerBundle.message("onboarding.anchorTip.content"),
                                NotificationType.INFORMATION);
                // R21（可发现性修缮包）：通知动作化——「立即试用」一键直达预览（原来只有文字，
                // 用户要自己翻快捷键）。ConvertToSql 需 editor data context，经 tryToExecute 从
                // 焦点解析（balloon 非模态不抢编辑器焦点，命中通知时用户正在看的文件即是目标）。
                notification.addAction(new AnAction(
                        MpSqlVisualizerBundle.message("onboarding.anchorTip.tryNow")) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        // 事件本身无 editor context——经 tryToExecute 从**焦点**（balloon 不抢焦点，
                        // 仍在用户正在看的编辑器）解析 data context 后调度 ConvertToSql
                        AnAction convertToSql = ActionManager.getInstance()
                                .getAction("cn.song.mybatisplus.visualizer.ConvertToSql");
                        if (convertToSql != null) {
                            ActionManager.getInstance().tryToExecute(convertToSql, null, null, null, true);
                        }
                    }
                });
                notification.notify(project);
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 领取一次性展示权：通过守卫则置「已展示」并返回 true（并发命中只有首个领取者返回 true）。
     * headless 测试同样走本入口——持久化语义与通知解耦（测试断言状态翻转）。
     */
    public static boolean tryClaimAndMark() {
        synchronized (CLAIM_LOCK) {
            PluginSettings.State s = PluginSettings.getInstance().getState();
            if (!shouldProbe(s.getOnboardingAnchorTipEnabled(), s.getOnboardingAnchorTipShown())) return false;
            s.setOnboardingAnchorTipShown(true);
            return true;
        }
    }

    /** 展示决策（纯函数，测试缝）：开关开且未展示过才探测。开关关闭 → 全程静默。 */
    public static boolean shouldProbe(boolean enabled, boolean alreadyShown) {
        return enabled && !alreadyShown;
    }

    /** 探测目标（纯函数，测试缝）：仅 Java / Kotlin / XML 源文件。 */
    public static boolean isProbeTarget(String fileName) {
        return PROBE_EXTENSIONS.contains(extensionOf(fileName));
    }

    /**
     * 锚点启发式（纯函数，测试缝）：三类可预览锚点的文本级子串匹配——
     * <ul>
     * <li>Wrapper 链：常见 MP/MPJ Wrapper 类型名（Query/Update/Join 族 + Wrappers. 工厂）；</li>
     * <li>注解 SQL：@Select / @Insert / @Update / @Delete（含 Provider 变体，前缀即命中）；</li>
     * <li>XML mapper：select/insert/update/delete 语句标签 + hbm 命名查询。</li>
     * </ul>
     */
    public static boolean anchorHintHit(String fileName, String text) {
        if (text.isEmpty()) return false;
        String ext = extensionOf(fileName);
        if (ext.equals("java") || ext.equals("kt")) {
            for (String hint : JAVA_HINTS) {
                if (text.contains(hint)) return true;
            }
            return false;
        }
        if (ext.equals("xml")) {
            for (String hint : XML_HINTS) {
                if (containsIgnoreCase(text, hint)) return true;
            }
            return false;
        }
        return false;
    }

    /** Java/Kotlin 文件的锚点关键字（大小写敏感——类名/注解形态固定，误报面最小；
     *  Wrapper 名单与 KtWrapperDetector 的链起点识别同源）。 */
    private static final List<String> JAVA_HINTS = List.of(
            "QueryWrapper", "UpdateWrapper", "JoinWrapper",
            "MPJLambdaWrapper", "MPJQueryWrapper",
            "Wrappers.",
            "@Select", "@Insert", "@Update", "@Delete");

    /** XML 文件的锚点关键字（mapper 语句标签 + hbm 命名查询，大小写不敏感）。 */
    private static final List<String> XML_HINTS = List.of(
            "<select", "<insert", "<update", "<delete",
            "<hibernate-mapping", "<sql-query");

    /** Kotlin {@code setOf("java", "kt", "xml")} 等价（isProbeTarget / anchorHintHit 共用）。 */
    private static final Set<String> PROBE_EXTENSIONS = Set.of("java", "kt", "xml");

    /** Kotlin {@code substringAfterLast('.', "")} 等价：无 '.' 返回空串，随后 lowercase(Locale.ROOT)。 */
    private static String extensionOf(String fileName) {
        int idx = fileName.lastIndexOf('.');
        String raw = idx < 0 ? "" : fileName.substring(idx + 1);
        return raw.toLowerCase(Locale.ROOT);
    }

    /** Kotlin {@code text.contains(hint, ignoreCase = true)} 等价（regionMatches 逐位比较，不整串折叠）。 */
    private static boolean containsIgnoreCase(String text, String hint) {
        int limit = text.length() - hint.length();
        for (int i = 0; i <= limit; i++) {
            if (text.regionMatches(true, i, hint, 0, hint.length())) return true;
        }
        return false;
    }
}
