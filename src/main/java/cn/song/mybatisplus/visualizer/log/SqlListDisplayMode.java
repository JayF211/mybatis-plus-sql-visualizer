package cn.song.mybatisplus.visualizer.log;

/**
 * SQL 列表显示模式（T-ListMode）：SQL 日志/历史列表条目中 SQL 行的展示形态。
 *
 * <ul>
 * <li>{@link #SINGLE_LINE} 单行原文（T-TwoLine 双行结构下的形态，列表固定行高走 O(1) 布局快路径）</li>
 * <li>{@link #KEYWORD_WRAP} 关键字换行——子句顶格换行、同行内容压一行（默认，长 SQL 快速总览）</li>
 * <li>{@link #FORMATTED} 格式化——多行缩进全展开（阅读细节）</li>
 * </ul>
 */
public enum SqlListDisplayMode {
    SINGLE_LINE,
    KEYWORD_WRAP,
    FORMATTED,
}
