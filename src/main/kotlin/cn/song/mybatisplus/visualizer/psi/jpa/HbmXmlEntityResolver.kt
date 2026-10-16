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

    private data class LookupKey(val name: String, val scope: GlobalSearchScope)
    private class LookupCache(val psiStamp: Long, val rootStamp: Long, val generation: Long) {
        val classes = ConcurrentHashMap<LookupKey, List<HbmXmlMapping.ClassMapping>>()
        val queries = ConcurrentHashMap<LookupKey, List<HbmXmlMapping.NamedQuery>>()
    }
    private val CACHE_KEY = com.intellij.openapi.util.Key.create<LookupCache>("mp-visualizer.scopedHbmMappings")
    private val generation = java.util.concurrent.atomic.AtomicLong()

    private fun cacheFor(project: Project): LookupCache {
        val psi = com.intellij.psi.util.PsiModificationTracker.getInstance(project).modificationCount
        val roots = com.intellij.openapi.roots.ProjectRootModificationTracker.getInstance(project).modificationCount
        val epoch = generation.get()
        val previous = project.getUserData(CACHE_KEY)
        if (previous != null && previous.psiStamp == psi && previous.rootStamp == roots && previous.generation == epoch) return previous
        return LookupCache(psi, roots, epoch).also { project.putUserData(CACHE_KEY, it) }
    }

    /**
     * 根据实体类名查找映射信息。
     *
     * @param entityClassName 实体类名（简单名如 "RuleTableItem" 或全限定名）
     * @param project         当前项目
     * @return 映射信息，找不到返回 null
     */
    fun resolve(
        entityClassName: String,
        project: Project,
        context: com.intellij.psi.PsiElement? = null,
    ): HbmXmlMapping.ClassMapping? {
        if (entityClassName.isBlank() || com.intellij.openapi.project.DumbService.isDumb(project)) return null
        return try {
            val scope = cn.song.mybatisplus.visualizer.psi.EntityClassResolver.searchScope(project, context)
            val cache = cacheFor(project).classes
            val key = LookupKey(entityClassName, scope)
            cache[key]?.let { return it.singleOrNull() }
            val matches = findHbmXmlFiles(project, scope).flatMap { parseHbmFile(it) }.filter {
                it.entityClassName == entityClassName || it.fullClassName == entityClassName
            }
            if (cache.size >= NEGATIVE_CACHE_MAX) cache.clear()
            cache[key] = matches
            matches.singleOrNull()
        } catch (_: com.intellij.openapi.project.IndexNotReadyException) {
            null
        }
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
    fun findNamedQuery(
        queryName: String,
        project: Project,
        context: com.intellij.psi.PsiElement? = null,
    ): HbmXmlMapping.NamedQuery? {
        if (queryName.isBlank() || com.intellij.openapi.project.DumbService.isDumb(project)) return null
        return try {
            val scope = cn.song.mybatisplus.visualizer.psi.EntityClassResolver.searchScope(project, context)
            val cache = cacheFor(project).queries
            val key = LookupKey(queryName, scope)
            cache[key]?.let { return it.singleOrNull() }
            val matches = mutableListOf<HbmXmlMapping.NamedQuery>()
            for (file in findHbmXmlFiles(project, scope)) {
                com.intellij.openapi.progress.ProgressManager.checkCanceled()
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
                    if (text.isNotBlank()) matches += HbmXmlMapping.NamedQuery(name, kind, text)
                }
            }
            if (cache.size >= NEGATIVE_CACHE_MAX) cache.clear()
            cache[key] = matches
            matches.singleOrNull()
        } catch (_: com.intellij.openapi.project.IndexNotReadyException) {
            null
        }
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
    fun resolveAll(project: Project, context: com.intellij.psi.PsiElement? = null): Map<String, HbmXmlMapping.ClassMapping> {
        if (com.intellij.openapi.project.DumbService.isDumb(project)) return emptyMap()
        return try {
            val scope = cn.song.mybatisplus.visualizer.psi.EntityClassResolver.searchScope(project, context)
            val mappings = findHbmXmlFiles(project, scope).flatMap { parseHbmFile(it) }
            mappings.flatMap { mapping -> setOf(mapping.entityClassName, mapping.fullClassName).map { it to mapping } }
                .groupBy({ it.first }, { it.second }).mapNotNull { (name, candidates) ->
                    candidates.singleOrNull()?.let { name to it }
                }.toMap()
        } catch (_: com.intellij.openapi.project.IndexNotReadyException) {
            emptyMap()
        }
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
    private fun findHbmXmlFiles(project: Project, scope: GlobalSearchScope): List<XmlFile> {
        // XmlFileType 在 262 平台不可直接引用（IPGP 裁剪），按项目惯例用 FileTypeManager 取标准 XML 类型
        val xmlFileType = FileTypeManager.getInstance().getStdFileType("XML")
        return FileTypeIndex.getFiles(xmlFileType, scope)
            .asSequence()
            .filter { it.name.endsWith(".hbm.xml", ignoreCase = true) }
            .mapNotNull { virtualFile ->
                com.intellij.openapi.progress.ProgressManager.checkCanceled()
                PsiManager.getInstance(project).findFile(virtualFile) as? XmlFile
            }.toList()
    }

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
        generation.incrementAndGet()
    }

}
