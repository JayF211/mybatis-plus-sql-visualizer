package cn.song.mybatisplus.visualizer.xml;

/**
 * MP 占位：变量名（@Param 名）+ 片段类型。
 *
 * @param variable 变量名
 * @param kind     片段类型（customSqlSegment / sqlSelect / sqlSet）
 */
public record EwPlaceholder(String variable, String kind) {
}
