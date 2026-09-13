package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;

/**
 * 中间表信息（T-J7 @ManyToMany @JoinTable / T-J18 @CollectionTable）。
 *
 * @param tableName           中间表名；@JoinTable 未显式指定 name 时为 null（JPA 默认按双方实体名拼接，无法静态推断）
 * @param schema              schema（@CollectionTable/@JoinTable schema）
 * @param catalog             catalog
 * @param joinColumns         指向本表的关联列（joinColumns）
 * @param inverseJoinColumns  指向目标表的关联列（inverseJoinColumns；@CollectionTable 无此概念，恒为空）
 */
public final class JoinTableInfo {
    private final String tableName;
    private final String schema;
    private final String catalog;
    private final List<JoinColumnRef> joinColumns;
    private final List<JoinColumnRef> inverseJoinColumns;

    public JoinTableInfo(String tableName, String schema, String catalog,
                         List<JoinColumnRef> joinColumns, List<JoinColumnRef> inverseJoinColumns) {
        this.tableName = tableName;
        this.schema = schema;
        this.catalog = catalog;
        this.joinColumns = joinColumns;
        this.inverseJoinColumns = inverseJoinColumns;
    }

    public JoinTableInfo(String tableName) {
        this(tableName, null, null, List.of(), List.of());
    }

    public String getTableName() {
        return tableName;
    }

    public String getSchema() {
        return schema;
    }

    public String getCatalog() {
        return catalog;
    }

    public List<JoinColumnRef> getJoinColumns() {
        return joinColumns;
    }

    public List<JoinColumnRef> getInverseJoinColumns() {
        return inverseJoinColumns;
    }

    public JoinTableInfo copy(String tableName, String schema, String catalog,
                              List<JoinColumnRef> joinColumns, List<JoinColumnRef> inverseJoinColumns) {
        return new JoinTableInfo(tableName, schema, catalog, joinColumns, inverseJoinColumns);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JoinTableInfo)) {
            return false;
        }
        JoinTableInfo that = (JoinTableInfo) o;
        return Objects.equals(tableName, that.tableName)
                && Objects.equals(schema, that.schema)
                && Objects.equals(catalog, that.catalog)
                && Objects.equals(joinColumns, that.joinColumns)
                && Objects.equals(inverseJoinColumns, that.inverseJoinColumns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName, schema, catalog, joinColumns, inverseJoinColumns);
    }

    @Override
    public String toString() {
        return "JoinTableInfo(tableName=" + tableName + ", schema=" + schema + ", catalog=" + catalog
                + ", joinColumns=" + joinColumns + ", inverseJoinColumns=" + inverseJoinColumns + ")";
    }
}
