package cn.song.mybatisplus.visualizer.log;

/**
 * 排序模式（P-06 / T-LogSortModes）：默认 {@link #TIME_ASC}（时间升序 = 捕获顺序，与未排序时观感一致）；
 * 另支持时间降序、耗时升/降序、行号。耗时缺失的条目恒沉底（两个方向的升降序都不让空值顶到前面）。
 */
public enum SortMode {
    TIME_ASC,
    TIME_DESC,
    DURATION_ASC,
    DURATION_DESC,
    SEQUENCE,
    STRUCTURE_CLUSTER,
    DATABASE_GROUP
}
