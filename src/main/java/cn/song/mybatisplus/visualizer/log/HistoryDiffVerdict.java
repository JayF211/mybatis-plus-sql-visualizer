package cn.song.mybatisplus.visualizer.log;

/**
 * R31 历史/版本 SQL 对比结论：{@link cn.song.mybatisplus.visualizer.ui.diff.SqlDiffer} 逐行 diff 统计 +
 * 结构指纹等价判定（测试断言载体）。
 */
public record HistoryDiffVerdict(
        /** 结构指纹等价（{@link LogSqlEntry#structureKey()} 同键）——UI 据此提示「结构等价，仅参数不同」。 */
        boolean structurallyEquivalent,
        /** 行级差异条数（非 EQUAL 条目数；一行改写 = DELETE + INSERT 成对）。 */
        int diffCount,
        /** 删除行条数。 */
        int deleteCount,
        /** 新增行条数。 */
        int insertCount,
        /** 时间序旧 SQL（Diff 左侧）。 */
        String oldSql,
        /** 时间序新 SQL（Diff 右侧）。 */
        String newSql
) {
}
