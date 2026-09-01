package cn.song.mybatisplus.visualizer.xml;

import java.util.List;

/** 子节点顺序拼接（parseDynamicTags 的出口，恒 true）。 */
public final class MixedSqlNode implements SqlNode {

    private final List<SqlNode> children;

    public MixedSqlNode(List<SqlNode> children) {
        this.children = children;
    }

    public List<SqlNode> getChildren() {
        return children;
    }

    @Override
    public boolean apply(DynamicContext context) {
        for (SqlNode it : children) {
            it.apply(context);
        }
        return true;
    }
}
