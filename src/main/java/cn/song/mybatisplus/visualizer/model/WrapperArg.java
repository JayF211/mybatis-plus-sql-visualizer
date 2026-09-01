package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;
import kotlin.Pair;

/**
 * Wrapper 方法实参（psi 层解析产物）。
 *
 * 区分四类：
 *  - ColumnArg：列引用实参（Entity::getXxx 方法引用或 MPJ 的字符串字段）
 *  - ValueArg：值实参（字面量 / 运行时变量 / 表达式）
 *  - NestedArg：嵌套调用链实参（and(consumer) / and(wrapper) 的 lambda 体或子 wrapper）
 *  - OnConditionArg：MPJ on(col1, col2) 两列比较实参（F-11 联表 ON 条件）
 */
public abstract sealed class WrapperArg
        permits WrapperArg.ColumnArg, WrapperArg.ValueArg, WrapperArg.NestedArg, WrapperArg.AliasArg,
        WrapperArg.FuncArg, WrapperArg.EntityRefArg, WrapperArg.OnConditionArg, WrapperArg.MapArg,
        WrapperArg.PredicateArg {

    /** 列引用实参。 */
    public static final class ColumnArg extends WrapperArg {
        private final ColumnRef column;

        public ColumnArg(ColumnRef column) {
            this.column = column;
        }

        public ColumnRef getColumn() {
            return column;
        }

        public ColumnArg copy(ColumnRef column) {
            return new ColumnArg(column);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ColumnArg)) {
                return false;
            }
            ColumnArg that = (ColumnArg) o;
            return Objects.equals(column, that.column);
        }

        @Override
        public int hashCode() {
            return Objects.hash(column);
        }

        @Override
        public String toString() {
            return "ColumnArg(column=" + column + ")";
        }
    }

    /** 值实参。 */
    public static final class ValueArg extends WrapperArg {
        private final SqlValue value;

        public ValueArg(SqlValue value) {
            this.value = value;
        }

        public SqlValue getValue() {
            return value;
        }

        public ValueArg copy(SqlValue value) {
            return new ValueArg(value);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ValueArg)) {
                return false;
            }
            ValueArg that = (ValueArg) o;
            return Objects.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value);
        }

        @Override
        public String toString() {
            return "ValueArg(value=" + value + ")";
        }
    }

    /** 嵌套调用链实参（and/or 的 lambda 体）。 */
    public static final class NestedArg extends WrapperArg {
        private final WrapperChain chain;

        public NestedArg(WrapperChain chain) {
            this.chain = chain;
        }

        public WrapperChain getChain() {
            return chain;
        }

        public NestedArg copy(WrapperChain chain) {
            return new NestedArg(chain);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof NestedArg)) {
                return false;
            }
            NestedArg that = (NestedArg) o;
            return Objects.equals(chain, that.chain);
        }

        @Override
        public int hashCode() {
            return Objects.hash(chain);
        }

        @Override
        public String toString() {
            return "NestedArg(chain=" + chain + ")";
        }
    }

    /** MPJ selectAs 目标 VO 字段引用（selectAs(Entity::getX, VO::getY) 第二参）：别名 = VO 属性名（保留驼峰）。 */
    public static final class AliasArg extends WrapperArg {
        private final String propertyName;
        private final String returnType;

        public AliasArg(String propertyName, String returnType) {
            this.propertyName = propertyName;
            this.returnType = returnType;
        }

        public AliasArg(String propertyName) {
            this(propertyName, null);
        }

        public String getPropertyName() {
            return propertyName;
        }

        public String getReturnType() {
            return returnType;
        }

        public AliasArg copy(String propertyName, String returnType) {
            return new AliasArg(propertyName, returnType);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AliasArg)) {
                return false;
            }
            AliasArg that = (AliasArg) o;
            return Objects.equals(propertyName, that.propertyName) && Objects.equals(returnType, that.returnType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(propertyName, returnType);
        }

        @Override
        public String toString() {
            return "AliasArg(propertyName=" + propertyName + ", returnType=" + returnType + ")";
        }
    }

    /** MPJ selectFunc 聚合函数实参（selectFunc("COUNT(%s)", func -> func.accept(列).values(值), "alias")）。 */
    public static final class FuncArg extends WrapperArg {
        private final List<ColumnRef> columns;
        private final List<SqlValue> values;

        public FuncArg(List<ColumnRef> columns, List<SqlValue> values) {
            this.columns = columns;
            this.values = values;
        }

        public List<ColumnRef> getColumns() {
            return columns;
        }

        public List<SqlValue> getValues() {
            return values;
        }

        public FuncArg copy(List<ColumnRef> columns, List<SqlValue> values) {
            return new FuncArg(columns, values);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof FuncArg)) {
                return false;
            }
            FuncArg that = (FuncArg) o;
            return Objects.equals(columns, that.columns) && Objects.equals(values, that.values);
        }

        @Override
        public int hashCode() {
            return Objects.hash(columns, values);
        }

        @Override
        public String toString() {
            return "FuncArg(columns=" + columns + ", values=" + values + ")";
        }
    }

    /** MPJ join 的关联实体引用（leftJoin(XxxEntity.class, ...) 第一参）：携带实体解析的表信息（F-13 扩展）。 */
    public static final class EntityRefArg extends WrapperArg {
        private final TableInfo tableInfo;

        public EntityRefArg(TableInfo tableInfo) {
            this.tableInfo = tableInfo;
        }

        public TableInfo getTableInfo() {
            return tableInfo;
        }

        public EntityRefArg copy(TableInfo tableInfo) {
            return new EntityRefArg(tableInfo);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof EntityRefArg)) {
                return false;
            }
            EntityRefArg that = (EntityRefArg) o;
            return Objects.equals(tableInfo, that.tableInfo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tableInfo);
        }

        @Override
        public String toString() {
            return "EntityRefArg(tableInfo=" + tableInfo + ")";
        }
    }

    /** MPJ on(col1, col2) 两列比较（left 主表列，right 关联表列，别名由 converter 补充）。 */
    public static final class OnConditionArg extends WrapperArg {
        private final ColumnRef left;
        private final ColumnRef right;

        public OnConditionArg(ColumnRef left, ColumnRef right) {
            this.left = left;
            this.right = right;
        }

        public ColumnRef getLeft() {
            return left;
        }

        public ColumnRef getRight() {
            return right;
        }

        public OnConditionArg copy(ColumnRef left, ColumnRef right) {
            return new OnConditionArg(left, right);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof OnConditionArg)) {
                return false;
            }
            OnConditionArg that = (OnConditionArg) o;
            return Objects.equals(left, that.left) && Objects.equals(right, that.right);
        }

        @Override
        public int hashCode() {
            return Objects.hash(left, right);
        }

        @Override
        public String toString() {
            return "OnConditionArg(left=" + left + ", right=" + right + ")";
        }
    }

    /**
     * allEq 的 Map 实参（T-M21-04，MP 官方 API）：
     * allEq(Map.of(k1, v1, ...)) / Map.ofEntries(...) 调用静态展开的键值对
     * （String 键 → ColumnRef，columnName = 键）；非 Map.of 的 Map 变量 → 空 entries（converter 渲染可读占位）。
     *
     * @param entries     键（列名）→ 值（字面量/占位符）对
     * @param null2IsNull allEq(Map, null2IsNull) 语义：true 且值静态可判 null 时渲染 col IS NULL
     */
    public static final class MapArg extends WrapperArg {
        private final List<Pair<ColumnRef, SqlValue>> entries;
        private final boolean null2IsNull;

        public MapArg(List<Pair<ColumnRef, SqlValue>> entries, boolean null2IsNull) {
            this.entries = entries;
            this.null2IsNull = null2IsNull;
        }

        public List<Pair<ColumnRef, SqlValue>> getEntries() {
            return entries;
        }

        public boolean isNull2IsNull() {
            return null2IsNull;
        }

        public boolean getNull2IsNull() {
            return null2IsNull;
        }

        public MapArg copy(List<Pair<ColumnRef, SqlValue>> entries, boolean null2IsNull) {
            return new MapArg(entries, null2IsNull);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MapArg)) {
                return false;
            }
            MapArg that = (MapArg) o;
            return null2IsNull == that.null2IsNull && Objects.equals(entries, that.entries);
        }

        @Override
        public int hashCode() {
            return Objects.hash(entries, null2IsNull);
        }

        @Override
        public String toString() {
            return "MapArg(entries=" + entries + ", null2IsNull=" + null2IsNull + ")";
        }
    }

    /**
     * selectFilter / select(Class, Predicate) 的列过滤谓词（T-M21-01，静态白/黑名单近似）：
     * lambda 体内方法调用实参的字符串字面量（!t.getProperty().equals("xxx") 的 "xxx"、
     * t.getColumn().equals("col_name") 等）→ 候选字段名；lambda 体含 ! 否定 → 黑名单 exclude，
     * 否则白名单 include。两者均空 = 谓词无法静态分析（converter 回退全列）。
     */
    public static final class PredicateArg extends WrapperArg {
        private final List<String> include;
        private final List<String> exclude;

        public PredicateArg(List<String> include, List<String> exclude) {
            this.include = include;
            this.exclude = exclude;
        }

        public List<String> getInclude() {
            return include;
        }

        public List<String> getExclude() {
            return exclude;
        }

        public PredicateArg copy(List<String> include, List<String> exclude) {
            return new PredicateArg(include, exclude);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PredicateArg)) {
                return false;
            }
            PredicateArg that = (PredicateArg) o;
            return Objects.equals(include, that.include) && Objects.equals(exclude, that.exclude);
        }

        @Override
        public int hashCode() {
            return Objects.hash(include, exclude);
        }

        @Override
        public String toString() {
            return "PredicateArg(include=" + include + ", exclude=" + exclude + ")";
        }
    }
}
