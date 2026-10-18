package cn.song.mybatisplus.visualizer.log;

/** 捕获通道归因，与 SQL 框架来源分开记录。 */
public enum CaptureProvenance {
    AGENT,
    CONSOLE_EP,
    CONSOLE_BRIDGE,
    MERGED,
    UNKNOWN
}
