package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.EntityResolver
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.impl.java.stubs.index.JavaAnnotationIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * 源码 SQL 补全上下文（T-M27-02）：XML 语句 / 注解方法 → [SqlCompletionContext] 主表 +
 * 联表候选 + 参数名，供 XML 与注解补全贡献器（T-M27-03/05）共用。
 *
 * @param main       主表上下文（可为 null → 降级只补关键字/动态标签，不阻塞补全）
 * @param candidates 联表候选：主表实体 + 项目内 `@TableName` 实体（按 tableName 去重、cap 50）
 * @param paramNames 语句方法参数名（@Param 值优先 + 实际名 + param1..N，复用
 *                   [AnnotationSqlPreviewService.paramNames]）
 */
data class SourceSqlContext(
    val main: SqlCompletionContext?,
    val candidates: List<TableInfo>,
    val paramNames: List<String>,
)

/**
 * XML/注解 SQL 补全上下文解析器（T-M27-02，M27 架构 §3.2）：
 *
 * **主实体链路**（优先级从高到低，对齐 M26-B XmlEwResolver 的 namespace→接口关联链路）：
 *  1. **Mapper 接口泛型基类实参**：XML 场景向上找根 `<mapper>` 的 namespace →
 *     [JavaPsiFacade.findClass]（[XmlEwResolver] 同款）；注解场景 method.containingClass 即
 *     Mapper 接口。沿接口继承链（DFS + 防环）找全限定名后缀 `BaseMapper`/`MPJBaseMapper`
 *     的基类，取 PsiClassType 泛型实参解析出的实体类；
 *  2. **语句方法实体参数**：XML 场景按语句 id → [PsiClass.findMethodsByName] 首方法；注解
 *     场景即 method 本身。取参数列表第一个**实体类型**参数——PsiClassType 可解析为 PsiClass，
 *     且简单名不在 Wrapper 族 / 基本类型包装 / String / Collection / Map / Page / IPage 集合中；
 *  3. 均失败 → 主表为 null（降级只补关键字/标签）。
 *
 * **@TableName 实体候选**（JOIN 后表名补全）：注解类可解析时走官方
 * [AnnotatedElementsSearch.searchPsiClasses]；不可解析（测试沙盒/缺依赖）时兜底直接按 FQN
 * 查注解 stub 索引 [JavaAnnotationIndex]（全限定名书写的 `@com.baomidou...TableName` 可命中）。
 * 查询失败/异常 → 空列表兜底（不抛异常）。
 *
 * **参数名**：复用提权后的 [AnnotationSqlPreviewService.paramNames]（@Param 值优先 + 实际名 +
 * param1..N，对齐 ParamNameResolver 语义）；方法找不到 → 空列表。
 *
 * 实体解析仅在补全触发时执行（用户主动触发，容忍一次 findClass + EntityResolver），
 * 不进入 daemon 路径（M27 性能红线 §3.5）。
 */
object XmlSqlCompletionContextResolver {

    /** @TableName 注解全限定名（真实 MP 注解，libs.mp.core 提供）。 */
    private const val TABLE_NAME_ANNOTATION = "com.baomidou.mybatisplus.annotation.TableName"

    /** 联表候选上限（防大项目候选爆炸，M27 §3.2）。 */
    private const val CANDIDATES_CAP = 50

    /** 非实体参数简单名集合：MP Wrapper 族 + 基本类型包装 + 常见非实体类型（简单名匹配）。 */
    private val NON_ENTITY_SIMPLE_NAMES = setOf(
        // MP Wrapper 族（简单名匹配——测试环境类型未解析场景 canonicalText 为短名同样生效）
        "LambdaQueryWrapper", "QueryWrapper", "UpdateWrapper", "LambdaUpdateWrapper",
        "LambdaDeleteWrapper", "MPJLambdaWrapper", "MPJQueryWrapper", "DeleteWrapper", "JoinWrapper",
        // 防御补充：Wrapper 抽象基类同样非实体（XmlEwResolverTest 的 `Wrapper ew` 参数场景）
        "Wrapper", "AbstractWrapper",
        // 基本类型包装 / 常见非实体类型
        "String", "Integer", "Long", "Boolean", "Double", "Float", "Short", "Byte", "Character",
        "Object", "List", "Set", "Collection", "Map", "Page", "IPage",
    )

