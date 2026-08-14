package cn.song.mybatisplus.visualizer.xml

/**
 * SqlNode 树（T-M24-01）：复刻 MyBatis 3.5.19 `org.apache.ibatis.scripting.xmltags.SqlNode` 体系。
 *
 * apply 返回布尔语义：只有 IfSqlNode 的返回值被消费（choose 的 when 短路判定），
 * 其余节点恒 true —— 与 MyBatis 各节点实现一致。
 */
sealed interface SqlNode {
    fun apply(context: DynamicContext): Boolean
}

/** 子节点顺序拼接（parseDynamicTags 的出口，恒 true）。 */
class MixedSqlNode(val children: List<SqlNode>) : SqlNode {
    override fun apply(context: DynamicContext): Boolean {
        children.forEach { it.apply(context) }
        return true
    }
}

/** 静态文本（无 `${}`），原样输出。 */
class StaticTextSqlNode(val text: String) : SqlNode {
    override fun apply(context: DynamicContext): Boolean {
        context.appendSql(text)
        return true
    }
}
