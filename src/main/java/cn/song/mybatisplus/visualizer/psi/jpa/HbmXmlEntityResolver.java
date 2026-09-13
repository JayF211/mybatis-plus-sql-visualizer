package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hibernate XML 映射文件（.hbm.xml）解析器。
 *
 * 解析 &lt;class&gt;, &lt;id&gt;, &lt;property&gt;, &lt;many-to-one&gt;, &lt;one-to-one&gt; 等映射元素，
 * 提取实体类名、表名、字段列映射、关联关系等元数据。
 *
 * <p>T-H1（20260907-1630）：新增 {@code <query>}（HQL）/ {@code <sql-query>}（原生 SQL）命名查询解析——
 * hbm 纯 POJO 项目（无 @NamedQuery 注解）的命名查询此前在插件视角不存在，{@code createNamedQuery(name)}
 * 回查与 hbm 文件 gutter 均无法覆盖。文本只取直属 XmlText（排除 {@code <return-scalar>} 等子标签），
 * CDATA 正常展开；按查询名（完整 {@code name} 属性）索引，负命中缓存沿用 NEGATIVE_CACHE_MAX 口径。
 *
 * <p>查找策略（T-J11 修复）：
 * <ol>
 *   <li>经 {@link FileTypeIndex} 按文件类型扫描项目内全部 XML 文件，再按文件名 {@code endsWith(".hbm.xml")}
 *       过滤——原先的 {@code FilenameIndex.getFilesByName(project, "*.hbm.xml", ...)} 按字面名匹配，
 *       通配符不生效，永远查不到文件；</li>
 *   <li>解析每个文件中的<b>全部</b> {@code <class>} 元素（原先 {@code findFirstSubTag} 只取第一个，
 *       多 class 映射文件会丢失其余实体）；</li>
 *   <li>按实体类名（简单名或全限定名）索引。</li>
 * </ol>
 *
 * <p>缓存策略：按 (project, classQualifiedName) 缓存 resolve 结果，避免重复解析；
 * 项目关闭时调用 {@link #clearCache}。
 */
public final class HbmXmlEntityResolver {

    /** 单例实例（Kotlin object 语义兼容，供外部 Xxx.method() 调用路由）。 */
    public static final HbmXmlEntityResolver INSTANCE = new HbmXmlEntityResolver();

    /** T-Perf-18：负命中缓存上限（超过整体清空）。 */
    private static final int NEGATIVE_CACHE_MAX = 4096;

    /** 解析缓存（按 project:className 索引）。 */
    private static final ConcurrentHashMap<String, HbmXmlMapping.ClassMapping> cache = new ConcurrentHashMap<>();

    /** T-H1：命名查询索引（键=项目名+查询名）；负命中沿用 negativeCache（前缀区分键）。 */
    private static final ConcurrentHashMap<String, HbmXmlMapping.NamedQuery> namedQueryCache =
            new ConcurrentHashMap<>();

    /**
     * T-Perf-18：负命中缓存（项目名+类名 → true 表示该实体无 hbm 映射）。
     * 此前每个无 hbm 实体每次预览都重跑索引全量查询（heavy 热路径）；
     * 负命中仅在 clearCache / 缓存封顶清空时失效（hbm 文件增删属开发期低频事件，
     * 设置页「清除缓存」可手动刷新）。
     */
    private static final Set<String> negativeCache = ConcurrentHashMap.newKeySet();

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
        if (negativeCache.contains(cacheKey)) return null;

        // 搜索所有 .hbm.xml 文件（T-DepApi 无需介入：本路径走 FileTypeIndex + projectScope，
        // 未使用全平台标 deprecated 的 FilenameIndex.getFilesByName）
        for (XmlFile file : findHbmXmlFiles(project)) {
            for (HbmXmlMapping.ClassMapping mapping : parseHbmFile(file)) {
                // 按简单类名和全限定名索引
                if (mapping.entityClassName().equals(entityClassName)
                        || mapping.fullClassName().equals(entityClassName)) {
                    cache.put(cacheKey, mapping);
                    return mapping;
                }
            }
        }

        // T-Perf-18：记入负命中缓存（封顶防膨胀）
        if (negativeCache.size() >= NEGATIVE_CACHE_MAX) negativeCache.clear();
        negativeCache.add(cacheKey);
        return null;
    }

    /**
     * T-H1：按查询名查找 hbm 命名查询（{@code <query>} HQL / {@code <sql-query>} 原生 SQL）。
     *
     * <p>供 {@code NamedQueryLocator.findByQueryName} 未命中 Java @NamedQuery 注解时 fallback
     * （hbm 纯 POJO 项目无注解可查），以及 hbm 文件 gutter 点击预览。
     *
     * @param queryName 完整查询名（如 {@code LegacyItem.findLowStockHql}）
     * @param project   当前项目
     */
    @Nullable
    public static HbmXmlMapping.NamedQuery findNamedQuery(@NotNull String queryName, @NotNull Project project) {
        if (queryName.isBlank()) return null;
        String cacheKey = "nq:" + project.getName() + ":" + queryName;
        HbmXmlMapping.NamedQuery cached = namedQueryCache.get(cacheKey);
        if (cached != null) return cached;
        if (negativeCache.contains(cacheKey)) return null;

        for (XmlFile file : findHbmXmlFiles(project)) {
            XmlTag rootTag = file.getRootTag();
            if (rootTag == null || !"hibernate-mapping".equals(rootTag.getName())) continue;
            for (XmlTag tag : rootTag.getSubTags()) {
                HbmXmlMapping.QueryKind kind;
                switch (tag.getName()) {
                    case "query" -> kind = HbmXmlMapping.QueryKind.HQL;
                    case "sql-query" -> kind = HbmXmlMapping.QueryKind.NATIVE;
                    default -> {
                        continue;
                    }
                }
                String name = tag.getAttributeValue("name");
                if (name == null || !name.equals(queryName)) continue;
                String text = extractNamedQueryText(tag);
                if (text.isBlank()) continue;
                HbmXmlMapping.NamedQuery query = new HbmXmlMapping.NamedQuery(name, kind, text);
                namedQueryCache.put(cacheKey, query);
                return query;
            }
        }

        if (negativeCache.size() >= NEGATIVE_CACHE_MAX) negativeCache.clear();
        negativeCache.add(cacheKey);
        return null;
    }

    /**
     * T-H1：提取命名查询文本——直属 XmlText 拼接（CDATA 已被 PSI 展开为文本节点），
     * 排除 {@code <return-scalar>}/{@code <return>}/{@code <query-param>} 等子标签的子内容。
     */
    private static String extractNamedQueryText(@NotNull XmlTag tag) {
        StringBuilder sb = new StringBuilder();
        for (com.intellij.psi.PsiElement child : tag.getChildren()) {
            if (child instanceof XmlText text) {
                sb.append(text.getText());
            }
        }
        return sb.toString().trim();
    }

    /**
     * T-J11：解析项目内全部 .hbm.xml 并按实体名索引（多 class 文件的每个 {@code <class>} 都会入索引）。
     *
     * <p><b>SqlPreviewService 接线点</b>：实体无 JPA 注解时（纯 .hbm.xml 映射的项目），可经本入口
     * 建立全量映射索引（键同时含简单名与全限定名），替代逐实体 resolve 反复全盘扫描。
     *
     * @param project 当前项目
     * @return 键为实体简单名 / 全限定名，值为对应映射；同名字段首个命中优先。无映射文件时返回空 Map
     */
    @NotNull
    public static Map<String, HbmXmlMapping.ClassMapping> resolveAll(@NotNull Project project) {
        Map<String, HbmXmlMapping.ClassMapping> index = new LinkedHashMap<>();
        for (XmlFile file : findHbmXmlFiles(project)) {
            for (HbmXmlMapping.ClassMapping mapping : parseHbmFile(file)) {
                index.putIfAbsent(mapping.entityClassName(), mapping);
                index.putIfAbsent(mapping.fullClassName(), mapping);
            }
        }
        return index;
    }

    // ------------------------------------------------------------------
    // 文件发现（T-J11：FilenameIndex 通配符失效 → FileTypeIndex + 后缀过滤）
    // ------------------------------------------------------------------

    /**
     * 查找项目内全部 .hbm.xml 文件。
     *
     * <p>{@code FileTypeIndex.getFiles(XmlFileType, projectScope)} 按<b>文件类型</b>索引取全量 XML
     * （.hbm.xml 以 {@code .xml} 结尾归 XML 文件类型），再按文件名后缀精确过滤。
     * 任何索引异常按空列表处理（优雅降级，绝不抛出）。
     */
    private static List<XmlFile> findHbmXmlFiles(@NotNull Project project) {
        try {
            // XmlFileType 在 262 平台不可直接引用（IPGP 裁剪），按项目惯例用 FileTypeManager 取标准 XML 类型
            com.intellij.openapi.fileTypes.FileType xmlFileType =
                    FileTypeManager.getInstance().getStdFileType("XML");
            List<XmlFile> result = new ArrayList<>();
            for (com.intellij.openapi.vfs.VirtualFile vf
                    : FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))) {
                if (vf.getName() == null || !vf.getName().toLowerCase().endsWith(".hbm.xml")) continue;
                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (psiFile instanceof XmlFile xmlFile) result.add(xmlFile);
            }
            return result;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析单个 .hbm.xml 文件，提取<b>全部</b>类映射（T-J11：不再只取第一个 {@code <class>}）。
     *
     * <p>单个 {@code <class>} 解析失败（缺 name/table 属性）仅跳过该类，不影响同文件其余映射。
     */
    private static List<HbmXmlMapping.ClassMapping> parseHbmFile(@NotNull XmlFile xmlFile) {
        XmlTag rootTag = xmlFile.getRootTag();
        if (rootTag == null) return List.of();
        List<HbmXmlMapping.ClassMapping> result = new ArrayList<>();
        for (XmlTag classTag : rootTag.findSubTags("class")) {
            HbmXmlMapping.ClassMapping mapping = parseClassTag(classTag);
            if (mapping != null) result.add(mapping);
        }
        return result;
    }

    /** 解析单个 {@code <class>} 元素为类映射；属性缺失返回 null（调用方跳过）。 */
    @Nullable
    private static HbmXmlMapping.ClassMapping parseClassTag(@NotNull XmlTag classTag) {
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
     * 清除缓存（含负命中缓存与命名查询索引；PluginCacheManager.clearAll 接线，项目关闭时调用）。
     */
    public static void clearCache() {
        cache.clear();
        namedQueryCache.clear();
        negativeCache.clear();
    }
}
