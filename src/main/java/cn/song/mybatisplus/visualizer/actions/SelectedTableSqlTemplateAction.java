// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/actions/SelectedTableSqlTemplateActions.kt 移植
// T-SyncK2JMaster104 波B 增量：T-SqlTemplateCaret（选区优先、光标处标识符退化）+ T-SqlTemplateUx P2（失败反馈富化）
package cn.song.mybatisplus.visualizer.actions;

import cn.song.mybatisplus.visualizer.execute.SqlExecutor;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;

/**
 * {@code ${table}} 快捷模板三动作（T-PreviewUx-Pack #3，吸收 DBX sqlShortcutActions 口径）：
 * mapper 编辑器 / SQL 预览编辑器内的目标文本（表名或片段）一键套模板送原生 Database 控制台执行——
 * EXPLAIN 执行计划 / COUNT 行数 / 抽样 10 行。三动作共享一个 action group 注册 EditorPopupMenu
 * （plugin.xml），与「生成 CRUD SQL」右键入口并存不合并菜单（目标互补：CRUD 生成 vs 诊断三动作）。
 *
 * <p>T-SqlTemplateCaret：目标文本<b>不再要求先划选区</b>——有非空选区时原样用选区文本（逐字节不变），
 * 无选区（或选区为空白）时退化为<b>光标处的标识符</b>（identifierAtCaret，表名/片段），
 * 两路都取不到才禁用。理由是「光标停在表名上就能用」录制与使用都更顺手；光标退化分支
 * 只做词边界扫描、不依赖具体语言/SQL 注入，故在 mapper XML / {@code .sql} / Java / 预览弹窗文本区
 * 等任意编辑器里都成立。</p>
 *
 * <p>执行通道：既有内嵌控制台链路（execute/DataSourceSelector + {@link SqlExecutor}）——选数据源 →
 * 原生 Database 控制台执行，凭证不出 IDE；Community（无 database 插件）由 {@link SqlExecutor} 提示降级。
 * 三动作产物均为查询类语句（EXPLAIN/COUNT/SELECT），免 DML 二次确认（与 EXPLAIN 既有口径一致，
 * SqlTypeClassifier.QUERY 同族）。</p>
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
        // 非空选区或光标处标识符至少有一即可用；两者都没有即禁用
        // （不增加低价值打断；右键菜单常态可看不可点）
        e.getPresentation().setEnabled(selectedOrCaretText(e) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        String text = selectedOrCaretText(e);
        if (text == null) return;
        String sql = template.apply(text.trim());
        // T-SqlTemplateUx P2：失败反馈与预览面板拉平——除气泡外挂「定位到出错语句」「复制错误信息」
        // 两个可操作项（做法②：不新建面板，直接给 Notification 挂 NotificationAction）。
        // 定位锚点在动作触发时（EDT）从**触发编辑器**捕获（用户选中表名处即语句来源）；
        // 解析不出（无编辑器 / 文件已失效）则不给定位动作，退化为只给「复制错误信息」——
        // 与面板 errorLocateAction 的「先解析、后显隐」同姿态，绝不虚标可点的定位。
        SelectedTableSqlTemplateActionsKt.ErrorLocateAnchor anchor = SelectedTableSqlTemplateActionsKt.resolveErrorLocateAnchor(
                e.getData(CommonDataKeys.EDITOR),
                e.getData(CommonDataKeys.VIRTUAL_FILE));
        Runnable locate = anchor != null
                ? () -> new OpenFileDescriptor(project, anchor.file(), anchor.line0(), 0).navigate(true)
                : null;
        SqlExecutor.execute(
                project, sql, null, true,
                null,
                null,
                errorMsg -> SelectedTableSqlTemplateActionsKt.notifySqlTemplateError(project, errorMsg, locate),
                // 富通道优先（execute/DataSourceSelector.deliverError 二选一）：与面板同走 onErrorRich，
                // 只是此处通知面不再需要 positionChar/lineInSql，故取 message 即可。
                info -> SelectedTableSqlTemplateActionsKt.notifySqlTemplateError(project, info.message(), locate));
    }

    /** 目标文本入口：从事件取编辑器，交 editorTemplateText 决策（选区优先、光标处退化）。 */
    private String selectedOrCaretText(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        return editor != null ? SelectedTableSqlTemplateActionsKt.editorTemplateText(editor) : null;
    }

    // 仅访问编辑器选择区/光标（UI 状态，非 PSI）→ EDT 更新线程安全且无慢操作风险（ConvertSqlToJavaAction 同款）
    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
