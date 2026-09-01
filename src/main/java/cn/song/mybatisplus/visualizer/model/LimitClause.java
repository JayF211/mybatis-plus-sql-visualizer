package cn.song.mybatisplus.visualizer.model;

import java.util.Objects;

/**
 * 分页子句（MP 的 last("limit ...") / page 参数解析产物）。
 *
 * @param offset 偏移量（null 表示无 OFFSET）；字面量直接渲染，运行时值渲染 ?（T-M29-03）
 * @param count  行数限制；字面量直接渲染，运行时值渲染 ?
 */
public final class LimitClause {
    private final SqlValue offset;
    private final SqlValue count;

    public LimitClause(SqlValue offset, SqlValue count) {
        this.offset = offset;
        this.count = count;
    }

    /** 便捷构造：Long 字面量（last("limit 10") 文本解析等既有路径，T-M29-03 兼容）。 */
    public LimitClause(Long offset, long count) {
        this(offset != null ? new SqlValue.Literal(offset.toString()) : null, new SqlValue.Literal(Long.toString(count)));
    }

    public SqlValue getOffset() {
        return offset;
    }

    public SqlValue getCount() {
        return count;
    }

    public LimitClause copy(SqlValue offset, SqlValue count) {
        return new LimitClause(offset, count);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LimitClause)) {
            return false;
        }
        LimitClause that = (LimitClause) o;
        return Objects.equals(offset, that.offset) && Objects.equals(count, that.count);
    }

    @Override
    public int hashCode() {
        return Objects.hash(offset, count);
    }

    @Override
    public String toString() {
        return "LimitClause(offset=" + offset + ", count=" + count + ")";
    }
}
