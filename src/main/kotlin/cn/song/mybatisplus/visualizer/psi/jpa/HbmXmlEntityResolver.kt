package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import cn.song.mybatisplus.visualizer.xml.directTextOf
import java.util.concurrent.ConcurrentHashMap

/**
 * Hibernate XML 映射文件（.hbm.xml）解析器。
 *
 * 解析 `<class>`, `<id>`, `<property>`, `<many-to-one>`, `<one-to-one>` 等映射元素，
 * 提取实体类名、表名、字段列映射、关联关系等元数据。
 *
 * T-H1（20260907-1630）：新增 `<query>`（HQL）/ `<sql-query>`（原生 SQL）命名查询解析——
 * hbm 纯 POJO 项目（无 @NamedQuery 注解）的命名查询此前在插件视角不存在，`createNamedQuery(name)`
 * 回查与 hbm 文件 gutter 均无法覆盖。文本只取直属 XmlText（排除 `<return-scalar>` 等子标签），
 * CDATA 正常展开；按查询名（完整 `name` 属性）索引，负命中缓存沿用 [NEGATIVE_CACHE_MAX] 口径。
 *
 * 查找策略（T-J11 修复）：
 * 1. 经 [FileTypeIndex] 按文件类型扫描项目内全部 XML 文件，再按文件名 `endsWith(".hbm.xml")`
 *    过滤——原先的 `FilenameIndex.getFilesByName(project, "*.hbm.xml", ...)` 按字面名匹配，
 *    通配符不生效，永远查不到文件；
 * 2. 解析每个文件中的**全部** `<class>` 元素（原先 `findFirstSubTag` 只取第一个，
 *    多 class 映射文件会丢失其余实体）；
 * 3. 按实体类名（简单名或全限定名）索引。
 *
 * 缓存策略：按 (project, classQualifiedName) 缓存 [resolve] 结果，避免重复解析；
 * 项目关闭时调用 [clearCache]。
 */
object HbmXmlEntityResolver {

    /** T-Perf-18：负命中缓存上限（超过整体清空）。 */
    private const val NEGATIVE_CACHE_MAX = 4096

    private val cache = ConcurrentHashMap<String, HbmXmlMapping.ClassMapping>()

    /** T-H1：命名查询索引（键=项目名+查询名）；负命中沿用 [negativeCache]（前缀区分键）。 */
    private val namedQueryCache = ConcurrentHashMap<String, HbmXmlMapping.NamedQuery>()

    /**
     * T-Perf-18：负命中缓存（项目名+类名 → true 表示该实体无 hbm 映射）。
     * 此前每个无 hbm 实体每次预览都重跑 FilenameIndex 全量索引查询（heavy 热路径）；
     * 负命中仅在 [clearCache] / 缓存封顶清空时失效（hbm 文件增删属开发期低频事件，
     * 设置页「清除缓存」可手动刷新）。
     */
    private val negativeCache: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 根据实体类名查找映射信息。
     *
     * @param entityClassName 实体类名（简单名如 "RuleTableItem" 或全限定名）
     * @param project         当前项目
     * @return 映射信息，找不到返回 null
     */
    fun resolve(entityClassName: String, project: Project): HbmXmlMapping.ClassMapping? {
        val cacheKey = "${project.name}:$entityClassName"
        cache[cacheKey]?.let { return it }
        if (cacheKey in negativeCache) return null

        // 搜索所有 .hbm.xml 文件（T-DepApi 无需介入：本路径走 FileTypeIndex + projectScope，
        // 未使用全平台标 deprecated 的 FilenameIndex.getFilesByName）
        for (file in findHbmXmlFiles(project)) {
            for (mapping in parseHbmFile(file)) {
                // 按简单类名和全限定名索引
                if (mapping.entityClassName == entityClassName || mapping.fullClassName == entityClassName) {
                    cache[cacheKey] = mapping
                    return mapping
                }
            }
        }

        // T-Perf-18：记入负命中缓存（封顶防膨胀）
        if (negativeCache.size >= NEGATIVE_CACHE_MAX) negativeCache.clear()
        negativeCache.add(cacheKey)
        return null
    }

