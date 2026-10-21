package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import cn.song.mybatisplus.visualizer.xml.XmlDirectText;
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

    /** @param name exact lookup name @param scope query scope including libraries */
    private record LookupKey(String name, GlobalSearchScope scope) {}
    private static final class LookupCache {
        final long psiStamp, rootStamp, generation;
        final Map<LookupKey, List<HbmXmlMapping.ClassMapping>> classes = new ConcurrentHashMap<>();
        final Map<LookupKey, List<HbmXmlMapping.NamedQuery>> queries = new ConcurrentHashMap<>();
        LookupCache(long psiStamp, long rootStamp, long generation) {
            this.psiStamp = psiStamp; this.rootStamp = rootStamp; this.generation = generation;
        }
    }
    private static final com.intellij.openapi.util.Key<LookupCache> CACHE_KEY = com.intellij.openapi.util.Key.create("mp-visualizer.scopedHbmMappings");
    private static final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();

    private static LookupCache cacheFor(Project project) {
        long psi = com.intellij.psi.util.PsiModificationTracker.getInstance(project).getModificationCount();
        long roots = com.intellij.openapi.roots.ProjectRootModificationTracker.getInstance(project).getModificationCount();
        long epoch = generation.get();
        LookupCache previous = project.getUserData(CACHE_KEY);
        if (previous != null && previous.psiStamp == psi && previous.rootStamp == roots && previous.generation == epoch) return previous;
        LookupCache cache = new LookupCache(psi, roots, epoch);
        project.putUserData(CACHE_KEY, cache);
        return cache;
    }

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
        return resolve(entityClassName, project, null);
    }

    @Nullable
    public static HbmXmlMapping.ClassMapping resolve(String entityClassName, Project project, com.intellij.psi.PsiElement context) {
        if (entityClassName.isBlank() || com.intellij.openapi.project.DumbService.isDumb(project)) return null;
        try {
            GlobalSearchScope scope = cn.song.mybatisplus.visualizer.psi.EntityClassResolver.searchScope(project, context);
            var cache = cacheFor(project).classes;
            LookupKey key = new LookupKey(entityClassName, scope);
            var cached = cache.get(key);
            if (cached != null) return cached.size() == 1 ? cached.get(0) : null;
            List<HbmXmlMapping.ClassMapping> matches = new ArrayList<>();
            for (XmlFile file : findHbmXmlFiles(project, scope)) {
                for (var mapping : parseHbmFile(file)) {
                    if (mapping.entityClassName().equals(entityClassName) || mapping.fullClassName().equals(entityClassName)) matches.add(mapping);
                }
            }
            if (cache.size() >= NEGATIVE_CACHE_MAX) cache.clear();
            cache.put(key, matches);
            return matches.size() == 1 ? matches.get(0) : null;
        } catch (com.intellij.openapi.project.IndexNotReadyException ignored) {
            return null;
        }
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
        return findNamedQuery(queryName, project, null);
    }

    @Nullable
    public static HbmXmlMapping.NamedQuery findNamedQuery(String queryName, Project project, com.intellij.psi.PsiElement context) {
        List<HbmXmlMapping.NamedQuery> matches = namedQueryMatches(queryName, project, context);
        return matches.size() == 1 && !matches.get(0).getQueryText().isBlank() ? matches.get(0) : null;
    }

    /** 调用点区分「未找到」与「重名歧义」，后者禁止把名字再当 HQL 字面量预览。 */
    public static boolean hasAmbiguousNamedQuery(@NotNull String queryName, @NotNull Project project) {
        return hasAmbiguousNamedQuery(queryName, project, null);
    }

    public static boolean hasAmbiguousNamedQuery(String queryName, Project project, com.intellij.psi.PsiElement context) {
        return namedQueryMatches(queryName, project, context).size() > 1;
    }

    private static List<HbmXmlMapping.NamedQuery> namedQueryMatches(String queryName, Project project, com.intellij.psi.PsiElement context) {
        if (queryName.isBlank() || com.intellij.openapi.project.DumbService.isDumb(project)) return List.of();
        try {
            GlobalSearchScope scope = cn.song.mybatisplus.visualizer.psi.EntityClassResolver.searchScope(project, context);
            var cache = cacheFor(project).queries;
            LookupKey key = new LookupKey(queryName, scope);
            var cached = cache.get(key);
            if (cached != null) return cached;
            List<HbmXmlMapping.NamedQuery> matches = new ArrayList<>();
            for (XmlFile file : findHbmXmlFiles(project, scope)) {
                com.intellij.openapi.progress.ProgressManager.checkCanceled();
                XmlTag rootTag = file.getRootTag();
                if (rootTag == null || !"hibernate-mapping".equals(rootTag.getName())) continue;
                for (XmlTag tag : rootTag.getSubTags()) {
                    HbmXmlMapping.QueryKind kind;
                    switch (tag.getName()) {
                        case "query" -> kind = HbmXmlMapping.QueryKind.HQL;
                        case "sql-query" -> kind = HbmXmlMapping.QueryKind.NATIVE;
                        default -> { continue; }
                    }
                    String name = tag.getAttributeValue("name");
                    if (!queryName.equals(name)) continue;
                    String text = extractNamedQueryText(tag);
                    // 空文本定义也参与消歧，不能过滤后把另一份同名定义虚构成唯一 owner。
                    matches.add(new HbmXmlMapping.NamedQuery(name, kind, text));
                }
            }
            if (cache.size() >= NEGATIVE_CACHE_MAX) cache.clear();
            cache.put(key, matches);
            return matches;
        } catch (com.intellij.openapi.project.IndexNotReadyException ignored) {
            return List.of();
        }
    }

    /** 仅为预览决策查找定义标签；不缓存 PSI/URI，避免把调用点误认成 XML owner。 */
    @Nullable
    public static XmlTag findNamedQueryDefinition(@NotNull HbmXmlMapping.NamedQuery query, @NotNull Project project) {
        return findNamedQueryDefinition(query, project, null);
    }

    @Nullable
    public static XmlTag findNamedQueryDefinition(HbmXmlMapping.NamedQuery query, Project project, com.intellij.psi.PsiElement context) {
        if (com.intellij.openapi.project.DumbService.isDumb(project)) return null;
        try {
            GlobalSearchScope scope = cn.song.mybatisplus.visualizer.psi.EntityClassResolver.searchScope(project, context);
            XmlTag owner = null;
            for (XmlFile file : findHbmXmlFiles(project, scope)) {
                com.intellij.openapi.progress.ProgressManager.checkCanceled();
                XmlTag root = file.getRootTag();
                if (root == null || !"hibernate-mapping".equals(root.getName())) continue;
                for (XmlTag tag : root.getSubTags()) {
                    HbmXmlMapping.QueryKind kind;
                    switch (tag.getName()) {
                        case "query" -> kind = HbmXmlMapping.QueryKind.HQL;
                        case "sql-query" -> kind = HbmXmlMapping.QueryKind.NATIVE;
                        default -> { continue; }
                    }
                    if (kind == query.getKind() && query.getName().equals(tag.getAttributeValue("name"))) {
                        if (owner != null || !extractNamedQueryText(tag).equals(query.getQueryText())) return null;
                        owner = tag;
                    }
                }
            }
            return owner;
        } catch (com.intellij.openapi.project.IndexNotReadyException ignored) {
            return null;
        }
    }

    /**
     * T-H1：提取命名查询文本——直属 XmlText 拼接（CDATA 剥包裹、trim，口径见
     * {@code xml/XmlDirectText}）；排除 {@code <return-scalar>}/{@code <return>}/{@code <query-param>} 等子标签的子内容。
     */
    private static String extractNamedQueryText(@NotNull XmlTag tag) {
        return XmlDirectText.directTextOf(tag);
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
        return resolveAll(project, null);
    }

    public static Map<String, HbmXmlMapping.ClassMapping> resolveAll(Project project, com.intellij.psi.PsiElement context) {
        if (com.intellij.openapi.project.DumbService.isDumb(project)) return Map.of();
        try {
            GlobalSearchScope scope = cn.song.mybatisplus.visualizer.psi.EntityClassResolver.searchScope(project, context);
            Map<String, List<HbmXmlMapping.ClassMapping>> groups = new LinkedHashMap<>();
            for (XmlFile file : findHbmXmlFiles(project, scope)) {
                for (var mapping : parseHbmFile(file)) {
                    for (String name : new java.util.LinkedHashSet<>(List.of(mapping.entityClassName(), mapping.fullClassName()))) {
                        groups.computeIfAbsent(name, key -> new ArrayList<>()).add(mapping);
                    }
                }
            }
            Map<String, HbmXmlMapping.ClassMapping> index = new LinkedHashMap<>();
            groups.forEach((name, matches) -> { if (matches.size() == 1) index.put(name, matches.get(0)); });
            return index;
        } catch (com.intellij.openapi.project.IndexNotReadyException ignored) {
            return Map.of();
        }
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
    private static List<XmlFile> findHbmXmlFiles(@NotNull Project project, GlobalSearchScope scope) {
        // XmlFileType 在 262 平台不可直接引用（IPGP 裁剪），按项目惯例用 FileTypeManager 取标准 XML 类型
        var xmlFileType = FileTypeManager.getInstance().getStdFileType("XML");
        List<XmlFile> result = new ArrayList<>();
        for (var vf : FileTypeIndex.getFiles(xmlFileType, scope)) {
            if (!vf.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hbm.xml")) continue;
            com.intellij.openapi.progress.ProgressManager.checkCanceled();
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile instanceof XmlFile xmlFile) result.add(xmlFile);
        }
        return result;
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
     * 清除实体与命名查询正/负缓存；PSI 或项目根变更也会自动失效，新增重复定义不会沿用旧首项。
     */
    public static void clearCache() {
        generation.incrementAndGet();
    }
}
