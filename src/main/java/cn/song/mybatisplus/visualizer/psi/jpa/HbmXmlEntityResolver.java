package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hibernate XML 映射文件（.hbm.xml）解析器。
 *
 * 解析 &lt;class&gt;, &lt;id&gt;, &lt;property&gt;, &lt;many-to-one&gt;, &lt;one-to-one&gt; 等映射元素，
 * 提取实体类名、表名、字段列映射、关联关系等元数据。
 *
 * 查找策略：
 * 1. 在项目中搜索所有 .hbm.xml 文件
 * 2. 解析每个文件，提取 &lt;class&gt; 映射信息
 * 3. 按实体类名（简单名或全限定名）索引
 *
 * 缓存策略：按 (project, classQualifiedName) 缓存，避免重复解析。
 */
public final class HbmXmlEntityResolver {

    /** 单例实例（Kotlin object 语义兼容，供外部 Xxx.method() 调用路由）。 */
    public static final HbmXmlEntityResolver INSTANCE = new HbmXmlEntityResolver();

    /** 解析缓存（按 project:className 索引）。 */
    private static final ConcurrentHashMap<String, HbmXmlMapping.ClassMapping> cache = new ConcurrentHashMap<>();

    private HbmXmlEntityResolver() {
    }

    /**
     * 根据实体类名查找映射信息。
     *
     * @param entityClassName 实体类名（简单名如 "RuleTableItem" 或全限定名）
     * @param project         当前项目
     * @return 映射信息，找不到返回 null
     */
    @Nullable
    public static HbmXmlMapping.ClassMapping resolve(@NotNull String entityClassName, @NotNull Project project) {
        String cacheKey = project.getName() + ":" + entityClassName;
        HbmXmlMapping.ClassMapping cached = cache.get(cacheKey);
        if (cached != null) return cached;

        // T-DepApi：getFilesByName 全平台标 deprecated——getVirtualFilesByName(name, scope)
        // （无 project 重载）243/253/262 javap 实证均无弃用标记；返回 VirtualFile 后经
        // PsiManager 转 XmlFile，解析语义不变。
        Collection<VirtualFile> hbmFiles =
                FilenameIndex.getVirtualFilesByName("*.hbm.xml", GlobalSearchScope.allScope(project));

        for (VirtualFile vf : hbmFiles) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (!(psiFile instanceof XmlFile)) continue;
            XmlFile xmlFile = (XmlFile) psiFile;
            HbmXmlMapping.ClassMapping mapping = parseHbmFile(xmlFile);
            if (mapping == null) continue;

            // 按简单类名和全限定名索引
            if (mapping.entityClassName().equals(entityClassName) || mapping.fullClassName().equals(entityClassName)) {
                cache.put(cacheKey, mapping);
                return mapping;
            }
        }

        return null;
    }

    /**
     * 解析单个 .hbm.xml 文件，提取所有类映射。
     */
    @Nullable
    private static HbmXmlMapping.ClassMapping parseHbmFile(@NotNull XmlFile xmlFile) {
        XmlTag rootTag = xmlFile.getRootTag();
        if (rootTag == null) return null;
        XmlTag classTag = rootTag.findFirstSubTag("class");
        if (classTag == null) return null;

        String name = classTag.getAttributeValue("name");
        if (name == null) return null;
        String table = classTag.getAttributeValue("table");
        if (table == null) return null;
        String entityClassName = name.substring(name.lastIndexOf('.') + 1);
        String fullClassName = name;

        List<HbmXmlMapping.PropertyMapping> properties = new ArrayList<>();
        List<HbmXmlMapping.AssociationMapping> associations = new ArrayList<>();

        // 解析子元素
        for (XmlTag subTag : classTag.getSubTags()) {
            String tagName = subTag.getName();
            switch (tagName) {
                case "id" -> {
                    String fieldName = subTag.getAttributeValue("name");
                    if (fieldName == null) continue;
                    String columnName = extractColumn(subTag);
                    if (columnName == null) continue;
                    properties.add(new HbmXmlMapping.PropertyMapping(fieldName, columnName, true));
                }
                case "property" -> {
                    String fieldName = subTag.getAttributeValue("name");
                    if (fieldName == null) continue;
                    String columnName = extractColumn(subTag);
                    if (columnName == null) continue;
                    properties.add(new HbmXmlMapping.PropertyMapping(fieldName, columnName, false));
                }
                case "many-to-one", "one-to-one" -> {
                    String fieldName = subTag.getAttributeValue("name");
                    if (fieldName == null) continue;
                    String targetClass = subTag.getAttributeValue("class");
                    if (targetClass == null) continue;
                    String joinColumn = extractColumn(subTag);
                    if (joinColumn == null) continue;
                    associations.add(new HbmXmlMapping.AssociationMapping(fieldName, targetClass, joinColumn));
                }
                default -> {
                    // 其它子元素忽略
                }
            }
        }

        return new HbmXmlMapping.ClassMapping(entityClassName, fullClassName, table, properties, associations);
    }

    /**
     * 从映射元素中提取列名。
     *
     * 优先从 &lt;column name="..."&gt; 子元素提取，其次从 column 属性提取。
     */
    @Nullable
    private static String extractColumn(@NotNull XmlTag tag) {
        // 优先：column 子元素
        XmlTag columnTag = tag.findFirstSubTag("column");
        if (columnTag != null) {
            return columnTag.getAttributeValue("name");
        }
        // 次选：column 属性
        return tag.getAttributeValue("column");
    }

    /**
     * 清除缓存（项目关闭时调用）。
     */
    public static void clearCache() {
        cache.clear();
    }
}
