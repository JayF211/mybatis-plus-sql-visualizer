package cn.song.mybatisplus.visualizer.xml;

/**
 * SqlNode 树（T-M24-01）：复刻 MyBatis 3.5.19 org.apache.ibatis.scripting.xmltags.SqlNode 体系。
 *
 * apply 返回布尔语义：只有 IfSqlNode 的返回值被消费（choose 的 when 短路判定），
 * 其余节点恒 true —— 与 MyBatis 各节点实现一致。
 */
public sealed interface SqlNode
        permits MixedSqlNode, StaticTextSqlNode, IfSqlNode, ChooseSqlNode,
        VarDeclSqlNode, TrimSqlNode, ForeachSqlNode, TextSqlNode {

    boolean apply(DynamicContext context);
}