    /**
     * T-H1：按查询名查找 hbm 命名查询（`<query>` HQL / `<sql-query>` 原生 SQL）。
     *
     * 供 `NamedQueryLocator.findByQueryName` 未命中 Java @NamedQuery 注解时 fallback
     * （hbm 纯 POJO 项目无注解可查），以及 hbm 文件 gutter 点击预览。
     *
     * @param queryName 完整查询名（如 `LegacyItem.findLowStockHql`）
     * @param project   当前项目
     */
    fun findNamedQuery(queryName: String, project: Project): HbmXmlMapping.NamedQuery? {
        if (queryName.isBlank()) return null
        val cacheKey = "nq:${project.name}:$queryName"
        namedQueryCache[cacheKey]?.let { return it }
        if (cacheKey in negativeCache) return null

        for (file in findHbmXmlFiles(project)) {
            val rootTag = file.rootTag ?: continue
            if (rootTag.name != "hibernate-mapping") continue
            for (tag in rootTag.subTags) {
                val kind = when (tag.name) {
                    "query" -> HbmXmlMapping.QueryKind.HQL
                    "sql-query" -> HbmXmlMapping.QueryKind.NATIVE
                    else -> continue
                }
                val name = tag.getAttributeValue("name") ?: continue
                if (name != queryName) continue
                val text = extractNamedQueryText(tag)
                if (text.isBlank()) continue
                val query = HbmXmlMapping.NamedQuery(name = name, kind = kind, queryText = text)
                namedQueryCache[cacheKey] = query
                return query
            }
        }

        if (negativeCache.size >= NEGATIVE_CACHE_MAX) negativeCache.clear()
        negativeCache.add(cacheKey)
        return null
    }

    /** 仅为预览决策查找定义标签；不缓存 PSI/URI，避免把调用点误认成 XML owner。 */
    fun findNamedQueryDefinition(query: HbmXmlMapping.NamedQuery, project: Project): XmlTag? {
        var owner: XmlTag? = null
        for (file in findHbmXmlFiles(project)) {
            val root = file.rootTag ?: continue
            if (root.name != "hibernate-mapping") continue
            for (tag in root.subTags) {
                val kind = when (tag.name) {
                    "query" -> HbmXmlMapping.QueryKind.HQL
                    "sql-query" -> HbmXmlMapping.QueryKind.NATIVE
                    else -> continue
                }
                if (kind == query.kind && tag.getAttributeValue("name") == query.name &&
                    directTextOf(tag) == query.queryText) {
                    if (owner != null) return null // 同名同文跨文件重复定义：owner 不唯一
                    owner = tag
                }
            }
        }
        return owner
    }

    /**
     * T-H1：提取命名查询文本——直属 XmlText 拼接（CDATA 剥包裹、trim，口径见
     * `xml/XmlDirectText.kt`）；排除 `<return-scalar>`/`<return>`/`<query-param>` 等子标签的子内容。
     */
    private fun extractNamedQueryText(tag: XmlTag): String = directTextOf(tag)

    /**
     * T-J11：解析项目内全部 .hbm.xml 并按实体名索引（多 class 文件的每个 `<class>` 都会入索引）。
     *
     * **SqlPreviewService 接线点**：实体无 JPA 注解时（纯 .hbm.xml 映射的项目），可经本入口
     * 建立全量映射索引（键同时含简单名与全限定名），替代逐实体 [resolve] 反复全盘扫描。
     *
     * @param project 当前项目
     * @return 键为实体简单名 / 全限定名，值为对应映射；同名字段首个命中优先。无映射文件时返回空 Map
     */
    fun resolveAll(project: Project): Map<String, HbmXmlMapping.ClassMapping> {
        val index = LinkedHashMap<String, HbmXmlMapping.ClassMapping>()
        for (file in findHbmXmlFiles(project)) {
            for (mapping in parseHbmFile(file)) {
                index.putIfAbsent(mapping.entityClassName, mapping)
                index.putIfAbsent(mapping.fullClassName, mapping)
            }
        }
        return index
    }

