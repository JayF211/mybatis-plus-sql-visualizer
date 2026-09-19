// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/actions/SelectedTableSqlTemplateActions.kt 移植
package cn.song.mybatisplus.visualizer.actions;

import cn.song.mybatisplus.visualizer.execute.SqlExecutor;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;

/**
 * {@code ${table}} 快捷模板三动作（T-PreviewUx-Pack #3，吸收 DBX sqlShortcutActions 口径）：
 * mapper 编辑器 / SQL 预览编辑器内<b>选中文本</b>（表名或片段）一键套模板送原生 Database 控制台执行——
 * EXPLAIN 执行计划 / COUNT 行数 / 抽样 10 行。三动作共享一个 action group 注册 EditorPopupMenu
 * （plugin.xml），与「生成 CRUD SQL」右键入口并存不合并菜单（目标互补：CRUD 生成 vs 诊断三动作）。
 *
 * <p>执行通道：既有内嵌控制台链路（execute/DataSourceSelector + {@link SqlExecutor}）——选数据源 →
 * 原生 Database 控制台执行，凭证不出 IDE；Community（无 database 插件）由 {@link SqlExecutor} 提示降级。
 * 三动作产物均为查询类语句（EXPLAIN/COUNT/SELECT），免 DML 二次确认（与 EXPLAIN 既有口径一致，
 * SqlTypeClassifier.QUERY 同族）。
 */
public abstract class SelectedTableSqlTemplateAction extends AnAction {

    /** 提示通知组（O-4 预览操作 toast 同组）。 */
    public static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    private final String textKey;
    private final Function<String, String> template;

    protected SelectedTableSqlTemplateAction(String textKey, Function<String, String> template) {
        this.textKey = textKey;
        this.template = template;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setText(MpSqlVisualizerBundle.message(textKey));
        // 无选中文本即禁用（不增加低价值打断；右键菜单常态可看不可点）
        e.getPresentation().setEnabled(selectedText(e) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        String text = selectedText(e);
        if (text == null) return;
        String sql = template.apply(text.trim());
        SqlExecutor.execute(
                project, sql, null, true,
                null,
                null,
                errorMsg -> NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP_ID)
                        .createNotification(
                                MpSqlVisualizerBundle.message("sqlTemplate.notify.title"),
                                errorMsg,
                                NotificationType.WARNING)
                        .notify(project));
    }

    private String selectedText(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) return null;
        if (!editor.getSelectionModel().hasSelection()) return null;
        String selected = editor.getSelectionModel().getSelectedText();
        return selected != null && !selected.isBlank() ? selected : null;
    }

    // 仅访问编辑器选择区（UI 状态，非 PSI）→ EDT 更新线程安全且无慢操作风险（ConvertSqlToJavaAction 同款）
    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
