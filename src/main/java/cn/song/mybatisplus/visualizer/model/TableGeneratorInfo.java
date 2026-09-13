package cn.song.mybatisplus.visualizer.model;

import java.util.Objects;

/**
 * 序列表生成器（T-J14 @TableGenerator）。
 *
 * @param name           生成器名（@GeneratedValue(generator = "...") 引用键）
 * @param table          序列表名（默认 "hibernate_sequences"；未显式指定为 null）
 * @param pkColumnName   序列表主键列名（默认 "sequence_name"）
 * @param valueColumnName 序列表值列名（默认 "next_val"）
 * @param pkColumnValue  序列表主键值（默认实体名）
 * @param allocationSize 分配大小（默认 50；未显式指定为 null）
 */
public final class TableGeneratorInfo implements GeneratorInfo {
    private final String name;
    private final String table;
    private final String pkColumnName;
    private final String valueColumnName;
    private final String pkColumnValue;
    private final String allocationSize;

    public TableGeneratorInfo(String name, String table, String pkColumnName,
                              String valueColumnName, String pkColumnValue, String allocationSize) {
        this.name = name;
        this.table = table;
        this.pkColumnName = pkColumnName;
        this.valueColumnName = valueColumnName;
        this.pkColumnValue = pkColumnValue;
        this.allocationSize = allocationSize;
    }

    public TableGeneratorInfo(String name, String table) {
        this(name, table, null, null, null, null);
    }

    @Override
    public String getName() {
        return name;
    }

    public String getTable() {
        return table;
    }

    public String getPkColumnName() {
        return pkColumnName;
    }

    public String getValueColumnName() {
        return valueColumnName;
    }

    public String getPkColumnValue() {
        return pkColumnValue;
    }

    public String getAllocationSize() {
        return allocationSize;
    }

    public TableGeneratorInfo copy(String name, String table, String pkColumnName,
                                   String valueColumnName, String pkColumnValue, String allocationSize) {
        return new TableGeneratorInfo(name, table, pkColumnName, valueColumnName, pkColumnValue, allocationSize);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TableGeneratorInfo)) {
            return false;
        }
        TableGeneratorInfo that = (TableGeneratorInfo) o;
        return Objects.equals(name, that.name)
                && Objects.equals(table, that.table)
                && Objects.equals(pkColumnName, that.pkColumnName)
                && Objects.equals(valueColumnName, that.valueColumnName)
                && Objects.equals(pkColumnValue, that.pkColumnValue)
                && Objects.equals(allocationSize, that.allocationSize);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, table, pkColumnName, valueColumnName, pkColumnValue, allocationSize);
    }

    @Override
    public String toString() {
        return "TableGeneratorInfo(name=" + name + ", table=" + table + ", pkColumnName=" + pkColumnName
                + ", valueColumnName=" + valueColumnName + ", pkColumnValue=" + pkColumnValue
                + ", allocationSize=" + allocationSize + ")";
    }
}
