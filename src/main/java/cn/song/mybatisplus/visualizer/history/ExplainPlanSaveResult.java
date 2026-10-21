package cn.song.mybatisplus.visualizer.history;

/** R90：EXPLAIN 计划保存结果（三类可区分——UI 据此提示）。 */
public enum ExplainPlanSaveResult {
    SAVED,
    SESSION_ONLY,
    RETENTION_DISABLED,
    TOO_LARGE,
    CLEARED_OR_REMOVED,
}
