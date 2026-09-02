package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.EntityResolver;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import cn.song.mybatisplus.visualizer.xml.PagesModel;
import cn.song.mybatisplus.visualizer.xml.PagesStatement;
import cn.song.mybatisplus.visualizer.xml.PagesXmlParser;
import cn.song.mybatisplus.visualizer.xml.PagesXmlParseException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * maintainPage 预览服务（F-45）：XML 文本 → 语句 → 预览模型 → PreviewResult。
 *
 * 纯函数（fileText 输入，无 PSI 依赖），gutter / Action 负责取文件文本与定位 maintainPage id；
 * 弹窗与面板完全复用 SqlPreviewService.showResult 链路。
 */
public final class PagesXmlPreviewService {

    public static final PagesXmlPreviewService INSTANCE = new PagesXmlPreviewService();

    private static final Pattern WHITESPACE_REGEX = Pattern.compile("\\s+");

    private PagesXmlPreviewService() {
    }

    @NotNull
    public PreviewResult preview(@NotNull String fileText, @Nullable String maintainPageId) {
        return preview(fileText, maintainPageId, null);
    }

    @NotNull
    public PreviewResult preview(@NotNull String fileText, @Nullable String maintainPageId, @Nullable Project project) {
        PagesModel pagesModel;
        try {
            pagesModel = new PagesXmlParser().parse(fileText);
        } catch (PagesXmlParseException e) {
            return emptyResult(e.getMessage() != null ? e.getMessage() : "XML 解析失败。");
        }

        PagesStatement statement = null;
        if (maintainPageId != null) {
            for (PagesStatement s : pagesModel.statements()) {
                if (maintainPageId.equals(s.id())) {
                    statement = s;
                    break;
                }
            }
        }
        if (statement == null) {
            for (PagesStatement s : pagesModel.statements()) {
                statement = s;
                break;
            }
        }
        if (statement == null) {
            return emptyResult("未找到可预览的 SQL 语句（<pages> 中无 <maintainPage>）。");
        }

        return toResult(statement, project);
    }

    /** PagesStatement → PreviewResult（纯 SQL 路径）。 */
    private PreviewResult toResult(@NotNull PagesStatement statement, @Nullable Project project) {
        String sql = statement.sql();
        List<String> params = extractPlaceholders(sql);
        SqlCompletionContext entityContext = resolveEntityContext(statement.entityClass(), project);
        return new PreviewResult(
                sql,
                WHITESPACE_REGEX.matcher(sql).replaceAll(" ").trim(),
                params,
                ParameterReplacer.buildParamTable(params),
                null,
                entityContext != null ? entityContext.tableName : null,
                null,
                entityContext != null ? entityContext.columns : List.of(),
                entityContext != null ? entityContext.columnComments : Map.of(),
                entityContext != null ? entityContext.tableComment : null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                statement.columns().isEmpty() ? null : statement.columns()
        );
    }

    /** 实体类推断：全限定名 → PSI findClass → EntityResolver → SqlCompletionContext。 */
    @Nullable
    private SqlCompletionContext resolveEntityContext(@Nullable String entityClass, @Nullable Project project) {
        if (entityClass == null || entityClass.isBlank() || project == null) return null;
        PsiClass psiClass = JavaPsiFacade.getInstance(project)
                .findClass(entityClass, GlobalSearchScope.allScope(project));
        if (psiClass == null) return null;
        TableInfo tableInfo = new EntityResolver().resolve(psiClass);
        Map<String, String> comments = new HashMap<>();
        for (ColumnInfo col : tableInfo.getColumns()) {
            String c = col.getComment();
            if (c != null && !c.isBlank()) {
                comments.put(col.getColumnName(), c);
            }
        }
        return new SqlCompletionContext(
                tableInfo.getTableName(),
                tableInfo.getAllColumnNames(),
                null,
                comments,
                tableInfo.getComment()
        );
    }

    /** 提取 SQL 中 ? 占位符 → param1..N。 */
    private List<String> extractPlaceholders(@NotNull String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') count++;
        }
        if (count == 0) return List.of();
        List<String> result = new ArrayList<>();
        for (int i = 1; i <= count; i++) result.add("param" + i);
        return result;
    }

    private PreviewResult emptyResult(@NotNull String message) {
        return new PreviewResult(
                "",
                "",
                List.of(),
                null,
                null,
                null,
                message,
                List.of(),
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null
        );
    }
}
