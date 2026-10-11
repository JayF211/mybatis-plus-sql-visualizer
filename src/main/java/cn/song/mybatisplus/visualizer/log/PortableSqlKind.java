package cn.song.mybatisplus.visualizer.log;

/** 可移植导出语句种类（注释头 {@code kind:} 段取枚举名）。 */
public enum PortableSqlKind {
    /** 当前预览的 CRUD 语句（本体出自方言生成链）。 */
    CRUD,

    /** 回滚链推导的可执行回滚语句。 */
    ROLLBACK,
}
