package cn.song.mybatisplus.visualizer.sql;

import java.util.List;
import java.util.Objects;

/**
 * SQL 生成结果：SQL 文本 + 未解析参数来源（F-02 参数对照表基础）。
 *
 * @param sql              生成的 SQL（未解析参数渲染为 `?`）
 * @param unresolvedParams 按出现顺序的未解析参数来源（变量名/表达式文本）
 * @param paramHints       与 {@code unresolvedParams} 并行的回填提示（M13）：引号与 LIKE 百分号，
 *                         供面板按用户输入值回填渲染；无上下文信息时为空
 */
public final class GeneratedSql {

    private final String sql;
    private final List<String> unresolvedParams;
    private final List<ParamHint> paramHints;

    public GeneratedSql(String sql, List<String> unresolvedParams, List<ParamHint> paramHints) {
        this.sql = sql;
        this.unresolvedParams = unresolvedParams;
        this.paramHints = paramHints;
    }

    /** 便捷构造：无回填提示（paramHints 默认空列表）。 */
    public GeneratedSql(String sql, List<String> unresolvedParams) {
        this(sql, unresolvedParams, List.of());
    }

    public String getSql() {
        return sql;
    }

    public List<String> getUnresolvedParams() {
        return unresolvedParams;
    }

    public List<ParamHint> getParamHints() {
        return paramHints;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GeneratedSql)) {
            return false;
        }
        GeneratedSql that = (GeneratedSql) o;
        return Objects.equals(sql, that.sql)
                && Objects.equals(unresolvedParams, that.unresolvedParams)
                && Objects.equals(paramHints, that.paramHints);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sql, unresolvedParams, paramHints);
    }

    @Override
    public String toString() {
        return "GeneratedSql(sql=" + sql + ", unresolvedParams=" + unresolvedParams + ", paramHints=" + paramHints + ")";
    }
}
