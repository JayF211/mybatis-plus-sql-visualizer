package cn.song.mybatisplus.visualizer.history;

/**
 * R90：提交前固定原条目——异步完成时禁止再按「最新 SQL」回查归属。
 *
 * @param entryId    原条目 ID（提交时固定）
 * @param generation SqlHistoryManager 代际（提交时捕获；完成时复核防跨周期错挂）
 * @param key        计划键
 */
public record ExplainPlanCapture(
        String entryId,
        long generation,
        ExplainPlanKey key
) {
}
