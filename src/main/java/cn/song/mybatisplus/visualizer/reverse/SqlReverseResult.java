package cn.song.mybatisplus.visualizer.reverse;

import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.SqlValue;

import java.util.List;

/**
 * 解析结果（T-M36.7 起支持增删改查；T-M37 支持多表 JOIN；T-SqlToJavaPromote M1 补齐正向对称字段）：
 * 语句类型 + 表名 / select 列 / 条件 / 排序 / 分页 / 插入多行值（insertValues 行 × 列）/
 * 多表删除目标（deleteTargets）/ 主表别名（tableAlias）/ distinct / groupBy / having / unions / forUpdate。
 *
 * @param tableName       表名
 * @param selectColumns    SELECT 列（null = SELECT *，生成器不生成 select 调用）
 * @param conditions       WHERE 条件列表
 * @param orderBy          ORDER BY 项
 * @param limit            分页子句
 * @param warnings         降级告警（无法静态转换的条件 → apply 透传等）
 * @param kind             语句类型
 * @param sets             UPDATE SET 赋值项
 * @param insertColumns    INSERT 列名清单
 * @param insertValues     INSERT 值（多行支持）：行 × 列
 * @param joins            联表子句
 * @param deleteTargets    DELETE 多表删除目标（别名/表名清单，剥反引号；空 = 单表删除）
 * @param tableAlias       主表别名（T-SqlToJavaPromote M1：SELECT/UPDATE/DELETE 的 consumeOptionalAlias 产物；无别名 null）
 * @param distinct         SELECT DISTINCT（M1 建模空实现，M2 填充解析）
 * @param groupBy          GROUP BY 列（M1 建模空实现，M2 填充解析）
 * @param having           HAVING 条件（M1 建模空实现，M2 填充解析）
 * @param unions           UNION / UNION ALL 子查询（M1 建模空实现，M2 填充解析）
 * @param forUpdate        FOR UPDATE 尾缀（M1 建模空实现，M2 填充解析）
 */
public record SqlReverseResult(
        String tableName,
        List<SelectColumn> selectColumns,
        List<Condition> conditions,
        List<OrderByItem> orderBy,
        LimitClause limit,
        List<String> warnings,
        ReverseStatementKind kind,
        List<ReverseSet> sets,
        List<String> insertColumns,
        List<List<SqlValue>> insertValues,
        List<ReverseJoin> joins,
        List<String> deleteTargets,
        String tableAlias,
        boolean distinct,
        List<ColumnRef> groupBy,
        Condition having,
        List<ReverseUnion> unions,
        boolean forUpdate
) {

    /**
     * Kotlin 默认参数等价重载：T-SqlToJavaPromote M1 新增的 6 参（tableAlias/distinct/groupBy/having/unions/forUpdate）
     * 取 master 默认值（null / false / emptyList / null / emptyList / false），保住 k2j 既有 12 参调用形态。
     */
    public SqlReverseResult(
            String tableName,
            List<SelectColumn> selectColumns,
            List<Condition> conditions,
            List<OrderByItem> orderBy,
            LimitClause limit,
            List<String> warnings,
            ReverseStatementKind kind,
            List<ReverseSet> sets,
            List<String> insertColumns,
            List<List<SqlValue>> insertValues,
            List<ReverseJoin> joins,
            List<String> deleteTargets) {
        this(tableName, selectColumns, conditions, orderBy, limit, warnings, kind, sets, insertColumns,
                insertValues, joins, deleteTargets, null, false, List.of(), null, List.of(), false);
    }
}
