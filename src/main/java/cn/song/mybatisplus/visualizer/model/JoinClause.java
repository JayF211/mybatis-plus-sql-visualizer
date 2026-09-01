package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;
/**
 * 联表子句（MPJ，F-11/F-12）。
 *
 * @param type        联表类型（INNER / LEFT / RIGHT / FULL）
 * @param tableName   关联表名
 * @param alias       关联表别名（MPJ 惯例：实体类名首字母小写）
 * @param onConditions ON 连接条件（如 cssm.scheme_id = t.share_scheme_id）
 * @param rawSql      原生 join 子句（leftJoin("address t1 on t1.user_id = t.id", args)，M15-P0）；
 *                    非空时整段原样渲染（含别名与 ON），忽略 tableName/alias/onConditions
 * @param customKeyword 自定义 join 关键字（join("FULL JOIN", ...)，M15-P0）；null 时按 type 映射
 * @param logicDeleteColumn 关联表逻辑删除列（@TableLogic 标注字段的列名，T-M20-05）；null 表示无逻辑删除
 * @param tableInfo          关联表实体元数据（PSI 解析的 EntityRefArg）；null 表示无实体信息
 */
public final class JoinClause {
    private final JoinType type;
    private final String tableName;
    private final String alias;
    private final List<Condition> onConditions;
    private final String rawSql;
    private final String customKeyword;
    private final String logicDeleteColumn;
    private final TableInfo tableInfo;

    public JoinClause(JoinType type, String tableName, String alias, List<Condition> onConditions, String rawSql,
                      String customKeyword, String logicDeleteColumn, TableInfo tableInfo) {
        this.type = type;
        this.tableName = tableName;
        this.alias = alias;
        this.onConditions = onConditions;
        this.rawSql = rawSql;
        this.customKeyword = customKeyword;
        this.logicDeleteColumn = logicDeleteColumn;
        this.tableInfo = tableInfo;
    }

    public JoinClause(JoinType type, String tableName,
                      String alias, List<Condition> onConditions) {
        this(type, tableName, alias, onConditions, null, null, null, null);
    }

    public JoinType getType() {
        return type;
    }

    public String getTableName() {
        return tableName;
    }

    public String getAlias() {
        return alias;
    }

    public List<Condition> getOnConditions() {
        return onConditions;
    }

    public String getRawSql() {
        return rawSql;
    }

    public String getCustomKeyword() {
        return customKeyword;
    }

    public String getLogicDeleteColumn() {
        return logicDeleteColumn;
    }

    public TableInfo getTableInfo() {
        return tableInfo;
    }

    public JoinClause copy(JoinType type, String tableName, String alias, List<Condition> onConditions, String rawSql,
                           String customKeyword, String logicDeleteColumn, TableInfo tableInfo) {
        return new JoinClause(type, tableName, alias, onConditions, rawSql, customKeyword, logicDeleteColumn, tableInfo);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JoinClause)) {
            return false;
        }
        JoinClause that = (JoinClause) o;
        return type == that.type
                && Objects.equals(tableName, that.tableName)
                && Objects.equals(alias, that.alias)
                && Objects.equals(onConditions, that.onConditions)
                && Objects.equals(rawSql, that.rawSql)
                && Objects.equals(customKeyword, that.customKeyword)
                && Objects.equals(logicDeleteColumn, that.logicDeleteColumn)
                && Objects.equals(tableInfo, that.tableInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, tableName, alias, onConditions, rawSql, customKeyword, logicDeleteColumn, tableInfo);
    }

    @Override
    public String toString() {
        return "JoinClause(type=" + type + ", tableName=" + tableName + ", alias=" + alias + ", onConditions="
                + onConditions + ", rawSql=" + rawSql + ", customKeyword=" + customKeyword + ", logicDeleteColumn="
                + logicDeleteColumn + ", tableInfo=" + tableInfo + ")";
    }
}
