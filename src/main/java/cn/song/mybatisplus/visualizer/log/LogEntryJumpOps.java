package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

/**
 * 日志条目跳转的共享原语（T-RecentExecMenu O-119）：解析 / 导航 / 轻提示 / 缺位置引导。
 *
 * <p><b>为什么只有原语、没有统一策略</b>：实时页与历史页的<b>分支策略本就不同</b>（搬迁前实测）——
 * 实时页 {@code MissingSource} 走轻通知（文案键 {@code log.panel.jumpToSource.*}），历史页把它并入
 * {@code NoSource} 走引导弹窗（文案键 {@code log.history.jumpToSource.*}）。共享件只收口两面板逐字相同的
 * 部分（{@link EntryJumpResolver} 转接、{@link MapperXmlLocator} 定位、{@code OpenFileDescriptor.navigate(true)}、
 * {@code isUnitTestMode} 跳过通知），<b>策略分支留在各自调用方</b>——避免「提取时取一侧丢另一侧」。
 *
 * <p>三个调用方：{@code SqlLogPanel}（实时页）、{@code HistorySqlLogPanel}（历史页）、{@code RecentExecutionsDialog}
 * （弹框右键菜单；策略取实时页口径，与菜单文案同源）。两面板的 {@code LogJumpTarget} /
 * {@code HistoryJumpTarget} 类型与 {@code resolveXxx} 签名<b>保持不动</b>（既有测试按其断言分支），内部改为
 * 由本对象转接。
 */
public final class LogEntryJumpOps {

    private LogEntryJumpOps() {
    }

    /** 轻提示通知组（实时/历史/弹框共用；plugin.xml 已注册）。 */
    public static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /**
     * 源码跳转目标（{@link EntryJumpResolver.JumpTarget} 的同名转接；{@code MissingSource} 与 {@code NoSource}
     * <b>保持区分</b>，合并与否由调用方策略定）。
     */
    public sealed interface SourceTarget {

        /** 精确位置。 */
        record Precise(
                /** 可跳转的虚拟文件。 */
                VirtualFile virtualFile,
                /** 目标行号（0 起，OpenFileDescriptor 直用）。 */
                int line
        ) implements SourceTarget {
        }

        /** 无用户调用点（纯提示）。 */
        final class NoSource implements SourceTarget {

            public static final NoSource INSTANCE = new NoSource();

            private NoSource() {
            }
        }

        /** 主位置不在工程内。 */
        record NotFound(
                /** 主位置源文件相对路径。 */
                String sourceFile
        ) implements SourceTarget {
        }

        /** 条目无源码位置。 */
        final class MissingSource implements SourceTarget {

            public static final MissingSource INSTANCE = new MissingSource();

            private MissingSource() {
            }
        }
    }

    /** mapper XML 语句节点目标。 */
    public record XmlTarget(
            /** XML 虚拟文件。 */
            VirtualFile virtualFile,
            /** 目标行号（0 起）。 */
            int line
    ) {
    }

    /** 源码目标解析（仅依赖捕获位置，零启发式）。 */
    public static SourceTarget resolveSource(Project project, LogSqlEntry entry) {
        EntryJumpResolver.JumpTarget target = EntryJumpResolver.resolve(project, entry);
        if (target instanceof EntryJumpResolver.JumpTarget.Precise precise) {
            return new SourceTarget.Precise(precise.virtualFile(), precise.line());
        }
        if (target instanceof EntryJumpResolver.JumpTarget.NoSource) {
            return SourceTarget.NoSource.INSTANCE;
        }
        if (target instanceof EntryJumpResolver.JumpTarget.NotFound notFound) {
            return new SourceTarget.NotFound(notFound.sourceFile());
        }
        if (target instanceof EntryJumpResolver.JumpTarget.MissingSource) {
            return SourceTarget.MissingSource.INSTANCE;
        }
        // sealed 穷尽（Kotlin when 无 else）——防御分支，正常不可达
        throw new IllegalStateException("LogEntryJumpOps.resolveSource: unexpected JumpTarget " + target);
    }

    /**
     * mapper XML 目标解析：mapperStatement 非空且 {@link MapperXmlLocator} 定位成功才返回；
     * 缺失 / MP 内置语句无 XML / 多模块定位失败一律 null（调用方回退 Java 跳转，不劣化）。
     * 定位自带 ReadAction，EDT 直调安全。
     */
    public static XmlTarget resolveXml(Project project, LogSqlEntry entry) {
        String statementId = entry.mapperStatement();
        if (statementId == null || statementId.isBlank()) {
            return null;
        }
        MapperXmlLocator.Location location = MapperXmlLocator.locate(project, statementId);
        if (location == null) {
            return null;
        }
        return new XmlTarget(location.virtualFile(), location.line());
    }

    /** 打开文件并定位（{@code navigate(true)}：跳转后聚焦编辑器）。 */
    public static void navigate(Project project, VirtualFile virtualFile, int line) {
        new OpenFileDescriptor(project, virtualFile, line, 0).navigate(true);
    }

    /**
     * 轻提示（T-UiNative T-UN-02 形态：纯告知不打断）。
     * {@code titleKey} 由调用方给——实时/历史两页面板标题文案键不同，属各自的展示口径。
     * 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）。
     */
    public static void showToast(Project project, String titleKey, String content) {
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            return;
        }
        try {
            new Notification(
                    NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message(titleKey),
                    content,
                    NotificationType.INFORMATION).notify(project);
        } catch (Throwable t) {
            // runCatching 等价：通知失败静默（轻提示不得打断主链）
        }
    }

    /** 缺源码位置引导（T-JumpCapture：引导开启源码捕获拦截器）。 */
    public static void showMissingSourceGuide(Project project) {
        SourceCaptureGuide.showMissingSourceDialog(project);
    }

    /**
     * <b>实时页口径</b>的整体跳转（S1 后由 {@code SqlLogPanel} 右键与弹框右键共用——两处同文案同反馈，
     * 不收进共享件就会各写一份）：精确位置 → 打开定位；{@code NoSource}/{@code NotFound} → 轻通知
     * （键 {@code log.panel.jumpToSource.*}）；{@code MissingSource} → 源码捕获引导。
     *
     * <p>历史页口径<b>不同</b>（{@code MissingSource} 并入引导、{@code NotFound} 用 {@code log.history.*} 键），
     * 故不并入本函数——策略差异是既成事实，共享件只收口相同的部分。
     */
    public static void jumpToSourceRealtime(Project project, LogSqlEntry entry) {
        SourceTarget target = resolveSource(project, entry);
        if (target instanceof SourceTarget.Precise precise) {
            navigate(project, precise.virtualFile(), precise.line());
        } else if (target instanceof SourceTarget.NoSource) {
            showToast(project, "log.panel.jumpToSource",
                    MpSqlVisualizerBundle.message("log.panel.jumpToSource.noSource"));
        } else if (target instanceof SourceTarget.NotFound notFound) {
            showToast(
                    project,
                    "log.panel.jumpToSource",
                    MpSqlVisualizerBundle.message("log.panel.jumpToSource.notFound", notFound.sourceFile()));
        } else if (target instanceof SourceTarget.MissingSource) {
            showMissingSourceGuide(project);
        }
    }
}