    /**
     * 解析 XML 语句标签的补全上下文。
     *
     * @param project      当前项目（findClass/注解索引/EntityResolver 均需）
     * @param statementTag `<select|insert|update|delete>` 语句标签（根 `<mapper>` 的 namespace
     *                     经 [XmlFile.rootTag] 向上获取；id 属性即语句 id）
     * @return [SourceSqlContext]；任何环节失败均降级（main=null/空候选/空参数名），不抛异常
     */
    fun resolveXml(project: Project, statementTag: XmlTag): SourceSqlContext {
        // 向上找根 <mapper> tag 的 namespace（与 XmlEwResolver 的 namespace 来源一致）
        val namespace = (statementTag.containingFile as? XmlFile)?.rootTag?.getAttributeValue("namespace")
        val mapperClass = namespace?.takeIf { it.isNotEmpty() }?.let {
            JavaPsiFacade.getInstance(project).findClass(it, GlobalSearchScope.allScope(project))
        }
        // 语句 id → 接口同名方法（M26-B 同款 findMethodsByName，取第一个）
        val method = statementTag.getAttributeValue("id")?.let { id ->
            mapperClass?.findMethodsByName(id, false)?.firstOrNull()
        }
        return resolveCommon(project, mapperClass, method)
    }

    /**
     * 解析注解 SQL 方法（@Select/@Insert/... 所在 Mapper 方法）的补全上下文。
     * 所在接口即 Mapper 接口，主实体链路与 XML 场景一致（无 namespace 环节）。
     *
     * @param project 当前项目
     * @param method  Mapper 接口上的 SQL 注解方法
     */
    fun resolveAnnotation(project: Project, method: PsiMethod): SourceSqlContext =
        resolveCommon(project, method.containingClass, method)

    // ------------------------------------------------------------------
    // 主实体链路
    // ------------------------------------------------------------------

    /** 主实体解析（优先级从高到低）：① Mapper 泛型基类实参 ② 方法实体参数；均失败 → null。 */
    private fun resolveCommon(project: Project, mapperClass: PsiClass?, method: PsiMethod?): SourceSqlContext {
        val mainEntity = findMainEntity(mapperClass, method)
        val mainTable = mainEntity?.let { runCatching { EntityResolver().resolve(it) }.getOrNull() }
            ?.takeIf { it.tableName.isNotEmpty() }
        return SourceSqlContext(
            main = mainTable?.let { buildMainContext(it) },
            candidates = buildCandidates(project, mainTable),
            paramNames = AnnotationSqlPreviewService.paramNames(method),
        )
    }

    /** 主实体（优先级从高到低）：Mapper 泛型基类实参 → 语句方法实体参数。 */
    private fun findMainEntity(mapperClass: PsiClass?, method: PsiMethod?): PsiClass? {
        mapperClass?.let { resolveMapperGenericEntity(it) }?.let { return it }
        return findEntityParam(method)
    }

    /**
     * 沿 Mapper 接口继承链（DFS，visited 防环）找 `BaseMapper<X>` / `MPJBaseMapper<X>` 基类，
     * 返回泛型实参解析出的实体类；找不到/实参非实体 → null。
     * 全限定名后缀匹配（`com.baomidou.mybatisplus.core.mapper.BaseMapper` /
     * `com.github.yulichang.interfaces.MPJBaseMapper`）；`Service` 等非 Mapper 族不参与。
     */
    private fun resolveMapperGenericEntity(mapperClass: PsiClass): PsiClass? {
        val visited = mutableSetOf<PsiClass>()
        fun walk(clazz: PsiClass): PsiClass? {
            if (!visited.add(clazz)) return null
            for (superType in clazz.superTypes) {
                val superClass = superType.resolve() as? PsiClass ?: continue
                if (superClass is PsiTypeParameter) continue
                if (isMapperBase(superClass)) {
                    // 取 PsiClassType 泛型实参（BaseMapper<X> / MPJBaseMapper<X> 均单类型参数）
                    val entity = (superType.parameters.firstOrNull() as? PsiClassType)
                        ?.resolve() as? PsiClass ?: continue
                    if (entity is PsiTypeParameter) continue
                    return entity
                }
            }
            for (superType in clazz.superTypes) {
                val superClass = superType.resolve() as? PsiClass ?: continue
                walk(superClass)?.let { return it }
            }
            return null
        }
        return walk(mapperClass)
    }

