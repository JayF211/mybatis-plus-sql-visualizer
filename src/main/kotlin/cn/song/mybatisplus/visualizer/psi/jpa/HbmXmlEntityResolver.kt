package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import java.util.concurrent.ConcurrentHashMap

/**
 * Hibernate XML 映射文件（.hbm.xml）解析器。
 *
 * 解析 `<class>`, `<id>`, `<property>`, `<many-to-one>`, `<one-to-one>` 等映射元素，
 * 提取实体类名、表名、字段列映射、关联关系等元数据。
 *
 * 查找策略：
 * 1. 在项目中搜索所有 .hbm.xml 文件
 * 2. 解析每个文件，提取 `<class>` 映射信息
 * 3. 按实体类名（简单名或全限定名）索引
 *
 * 缓存策略：按 (project, classQualifiedName) 缓存，避免重复解析。
 */
object HbmXmlEntityResolver {

    /** T-Perf-18：负命中缓存上限（超过整体清空）。 */
    private const val NEGATIVE_CACHE_MAX = 4096

    private val cache = ConcurrentHashMap<String, HbmXmlMapping.ClassMapping>()

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

        // 搜索所有 .hbm.xml 文件
        val hbmFiles = FilenameIndex.getFilesByName(project, "*.hbm.xml", GlobalSearchScope.allScope(project))

        for (file in hbmFiles) {
            val xmlFile = file as? XmlFile ?: continue
            val mapping = parseHbmFile(xmlFile) ?: continue

            // 按简单类名和全限定名索引
            if (mapping.entityClassName == entityClassName || mapping.fullClassName == entityClassName) {
                cache[cacheKey] = mapping
                return mapping
            }
        }

        // T-Perf-18：记入负命中缓存（封顶防膨胀）
        if (negativeCache.size >= NEGATIVE_CACHE_MAX) negativeCache.clear()
        negativeCache.add(cacheKey)
        return null
    }

    /**
     * 解析单个 .hbm.xml 文件，提取所有类映射。
     */
    private fun parseHbmFile(xmlFile: XmlFile): HbmXmlMapping.ClassMapping? {
        val rootTag = xmlFile.rootTag ?: return null
        val classTag = rootTag.findFirstSubTag("class") ?: return null

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
     * 清除缓存（含负命中缓存；PluginCacheManager.clearAll 接线，项目关闭时调用）。
     */
    fun clearCache() {
        cache.clear()
        negativeCache.clear()
    }

}