    // ------------------------------------------------------------------
    // 文件发现（T-J11：FilenameIndex 通配符失效 → FileTypeIndex + 后缀过滤）
    // ------------------------------------------------------------------

    /**
     * 查找项目内全部 .hbm.xml 文件。
     *
     * `FileTypeIndex.getFiles(XmlFileType, projectScope)` 按**文件类型**索引取全量 XML
     * （.hbm.xml 以 `.xml` 结尾归 XML 文件类型），再按文件名后缀精确过滤。
     * 任何索引异常按空列表处理（优雅降级，绝不抛出）。
     */
    private fun findHbmXmlFiles(project: Project): List<XmlFile> = runCatching {
        // XmlFileType 在 262 平台不可直接引用（IPGP 裁剪），按项目惯例用 FileTypeManager 取标准 XML 类型
        val xmlFileType = FileTypeManager.getInstance().getStdFileType("XML")
        FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))
            .asSequence()
            .filter { it.name.endsWith(".hbm.xml", ignoreCase = true) }
            .mapNotNull { virtualFile -> PsiManager.getInstance(project).findFile(virtualFile) as? XmlFile }
            .toList()
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析单个 .hbm.xml 文件，提取**全部**类映射（T-J11：不再只取第一个 `<class>`）。
     *
     * 单个 `<class>` 解析失败（缺 name/table 属性）仅跳过该类，不影响同文件其余映射。
     */
    private fun parseHbmFile(xmlFile: XmlFile): List<HbmXmlMapping.ClassMapping> {
        val rootTag = xmlFile.rootTag ?: return emptyList()
        return rootTag.findSubTags("class").mapNotNull { classTag -> parseClassTag(classTag) }
    }

    /** 解析单个 `<class>` 元素为类映射；属性缺失返回 null（调用方跳过）。 */
    private fun parseClassTag(classTag: XmlTag): HbmXmlMapping.ClassMapping? {
        val name = classTag.getAttributeValue("name") ?: return null
        val table = classTag.getAttributeValue("table") ?: return null
        val entityClassName = name.substringAfterLast('.')
        val fullClassName = name

        val properties = mutableListOf<HbmXmlMapping.PropertyMapping>()
        val associations = mutableListOf<HbmXmlMapping.AssociationMapping>()

        // 解析子元素
        for (subTag in classTag.subTags) {
            when (subTag.name) {
                "id" -> {
                    val fieldName = subTag.getAttributeValue("name") ?: continue
                    val columnName = extractColumn(subTag) ?: continue
                    properties.add(HbmXmlMapping.PropertyMapping(fieldName, columnName, isId = true))
                }
                "property" -> {
                    val fieldName = subTag.getAttributeValue("name") ?: continue
                    val columnName = extractColumn(subTag) ?: continue
                    properties.add(HbmXmlMapping.PropertyMapping(fieldName, columnName, isId = false))
                }
                "many-to-one", "one-to-one" -> {
                    val fieldName = subTag.getAttributeValue("name") ?: continue
                    val targetClass = subTag.getAttributeValue("class") ?: continue
                    val joinColumn = extractColumn(subTag) ?: continue
                    associations.add(HbmXmlMapping.AssociationMapping(fieldName, targetClass, joinColumn))
                }
            }
        }

        return HbmXmlMapping.ClassMapping(
            entityClassName = entityClassName,
            fullClassName = fullClassName,
            tableName = table,
            properties = properties,
            associations = associations,
        )
    }

    /**
     * 从映射元素中提取列名。
     *
     * 优先从 `<column name="...">` 子元素提取，其次从 `column` 属性提取。
     */
    private fun extractColumn(tag: XmlTag): String? {
        // 优先：column 子元素
        val columnTag = tag.findFirstSubTag("column")
        if (columnTag != null) {
            return columnTag.getAttributeValue("name")
        }
        // 次选：column 属性
        return tag.getAttributeValue("column")
    }

    /**
     * 清除缓存（含负命中缓存与命名查询索引；PluginCacheManager.clearAll 接线，项目关闭时调用）。
     */
    fun clearCache() {
        cache.clear()
        namedQueryCache.clear()
        negativeCache.clear()
    }

}
