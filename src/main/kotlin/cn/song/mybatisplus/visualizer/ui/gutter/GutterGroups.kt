package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings

/**
 * R2（W2 体验包）：gutter 按来源分组显隐——组 → provider 映射单一事实源。
 *
 * 动机＝MyBatisX「图标太大太醒目」差评多轮；此前仅全局布尔 showGutterIcon，
 * 15 个 lineMarkerProvider 无分组。组口径（默认全开=现行为不变）：
 *  - MAPPER_XML：mapper XML 语句（select/insert/update/delete 文本 + pages/hbm 命名查询）
 *  - ANNOTATION：注解 SQL（@Select 族 + @NamedQuery）
 *  - JPA_HQL：JPA/HQL 族（JPA 仓储 / JPQL / Criteria / HQL / 自研 DAO HQL）
 *  - JDBC：JdbcTemplate 字符串 SQL
 *  - INLINE_FALLBACK：通用 XML 内嵌兜底（第四通道）
 *  - OTHER：MP Wrapper 链 / 方法名字符串 SQL 等其余
 *
 * [enabledFor] 为平台包装（读设置，测试环境兜底全开）；[groupOf]/纯逻辑面独立可测。
 */
object GutterGroups {

    /** 组枚举（设置页每组一个开关）。 */
    val GROUPS = listOf("mapperXml", "annotation", "jpaHql", "jdbc", "inlineFallback", "other")

    /** provider 类 simpleName → 组（未登记 = other）。 */
    private val PROVIDER_GROUP: Map<String, String> = mapOf(
        "XmlSqlGutterLineMarkerProvider" to "mapperXml",
        "PagesXmlGutterLineMarkerProvider" to "mapperXml",
        "HbmNamedQueryGutterLineMarkerProvider" to "mapperXml",
        "AnnotationSqlGutterLineMarkerProvider" to "annotation",
        "NamedQueryGutterLineMarkerProvider" to "annotation",
        "JpaGutterLineMarkerProvider" to "jpaHql",
        "JpqlGutterLineMarkerProvider" to "jpaHql",
        "CriteriaQueryGutterLineMarkerProvider" to "jpaHql",
        "HqlGutterLineMarkerProvider" to "jpaHql",
        "CustomDaoHqlGutterLineMarkerProvider" to "jpaHql",
        "JdbcTemplateSqlGutterLineMarkerProvider" to "jdbc",
        "XmlInlineSqlGutterLineMarkerProvider" to "inlineFallback",
        "SqlGutterLineMarkerProvider" to "other",
        "StringSqlMethodGutterLineMarkerProvider" to "other",
    )

    /** provider 类所属组（未登记 = other）。 */
    fun groupOf(providerSimpleName: String): String =
        PROVIDER_GROUP[providerSimpleName] ?: "other"

    /** 该 provider 的组开关是否开启（全局 showGutterIcon 总闸在前；测试环境兜底全开）。 */
    fun enabledFor(providerClass: Class<*>, showGutterIcon: Boolean): Boolean {
        if (!showGutterIcon) return false
        val group = groupOf(providerClass.simpleName)
        return runCatching {
            val s = PluginSettings.getInstance().state
            groupEnabled(group, s)
        }.getOrDefault(true)
    }

    /** 纯逻辑面（可测）：组开关读取（组 → State 字段映射单一事实）。 */
    fun groupEnabled(group: String, s: PluginSettings.State): Boolean = when (group) {
        "mapperXml" -> s.gutterGroupMapperXml
        "annotation" -> s.gutterGroupAnnotation
        "jpaHql" -> s.gutterGroupJpaHql
        "jdbc" -> s.gutterGroupJdbc
        "inlineFallback" -> s.gutterGroupInlineFallback
        else -> s.gutterGroupOther
    }
}
