package cn.song.mybatisplus.visualizer.actions;

import cn.song.mybatisplus.visualizer.database.DbTableSpecAdapter;
import cn.song.mybatisplus.visualizer.generator.CrudStatement;
import cn.song.mybatisplus.visualizer.generator.CrudSqlGenerator;
import cn.song.mybatisplus.visualizer.generator.CrudTableSpec;
import com.intellij.database.psi.DbTable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiElement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Database 工具窗口表节点右键生成 CRUD SQL（T-CrudSql-03）。
 *
 * <p>注册：database-optional.xml {@code <actions>} 段 → {@code DatabaseViewPopupMenu}（anchor=last；该组为
 * DatabaseTools 内部组，2024.3.7.1 plugin.xml:713 实证对第三方开放，无专门视图 action EP）。
 * 仅在 com.intellij.database 插件在场时加载——本类与 DbTableSpecAdapter 的 database 类引用
 * 全部直引编译期类型，无反射入口。
 *
 * <p>update 判定：选中 PSI_ELEMENT_ARRAY 含 {@link DbTable}（Database 视图树选中项以 PSI 暴露，
 * DbElement extends PsiFileSystemItem）。多选表支持——后台 ReadAction 逐表适配自省元数据，
 * 生成后 EDT 上 {@link CrudSqlPreviewFlow#showCandidates} 合并展示（标签带表名前缀）。
 */
public final class GenerateCrudSqlFromDbTableAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        boolean hasTable = e.getProject() != null && !selectedTables(e).isEmpty();
        e.getPresentation().setEnabledAndVisible(hasTable);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        List<DbTable> tables = selectedTables(e);
        if (tables.isEmpty()) {
            return;
        }
        // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
        List<Pair<String, List<CrudStatement>>> payload;
        try {
            payload = ReadAction.compute(() -> {
                List<Pair<String, List<CrudStatement>>> result = new ArrayList<>();
                for (DbTable table : tables) {
                    CrudTableSpec spec = DbTableSpecAdapter.toSpec(table);
                    List<CrudStatement> statements = CrudSqlGenerator.generate(spec);
                    if (!statements.isEmpty()) {
                        result.add(Pair.create(spec.getTableName(), statements));
                    }
                }
                return result;
            });
        } catch (Throwable t) {
            payload = Collections.emptyList();
        }
        List<Pair<String, List<CrudStatement>>> finalPayload = payload;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (finalPayload.isEmpty()) {
                CrudSqlPreviewFlow.showNoneFound(project);
            } else {
                CrudSqlPreviewFlow.showCandidates(project, finalPayload);
            }
        });
    }

    private static List<DbTable> selectedTables(AnActionEvent e) {
        PsiElement[] elements = e.getData(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY);
        if (elements == null) {
            return Collections.emptyList();
        }
        List<DbTable> tables = new ArrayList<>();
        for (PsiElement element : elements) {
            if (element instanceof DbTable) {
                tables.add((DbTable) element);
            }
        }
        return tables;
    }
}
