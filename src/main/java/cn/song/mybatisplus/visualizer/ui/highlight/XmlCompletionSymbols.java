// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/XmlCompletionSymbols.kt
package cn.song.mybatisplus.visualizer.ui.highlight;

import cn.song.mybatisplus.visualizer.ui.SourceSqlContext;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * T-XmlGap W4/W5：mapper XML 补全的<b>有界符号来源</b>（零无界搜索）。
 *
 * <p>设计红线（计划 §八 风险「补全扩面引入 dumb 态回归」+ 需求「禁止全项目类扫描」）：</p>
 * <ul>
 *   <li>每条来源都在<b>有界集合</b>上取候选：当前文件标签 / namespace 接口 / 接口方法签名类型 /
 *       EntityResolver 已发现实体 / 项目内 XML 文件（按 XML 文件类型索引，非全项目类扫描）；</li>
 *   <li>一切外部调用 runCatching 包裹，失败降级空列表（不抛、不阻塞补全）；</li>
 *   <li>dumb 态禁用触索引的来源（FileTypeIndex），只留纯 PSI 可达的（当前文件标签）；</li>
 *   <li>每来源 cap（防大项目候选爆炸）。</li>
 * </ul>
 *
 * <p>与既有实现的关系：XmlSqlCompletionContextResolver 已解析 {@code @TableName} 实体候选
 * （SourceSqlContext.candidates）与参数名 —— 本类在其之上补齐「接口方法名 / 类名 / 标签 id 引用 /
 * 跨文件 sql id / resultType 类字段」，不重复解析实体。</p>
 */
public final class XmlCompletionSymbols {

    private XmlCompletionSymbols() {
    }

    /** 候选上限（每条来源的 cap）。 */
    private static final int CAP = 200;

    /** MyBatis {@code @Param} 全限定名（foreach item 元素类型反查用；与 XmlParamTypeResolver 同口径）。 */
    private static final String PARAM_ANNOTATION = "org.apache.ibatis.annotations.Param";

    // ------------------------------------------------------------------
    // namespace 接口
    // ------------------------------------------------------------------

