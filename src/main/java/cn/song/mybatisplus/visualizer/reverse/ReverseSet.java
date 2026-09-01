package cn.song.mybatisplus.visualizer.reverse;

import cn.song.mybatisplus.visualizer.model.SqlValue;

/**
 * UPDATE SET 赋值项（T-M36.7）：`列 = 值`；value 为 null 表示 SQL NULL（生成 Java null）；
 * rawSql 非空表示函数/表达式值原样透传（生成 setSql）。
 *
 * @param column  列名
 * @param value   值（null 表示 SQL NULL）
 * @param rawSql  函数/表达式值原文（非空 → setSql 透传）
 */
public record ReverseSet(String column, SqlValue value, String rawSql) {
}
