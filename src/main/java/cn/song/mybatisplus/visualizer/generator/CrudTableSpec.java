package cn.song.mybatisplus.visualizer.generator;

import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.MySqlDialect;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * 统一表描述（T-CrudSql-01）：实体类与数据库表两个入口的公共目标模型。
 *
 * @param tableName         表名
 * @param columns           列清单（顺序即渲染顺序：实体侧经角色分组稳定排序；DB 侧自省顺序）
 * @param logicDeleteColumn 逻辑删除列（实体侧 @TableLogic；DB 侧无此元数据恒 null——
 *                          物理删除之外是否提供逻辑删除变体由本列决定）
 * @param versionColumn     乐观锁版本列（实体侧 @Version，对齐 updateById 语义：SET 由
 *                          {@code version = version + 1} 取代、WHERE 末尾追加；DB 侧无此元数据恒 null）
 * @param dialect           方言（保留字引用包裹；读插件设置，缺省 MySQL）
 */
public final class CrudTableSpec {
    private final String tableName;
    private final List<CrudColumn> columns;
    private final String logicDeleteColumn;
    private final String versionColumn;
    private final Dialect dialect;

    public CrudTableSpec(String tableName, List<CrudColumn> columns, @Nullable String logicDeleteColumn,
                         @Nullable String versionColumn, Dialect dialect) {
        this.tableName = tableName;
        this.columns = columns;
        this.logicDeleteColumn = logicDeleteColumn;
        this.versionColumn = versionColumn;
        this.dialect = dialect;
    }

    public CrudTableSpec(String tableName, List<CrudColumn> columns, @Nullable String logicDeleteColumn,
                         @Nullable String versionColumn) {
        this(tableName, columns, logicDeleteColumn, versionColumn, MySqlDialect.INSTANCE);
    }

    public CrudTableSpec(String tableName, List<CrudColumn> columns, @Nullable String logicDeleteColumn) {
        this(tableName, columns, logicDeleteColumn, null, MySqlDialect.INSTANCE);
    }

    public CrudTableSpec(String tableName, List<CrudColumn> columns) {
        this(tableName, columns, null, null, MySqlDialect.INSTANCE);
    }

    public String getTableName() {
        return tableName;
    }

    public List<CrudColumn> getColumns() {
        return columns;
    }

    @Nullable
    public String getLogicDeleteColumn() {
        return logicDeleteColumn;
    }

    @Nullable
    public String getVersionColumn() {
        return versionColumn;
    }

    public Dialect getDialect() {
        return dialect;
    }

    /** 主键列（保持 {@link #getColumns()} 顺序；复合主键 AND 连接）。 */
    public List<CrudColumn> getPrimaryKeys() {
        List<CrudColumn> result = new ArrayList<>();
        for (CrudColumn column : columns) {
            if (column.isPrimaryKey()) {
                result.add(column);
            }
        }
        return result;
    }

    /** 是否识别到主键——UPDATE/DELETE/按主键 SELECT 的 WHERE 子句依赖；无主键走降级形态。 */
    public boolean hasPrimaryKey() {
        return !getPrimaryKeys().isEmpty();
    }

    /** 兼容 Kotlin 属性 getter 命名（getHasPrimaryKey）。 */
    public boolean getHasPrimaryKey() {
        return hasPrimaryKey();
    }

    public CrudTableSpec copy(String tableName, List<CrudColumn> columns, @Nullable String logicDeleteColumn,
                              @Nullable String versionColumn, Dialect dialect) {
        return new CrudTableSpec(tableName, columns, logicDeleteColumn, versionColumn, dialect);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CrudTableSpec)) {
            return false;
        }
        CrudTableSpec that = (CrudTableSpec) o;
        return Objects.equals(tableName, that.tableName)
                && Objects.equals(columns, that.columns)
                && Objects.equals(logicDeleteColumn, that.logicDeleteColumn)
                && Objects.equals(versionColumn, that.versionColumn)
                && Objects.equals(dialect, that.dialect);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName, columns, logicDeleteColumn, versionColumn, dialect);
    }

    @Override
    public String toString() {
        return "CrudTableSpec(tableName=" + tableName + ", columns=" + columns + ", logicDeleteColumn="
                + logicDeleteColumn + ", versionColumn=" + versionColumn + ", dialect=" + dialect + ")";
    }
}