    /** 根 {@code <mapper namespace>} → Java 接口（不可解析 → null）。 */
    @Nullable
    public static PsiClass mapperClass(Project project, XmlTag statementTag) {
        try {
            String namespace = mapperNamespace(statementTag);
            if (namespace == null) return null;
            return JavaPsiFacade.getInstance(project).findClass(namespace, GlobalSearchScope.allScope(project));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 根 {@code <mapper namespace>} 值（无根标签/无 namespace → null）。 */
    @Nullable
    public static String mapperNamespace(XmlTag statementTag) {
        try {
            String namespace = rootTag(statementTag).getAttributeValue("namespace");
            return namespace != null && !namespace.isBlank() ? namespace : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** namespace 接口全部方法名（语句 {@code id} 属性值候选；W4-2）。 */
    public static List<String> mapperMethodNames(Project project, XmlTag statementTag) {
        try {
            PsiClass mapper = mapperClass(project, statementTag);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            if (mapper != null) {
                for (com.intellij.psi.PsiMethod method : mapper.getMethods()) {
                    String name = method.getName();
                    if (name != null && !name.isBlank()) names.add(name);
                }
            }
            return names.stream().limit(CAP).toList();
        } catch (Throwable t) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // 类名候选（有界）
    // ------------------------------------------------------------------

    /**
     * 类名候选（W4-2 的 {@code resultType}/{@code type}/{@code ofType}/{@code parameterType}/
     * {@code typeHandler}/{@code javaType}）：
     *
     * <p>来源（全部有界）：</p>
     * <ol>
     *   <li>EntityResolver 已发现实体候选（SourceSqlContext.candidates 的实体简单名）；</li>
     *   <li>namespace 接口自身 + 其方法签名里出现过的类型（返回类型 / 参数类型 / 继承的父接口）；</li>
     *   <li>MyBatis 常用类型别名（{@code string}/{@code long} 等）与常见 JDK 类型简单名。</li>
     * </ol>
     *
     * <p><b>明确不做</b>：全项目 PsiShortNamesCache 空名搜索（无界）。已 import 类型清单暂不纳入
     * （需要解析 statement 所在文件导入表，收益小于成本；在报告中登记为边界）。</p>
     */
    public static List<String> classCandidates(Project project, XmlTag statementTag, SourceSqlContext context) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (cn.song.mybatisplus.visualizer.model.TableInfo table : context.getCandidates()) {
            String entityClassName = table.getEntityClassName();
            if (entityClassName != null && !entityClassName.isBlank()) out.add(entityClassName);
        }
        try {
            PsiClass mapper = mapperClass(project, statementTag);
            if (mapper != null) {
                String name = mapper.getName();
                if (name != null && !name.isBlank()) out.add(name);
                for (PsiType superType : mapper.getSuperTypes()) {
                    addTypeSimpleName(superType, out);
                }
                for (com.intellij.psi.PsiMethod method : mapper.getMethods()) {
                    addTypeSimpleName(method.getReturnType(), out);
                    for (PsiParameter parameter : method.getParameterList().getParameters()) {
                        addTypeSimpleName(parameter.getType(), out);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 类名来源探测失败 → 跳过该来源，其余来源照常
        }
        out.addAll(MYBATIS_TYPE_ALIASES);
        return out.stream().limit(CAP).toList();
    }

    /** 类型 → 简单名（剥泛型/包前缀；空白忽略）。 */
    private static void addTypeSimpleName(PsiType type, Set<String> out) {
        if (type == null) return;
        String text = type.getCanonicalText();
        int lt = text.indexOf('<');
        String noGeneric = (lt >= 0 ? text.substring(0, lt) : text).trim();
        int dot = noGeneric.lastIndexOf('.');
        String simple = dot >= 0 ? noGeneric.substring(dot + 1) : noGeneric;
        if (!simple.isBlank() && !"void".equals(simple)) out.add(simple);
    }

    /**
     * {@code resultType}/{@code parameterType}/{@code type} 类 → 属性（Java 字段）名
     * （W4-2 的 {@code property}、W5-7 投影位）。
     *
     * <p>解析顺序：{@code resultType} → {@code parameterType} → {@code type} 属性值（FQN 或简单名，
     * 简单名走 PsiShortNamesCache——<b>有界</b>：单名查询非全项目扫描）。解析不到 → fallback。</p>
     */
    public static List<String> classPropertyNames(Project project, XmlTag ownerTag, List<String> fallback) {
        String typeName = null;
        for (String attr : List.of("resultType", "parameterType", "type")) {
            String v = ownerTag.getAttributeValue(attr);
            if (v != null && !v.isBlank()) {
                typeName = v;
                break;
            }
        }
        List<String> fields = typeName != null ? fieldsOfClass(project, typeName) : List.of();
        return !fields.isEmpty() ? fields : fallback;
    }

    /** 类名（FQN 或简单名）→ 全部字段名；不可解析 → 空。 */
    public static List<String> fieldsOfClass(Project project, String typeName) {
        try {
            PsiClass clazz = resolveClass(project, typeName);
            if (clazz == null) return List.of();
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (com.intellij.psi.PsiField field : clazz.getAllFields()) {
                String name = field.getName();
                if (name != null && !name.isBlank()) names.add(name);
            }
            return names.stream().limit(CAP).toList();
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** 类名（FQN 优先，简单名兜底）→ PsiClass（不可解析 → null）。 */
    @Nullable
    public static PsiClass resolveClass(Project project, String typeName) {
        try {
            String trimmed = typeName.trim();
            int lt = trimmed.indexOf('<');
            if (lt >= 0) trimmed = trimmed.substring(0, lt).trim();
            if (trimmed.isEmpty()) return null;
            JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);
            if (trimmed.contains(".")) return facade.findClass(trimmed, scope);
            PsiClass[] classes = PsiShortNamesCache.getInstance(project).getClassesByName(trimmed, scope);
            return classes.length > 0 ? classes[0] : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * resultMap 上下文（{@code result}/{@code id}/{@code collection}/{@code association}/{@code arg}/
     * {@code idArg}/…）的属性候选来源：最近 {@code <resultMap type="X">} 的类字段（不可解析 → 空）。
     */
    public static List<String> resultMapTypeFields(XmlTag ownerTag) {
        try {
            XmlTag cur = ownerTag;
            while (cur != null && !cur.getName().equals("resultMap")) cur = cur.getParentTag();
            if (cur == null) return List.of();
            String type = cur.getAttributeValue("type");
            if (type == null || type.isBlank()) return List.of();
            return fieldsOfClass(ownerTag.getProject(), type);
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * foreach item 的元素类型字段（W5 验收：{@code <foreach item="x">} 后 {@code #{x.} 出字段候选）：
     * 由最近 {@code <foreach item=x collection="C">} 的 {@code C} 反查语句方法参数类型 {@code List<E>} 的
     * {@code E} 字段。反查不到（无类型信息/集合表达式非参数名）→ 空（调用方回退主实体字段）。
     */
    public static List<String> foreachItemFields(XmlTag enclosingTag, PsiElement caretElement, String itemName) {
        if (itemName.isBlank()) return List.of();
        try {
            XmlTag foreach = enclosingForeach(caretElement, enclosingTag, itemName);
            if (foreach == null) return List.of();
            String collectionExprRaw = foreach.getAttributeValue("collection");
            if (collectionExprRaw == null) return List.of();
            String collectionExpr = collectionExprRaw.trim();
            if (collectionExpr.isBlank()) return List.of();
            Project project = enclosingTag.getProject();
            PsiClass mapper = mapperClass(project, enclosingTag);
            if (mapper == null) return List.of();
            String statementId = enclosingTag.getAttributeValue("id");
            if (statementId == null) return List.of();
            com.intellij.psi.PsiMethod[] byName = mapper.findMethodsByName(statementId, false);
            if (byName.length == 0) return List.of();
            com.intellij.psi.PsiMethod method = byName[0];
            PsiParameter parameter = matchParameter(method.getParameterList().getParameters(), collectionExpr);
            if (parameter == null) return List.of();
            if (!(parameter.getType() instanceof PsiClassType)) return List.of();
            PsiClassType classType = (PsiClassType) parameter.getType();
            PsiType[] typeArgs = classType.getParameters();
            if (typeArgs.length != 1 || !(typeArgs[0] instanceof PsiClassType)) return List.of();
            PsiClass element = ((PsiClassType) typeArgs[0]).resolve();
            if (element == null) return List.of();
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (com.intellij.psi.PsiField field : element.getAllFields()) {
                String name = field.getName();
                if (name != null && !name.isBlank()) names.add(name);
            }
            return List.copyOf(names);
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** 光标祖先链上最近的、{@code item} 与 itemName 相同的 {@code <foreach>}（不越过 enclosingTag）。 */
    @Nullable
    private static XmlTag enclosingForeach(PsiElement caretElement, XmlTag enclosingTag, String itemName) {
        XmlTag current = PsiTreeUtil.getParentOfType(caretElement, XmlTag.class, false);
        while (current != null) {
            if (current.getName().equals("foreach")
                    && java.util.Objects.equals(current.getAttributeValue("item"), itemName)) {
                return current;
            }
            if (current == enclosingTag) break;
            current = current.getParentTag();
        }
        return null;
    }

    /**
     * 集合表达式 → 方法参数：
     * <ul>
     *   <li>表达式等于某参数名或其 {@code @Param} 值 → 该参数；</li>
     *   <li>{@code list}/{@code collection}/{@code array}（MyBatis 集合包装别名）→ 单参数方法的那一个参数。</li>
     * </ul>
     */
    @Nullable
    private static PsiParameter matchParameter(PsiParameter[] parameters, String expr) {
        for (PsiParameter p : parameters) {
            if (expr.equals(p.getName())) return p;
        }
        for (PsiParameter p : parameters) {
            if (expr.equals(annotationParamValue(p))) return p;
        }
        if (expr.equals("list") || expr.equals("collection") || expr.equals("array")) {
            return parameters.length == 1 ? parameters[0] : null;
        }
        return null;
    }

    /** {@code @Param("x")} 的显式值（无注解/值非字面量 → null）。 */
    @Nullable
    private static String annotationParamValue(PsiParameter parameter) {
        try {
            if (parameter.getModifierList() == null) return null;
            com.intellij.psi.PsiAnnotation[] annotations = parameter.getModifierList().getAnnotations();
            com.intellij.psi.PsiAnnotation annotation = null;
            for (com.intellij.psi.PsiAnnotation a : annotations) {
                if (PARAM_ANNOTATION.equals(a.getQualifiedName())) {
                    annotation = a;
                    break;
                }
            }
            if (annotation == null) {
                for (com.intellij.psi.PsiAnnotation a : annotations) {
                    if (a.getNameReferenceElement() != null
                            && "Param".equals(a.getNameReferenceElement().getReferenceName())) {
                        annotation = a;
                        break;
                    }
                }
            }
            if (annotation == null) return null;
            com.intellij.psi.PsiAnnotationMemberValue memberValue = annotation.findAttributeValue("value");
            if (memberValue instanceof PsiLiteralExpression) {
                Object value = ((PsiLiteralExpression) memberValue).getValue();
                return value instanceof String ? (String) value : null;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 当前文件标签 id 引用（W4-2）
    // ------------------------------------------------------------------

    /** 当前 mapper 文件里 {@code tagName} 标签的 {@code id} 属性值（{@code <sql id>} / {@code <resultMap id>} / {@code <parameterMap id>}）。 */
    public static List<String> localIds(XmlTag statementTag, String tagName) {
        try {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (XmlTag tag : rootTag(statementTag).findSubTags(tagName)) {
                String id = tag.getAttributeValue("id");
                if (id != null && !id.isBlank()) ids.add(id);
            }
            return ids.stream().limit(CAP).toList();
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** 当前文件的语句标签 id（{@code arg}/{@code association}/{@code collection} 的 {@code select} 属性值候选）。 */
    public static List<String> localStatementIds(XmlTag statementTag) {
        try {
            XmlTag root = rootTag(statementTag);
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (String tag : MyBatisXmlCompletionData.STATEMENT_TAGS) {
                for (XmlTag t : root.findSubTags(tag)) {
                    String id = t.getAttributeValue("id");
                    if (id != null && !id.isBlank()) ids.add(id);
                }
            }
            return ids.stream().limit(CAP).toList();
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * 跨文件 {@code <sql id>} 候选（<b>带 {@code namespace.} 前缀</b>形态，W4-2 的 {@code refid} 跨文件）：
     * 项目内其它 XML 文件里根 {@code <mapper>} 的 {@code <sql id>}。
     *
     * <p>索引来源 FileTypeIndex（XML 文件类型，<b>非全项目类扫描</b>）；dumb 态或索引不可用 → 空。</p>
     */
    public static List<String> crossFileSqlIds(Project project, @Nullable XmlFile currentFile) {
        try {
            if (DumbService.isDumb(project)) return List.of();
            com.intellij.openapi.vfs.VirtualFile currentVf = currentFile != null ? currentFile.getVirtualFile() : null;
            PsiManager psiManager = PsiManager.getInstance(project);
            LinkedHashSet<String> out = new LinkedHashSet<>();
            int scanned = 0;
            for (com.intellij.openapi.vfs.VirtualFile vf :
                    FileTypeIndex.getFiles(XmlFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
                if (out.size() >= CAP) break;
                if (vf.equals(currentVf)) continue;
                if (++scanned > CROSS_FILE_CAP) break;
                PsiFile pf = psiManager.findFile(vf);
                if (!(pf instanceof XmlFile)) continue;
                XmlTag root = ((XmlFile) pf).getRootTag();
                if (root == null || !root.getName().equals("mapper")) continue;
                String ns = root.getAttributeValue("namespace");
                ns = ns != null && !ns.isBlank() ? ns : null;
                for (XmlTag sql : root.findSubTags("sql")) {
                    String id = sql.getAttributeValue("id");
                    if (id == null || id.isBlank()) continue;
                    out.add(ns != null ? ns + "." + id : id);
                }
            }
            return out.stream().limit(CAP).toList();
        } catch (Throwable t) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // 主实体字段名 / resultMap 引用列（W5-8 / W5-10）
    // ------------------------------------------------------------------

    /** 主实体 Java 字段名（OGNL / {@code property} 的兜底来源）：{@code tableName == main.tableName} 的候选实体。 */
    public static List<String> entityFieldNames(SourceSqlContext context) {
        SqlCompletionContext main = context.getMain();
        if (main == null || main.tableName == null) return List.of();
        cn.song.mybatisplus.visualizer.model.TableInfo table = null;
        for (cn.song.mybatisplus.visualizer.model.TableInfo t : context.getCandidates()) {
            if (t.getTableName().equals(main.tableName)) {
                table = t;
                break;
            }
        }
        if (table == null) return List.of();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (cn.song.mybatisplus.visualizer.model.ColumnInfo col : table.getColumns()) {
            String fieldName = col.getFieldName();
            if (fieldName != null && !fieldName.isBlank()) names.add(fieldName);
        }
        return List.copyOf(names);
    }

    /** 主表列名（{@code keyColumn}/{@code columnPrefix} 兜底来源）。 */
    public static List<String> entityColumnNames(SourceSqlContext context) {
        SqlCompletionContext main = context.getMain();
        return main != null ? main.columns.stream().distinct().toList() : List.of();
    }

    // ------------------------------------------------------------------
    // mybatis-config.xml `<properties>` key（W5-4，全项目聚合 + 缓存）
    // ------------------------------------------------------------------

    /**
     * 缓存<b>挂在 project 上</b>（不是进程级单例字段）：原先用「进程级 @Volatile stamp/value +
     * 比当前 project 的 modificationCount」，多项目 IDE 里两个 project 的计数撞上时会互相读到
     * 对方的缓存值（本轮补测发现的结构性缺陷，已改）。挂 project 后随 project 一起回收，无泄漏。
     */
    private static final Key<Long> PROPS_CACHE_STAMP = Key.create("MPSV_CONFIG_PROPERTIES_CACHE_STAMP");

    private static final Key<List<String>> PROPS_CACHE_VALUE = Key.create("MPSV_CONFIG_PROPERTIES_CACHE_VALUE");

    /**
     * 全项目 mybatis-config.xml {@code <properties>} 的 key 聚合（<b>带缓存</b>：按
     * {@code PsiModificationTracker.modificationCount} 失效，缓存挂 project）。
     *
     * <p>key 口径<b>以 MyBatis 实现为准</b>（XMLConfigBuilder.propertiesElement →
     * XNode.getChildrenAsProperties()，mybatis 3.5.19 sources 实读）：{@code <properties>} 的子标签
     * <b>标签名被忽略</b>，key 取子标签的 {@code name} 属性值（且要求 {@code value} 属性在场）。
     * 例：{@code <property name="schemaName" value="app"/>} → key = schemaName。
     * 本轮补测前这里取的是<b>子标签名</b>（恒为 "property"），等于该能力对真实配置文件无效。</p>
     *
     * <p>已知边界（不覆盖）：{@code <properties resource="…"/>}/{@code url="…"} 指向的外部
     * .properties 文件里的 key 不可见（需读文件内容，本轮不做）；{@code <settings>} 的 {@code name}
     * 属性是设置项、不是变量，不入集。</p>
     *
     * <p>dumb 态/索引不可用 → 空。单文件纯函数 {@link #propertyKeysOf} 供测试直测。</p>
     */
    public static List<String> configPropertyKeys(Project project) {
        try {
            if (DumbService.isDumb(project)) return List.of();
            long stamp = PsiModificationTracker.getInstance(project).getModificationCount();
            Long cachedStamp = project.getUserData(PROPS_CACHE_STAMP);
            if (cachedStamp != null && cachedStamp == stamp) {
                List<String> cached = project.getUserData(PROPS_CACHE_VALUE);
                return cached != null ? cached : List.of();
            }
            PsiManager psiManager = PsiManager.getInstance(project);
            LinkedHashSet<String> value = new LinkedHashSet<>();
            int scanned = 0;
            for (com.intellij.openapi.vfs.VirtualFile vf :
                    FileTypeIndex.getFiles(XmlFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
                if (++scanned > CROSS_FILE_CAP) break;
                PsiFile pf = psiManager.findFile(vf);
                if (!(pf instanceof XmlFile)) continue;
                value.addAll(propertyKeysOf((XmlFile) pf));
            }
            List<String> frozen = value.stream().limit(CAP).toList();
            project.putUserData(PROPS_CACHE_STAMP, stamp);
            project.putUserData(PROPS_CACHE_VALUE, frozen);
            return frozen;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * 单文件纯函数：{@code <configuration><properties>} 下各 {@code <property name="X">} 的
     * <b>{@code name} 属性值</b>（key 口径见 {@link #configPropertyKeys} KDoc，
     * MyBatis getChildrenAsProperties 实证）。
     */
    public static List<String> propertyKeysOf(XmlFile xmlFile) {
        try {
            XmlTag root = xmlFile.getRootTag();
            if (root == null || !root.getName().equals("configuration")) return List.of();
            List<String> out = new ArrayList<>();
            for (XmlTag props : root.findSubTags("properties")) {
                for (XmlTag property : props.findSubTags("property")) {
                    String name = property.getAttributeValue("name");
                    if (name != null && !name.isBlank()) out.add(name);
                }
            }
            return out.stream().distinct().toList();
        } catch (Throwable t) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static XmlTag rootTag(XmlTag tag) {
        XmlTag cur = tag;
        while (cur.getParentTag() != null) cur = cur.getParentTag();
        return cur;
    }

    /** MyBatis 常用类型别名（{@code org.apache.ibatis.type.TypeAliasRegistry.registerAlias} 的既有别名子集）。 */
    private static final List<String> MYBATIS_TYPE_ALIASES = List.of(
            "string", "byte", "long", "short", "int", "integer", "double", "float", "boolean",
            "date", "decimal", "bigdecimal", "biginteger", "object", "map", "hashmap", "list", "arraylist");

    /** 跨文件扫描上限（防大项目遍历成本）。 */
    private static final int CROSS_FILE_CAP = 500;
}
