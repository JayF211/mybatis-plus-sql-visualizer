package cn.song.mybatisplus.visualizer.model;

import java.util.Objects;

/**
 * ORDER BY 项。
 *
 * @param column 排序列
 * @param asc    true=ASC，false=DESC
 */
public final class OrderByItem {
    private final ColumnRef column;
    private final boolean asc;

    public OrderByItem(ColumnRef column, boolean asc) {
        this.column = column;
        this.asc = asc;
    }

    public ColumnRef getColumn() {
        return column;
    }

    public boolean isAsc() {
        return asc;
    }

    public boolean getAsc() {
        return asc;
    }

    public OrderByItem copy(ColumnRef column, boolean asc) {
        return new OrderByItem(column, asc);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderByItem)) {
            return false;
        }
        OrderByItem that = (OrderByItem) o;
        return asc == that.asc && Objects.equals(column, that.column);
    }

    @Override
    public int hashCode() {
        return Objects.hash(column, asc);
    }

    @Override
    public String toString() {
        return "OrderByItem(column=" + column + ", asc=" + asc + ")";
    }
}
