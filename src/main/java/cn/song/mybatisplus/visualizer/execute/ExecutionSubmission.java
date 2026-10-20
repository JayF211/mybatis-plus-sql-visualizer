package cn.song.mybatisplus.visualizer.execute;

/**
 * 提交回执，不代表数据库执行成功；与旧准备/选择 Boolean 回调分离。
 */
public enum ExecutionSubmission {
    SUBMITTED,
    CANCELLED,
    BLOCKED,
}
