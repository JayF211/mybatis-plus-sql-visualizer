package cn.song.mybatisplus.visualizer.sql.interceptor

import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.UpdateStatement

/**
 * TenantLine 拦截器模拟增强（F-6 Phase A）。
 *
 * 扩展现有的 WHERE 条件注入（见 [cn.song.mybatisplus.visualizer.ui.SqlPreviewService.injectTenantLineCondition]），
 * 补充两类增强：
 *  - UPDATE：SET 末尾追加 `tenantColumn = tenantDefault`（对齐 MP TenantLineInnerInterceptor
 *    运行时行为——UPDATE 含租户列赋值）；SET 已显式引用租户列时不重复追加。
 *  - INSERT：提供 [tenantInsertColumn] 生成租户列信息，供 [cn.song.mybatisplus.visualizer.psi.BatchOperationConverter]
 *    追加到 INSERT 列清单（实体已有租户列时去重返回 null）。
 *
 * 本类为纯函数，不依赖 PSI / IDE，可独立单测。
 */
object TenantLineEnhancer {

    /**
     * 为 UPDATE 语句追加租户列 SET 子句。
     *
     * SET 已显式引用租户列（按列名匹配，含原始 SQL 形式）时不重复追加；
     * 追加的 SET 子句带 [UpdateStatement.tableAlias]（联表更新场景对齐现有 version 列注入格式）。
     *
     * @param statement      UPDATE 语句模型（不可变，返回新实例）
     * @param tenantColumn   租户列名（如 tenant_id / org_id）
     * @param tenantDefault  租户列默认值（如 "1" / "42"），渲染为字面量
     * @return 追加 SET 后的新语句（无需追加时返回原实例）
     */
    fun enhanceUpdateSet(
        statement: UpdateStatement,
        tenantColumn: String,
        tenantDefault: String,
    ): UpdateStatement {
        if (setsReferenceColumn(statement.sets, tenantColumn)) return statement
        val alias = statement.tableAlias
        return statement.copy(
            statement.table,
            statement.sets + SetClause(ColumnRef(alias, tenantColumn, null, null, null, null, null), SqlValue.Literal(tenantDefault), null),
            statement.conditions,
            statement.rawTail,
            statement.tableAlias,
            statement.joins,
        )
    }

    /**
     * 为 INSERT 模板生成租户列信息。
     *
     * 实体已有租户列（按列名匹配）时返回 null（去重，不重复追加）；
     * 否则返回默认勾选的 [BatchColumn]，source = `entity.{tenantColumn}`。
     *
     * @param existingColumns INSERT 已有列清单（来自 BatchOperationConverter 实体字段展开）
     * @param tenantColumn    租户列名
     * @param tenantDefault   租户列默认值（暂存，供面板回填渲染；当前保留语义占位）
     * @return 需追加的租户列，或 null（已存在）
     */
    fun tenantInsertColumn(
        existingColumns: List<BatchColumn>,
        tenantColumn: String,
        tenantDefault: String,
    ): BatchColumn? {
        if (columnsReferenceColumn(existingColumns, tenantColumn)) return null
        return BatchColumn(tenantColumn, "entity.$tenantColumn", null, true)
    }

    /**
     * SET 列表是否已引用指定列（按列名匹配，原始 SQL 形式 [SetClause.rawSql] 不参与匹配）。
     */
    fun setsReferenceColumn(sets: List<SetClause>, columnName: String): Boolean =
        sets.any { !it.isRaw && it.column?.columnName == columnName }

    /**
     * 批量列清单是否已包含指定列名。
     */
    fun columnsReferenceColumn(columns: List<BatchColumn>, columnName: String): Boolean =
        columns.any { it.columnName == columnName }
}
