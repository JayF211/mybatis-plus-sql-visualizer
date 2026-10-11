package cn.song.mybatisplus.visualizer.execute;

/** 入口判定结果（三类可区分——所有权与终结义务不同，见 {@link PrepareSingleFlightGate#tryEnter}）。 */
public enum PrepareGateDecision {
    /** 本链持有单飞位——终结时必须 finishPrepare（含失败路径）。 */
    PROCEED,

    /** 已有他链在飞且本链为预览准备——直通不取所有权（不 finishPrepare、不消费排队意图）。 */
    PASS_THROUGH,

    /** 执行意图已排队——调用方 callback(true) 后直接返回，不建第二链。 */
    QUEUE_ONLY,
}
