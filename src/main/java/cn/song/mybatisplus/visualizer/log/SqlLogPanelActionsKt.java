package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPopupMenu;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.FileSaverDialog;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code SqlLogPanelActions.kt}（T-M53-II D2 首件）的 Java 承载类——SqlLogPanel 的「右键菜单、
 * 导出与抑制反馈」内聚成员，以同包静态方法（原 Kotlin 扩展函数 {@code internal fun SqlLogPanel.xxx()}）形式承载。
 * 方法体逐字符搬运，被触及的私有成员已按需放宽为 internal（Java 侧为同包可见）。
 */
public final class SqlLogPanelActionsKt {

    private SqlLogPanelActionsKt() {
    }

    /** R4 导出：格式四选（CSV/TSV/TXT/SQL，默认 SQL）→ 文件保存 → 后台写盘 → 通知（行集=lastRendered 快照）。 */
    public static void exportVisibleEntries(@NotNull SqlLogPanel receiver) {
        List<SqlExportRow> rows = new ArrayList<>();
        for (LogSqlEntry it : receiver.lastRendered) {
            String source = "";
            if (it.sourceFile() != null && !it.sourceFile().isBlank()) {
                source = it.sourceFile();
                if (it.sourceLine() > 0) {
                    source = source + ":" + it.sourceLine();
                }
            }
            rows.add(new SqlExportRow(
                    SqlExportService.formatTime(it.timestamp()),
                    it.restoredSql(),
                    source,
                    it.executionTimeMs()));
        }
        if (rows.isEmpty()) return;
        SqlExportFormat format = chooseExportFormat(receiver);
        if (format == null) return;
        FileSaverDescriptor descriptor = new FileSaverDescriptor(
                MpSqlVisualizerBundle.message("log.export.visible"),
                "SQL (" + format.getExt().toUpperCase() + ")",
                format.getExt());
        FileSaverDialog dialog = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, receiver.project);
        // T-ExportSqlFormat（用户拍板 20261009）：SQL 格式默认名 sql-export-<yyyyMMdd-HHmmss>.sql
        // （时间戳防同日覆盖、不占 sql-history 名族）；CSV/TSV/TXT 保持原名随 ext。
        String defaultName = format == SqlExportFormat.SQL
                ? SqlExportService.defaultSqlExportFileName()
                : "sql-log." + format.getExt();
        VirtualFileWrapper saved = dialog.save((com.intellij.openapi.vfs.VirtualFile) null, defaultName);
        if (saved == null) return;
        File target = saved.getFile();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                SqlExportService.exportToFile(target, SqlExportService.format(rows, format));
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(SqlLogPanel.NOTIFICATION_GROUP_ID)
                        .createNotification(
                                MpSqlVisualizerBundle.message("log.history.export.done", rows.size(), target.getAbsolutePath()),
                                NotificationType.INFORMATION)
                        .notify(receiver.project);
            } catch (Throwable t) {
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(SqlLogPanel.NOTIFICATION_GROUP_ID)
                        .createNotification(
                                MpSqlVisualizerBundle.message("log.history.export.failed", t.getMessage() != null ? t.getMessage() : ""),
                                NotificationType.ERROR)
                        .notify(receiver.project);
            }
        });
    }

    /** R4：导出格式四选（取消返回 null）。默认选中 SQL（T-ExportSqlFormat 用户拍板 20261009；
     *  P3：showChooseDialog 返回所选下标、取消 -1）。 */
    public static SqlExportFormat chooseExportFormat(@NotNull SqlLogPanel receiver) {
        String[] names = {"CSV", "TSV", "TXT", "SQL"};
        int idx = Messages.showChooseDialog(
                receiver.project,
                MpSqlVisualizerBundle.message("log.history.export.choose"),
                MpSqlVisualizerBundle.message("log.export.visible"),
                Messages.getQuestionIcon(),
                names,
                names[3]);
        if (idx < 0) return null;
        switch (idx) {
            case 0: return SqlExportFormat.CSV;
            case 1: return SqlExportFormat.TSV;
            case 2: return SqlExportFormat.TXT;
            case 3: return SqlExportFormat.SQL;
            default: return null;
        }
    }

    public static void showContextMenu(@NotNull SqlLogPanel receiver, MouseEvent e) {
        int index = receiver.list.locationToIndex(e.getPoint());
        if (index < 0) return;
        // T-ListPreviewCopy：命中行已在多选内 → 保留多选（复制项=批量复制多条）；否则收敛为该行。
        // 删除/发送/跳转等其余菜单项恒作用于被右键的行
        receiver.list.setSelectedIndices(
                SqlLogPanelKt.resolveContextMenuSelection(receiver.list.getSelectedIndices(), index));
        LogSqlEntry entry = receiver.list.getModel().getElementAt(index);
        List<LogSqlEntry> selectedEntries = new ArrayList<>();
        for (int i : receiver.list.getSelectedIndices()) {
            selectedEntries.add(receiver.list.getModel().getElementAt(i));
        }
        // T-UiNative P1a：JPopupMenu+JMenuItem → 平台 DefaultActionGroup+AnAction（ActionPopupMenu 承载）
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(receiver.new CopySqlAction(selectedEntries));
        // T-LogSearchRowTime：复制时间戳（所见即所复制的纯时间文本，无方括号）；
        // 显示开关关闭时无片段可复制 → 菜单项置灰（置灰逻辑在 CopyTimestampAction.update）
        String timestampClip = SqlLogPanelKt.batchCopyTimestamps(selectedEntries);
        group.add(receiver.new CopyTimestampAction(selectedEntries, timestampClip));
        group.add(receiver.new DeleteSqlAction(entry));
        // T-SqlSuppress（O-69）：屏蔽此 SQL（多选按不同指纹各成一条规则，已存在跳过）——
        // 置「删除」之后（计划定稿位），balloon 反馈带管理直达
        group.add(receiver.new SuppressSqlAction(selectedEntries));
        // T-M37 P1：发送到 SQL → Java 转换（第三 tab）——还原 SQL 写入输入区并切换选中该 tab
        group.add(receiver.new SendToSqlToJavaAction(entry));
        // T-M38：发送到日志转 SQL（第二 tab）——条目渲染回日志文本并立即转换
        group.add(receiver.new SendToLogToSqlAction(entry));
        // O-1：日志 ↔ 源码关联跳转（Phase A）；T-M55-01：拆「跳转到 Java」「跳转到 XML」双目标
        group.add(receiver.new JumpToSourceAction(entry));
        group.add(receiver.new JumpToMapperXmlAction(entry));
        // T-M55-03：方法级聚焦（sourceFile 非空时可用；解析失败 toast 提示，无静默吞没）
        group.add(receiver.new FocusCallerMethodAction(entry));
        // T-M55-04：按请求聚焦（requestTag 非空时可用；页签 K 有界防高基数爆列表）
        group.add(receiver.new FocusRequestAction(entry));
        // O-90：按模板聚焦（「×N 同模板」徽章达阈值才可用；过滤出该模板全部条目）
        group.add(receiver.new FocusTemplateAction(entry));
        // R4（W3 历史工作流包）：导出当前可见行集（与面板过滤/搜索一致；CSV/TSV/TXT/SQL，后台写盘）
        group.add(receiver.new ExportVisibleAction());
        ActionPopupMenu popup = ActionManager.getInstance().createActionPopupMenu("mpvs.log.list", group);
        // 243 的 ActionPopupMenu 只有 setter 无 getter，Kotlin 不合成 targetComponent 属性——显式调 setter
        popup.setTargetComponent(receiver.list);
        popup.getComponent().show(receiver.list, e.getX(), e.getY());
    }

    /** T-SqlSuppress：屏蔽成功 balloon（D5 定案独立通知组 logsuppress）。附「管理屏蔽规则…」动作直达对话框；测试模式跳过。 */
    public static void showSuppressBalloon(@NotNull SqlLogPanel receiver, int addedCount) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            Notification notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(SqlLogPanel.SUPPRESS_NOTIFICATION_GROUP_ID)
                    .createNotification(
                            MpSqlVisualizerBundle.message("log.suppress.added.title"),
                            MpSqlVisualizerBundle.message("log.suppress.added.body", Integer.toString(addedCount)),
                            NotificationType.INFORMATION);
            notification.addAction(
                    NotificationAction.createSimple(MpSqlVisualizerBundle.message("log.suppress.manage"), () -> {
                        notification.expire();
                        new SqlSuppressManagerDialog(receiver.project).show();
                    }));
            notification.notify(receiver.project);
        } catch (Throwable ignored) {
            // 通知失败不影响主流程
        }
    }

    /** T-SqlSuppress：达上限提示 balloon（D6：拒新增不淘汰，文案引导先删不用的规则）。 */
    public static void showSuppressLimitBalloon(@NotNull SqlLogPanel receiver) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(SqlLogPanel.SUPPRESS_NOTIFICATION_GROUP_ID)
                    .createNotification(
                            MpSqlVisualizerBundle.message("log.suppress.added.title"),
                            MpSqlVisualizerBundle.message(
                                    "log.suppress.limit",
                                    Integer.toString(SqlSuppressRuleManager.MAX_RULES)),
                            NotificationType.WARNING)
                    .notify(receiver.project);
        } catch (Throwable ignored) {
            // 通知失败不影响主流程
        }
    }
}
