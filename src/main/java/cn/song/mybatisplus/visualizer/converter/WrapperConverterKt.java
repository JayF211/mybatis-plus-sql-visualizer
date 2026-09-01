package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.WrapperArg;

/**
 * 共享实参解析辅助（各 converter 复用；对应 psi 层 WrapperArg 产物）。
 */
public final class WrapperConverterKt {
    private WrapperConverterKt() {
    }

    /** 实参转列：ColumnArg 直接取；ValueArg(字面量) 视为字符串列名（MP 字符串字段版）。 */
    public static ColumnRef asColumn(WrapperArg arg) {
        if (arg instanceof WrapperArg.ColumnArg ca) return ca.getColumn();
        if (arg instanceof WrapperArg.ValueArg va) {
            SqlValue v = va.getValue();
            if (v instanceof SqlValue.Literal lit) {
                return new ColumnRef(null, lit.getUnquoted(), null, null, null, null, null);
            }
        }
        return null;
    }

    /** 实参转值。 */
    public static SqlValue asValue(WrapperArg arg) {
        if (arg instanceof WrapperArg.ValueArg va) return va.getValue();
        return null;
    }

    /** 为列引用补表别名（MPJ 主表场景），已有别名时保持不变。 */
    public static ColumnRef withTableAlias(ColumnRef column, String alias) {
        return column.getTableAlias() == null
                ? new ColumnRef(alias, column.getColumnName(), column.getAlias(), column.getFunction(),
                column.getRawExpr(), column.getEntityClass(), column.getType())
                : column;
    }
}
