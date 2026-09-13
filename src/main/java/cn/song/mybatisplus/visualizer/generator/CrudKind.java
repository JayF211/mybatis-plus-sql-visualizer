package cn.song.mybatisplus.visualizer.generator;

/**
 * CRUD 语句种类（T-CrudSql-01）：候选项标签的 bundle 键来源。
 */
public enum CrudKind {
    SELECT_ALL("crudSql.kind.selectAll"),
    SELECT_BY_PK("crudSql.kind.selectByPk"),
    INSERT("crudSql.kind.insert"),
    UPDATE("crudSql.kind.update"),
    DELETE("crudSql.kind.delete"),
    LOGIC_DELETE("crudSql.kind.logicDelete");

    private final String labelKey;

    CrudKind(String labelKey) {
        this.labelKey = labelKey;
    }

    public String getLabelKey() {
        return labelKey;
    }
}
