package cn.song.mybatisplus.visualizer.reverse;

import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.SqlValue;

import java.util.List;

/**
 * 解析结果（T-M36.7 起支持增删改查；T-M37 支持多表 JOIN）：语句类型 + 表名 / select 列 / 条件 /
 * 排序 / 分页 / 插入多行值（insertValues 行 × 列）/ 多表删除目标（deleteTargets）。
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
        List<String> deleteTargets
) {
}
