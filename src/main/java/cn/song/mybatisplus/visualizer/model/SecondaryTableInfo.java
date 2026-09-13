package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;

/**
 * 副表信息（T-J18 @SecondaryTable）。
 *
 * @param tableName     副表名
 * @param schema        schema
 * @param catalog       catalog
 * @param pkJoinColumns 主键关联列（pkJoinColumns；语义：主表 PK = 副表关联列）
 */
public final class SecondaryTableInfo {
    private final String tableName;
    private final String schema;
    private final String catalog;
    private final List<JoinColumnRef> pkJoinColumns;

    public SecondaryTableInfo(String tableName, String schema, String catalog, List<JoinColumnRef> pkJoinColumns) {
        this.tableName = tableName;
        this.schema = schema;
        this.catalog = catalog;
        this.pkJoinColumns = pkJoinColumns;
    }

    public SecondaryTableInfo(String tableName) {
        this(tableName, null, null, List.of());
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

    public List<JoinColumnRef> getPkJoinColumns() {
        return pkJoinColumns;
    }

    public SecondaryTableInfo copy(String tableName, String schema, String catalog, List<JoinColumnRef> pkJoinColumns) {
        return new SecondaryTableInfo(tableName, schema, catalog, pkJoinColumns);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SecondaryTableInfo)) {
            return false;
        }
        SecondaryTableInfo that = (SecondaryTableInfo) o;
        return Objects.equals(tableName, that.tableName)
                && Objects.equals(schema, that.schema)
                && Objects.equals(catalog, that.catalog)
                && Objects.equals(pkJoinColumns, that.pkJoinColumns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName, schema, catalog, pkJoinColumns);
    }

    @Override
    public String toString() {
        return "SecondaryTableInfo(tableName=" + tableName + ", schema=" + schema + ", catalog=" + catalog
                + ", pkJoinColumns=" + pkJoinColumns + ")";
    }
}
