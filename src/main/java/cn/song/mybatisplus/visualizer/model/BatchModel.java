package cn.song.mybatisplus.visualizer.model;

import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.MySqlDialect;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 批量可选参数模型（T-M23-04）：INSERT/UPDATE/UPSERT 模板的列级勾选结构。
 *
 * 驱动面板勾选重建 SQL：save(entity) 自增主键默认不勾选（对齐 MP：IdType.AUTO 不进 INSERT 列清单）；
 * updateById(entity) 的 SET 字段可按需取消（近似 MP 默认 FieldStrategy.NOT_NULL 的"只更新需要修改的字段"）。
 *
 * renderSql 是唯一渲染源——转换器初始 SQL 与面板勾选重建共用，保证两者逐字符一致；
 * 本类为纯函数，不依赖 PSI / IDE，可独立单测。
 */
public final class BatchModel {
    private final BatchKind kind;
    private final String tableName;
    private final List<BatchColumn> toggleColumns;
    private final List<BatchColumn> fixedColumns;
    private final String versionColumn;
    private final String conflictColumn;
    private final Dialect dialect;

    public BatchModel(BatchKind kind, String tableName, List<BatchColumn> toggleColumns,
                      List<BatchColumn> fixedColumns, String versionColumn, String conflictColumn, Dialect dialect) {
        this.kind = kind;
        this.tableName = tableName;
        this.toggleColumns = toggleColumns;
        this.fixedColumns = fixedColumns;
        this.versionColumn = versionColumn;
        this.conflictColumn = conflictColumn;
        this.dialect = dialect;
    }

    public BatchModel(BatchKind kind, String tableName, List<BatchColumn> toggleColumns,
                      List<BatchColumn> fixedColumns, String versionColumn, String conflictColumn) {
        this(kind, tableName, toggleColumns, fixedColumns, versionColumn, conflictColumn, MySqlDialect.INSTANCE);
    }

    public BatchModel(BatchKind kind, String tableName, List<BatchColumn> toggleColumns,
                      List<BatchColumn> fixedColumns, String versionColumn) {
        this(kind, tableName, toggleColumns, fixedColumns, versionColumn, null, MySqlDialect.INSTANCE);
    }

    public BatchModel(BatchKind kind, String tableName, List<BatchColumn> toggleColumns,
                      List<BatchColumn> fixedColumns) {
        this(kind, tableName, toggleColumns, fixedColumns, null, null, MySqlDialect.INSTANCE);
    }

    public BatchModel(BatchKind kind, String tableName, List<BatchColumn> toggleColumns) {
        this(kind, tableName, toggleColumns, List.of(), null, null, MySqlDialect.INSTANCE);
    }

    public BatchKind getKind() {
        return kind;
    }

    public String getTableName() {
        return tableName;
    }

    public List<BatchColumn> getToggleColumns() {
        return toggleColumns;
    }

    public List<BatchColumn> getFixedColumns() {
        return fixedColumns;
    }

    public String getVersionColumn() {
        return versionColumn;
    }

    public String getConflictColumn() {
        return conflictColumn;
    }

    public Dialect getDialect() {
        return dialect;
    }

    /**
     * 渲染顺序的列（? 出现顺序 = 参数行顺序）：
     * UPDATE 先 SET（toggle）后 WHERE（fixed）；INSERT/UPSERT 固定列在前（UPSERT 无固定列时即 toggle 顺序）。
     */
    public List<BatchColumn> orderedColumns(Set<String> activeColumns) {
        List<BatchColumn> result = new ArrayList<>();
        if (kind == BatchKind.UPDATE) {
            for (BatchColumn c : toggleColumns) {
                if (activeColumns.contains(c.getColumnName())) {
                    result.add(c);
                }
            }
            result.addAll(fixedColumns);
        } else {
            result.addAll(fixedColumns);
            for (BatchColumn c : toggleColumns) {
                if (activeColumns.contains(c.getColumnName())) {
                    result.add(c);
                }
            }
        }
        return result;
    }

    /** 渲染顺序的参数来源（面板 backfill 与参数行共用）。 */
    public List<String> orderedSources(Set<String> activeColumns) {
        List<BatchColumn> cols = orderedColumns(activeColumns);
        List<String> result = new ArrayList<>();
        for (BatchColumn c : cols) {
            result.add(c.getSource());
        }
        return result;
    }

    /** 渲染顺序的回填引号提示。 */
    public List<ParamHint> orderedHints(Set<String> activeColumns) {
        List<BatchColumn> cols = orderedColumns(activeColumns);
        List<ParamHint> result = new ArrayList<>();
        for (BatchColumn c : cols) {
            result.add(new ParamHint(c.getQuote(), null, false));
        }
        return result;
    }

