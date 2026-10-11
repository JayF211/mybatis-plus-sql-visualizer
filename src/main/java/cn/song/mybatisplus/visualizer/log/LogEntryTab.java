// T-SyncK2JMaster482 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogManager.kt 移植
package cn.song.mybatisplus.visualizer.log;

/**
 * T-RecentExecMenu O-119：可承接日志条目的工具窗页签（{@link SqlLogManager#onOpenEntryInTab} 的目标）。
 * 面板外调用方只表达「送到哪个页签」，页面切换与装载细节留在 {@link SqlLogPanel}。
 */
public enum LogEntryTab {
    /** 日志转 SQL（第二页签）：条目渲染回日志文本并转换。 */
    LOG_TO_SQL,

    /** SQL → Java（第三页签）：还原 SQL 写入输入区。 */
    SQL_TO_JAVA,
}
