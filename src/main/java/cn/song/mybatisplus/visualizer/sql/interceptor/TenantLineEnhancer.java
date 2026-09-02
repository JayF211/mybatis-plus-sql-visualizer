package cn.song.mybatisplus.visualizer.sql.interceptor;

import cn.song.mybatisplus.visualizer.model.BatchColumn;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.SetClause;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;

import java.util.ArrayList;
import java.util.List;

/**
 * TenantLine 拦截器模拟增强（F-6 Phase A）。
 *
 * <p>扩展现有的 WHERE 条件注入（见
 * {@code cn.song.mybatisplus.visualizer.ui.SqlPreviewService.injectTenantLineCondition}），
 * 补充两类增强：
 * <ul>
 *  <li>UPDATE：SET 末尾追加 {@code tenantColumn = tenantDefault}（对齐 MP TenantLineInnerInterceptor
 *    运行时行为——UPDATE 含租户列赋值）；SET 已显式引用租户列时不重复追加。</li>
 *  <li>INSERT：提供 {@link #tenantInsertColumn} 生成租户列信息，供
 *    {@code cn.song.mybatisplus.visualizer.psi.BatchOperationConverter} 追加到 INSERT 列清单
 *    （实体已有租户列时去重返回 null）。</li>
 * </ul>
 *
 * <p>本类为纯函数，不依赖 PSI / IDE，可独立单测。
 */
public final class TenantLineEnhancer {

    private TenantLineEnhancer() {
    }

    /**
     * 为 UPDATE 语句追加租户列 SET 子句。
     *
     * <p>SET 已显式引用租户列（按列名匹配，含原始 SQL 形式）时不重复追加；
     * 追加的 SET 子句带 {@link UpdateStatement#getTableAlias()}（联表更新场景对齐现有 version 列注入格式）。
     *
     * @param statement     UPDATE 语句模型（不可变，返回新实例）
     * @param tenantColumn  租户列名（如 tenant_id / org_id）
     * @param tenantDefault 租户列默认值（如 "1" / "42"），渲染为字面量
     * @return 追加 SET 后的新语句（无需追加时返回原实例）
     */
    public static UpdateStatement enhanceUpdateSet(
            UpdateStatement statement,
            String tenantColumn,
            String tenantDefault) {
        if (setsReferenceColumn(statement.getSets(), tenantColumn)) return statement;
        String alias = statement.getTableAlias();
        List<SetClause> newSets = new ArrayList<>(statement.getSets());
        newSets.add(new SetClause(
                new ColumnRef(alias, tenantColumn, null, null, null),
                new SqlValue.Literal(tenantDefault)));
        return statement.copy(
                statement.getTable(),
                newSets,
                statement.getConditions(),
                statement.getRawTail(),
                statement.getTableAlias(),
                statement.getJoins());
    }

    /**
     * 为 INSERT 模板生成租户列信息。
     *
     * <p>实体已有租户列（按列名匹配）时返回 null（去重，不重复追加）；
     * 否则返回默认勾选的 {@link BatchColumn}，source = {@code entity.{tenantColumn}}。
     *
     * @param existingColumns INSERT 已有列清单（来自 BatchOperationConverter 实体字段展开）
     * @param tenantColumn    租户列名
     * @param tenantDefault   租户列默认值（暂存，供面板回填渲染；当前保留语义占位）
     * @return 需追加的租户列，或 null（已存在）
     */
    public static BatchColumn tenantInsertColumn(
            List<BatchColumn> existingColumns,
            String tenantColumn,
            String tenantDefault) {
        if (columnsReferenceColumn(existingColumns, tenantColumn)) return null;
        return new BatchColumn(tenantColumn, "entity." + tenantColumn, null, true);
    }

    /**
     * SET 列表是否已引用指定列（按列名匹配，原始 SQL 形式 {@link SetClause#isRaw()} 不参与匹配）。
     */
    public static boolean setsReferenceColumn(List<SetClause> sets, String columnName) {
        for (SetClause set : sets) {
            if (!set.isRaw() && set.getColumn() != null
                    && columnName.equals(set.getColumn().getColumnName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 批量列清单是否已包含指定列名。
     */
    public static boolean columnsReferenceColumn(List<BatchColumn> columns, String columnName) {
        for (BatchColumn column : columns) {
            if (columnName.equals(column.getColumnName())) return true;
        }
        return false;
    }
}
