package cn.song.mybatisplus.visualizer.model;

import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.IdentifierCaseSource;
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
    /**
     * 名字大小写来源（O-42）：目录来源 {@link IdentifierCaseSource#CATALOG} 时按方言的按需引用覆写判定
     * （Oracle 含小写字母即加引号）；缺省 {@link IdentifierCaseSource#ENTITY} = 既有 {@link Dialect#quoteIfKeyword}
     * 行为逐字不变。本类既有构造点（实体转换链、批量转换链、面板测试）全部走默认值，不受影响。
     */
    private final IdentifierCaseSource identifierCaseSource;

    public BatchModel(BatchKind kind, String tableName, List<BatchColumn> toggleColumns,
                      List<BatchColumn> fixedColumns, String versionColumn, String conflictColumn, Dialect dialect,
                      IdentifierCaseSource identifierCaseSource) {
        this.kind = kind;
        this.tableName = tableName;
        this.toggleColumns = toggleColumns;
        this.fixedColumns = fixedColumns;
        this.versionColumn = versionColumn;
        this.conflictColumn = conflictColumn;
        this.dialect = dialect;
        this.identifierCaseSource = identifierCaseSource;
    }

    public BatchModel(BatchKind kind, String tableName, List<BatchColumn> toggleColumns,
                      List<BatchColumn> fixedColumns, String versionColumn, String conflictColumn, Dialect dialect) {
        this(kind, tableName, toggleColumns, fixedColumns, versionColumn, conflictColumn, dialect,
                IdentifierCaseSource.ENTITY);
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

    public IdentifierCaseSource getIdentifierCaseSource() {
        return identifierCaseSource;
    }

    /** 列名/表名的按需引用（按名字来源分流；未接线点即 {@link IdentifierCaseSource#ENTITY} 旧行为）。 */
    private String q(String identifier) {
        return dialect.quoteIfNeeded(identifier, identifierCaseSource);
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
                    cols.add(q(c.getColumnName()));
                }
            }
            String colList = String.join(", ", cols);
            String marks = String.join(", ", Collections.nCopies(cols.size(), "?"));
            return "INSERT INTO " + q(tableName) + " (" + colList + ")\nVALUES (" + marks + ")";
        } else if (kind == BatchKind.UPDATE) {
            StringBuilder setSql = new StringBuilder();
            boolean first = true;
            for (BatchColumn c : toggleColumns) {
                if (activeColumns.contains(c.getColumnName())) {
                    if (!first) {
                        setSql.append(", ");
                    }
                    setSql.append(q(c.getColumnName())).append(" = ?");
                    first = false;
                }
            }
            if (versionColumn != null) {
                if (!first) {
                    setSql.append(", ");
                }
                setSql.append(q(versionColumn)).append(" = ").append(versionColumn).append(" + 1");
                first = false;
            }
            List<String> whereParts = new ArrayList<>();
            for (BatchColumn c : fixedColumns) {
                whereParts.add(q(c.getColumnName()) + " = ?");
            }
            String whereSql = String.join(" AND ", whereParts);
            return "UPDATE " + q(tableName) + "\nSET " + setSql + "\nWHERE " + whereSql;
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
                cols.add(q(c.getColumnName()));
            }
            String base = "INSERT INTO " + q(tableName) + " (" + String.join(", ", cols)
                    + ")\nVALUES (" + String.join(", ", Collections.nCopies(cols.size(), "?")) + ")";
            if (conflict == null) {
                // 主键未勾选（新记录插入路径）→ 纯 INSERT，无冲突子句
                return base;
            }
            List<String> toggleNames = new ArrayList<>();
            for (BatchColumn c : toggles) {
                toggleNames.add(c.getColumnName());
            }
            // P-立项-05：整语句形态优先（H2 原生 upsert 为 MERGE INTO..KEY..VALUES，
            // 无法以「INSERT+后缀」表达）；null → 后缀形态；后缀亦 null（Oracle/SQL Server/DM
            // 无原生简洁 upsert）→ 降级纯 INSERT
            //
            // O-42：这两个方言辅助内部仍走既有 quoteIfKeyword（未接线）。当前**无行为差异**——
            // Oracle 的 upsert 两条路径都返回 null（不进入渲染），其余方言的 quoteIfNeeded
            // 默认即委托 quoteIfKeyword。若日后为别的方言引入目录来源判定，此处须一并接线。
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
        return new BatchModel(kind, tableName, toggleColumns, fixedColumns, versionColumn, conflictColumn, dialect,
                identifierCaseSource);
    }

    public BatchModel copy(Dialect dialect) {
        return new BatchModel(kind, tableName, toggleColumns, fixedColumns, versionColumn, conflictColumn, dialect,
                identifierCaseSource);
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
                && Objects.equals(dialect, that.dialect)
                && identifierCaseSource == that.identifierCaseSource;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, tableName, toggleColumns, fixedColumns, versionColumn, conflictColumn, dialect,
                identifierCaseSource);
    }

    @Override
    public String toString() {
        return "BatchModel(kind=" + kind + ", tableName=" + tableName + ", toggleColumns=" + toggleColumns
                + ", fixedColumns=" + fixedColumns + ", versionColumn=" + versionColumn
                + ", conflictColumn=" + conflictColumn + ", dialect=" + dialect
                + ", identifierCaseSource=" + identifierCaseSource + ")";
    }
}
