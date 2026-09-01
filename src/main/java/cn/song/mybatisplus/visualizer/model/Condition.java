package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;

/**
 * WHERE / HAVING 条件树（M1 条件解析产物）。
 *
 * 设计为密封类层级：每种条件操作一种节点类型，顶层以 Connector.AND 连接多个条件。
 * 嵌套的 and(...) / or(...) 用 Group 表达，渲染时加括号。
 */
public abstract sealed class Condition
        permits Condition.Simple, Condition.Unary, Condition.In, Condition.Between,
        Condition.Like, Condition.Group, Condition.ColumnComparison, Condition.Raw {

    /** 动态条件元数据（M12）：布尔条件重载的解析产物，null 表示该条件不可勾选（非动态）。 */
    public abstract ConditionToggle getToggle();

    /** 复制并挂载/替换动态条件元数据（M12）。非动态条件传 null 保持原样。 */
    public Condition withToggle(ConditionToggle toggle) {
        if (this instanceof Simple s) {
            return s.copy(s.getColumn(), s.getOp(), s.getValue(), toggle);
        }
        if (this instanceof Unary u) {
            return u.copy(u.getColumn(), u.getOp(), toggle);
        }
        if (this instanceof In i) {
            return i.copy(i.getColumn(), i.isNegated(), i.getValues(), toggle);
        }
        if (this instanceof Between b) {
            return b.copy(b.getColumn(), b.isNegated(), b.getLo(), b.getHi(), toggle);
        }
        if (this instanceof Like l) {
            return l.copy(l.getColumn(), l.isNegated(), l.getPattern(), l.getPosition(), toggle);
        }
        if (this instanceof Group g) {
            return g.copy(g.getConnector(), g.getChildren(), toggle, g.isNegated());
        }
        if (this instanceof ColumnComparison c) {
            return c.copy(c.getLeft(), c.getRight(), toggle);
        }
        if (this instanceof Raw r) {
            return r.copy(r.getSqlText(), toggle);
        }
        throw new IllegalStateException("Unknown Condition subtype: " + getClass());
    }

    /** 二元比较条件：column op value。覆盖 eq(=)、ne(!=)、gt(>)、ge(>=)、lt(<)、le(<=) 等。 */
    public static final class Simple extends Condition {
        private final ColumnRef column;
        private final String op;
        private final SqlValue value;
        private final ConditionToggle toggle;

        public Simple(ColumnRef column, String op, SqlValue value, ConditionToggle toggle) {
            this.column = column;
            this.op = op;
            this.value = value;
            this.toggle = toggle;
        }

        public ColumnRef getColumn() {
            return column;
        }

        public String getOp() {
            return op;
        }

        public SqlValue getValue() {
            return value;
        }

        @Override
        public ConditionToggle getToggle() {
            return toggle;
        }

        public Simple copy(ColumnRef column, String op, SqlValue value, ConditionToggle toggle) {
            return new Simple(column, op, value, toggle);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Simple)) {
                return false;
            }
            Simple that = (Simple) o;
            return Objects.equals(column, that.column) && Objects.equals(op, that.op)
                    && Objects.equals(value, that.value) && Objects.equals(toggle, that.toggle);
        }

        @Override
        public int hashCode() {
            return Objects.hash(column, op, value, toggle);
        }

        @Override
        public String toString() {
            return "Simple(column=" + column + ", op=" + op + ", value=" + value + ", toggle=" + toggle + ")";
        }
    }

    /** 一元条件：column IS NULL / column IS NOT NULL。 */
    public static final class Unary extends Condition {
        private final ColumnRef column;
        private final String op;
        private final ConditionToggle toggle;

        public Unary(ColumnRef column, String op, ConditionToggle toggle) {
            this.column = column;
            this.op = op;
            this.toggle = toggle;
        }

        public ColumnRef getColumn() {
            return column;
        }

        public String getOp() {
            return op;
        }

        @Override
        public ConditionToggle getToggle() {
            return toggle;
        }

        public Unary copy(ColumnRef column, String op, ConditionToggle toggle) {
            return new Unary(column, op, toggle);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Unary)) {
                return false;
            }
            Unary that = (Unary) o;
            return Objects.equals(column, that.column) && Objects.equals(op, that.op)
                    && Objects.equals(toggle, that.toggle);
        }

        @Override
        public int hashCode() {
            return Objects.hash(column, op, toggle);
        }

        @Override
        public String toString() {
            return "Unary(column=" + column + ", op=" + op + ", toggle=" + toggle + ")";
        }
    }

    /** IN 列表条件：column IN (v1, v2, ...) / column NOT IN (...)。 */
    public static final class In extends Condition {
        private final ColumnRef column;
        private final boolean negated;
        private final List<SqlValue> values;
        private final ConditionToggle toggle;

        public In(ColumnRef column, boolean negated, List<SqlValue> values, ConditionToggle toggle) {
            this.column = column;
            this.negated = negated;
            this.values = values;
            this.toggle = toggle;
        }

        public ColumnRef getColumn() {
            return column;
        }

        public boolean isNegated() {
            return negated;
        }

        public boolean getNegated() {
            return negated;
        }

        public List<SqlValue> getValues() {
            return values;
        }

        @Override
        public ConditionToggle getToggle() {
            return toggle;
        }

        public In copy(ColumnRef column, boolean negated, List<SqlValue> values, ConditionToggle toggle) {
            return new In(column, negated, values, toggle);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof In)) {
                return false;
            }
            In that = (In) o;
            return negated == that.negated && Objects.equals(column, that.column)
                    && Objects.equals(values, that.values) && Objects.equals(toggle, that.toggle);
        }

        @Override
        public int hashCode() {
            return Objects.hash(column, negated, values, toggle);
        }

        @Override
        public String toString() {
            return "In(column=" + column + ", negated=" + negated + ", values=" + values + ", toggle=" + toggle + ")";
        }
    }

    /** BETWEEN 范围条件：column BETWEEN lo AND hi / column NOT BETWEEN ...。 */
    public static final class Between extends Condition {
        private final ColumnRef column;
        private final boolean negated;
        private final SqlValue lo;
        private final SqlValue hi;
        private final ConditionToggle toggle;

        public Between(ColumnRef column, boolean negated, SqlValue lo, SqlValue hi, ConditionToggle toggle) {
            this.column = column;
            this.negated = negated;
            this.lo = lo;
            this.hi = hi;
            this.toggle = toggle;
        }

        public ColumnRef getColumn() {
            return column;
        }

        public boolean isNegated() {
            return negated;
        }

        public boolean getNegated() {
            return negated;
        }

        public SqlValue getLo() {
            return lo;
        }

        public SqlValue getHi() {
            return hi;
        }

        @Override
        public ConditionToggle getToggle() {
            return toggle;
        }

        public Between copy(ColumnRef column, boolean negated, SqlValue lo, SqlValue hi, ConditionToggle toggle) {
            return new Between(column, negated, lo, hi, toggle);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Between)) {
                return false;
            }
            Between that = (Between) o;
            return negated == that.negated && Objects.equals(column, that.column)
                    && Objects.equals(lo, that.lo) && Objects.equals(hi, that.hi)
                    && Objects.equals(toggle, that.toggle);
        }

        @Override
        public int hashCode() {
            return Objects.hash(column, negated, lo, hi, toggle);
        }

        @Override
        public String toString() {
            return "Between(column=" + column + ", negated=" + negated + ", lo=" + lo + ", hi=" + hi
                    + ", toggle=" + toggle + ")";
        }
    }

    /** LIKE 模糊条件，位置语义见 LikePosition。 */
    public static final class Like extends Condition {
        private final ColumnRef column;
        private final boolean negated;
        private final SqlValue pattern;
        private final LikePosition position;
        private final ConditionToggle toggle;

        public Like(ColumnRef column, boolean negated, SqlValue pattern, LikePosition position, ConditionToggle toggle) {
            this.column = column;
            this.negated = negated;
            this.pattern = pattern;
            this.position = position;
            this.toggle = toggle;
        }

        public ColumnRef getColumn() {
            return column;
        }

        public boolean isNegated() {
            return negated;
        }

        public boolean getNegated() {
            return negated;
        }

        public SqlValue getPattern() {
            return pattern;
        }

        public LikePosition getPosition() {
            return position;
        }

        @Override
        public ConditionToggle getToggle() {
            return toggle;
        }

        public Like copy(ColumnRef column, boolean negated, SqlValue pattern, LikePosition position, ConditionToggle toggle) {
            return new Like(column, negated, pattern, position, toggle);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Like)) {
                return false;
            }
            Like that = (Like) o;
            return negated == that.negated && Objects.equals(column, that.column)
                    && Objects.equals(pattern, that.pattern) && position == that.position
                    && Objects.equals(toggle, that.toggle);
        }

        @Override
        public int hashCode() {
            return Objects.hash(column, negated, pattern, position, toggle);
        }

        @Override
        public String toString() {
            return "Like(column=" + column + ", negated=" + negated + ", pattern=" + pattern
                    + ", position=" + position + ", toggle=" + toggle + ")";
        }
    }

    /**
     * 逻辑分组（and / or 嵌套，或 and(consumer) / or(consumer) 的 lambda 体）。
     *
     * @param connector 组内子条件的连接符（AND 或 OR）
     * @param children  子条件（按调用顺序）
     * @param toggle    动态条件元数据（null 表示非动态）
     * @param negated   NOT 包裹（not(consumer) 官方 API，M15-P0）：渲染为 NOT (条件)
     */
    public static final class Group extends Condition {
        private final Connector connector;
        private final List<Condition> children;
        private final ConditionToggle toggle;
        private final boolean negated;

        public Group(Connector connector, List<Condition> children, ConditionToggle toggle, boolean negated) {
            this.connector = connector;
            this.children = children;
            this.toggle = toggle;
            this.negated = negated;
        }

        public Group(Connector connector, List<Condition> children, ConditionToggle toggle) {
            this(connector, children, toggle, false);
        }

        public Connector getConnector() {
            return connector;
        }

        public List<Condition> getChildren() {
            return children;
        }

        @Override
        public ConditionToggle getToggle() {
            return toggle;
        }

        public boolean isNegated() {
            return negated;
        }

        public boolean getNegated() {
            return negated;
        }

        public Group copy(Connector connector, List<Condition> children, ConditionToggle toggle, boolean negated) {
            return new Group(connector, children, toggle, negated);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Group)) {
                return false;
            }
            Group that = (Group) o;
            return negated == that.negated && connector == that.connector
                    && Objects.equals(children, that.children) && Objects.equals(toggle, that.toggle);
        }

        @Override
        public int hashCode() {
            return Objects.hash(connector, children, toggle, negated);
        }

        @Override
        public String toString() {
            return "Group(connector=" + connector + ", children=" + children + ", toggle=" + toggle
                    + ", negated=" + negated + ")";
        }
    }

    /** 列对列比较（MPJ 联表 ON 条件，F-11）：left = right。 */
    public static final class ColumnComparison extends Condition {
        private final ColumnRef left;
        private final ColumnRef right;
        private final ConditionToggle toggle;

        public ColumnComparison(ColumnRef left, ColumnRef right, ConditionToggle toggle) {
            this.left = left;
            this.right = right;
            this.toggle = toggle;
        }

        public ColumnRef getLeft() {
            return left;
        }

        public ColumnRef getRight() {
            return right;
        }

        @Override
        public ConditionToggle getToggle() {
            return toggle;
        }

        public ColumnComparison copy(ColumnRef left, ColumnRef right, ConditionToggle toggle) {
            return new ColumnComparison(left, right, toggle);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ColumnComparison)) {
                return false;
            }
            ColumnComparison that = (ColumnComparison) o;
            return Objects.equals(left, that.left) && Objects.equals(right, that.right)
                    && Objects.equals(toggle, that.toggle);
        }

        @Override
        public int hashCode() {
            return Objects.hash(left, right, toggle);
        }

        @Override
        public String toString() {
            return "ColumnComparison(left=" + left + ", right=" + right + ", toggle=" + toggle + ")";
        }
    }

    /** 原始 SQL 片段（apply / last 等透传），不解析直接渲染。 */
    public static final class Raw extends Condition {
        private final String sqlText;
        private final ConditionToggle toggle;

        public Raw(String sqlText, ConditionToggle toggle) {
            this.sqlText = sqlText;
            this.toggle = toggle;
        }

        public Raw(String sqlText) {
            this(sqlText, null);
        }

        public String getSqlText() {
            return sqlText;
        }

        @Override
        public ConditionToggle getToggle() {
            return toggle;
        }

        public Raw copy(String sqlText, ConditionToggle toggle) {
            return new Raw(sqlText, toggle);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Raw)) {
                return false;
            }
            Raw that = (Raw) o;
            return Objects.equals(sqlText, that.sqlText) && Objects.equals(toggle, that.toggle);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sqlText, toggle);
        }

        @Override
        public String toString() {
            return "Raw(sqlText=" + sqlText + ", toggle=" + toggle + ")";
        }
    }
}