    /** 是否为 Mapper 族基类（全限定名后缀 BaseMapper / MPJBaseMapper）。 */
    private fun isMapperBase(clazz: PsiClass): Boolean {
        val fqn = clazz.qualifiedName ?: return false
        return fqn.endsWith(".BaseMapper") || fqn.endsWith(".MPJBaseMapper")
    }

    /**
     * 语句方法参数中第一个实体类型参数：
     *  - PsiClassType 可解析为 PsiClass（PsiPrimitiveType/PsiArrayType 天然排除）；
     *  - 非 Wrapper 类型（简单名不在 [NON_ENTITY_SIMPLE_NAMES] 的 Wrapper 族）；
     *  - 非基本类型包装 / String / Collection / Map / Page / IPage。
     * 找不到 → null。
     */
    private fun findEntityParam(method: PsiMethod?): PsiClass? {
        if (method == null) return null
        for (param in method.parameterList.parameters) {
            val type = param.type as? PsiClassType ?: continue
            val clazz = type.resolve() as? PsiClass ?: continue
            if (clazz is PsiTypeParameter) continue
            val name = clazz.name ?: continue
            if (name in NON_ENTITY_SIMPLE_NAMES) continue
            return clazz
        }
        return null
    }

    // ------------------------------------------------------------------
    // 主表上下文 / 联表候选
    // ------------------------------------------------------------------

    /** TableInfo → SqlCompletionContext（tableAlias 恒 null；列注释非空才收录；表注释原样透传）。 */
    private fun buildMainContext(table: TableInfo): SqlCompletionContext = SqlCompletionContext(
        tableName = table.tableName,
        columns = table.columns.map { it.columnName },
        tableAlias = null,
        columnComments = table.columns.mapNotNull { col ->
            col.comment?.takeIf { it.isNotBlank() }?.let { col.columnName to it }
        }.toMap(),
        tableComment = table.comment,
    )

    /**
     * 联表候选：主表实体 TableInfo 置顶 + 项目内 `@TableName` 实体（按 tableName 去重、cap 50）。
     * 查询失败/异常 → 仅主表或空列表兜底（不抛异常）。
     */
    private fun buildCandidates(project: Project, mainTable: TableInfo?): List<TableInfo> {
        val result = mutableListOf<TableInfo>()
        val seen = mutableSetOf<String>()
        mainTable?.let {
            result += it
            seen += it.tableName
        }
        findTableNameEntities(project).forEach { entityClass ->
            val table = runCatching { EntityResolver().resolve(entityClass) }.getOrNull() ?: return@forEach
            if (seen.add(table.tableName)) result += table
        }
        return result.take(CANDIDATES_CAP)
    }

    /**
     * 项目内 `@TableName` 实体类：
     *  - 注解类可解析（真实 IDE）→ [AnnotatedElementsSearch.searchPsiClasses]（官方注解索引）；
     *  - 注解类不可解析（测试沙盒/缺依赖）→ 兜底按 FQN 直查 [JavaAnnotationIndex] 注解 stub
     *    索引（源文件中**全限定名**书写的注解可命中，实体类注解场景即类级注解）。
     * 任一环节异常 → 空列表（不抛异常）。
     */
    private fun findTableNameEntities(project: Project): List<PsiClass> {
        val scope = GlobalSearchScope.allScope(project)
        return runCatching {
            val annotationClass = JavaPsiFacade.getInstance(project).findClass(TABLE_NAME_ANNOTATION, scope)
            if (annotationClass != null) {
                AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll().toList()
            } else {
                JavaAnnotationIndex.getInstance().getAnnotations(TABLE_NAME_ANNOTATION, project, scope)
                    .mapNotNull { PsiTreeUtil.getParentOfType(it, PsiClass::class.java) }
            }
        }.getOrElse { emptyList() }
    }
}
