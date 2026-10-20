package cn.song.mybatisplus.visualizer.model;

import java.util.Objects;

/**
 * SQL 参数值（F-02 参数替换的基础模型）。
 *
 * Wrapper 调用实参在源码中的可读性分为两类：
 *  - 字面量/可静态求值的常量：可直接渲染进 SQL（如 1001L → 1001、"abc" → 'abc'）
 *  - 运行时变量/复杂表达式：源码中无法确定值，渲染为 ? 占位符（M2 再做变量追踪替换）
 */
public abstract sealed class SqlValue permits SqlValue.Literal, SqlValue.Placeholder {

    /** 该值渲染后是否为 ? 占位符。 */
    public boolean isPlaceholder() {
        return this instanceof Placeholder;
    }

    /**
     * 源码中可直接确定的值。
     *
     * @param raw      渲染进 SQL 的文本（字符串已带单引号 'abc'，数值原样 1001）
     * @param unquoted 原始值文本（字符串无引号 abc，数值与 raw 相同）。
     *                 用于 SQL 片段场景（last("limit 10")、setSql、selectAs 别名、字符串列名），
     *                 这些场景不需要引号包裹。
     */
    public static final class Literal extends SqlValue {
        private final String raw;
        private final String unquoted;

        public Literal(String raw, String unquoted) {
            this.raw = raw;
            this.unquoted = unquoted;
        }

        public Literal(String raw) {
            this(raw, raw);
        }

        public String getRaw() {
            return raw;
        }

        public String getUnquoted() {
            return unquoted;
        }

        public Literal copy(String raw, String unquoted) {
            return new Literal(raw, unquoted);
        }

        public Literal copy(String raw) {
            return new Literal(raw, raw);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Literal)) {
                return false;
            }
            Literal that = (Literal) o;
            return Objects.equals(raw, that.raw) && Objects.equals(unquoted, that.unquoted);
        }

        @Override
        public int hashCode() {
            return Objects.hash(raw, unquoted);
        }

        @Override
        public String toString() {
            return "Literal(raw=" + raw + ", unquoted=" + unquoted + ")";
        }
    }

    /**
     * 运行时值，渲染为占位符。
     *
     * @param source 参数来源描述（变量名或表达式文本），用于 M2 参数替换与对照表
     * @param fragment 该表达式作为<b>原始 SQL 片段</b>使用时的形态（文本 + 占位符来源，
     *                 {@code "amount = amount * " + rate} → {@code amount = amount * ?}，来源 {@code [rate]}）。
     *                 仅字符串拼接时非 null（见 ExpressionEvaluator）。
     *                 片段位置（setSql/apply/last/having 等不做参数绑定的入口）经
     *                 {@code converter.asSqlFragment} 取用；值位置（eq/set 的值实参）恒渲染
     *                 {@code ?}，不读本字段——那里整个字符串是一个绑定参数。
     */
    public static final class Placeholder extends SqlValue {
        private final String source;
        private final SqlFragment fragment;

        public Placeholder(String source, SqlFragment fragment) {
            this.source = source;
            this.fragment = fragment;
        }

        public Placeholder(String source) {
            this(source, null);
        }

        public String getSource() {
            return source;
        }

        public SqlFragment getFragment() {
            return fragment;
        }

        public Placeholder copy(String source, SqlFragment fragment) {
            return new Placeholder(source, fragment);
        }

        public Placeholder copy(String source) {
            return new Placeholder(source, fragment);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Placeholder)) {
                return false;
            }
            Placeholder that = (Placeholder) o;
            return Objects.equals(source, that.source) && Objects.equals(fragment, that.fragment);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, fragment);
        }

        @Override
        public String toString() {
            return "Placeholder(source=" + source + ", fragment=" + fragment + ")";
        }
    }
}
