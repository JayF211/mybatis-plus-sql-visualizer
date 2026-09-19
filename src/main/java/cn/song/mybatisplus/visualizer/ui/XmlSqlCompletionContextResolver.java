package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.EntityResolver;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.impl.java.stubs.index.JavaAnnotationIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * XML/注解 SQL 补全上下文解析器（T-M27-02，M27 架构 §3.2）：
 *
 * <p><b>主实体链路</b>（优先级从高到低，对齐 M26-B XmlEwResolver 的 namespace→接口关联链路）：
 * 1. <b>Mapper 接口泛型基类实参</b>：XML 场景向上找根 {@code <mapper>} 的 namespace →
 *    {@link JavaPsiFacade#findClass}（{@link XmlEwResolver} 同款）；注解场景 method.containingClass 即
 *    Mapper 接口。沿接口继承链（DFS + 防环）找全限定名后缀 {@code BaseMapper}/{@code MPJBaseMapper}
 *    的基类，取 PsiClassType 泛型实参解析出的实体类；
 * 2. <b>语句方法实体参数</b>：XML 场景按语句 id → {@link PsiClass#findMethodsByName} 首方法；注解
 *    场景即 method 本身。取参数列表第一个<b>实体类型</b>参数——PsiClassType 可解析为 PsiClass，
 *    且简单名不在 Wrapper 族 / 基本类型包装 / String / Collection / Map / Page / IPage 集合中；
 * 3. 均失败 → 主表为 null（降级只补关键字/标签）。
 *
 * <p><b>@TableName 实体候选</b>（JOIN 后表名补全）：注解类可解析时走官方
 * {@link AnnotatedElementsSearch#searchPsiClasses}；不可解析（测试沙盒/缺依赖）时兜底直接按 FQN
 * 查注解 stub 索引 {@link JavaAnnotationIndex}（全限定名书写的 {@code @com.baomidou...TableName} 可命中）。
 * 查询失败/异常 → 空列表兜底（不抛异常）。
 *
 * <p><b>参数名</b>：复用 {@link AnnotationSqlPreviewService#paramNames}（@Param 值优先 + 实际名 +
 * param1..N，对齐 ParamNameResolver 语义）；方法找不到 → 空列表。
 *
 * <p>实体解析仅在补全触发时执行（用户主动触发，容忍一次 findClass + EntityResolver），
 * 不进入 daemon 路径（M27 性能红线 §3.5）。
 */
public final class XmlSqlCompletionContextResolver {

    /** @TableName 注解全限定名（真实 MP 注解，libs.mp.core 提供）。 */
    private static final String TABLE_NAME_ANNOTATION = "com.baomidou.mybatisplus.annotation.TableName";

    /** 联表候选上限（防大项目候选爆炸，M27 §3.2）。 */
    private static final int CANDIDATES_CAP = 50;

    /** 非实体参数简单名集合：MP Wrapper 族 + 基本类型包装 + 常见非实体类型（简单名匹配）。 */
    private static final Set<String> NON_ENTITY_SIMPLE_NAMES = Set.of(
            // MP Wrapper 族（简单名匹配——测试环境类型未解析场景 canonicalText 为短名同样生效）
            "LambdaQueryWrapper", "QueryWrapper", "UpdateWrapper", "LambdaUpdateWrapper",
            "LambdaDeleteWrapper", "MPJLambdaWrapper", "MPJQueryWrapper", "DeleteWrapper", "JoinWrapper",
            // 防御补充：Wrapper 抽象基类同样非实体（XmlEwResolverTest 的 `Wrapper ew` 参数场景）
            "Wrapper", "AbstractWrapper",
            // 基本类型包装 / 常见非实体类型
            "String", "Integer", "Long", "Boolean", "Double", "Float", "Short", "Byte", "Character",
            "Object", "List", "Set", "Collection", "Map", "Page", "IPage"
    );

    public static final XmlSqlCompletionContextResolver INSTANCE = new XmlSqlCompletionContextResolver();

    private XmlSqlCompletionContextResolver() {
    }

    /**
     * 解析 XML 语句标签的补全上下文。
     *
     * @param project     当前项目（findClass/注解索引/EntityResolver 均需）
     * @param statementTag {@code <select|insert|update|delete>} 语句标签（根 {@code <mapper>} 的 namespace
     *                    经 {@link XmlFile#getRootTag()} 向上获取；id 属性即语句 id）
     * @return {@link SourceSqlContext}；任何环节失败均降级（main=null/空候选/空参数名），不抛异常
     */
    @NotNull
    public SourceSqlContext resolveXml(@NotNull Project project, @NotNull XmlTag statementTag) {
        String namespace = null;
        if (statementTag.getContainingFile() instanceof XmlFile) {
            XmlTag root = ((XmlFile) statementTag.getContainingFile()).getRootTag();
            if (root != null) namespace = root.getAttributeValue("namespace");
        }
        PsiClass mapperClass = null;
        if (namespace != null && !namespace.isEmpty()) {
            mapperClass = JavaPsiFacade.getInstance(project).findClass(namespace, GlobalSearchScope.allScope(project));
        }
        PsiMethod method = null;
        String id = statementTag.getAttributeValue("id");
        if (id != null && mapperClass != null) {
            PsiMethod[] methods = mapperClass.findMethodsByName(id, false);
            if (methods.length > 0) method = methods[0];
        }
        return resolveCommon(project, mapperClass, method);
    }

    /**
     * 解析注解 SQL 方法（@Select/@Insert/... 所在 Mapper 方法）的补全上下文。
     * 所在接口即 Mapper 接口，主实体链路与 XML 场景一致（无 namespace 环节）。
     *
     * @param project 当前项目
     * @param method  Mapper 接口上的 SQL 注解方法
     */
    @NotNull
    public SourceSqlContext resolveAnnotation(@NotNull Project project, @NotNull PsiMethod method) {
        return resolveCommon(project, method.getContainingClass(), method);
    }

    // ------------------------------------------------------------------
    // 主实体链路
    // ------------------------------------------------------------------

    /** 主实体解析（优先级从高到低）：① Mapper 泛型基类实参 ② 方法实体参数；均失败 → null。 */
    private SourceSqlContext resolveCommon(@NotNull Project project, @Nullable PsiClass mapperClass, @Nullable PsiMethod method) {
        PsiClass mainEntity = findMainEntity(mapperClass, method);
        TableInfo mainTable = null;
        if (mainEntity != null) {
            try {
                TableInfo resolved = new EntityResolver().resolve(mainEntity);
                if (resolved != null && resolved.getTableName() != null && !resolved.getTableName().isEmpty()) {
                    mainTable = resolved;
                }
            } catch (Exception ignored) {
                // 实体解析异常 → 降级
            }
        }
        return new SourceSqlContext(
                mainTable != null ? buildMainContext(mainTable) : null,
                buildCandidates(project, mainTable),
                // T-PT-01：paramTypes 键序 = 命名参数序 + param1..N（与旧 paramNames 列表逐项一致）
                new ArrayList<>(AnnotationSqlPreviewService.INSTANCE.paramTypes(method).keySet())
        );
    }

    /** 主实体（优先级从高到低）：Mapper 泛型基类实参 → 语句方法实体参数。 */
    @Nullable
    private PsiClass findMainEntity(@Nullable PsiClass mapperClass, @Nullable PsiMethod method) {
        if (mapperClass != null) {
            PsiClass entity = resolveMapperGenericEntity(mapperClass, new HashSet<>());
            if (entity != null) return entity;
        }
        return findEntityParam(method);
    }

    /**
     * 沿 Mapper 接口继承链（DFS，visited 防环）找 {@code BaseMapper<X>} / {@code MPJBaseMapper<X>} 基类，
     * 返回泛型实参解析出的实体类；找不到/实参非实体 → null。
     * 全限定名后缀匹配（{@code com.baomidou.mybatisplus.core.mapper.BaseMapper} /
     * {@code com.github.yulichang.interfaces.MPJBaseMapper}）；{@code Service} 等非 Mapper 族不参与。
     */
    @Nullable
    private PsiClass resolveMapperGenericEntity(@NotNull PsiClass mapperClass, @NotNull Set<PsiClass> visited) {
        if (!visited.add(mapperClass)) return null;
        for (PsiClassType superType : mapperClass.getSuperTypes()) {
            PsiClass superClass = superType.resolve();
            if (superClass == null) continue;
            if (superClass instanceof PsiTypeParameter) continue;
            if (isMapperBase(superClass)) {
                PsiType[] parameters = superType.getParameters();
                if (parameters.length > 0 && parameters[0] instanceof PsiClassType) {
                    PsiClass entity = ((PsiClassType) parameters[0]).resolve();
                    if (entity != null && !(entity instanceof PsiTypeParameter)) return entity;
                }
            }
        }
        for (PsiClassType superType : mapperClass.getSuperTypes()) {
            PsiClass superClass = superType.resolve();
            if (superClass == null) continue;
            PsiClass result = resolveMapperGenericEntity(superClass, visited);
            if (result != null) return result;
        }
        return null;
    }

    /** 是否为 Mapper 族基类（全限定名后缀 BaseMapper / MPJBaseMapper）。 */
    private boolean isMapperBase(@NotNull PsiClass clazz) {
        String fqn = clazz.getQualifiedName();
        if (fqn == null) return false;
        return fqn.endsWith(".BaseMapper") || fqn.endsWith(".MPJBaseMapper");
    }

    /**
     * 语句方法参数中第一个实体类型参数：
     *  - PsiClassType 可解析为 PsiClass（PsiPrimitiveType/PsiArrayType 天然排除）；
     *  - 非 Wrapper 类型（简单名不在 {@link #NON_ENTITY_SIMPLE_NAMES} 的 Wrapper 族）；
     *  - 非基本类型包装 / String / Collection / Map / Page / IPage。
     * 找不到 → null。
     */
    @Nullable
    private PsiClass findEntityParam(@Nullable PsiMethod method) {
        if (method == null) return null;
        for (PsiParameter param : method.getParameterList().getParameters()) {
            if (!(param.getType() instanceof PsiClassType)) continue;
            PsiClass clazz = ((PsiClassType) param.getType()).resolve();
            if (clazz == null) continue;
            if (clazz instanceof PsiTypeParameter) continue;
            String name = clazz.getName();
            if (name == null) continue;
            if (NON_ENTITY_SIMPLE_NAMES.contains(name)) continue;
            return clazz;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 主表上下文 / 联表候选
    // ------------------------------------------------------------------

    /** TableInfo → SqlCompletionContext（tableAlias 恒 null；列注释非空才收录；表注释原样透传）。 */
    @NotNull
    private SqlCompletionContext buildMainContext(@NotNull TableInfo table) {
        Map<String, String> columnComments = new LinkedHashMap<>();
        for (ColumnInfo col : table.getColumns()) {
            String comment = col.getComment();
            if (comment != null && !comment.isBlank()) {
                columnComments.put(col.getColumnName(), comment);
            }
        }
        List<String> columns = new ArrayList<>();
        for (ColumnInfo col : table.getColumns()) {
            columns.add(col.getColumnName());
        }
        return new SqlCompletionContext(
                table.getTableName(),
                columns,
                null,
                columnComments,
                table.getComment()
        );
    }

    /**
     * 联表候选：主表实体 TableInfo 置顶 + 项目内 {@code @TableName} 实体（按 tableName 去重、cap 50）。
     * 查询失败/异常 → 仅主表或空列表兜底（不抛异常）。
     */
    @NotNull
    private List<TableInfo> buildCandidates(@NotNull Project project, @Nullable TableInfo mainTable) {
        List<TableInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (mainTable != null) {
            result.add(mainTable);
            seen.add(mainTable.getTableName());
        }
        for (PsiClass entityClass : findTableNameEntities(project)) {
            TableInfo table = null;
            try {
                table = new EntityResolver().resolve(entityClass);
            } catch (Exception ignored) {
                // 实体解析异常 → 跳过
            }
            if (table == null) continue;
            if (seen.add(table.getTableName())) result.add(table);
        }
        if (result.size() > CANDIDATES_CAP) {
            return new ArrayList<>(result.subList(0, CANDIDATES_CAP));
        }
        return result;
    }

    /**
     * 项目内 {@code @TableName} 实体类：
     *  - 注解类可解析（真实 IDE）→ {@link AnnotatedElementsSearch#searchPsiClasses}（官方注解索引）；
     *  - 注解类不可解析（测试沙盒/缺依赖）→ 兜底按 FQN 直查 {@link JavaAnnotationIndex} 注解 stub
     *    索引（源文件中<b>全限定名</b>书写的注解可命中，实体类注解场景即类级注解）。
     * 任一环节异常 → 空列表（不抛异常）。
     */
    @NotNull
    private List<PsiClass> findTableNameEntities(@NotNull Project project) {
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        try {
            PsiClass annotationClass = JavaPsiFacade.getInstance(project).findClass(TABLE_NAME_ANNOTATION, scope);
            if (annotationClass != null) {
                return new ArrayList<>(AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll());
            } else {
                Collection<PsiAnnotation> annotations =
                        JavaAnnotationIndex.getInstance().getAnnotations(TABLE_NAME_ANNOTATION, project, scope);
                List<PsiClass> result = new ArrayList<>();
                for (PsiAnnotation annotation : annotations) {
                    PsiClass psiClass = PsiTreeUtil.getParentOfType(annotation, PsiClass.class);
                    if (psiClass != null) result.add(psiClass);
                }
                return result;
            }
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
