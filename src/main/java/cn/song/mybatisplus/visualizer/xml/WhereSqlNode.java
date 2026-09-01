package cn.song.mybatisplus.visualizer.xml;

import java.util.List;

/**
 * where 节点（T-M24-01）：复刻 MyBatis 3.5.19 WhereSqlNode——
 * trim(prefix="WHERE", prefixOverrides=["AND ","OR ","AND\n","OR\n","AND\r","OR\r","AND\t","OR\t"])。
 */
public class WhereSqlNode extends TrimSqlNode {

    public WhereSqlNode(SqlNode contents) {
        super(contents, "WHERE",
                List.of("AND ", "OR ", "AND\n", "OR\n", "AND\r", "OR\r", "AND\t", "OR\t"),
                null, null);
    }
}
