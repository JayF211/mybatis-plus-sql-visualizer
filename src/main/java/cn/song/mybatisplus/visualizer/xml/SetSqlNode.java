package cn.song.mybatisplus.visualizer.xml;

import java.util.List;

/**
 * set 节点（T-M24-01）：复刻 MyBatis 3.5.19 SetSqlNode——
 * trim(prefix="SET", prefixOverrides=[","], suffixOverrides=[","])。
 */
public class SetSqlNode extends TrimSqlNode {

    public SetSqlNode(SqlNode contents) {
        super(contents, "SET", List.of(","), null, List.of(","));
    }
}