    /**
     * 按活跃列重建 SQL。
     *
     * @param activeColumns 勾选中的列名集合（null → 全部按默认勾选态）
     */
    public String renderSql(Set<String> activeColumns) {
        if (kind == BatchKind.INSERT) {
            List<String> cols = new ArrayList<>();
            for (BatchColumn c : toggleColumns) {
                if (activeColumns.contains(c.getColumnName())) {
                    cols.add(dialect.quoteIfKeyword(c.getColumnName()));
                }
            }
            String colList = String.join(", ", cols);
            String marks = String.join(", ", Collections.nCopies(cols.size(), "?"));
            return "INSERT INTO " + dialect.quoteIfKeyword(tableName) + " (" + colList + ")\nVALUES (" + marks + ")";
        } else if (kind == BatchKind.UPDATE) {
            StringBuilder setSql = new StringBuilder();
            boolean first = true;
            for (BatchColumn c : toggleColumns) {
                if (activeColumns.contains(c.getColumnName())) {
                    if (!first) {
                        setSql.append(", ");
                    }
                    setSql.append(dialect.quoteIfKeyword(c.getColumnName())).append(" = ?");
                    first = false;
                }
            }
            if (versionColumn != null) {
                if (!first) {
                    setSql.append(", ");
                }
                setSql.append(dialect.quoteIfKeyword(versionColumn)).append(" = ").append(versionColumn).append(" + 1");
                first = false;
            }
            List<String> whereParts = new ArrayList<>();
            for (BatchColumn c : fixedColumns) {
                whereParts.add(dialect.quoteIfKeyword(c.getColumnName()) + " = ?");
            }
            String whereSql = String.join(" AND ", whereParts);
            return "UPDATE " + dialect.quoteIfKeyword(tableName) + "\nSET " + setSql + "\nWHERE " + whereSql;
        } else {
            List<BatchColumn> toggles = new ArrayList<>();
            for (BatchColumn c : toggleColumns) {
                if (activeColumns.contains(c.getColumnName())) {
                    toggles.add(c);
                }
            }
            String conflict = (conflictColumn != null && activeColumns.contains(conflictColumn)) ? conflictColumn : null;
            List<String> cols = new ArrayList<>();
            for (BatchColumn c : toggles) {
                cols.add(dialect.quoteIfKeyword(c.getColumnName()));
            }
            String base = "INSERT INTO " + dialect.quoteIfKeyword(tableName) + " (" + String.join(", ", cols)
                    + ")\nVALUES (" + String.join(", ", Collections.nCopies(cols.size(), "?")) + ")";
            if (conflict == null) {
                return base;
            }
            List<String> toggleNames = new ArrayList<>();
            for (BatchColumn c : toggles) {
                toggleNames.add(c.getColumnName());
            }
            String upsertStmt = dialect.renderUpsertStatement(tableName, toggleNames, conflict);
            if (upsertStmt != null) {
                return upsertStmt;
            }
            List<String> nonConflictNames = new ArrayList<>();
            for (BatchColumn c : toggles) {
                if (!conflict.equals(c.getColumnName())) {
                    nonConflictNames.add(c.getColumnName());
                }
            }
            String suffix = dialect.renderUpsert(conflict, nonConflictNames);
            if (suffix != null) {
                return base + "\n" + suffix;
            }
            return base;
        }
    }

    public BatchModel copy(BatchKind kind, String tableName, List<BatchColumn> toggleColumns,
                           List<BatchColumn> fixedColumns, String versionColumn, String conflictColumn, Dialect dialect) {
        return new BatchModel(kind, tableName, toggleColumns, fixedColumns, versionColumn, conflictColumn, dialect);
    }

    public BatchModel copy(Dialect dialect) {
        return new BatchModel(kind, tableName, toggleColumns, fixedColumns, versionColumn, conflictColumn, dialect);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BatchModel)) {
            return false;
        }
        BatchModel that = (BatchModel) o;
        return kind == that.kind
                && Objects.equals(tableName, that.tableName)
                && Objects.equals(toggleColumns, that.toggleColumns)
                && Objects.equals(fixedColumns, that.fixedColumns)
                && Objects.equals(versionColumn, that.versionColumn)
                && Objects.equals(conflictColumn, that.conflictColumn)
                && Objects.equals(dialect, that.dialect);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, tableName, toggleColumns, fixedColumns, versionColumn, conflictColumn, dialect);
    }

    @Override
    public String toString() {
        return "BatchModel(kind=" + kind + ", tableName=" + tableName + ", toggleColumns=" + toggleColumns
                + ", fixedColumns=" + fixedColumns + ", versionColumn=" + versionColumn
                + ", conflictColumn=" + conflictColumn + ", dialect=" + dialect + ")";
    }
}
