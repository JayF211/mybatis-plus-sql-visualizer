package cn.song.mybatisplus.visualizer.ui.highlight;

import cn.song.mybatisplus.visualizer.model.TableInfo;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * SQL 补全上下文（M9-03/04）：当前预览涉及的实体表/列，随 Editor/PSI 文件双载体传递。
 *
 * 面板在创建预览编辑器时通过 {@link #KEY} 绑定上下文，补全器从
 * {@code editor.getUserData(KEY)}（优先，M16.2——嵌入式编辑器编辑后稳定）或
 * {@code position.containingFile.getUserData(KEY)}（兜底）读取，避免全局可变状态。
 */
public final class SqlCompletionContext {

    /** 当前主表名（如 merchant_entity）。 */
    @Nullable
    public final String tableName;
    /** 当前实体列名（snake_case）。 */
    @NotNull
    public final List<String> columns;
    /** 主表别名（MPJ 场景如 t；单表为 null）。 */
    @Nullable
    public final String tableAlias;
    /** 列注释（columnName → comment，M16：补全 tailText 与关键词检索；缺省空 Map 向后兼容）。 */
    @NotNull
    public final Map<String, String> columnComments;
    /** 表注释（M16：表名补全 tailText 与关键词检索）。 */
    @Nullable
    public final String tableComment;
    /** T-M27.4：联表候选表（@TableName 实体；表名补全全位置提示，主表去重）。 */
    @NotNull
    public final List<TableInfo> candidateTables;

    public SqlCompletionContext(@Nullable String tableName,
                                @NotNull List<String> columns,
                                @Nullable String tableAlias) {
        this(tableName, columns, tableAlias, Collections.emptyMap(), null, Collections.emptyList());
    }

    public SqlCompletionContext(@Nullable String tableName,
                                @NotNull List<String> columns,
                                @Nullable String tableAlias,
                                @NotNull Map<String, String> columnComments) {
        this(tableName, columns, tableAlias, columnComments, null, Collections.emptyList());
    }

    public SqlCompletionContext(@Nullable String tableName,
                                @NotNull List<String> columns,
                                @Nullable String tableAlias,
                                @NotNull Map<String, String> columnComments,
                                @Nullable String tableComment) {
        this(tableName, columns, tableAlias, columnComments, tableComment, Collections.emptyList());
    }

    public SqlCompletionContext(@Nullable String tableName,
                                @NotNull List<String> columns,
                                @Nullable String tableAlias,
                                @NotNull Map<String, String> columnComments,
                                @Nullable String tableComment,
                                @NotNull List<TableInfo> candidateTables) {
        this.tableName = tableName;
        this.columns = columns;
        this.tableAlias = tableAlias;
        this.columnComments = columnComments;
        this.tableComment = tableComment;
        this.candidateTables = candidateTables;
    }

    /** 当前预览涉及的实体表/列上下文键。 */
    @NotNull
    public static final Key<SqlCompletionContext> KEY = Key.create("MP_SQL_COMPLETION_CONTEXT");
}
