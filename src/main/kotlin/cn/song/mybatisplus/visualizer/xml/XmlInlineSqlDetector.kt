package cn.song.mybatisplus.visualizer.xml

import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import java.util.Locale

/**
 * 通用 XML 内嵌 SQL 检测（O-72 T-XmlInlineSql）。
 *
 * 背景：RFListPage 等自研框架 XML 把 SQL/HQL 写在自定义标签的 CDATA/文本里，
 * 根元素名不固定（用户实证），mapper/pages/hbm 三套「根元素+标签名」白名单全部
 * 不认领。本检测**锚定内容不锚定标签名**：叶子标签 + 文本首词命中 SQL 关键字
 * 即视为 SQL 载体，对任何框架形态生效。
 *
 * - 认领条件（[isSqlCarrierTag]）：无子标签 + 首段非空文本的前缀词 ∈ [SQL_KEYWORDS]；
 * - 排除既有认领（[isClaimedByKnownStructure]）：mapper 语句 / hbm 命名查询 /
 *   pages 的 `<datasource>`（图标在 maintainPage 上）——防止既有文件双图标；
 * - HQL 判别（[queryTypeOf]）：自身或祖先标签的 `type` 属性 ∈ {hql, jpql}
 *   （就近优先），仅预览链（后台线程）调用，不进 gutter 快速路径。
 *
 * **性能红线**：gutter 快速路径只做局部判定——叶子判定 + 首段非空文本截断前缀
 * （[PREFIX_PROBE_LENGTH] 字符），零 resolve、零全文件遍历、零转换。
 */
object XmlInlineSqlDetector {

    /** 首词命中即认领（宽口径，决策 D2）：SQL 关键字集，大小写不敏感。 */
    val SQL_KEYWORDS: Set<String> = setOf("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "REPLACE")

    /** type 属性取这些值时按 HQL 走 [cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter] 管道。 */
    private val HQL_TYPES: Set<String> = setOf("hql", "jpql")

    /** gutter 快速路径的文本探测上限（只影响首词判定，不影响预览时的完整提取）。 */
    const val PREFIX_PROBE_LENGTH = 64

    /** 判定 [tag] 是否为通用 SQL 载体标签（叶子 + 文本像 SQL + 未被既有结构认领）。 */
    fun isSqlCarrierTag(tag: XmlTag): Boolean {
        if (tag.subTags.isNotEmpty()) return false
        if (isClaimedByKnownStructure(tag)) return false
        val firstWord = firstTextWord(tag) ?: return false
        return firstWord in SQL_KEYWORDS
    }

    /** 该标签是否已被 mapper / hbm / pages 三套既有白名单认领（通用检测必须让位，防双图标）。 */
    fun isClaimedByKnownStructure(tag: XmlTag): Boolean {
        val name = tag.name
        val parentName = tag.parentTag?.name
        // mapper 语句（XmlSqlGutterLineMarkerProvider：STATEMENT_TAGS 且父标签 mapper）
        if (name in MAPPER_STATEMENT_TAGS && parentName == "mapper") return true
        // hbm 命名查询（HbmNamedQueryGutterLineMarkerProvider：query/sql-query 且父标签 hibernate-mapping）
        if (name in HBM_QUERY_TAGS && parentName == "hibernate-mapping") return true
        // pages 的 SQL 载体（PagesXmlGutterLineMarkerProvider 图标锚在 maintainPage 上）
        if (name == "datasource" && parentName == "maintainPage") return true
        return false
    }

    /** 首段非空文本的截断前缀首词（大写；无文本 → null）。 */
    fun firstTextWord(tag: XmlTag): String? {
        val prefix = firstNonBlankTextPrefix(tag) ?: return null
        val word = prefix.trim().split(WHITESPACE).firstOrNull().orEmpty()
        return word.uppercase(Locale.ROOT).ifEmpty { null }
    }

    /** 完整提取载体标签内 SQL 文本（直属文本段拼接，CDATA 剥包裹，trim）。
     *  实测（O-72）：XmlText.text 对 CDATA 返回带 <![CDATA[ ]]> 包裹的字面全文，
     *  并非展开内容——统一剥包裹后再拼（混排文本/多 CDATA 段均安全）。 */
    fun extractSqlText(tag: XmlTag): String =
        tag.children
            .filterIsInstance<XmlText>()
            .joinToString("") { unwrapCdata(it.text) }
            .trim()

    /** 自身或祖先标签的 `type` 属性值（就近优先，小写；无 → null）。 */
    fun queryTypeOf(tag: XmlTag): String? {
        var cur: XmlTag? = tag
        while (cur != null) {
            val type = cur.getAttributeValue("type")
            if (!type.isNullOrBlank()) return type.trim().lowercase(Locale.ROOT)
            cur = cur.parentTag
        }
        return null
    }

    /** [queryTypeOf] 是否判为 HQL 族（hql/jpql）——预览链据此走 HqlConverter。 */
    fun isHqlType(tag: XmlTag): Boolean = queryTypeOf(tag) in HQL_TYPES

    /** 通用检测总开关（设置页可关；测试环境 service 未注册时按默认开）。 */
    fun detectionEnabled(): Boolean = runCatching {
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.xmlInlineSqlEnabled
    }.getOrDefault(true)

    /** 首段非空文本的截断前缀（遍历直属文本段，剥 CDATA 包裹、跳过纯空白段；无 → null）。 */
    private fun firstNonBlankTextPrefix(tag: XmlTag): String? {
        for (child in tag.children) {
            val text = (child as? XmlText)?.text ?: continue
            val trimmed = unwrapCdata(text).trim()
            if (trimmed.isNotEmpty()) return trimmed.take(PREFIX_PROBE_LENGTH)
        }
        return null
    }

    /** 剥 CDATA 包裹（保留内容；普通文本原样返回）。 */
    private fun unwrapCdata(text: String): String = text.replace(CDATA_WRAPPER, "$1")

    private val CDATA_WRAPPER = Regex("(?s)<!\\[CDATA\\[(.*?)]]>")

    private val WHITESPACE = Regex("\\s+")

    /** 与 XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS 同源（引用而非复写，防两处漂移）。 */
    private val MAPPER_STATEMENT_TAGS: Set<String> =
        cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS

    private val HBM_QUERY_TAGS: Set<String> =
        cn.song.mybatisplus.visualizer.ui.gutter.HbmNamedQueryGutterLineMarkerProvider.QUERY_TAGS
}
