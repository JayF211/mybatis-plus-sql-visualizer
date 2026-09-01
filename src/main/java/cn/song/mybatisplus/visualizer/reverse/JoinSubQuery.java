package cn.song.mybatisplus.visualizer.reverse;

import cn.song.mybatisplus.visualizer.model.Condition;

import java.util.List;

/**
 * 子查询 JOIN 的 MConsumer 形态信息。
 * 当子查询是简单单表查询（无 JOIN、无嵌套子查询）时解析，用于生成
 * `leftJoin(SubClass.class, t -> t.setAlias(...).selectAll().ge(...), left, right)`。
 *
 * @param tableName        子查询内的表名（如 "station_info"）
 * @param alias           子查询别名（如 "tt"）
 * @param selectAll       是否 SELECT *
 * @param selectColumns   显式列名（selectAll=false 时）
 * @param whereConditions WHERE 条件
 */
public record JoinSubQuery(
        String tableName,
        String alias,
        boolean selectAll,
        List<String> selectColumns,
        List<Condition> whereConditions
) {
}
