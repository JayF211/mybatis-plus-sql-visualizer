package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 源码 SQL 补全上下文（T-M27-02）：XML 语句 / 注解方法 → {@link SqlCompletionContext} 主表 +
 * 联表候选 + 参数名，供 XML 与注解补全贡献器（T-M27-03/05）共用。
 *
 * @param main       主表上下文（可为 null → 降级只补关键字/动态标签，不阻塞补全）
 * @param candidates 联表候选：主表实体 + 项目内 {@code @TableName} 实体（按 tableName 去重、cap 50）
 * @param paramNames 语句方法参数名（@Param 值优先 + 实际名 + param1..N，复用
 *                   {@link AnnotationSqlPreviewService#paramNames}）
 */
public final class SourceSqlContext {

    private final SqlCompletionContext main;
    private final List<TableInfo> candidates;
    private final List<String> paramNames;

    public SourceSqlContext(@Nullable SqlCompletionContext main,
                           @Nullable List<TableInfo> candidates,
                           @Nullable List<String> paramNames) {
        this.main = main;
        this.candidates = candidates != null ? candidates : java.util.Collections.emptyList();
        this.paramNames = paramNames != null ? paramNames : java.util.Collections.emptyList();
    }

    @Nullable
    public SqlCompletionContext getMain() {
        return main;
    }

    @NotNull
    public List<TableInfo> getCandidates() {
        return candidates;
    }

    @NotNull
    public List<String> getParamNames() {
        return paramNames;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SourceSqlContext)) return false;
        SourceSqlContext that = (SourceSqlContext) o;
        return Objects.equals(main, that.main)
                && Objects.equals(candidates, that.candidates)
                && Objects.equals(paramNames, that.paramNames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(main, candidates, paramNames);
    }

    @Override
    public String toString() {
        return "SourceSqlContext{main=" + main + ", candidates=" + candidates + ", paramNames=" + paramNames + '}';
    }
}
