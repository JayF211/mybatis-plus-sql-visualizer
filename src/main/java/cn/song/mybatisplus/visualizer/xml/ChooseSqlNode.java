package cn.song.mybatisplus.visualizer.xml;

import java.util.List;

/**
 * choose 节点（T-M24-01）：复刻 MyBatis 3.5.19 org.apache.ibatis.scripting.xmltags.ChooseSqlNode——
 * 按顺序取第一个命中的 when 短路输出；全部不中且 otherwise 存在时输出 otherwise。
 */
public final class ChooseSqlNode implements SqlNode {

    private final List<SqlNode> whenNodes;
    private final SqlNode defaultNode;

    public ChooseSqlNode(List<SqlNode> whenNodes, SqlNode defaultNode) {
        this.whenNodes = whenNodes;
        this.defaultNode = defaultNode;
    }

    public List<SqlNode> getWhenNodes() {
        return whenNodes;
    }

    public SqlNode getDefaultNode() {
        return defaultNode;
    }

    @Override
    public boolean apply(DynamicContext context) {
        for (SqlNode node : whenNodes) {
            if (node.apply(context)) {
                return true;
            }
        }
        if (defaultNode != null) {
            defaultNode.apply(context);
        }
        return false;
    }
}
