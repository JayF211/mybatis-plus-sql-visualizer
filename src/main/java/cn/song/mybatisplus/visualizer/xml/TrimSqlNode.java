package cn.song.mybatisplus.visualizer.xml;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * trim 节点（T-M24-01）：复刻 MyBatis 3.5.19 org.apache.ibatis.scripting.xmltags.TrimSqlNode。
 *
 * 算法（FilteredDynamicContext.applyAll）：
 *  1. 子内容整体 trim()（去首尾空白含换行）；
 *  2. 裁剪后为空 → 什么都不输出（prefix/suffix 都不加）；
 *  3. 前缀：内容转大写，按 prefixOverrides（| 分隔，转大写）顺序 startsWith，命中第一个即停、
 *     按 toRemove.trim().length() 长度删除（大小写不敏感、空格/制表符为字面量）；
 *     prefix 追加 = 先插空格再插 prefix（prefix + " " + 内容，恒有一个空格）；
 *  4. 后缀：同理 endsWith 或 endsWith(trim 后)，只删第一个命中；suffix 前插空格后追加；
 *  5. bind / 唯一编号转发外层上下文（filtered 只拦截 appendSql 到自缓冲）。
 */
public non-sealed class TrimSqlNode implements SqlNode {

    private final SqlNode contents;
    private final String prefix;
    private final String suffix;
    private final List<String> prefixesToOverride;
    private final List<String> suffixesToOverride;

    public TrimSqlNode(
            SqlNode contents,
            String prefix,
            List<String> prefixesToOverride,
            String suffix,
            List<String> suffixesToOverride
    ) {
        this.contents = contents;
        this.prefix = prefix;
        this.suffix = suffix;
        this.prefixesToOverride = parseOverrides(prefixesToOverride);
        this.suffixesToOverride = parseOverrides(suffixesToOverride);
    }

    public SqlNode getContents() {
        return contents;
    }

    /** | 分隔 + 大写化（大小写不敏感匹配的载体）。 */
    private static List<String> parseOverrides(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        for (String s : raw) {
            out.add(s.toUpperCase(Locale.ENGLISH));
        }
        return out;
    }

    @Override
    public boolean apply(DynamicContext context) {
        FilteredDynamicContext filtered = new FilteredDynamicContext(context);
        boolean result = contents.apply(filtered);
        filtered.applyAll();
        return result;
    }

    /** 子内容缓冲上下文：appendSql 攒入自缓冲；bind/唯一编号/条件覆盖转发外层。 */
    private final class FilteredDynamicContext extends DynamicContext {

        private final DynamicContext delegate;
        private final StringBuilder sqlBuffer = new StringBuilder();

        FilteredDynamicContext(DynamicContext delegate) {
            super(Map.of());
            this.delegate = delegate;
        }

        @Override
        public void appendSql(String sql) {
            sqlBuffer.append(sql);
        }

        @Override
        public void bind(String name, Object value) {
            delegate.bind(name, value);
        }

        @Override
        public void countDollarSubstitution() {
            delegate.countDollarSubstitution();
        }

        @Override
        public int getUniqueNumber() {
            return delegate.getUniqueNumber();
        }

        @Override
        public Boolean conditionResult(String test) {
            return delegate.conditionResult(test);
        }

        @Override
        public boolean isProtected(String name) {
            return delegate.isProtected(name);
        }

        @Override
        public Map<String, Object> getStaticFieldValues() {
            return delegate.getStaticFieldValues();
        }

        @Override
        public Map<String, Object> getBindings() {
            return delegate.getBindings();
        }

        // T-FS-07：errors 必须转发——否则 where/trim/set 体内 `<if test="非法表达式">` 的求值失败被丢弃
        // （与 Foreach 的 Prefixed/Filtered 同一缺陷形态，本次一并收口）
        @Override
        public List<String> getErrors() {
            return delegate.getErrors();
        }

        // R30 探镜：体内条件判定结果转发根上下文（同 errors 的转发缺陷形态，缺席即丢）
        @Override
        public Map<String, Boolean> getConditionOutcomes() {
            return delegate.getConditionOutcomes();
        }

        void applyAll() {
            String sql = sqlBuffer.toString().trim();
            String upper = sql.toUpperCase(Locale.ENGLISH);
            if (!upper.isEmpty()) {
                String p = applyPrefix(sql, upper);
                if (p != null) {
                    sql = p;
                }
                String s = applySuffix(sql, upper);
                if (s != null) {
                    sql = s;
                }
            }
            delegate.appendSql(sql);
        }

        private String applyPrefix(String sql, String upper) {
            String result = sql;
            for (String toRemove : prefixesToOverride) {
                if (upper.startsWith(toRemove)) {
                    result = result.substring(toRemove.trim().length());
                    break;
                }
            }
            return prefix != null ? prefix + " " + result : result;
        }

        private String applySuffix(String sql, String upper) {
            String result = sql;
            for (String toRemove : suffixesToOverride) {
                if (upper.endsWith(toRemove) || upper.endsWith(toRemove.trim())) {
                    result = result.substring(0, result.length() - toRemove.trim().length());
                    break;
                }
            }
            return suffix != null ? result + " " + suffix : result;
        }
    }
}
