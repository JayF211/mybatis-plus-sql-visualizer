package cn.song.mybatisplus.visualizer.reverse;

import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.JoinType;

import java.util.List;

/**
 * 联表子句（T-M37 多表 JOIN）：`[LEFT|RIGHT|INNER|FULL] JOIN 副表 [别名] ON 条件`。
 *
 * @param type        联表类型
 * @param tableName   副表名（子查询时为完整子查询文本）
 * @param alias       副表别名
 * @param onConditions ON 条件（等值列对 → ColumnComparison；其他 → 普通条件/Raw 降级）
 * @param rawOn       ON 条件原文（token 区间收集，供生成器 raw join 字符串透传）
 * @param subQuery    子查询结构（简单单表查询时非 null，用于生成 MConsumer 形态）
 */
public record ReverseJoin(
        JoinType type,
        String tableName,
        String alias,
        List<Condition> onConditions,
        String rawOn,
        JoinSubQuery subQuery
) {
}
