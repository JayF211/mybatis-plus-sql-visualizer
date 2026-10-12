package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.ui.SourceSqlContext
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * T-XmlGap W4/W5：mapper XML 补全的**有界符号来源**（零无界搜索）。
 *
 * 设计红线（计划 §八 风险「补全扩面引入 dumb 态回归」+ 需求「禁止全项目类扫描」）：
 *  - 每条来源都在**有界集合**上取候选：当前文件标签 / namespace 接口 / 接口方法签名类型 /
 *    `EntityResolver` 已发现实体 / 项目内 XML 文件（按 XML 文件类型索引，非全项目类扫描）；
 *  - 一切外部调用 `runCatching` 包裹，失败降级空列表（不抛、不阻塞补全）；
 *  - dumb 态禁用触索引的来源（`FileTypeIndex`），只留纯 PSI 可达的（当前文件标签）；
 *  - 每来源 cap（防大项目候选爆炸）。
 *
 * 与既有实现的关系：[XmlSqlCompletionContextResolver] 已解析 `@TableName` 实体候选（[SourceSqlContext.candidates]）
 * 与参数名 —— 本对象在其之上补齐「接口方法名 / 类名 / 标签 id 引用 / 跨文件 sql id / resultType 类字段」，
 * 不重复解析实体。
 */
internal object XmlCompletionSymbols {

    /** 候选上限（每条来源的 cap）。 */
    private const val CAP = 200

    /** MyBatis `@Param` 全限定名（foreach item 元素类型反查用；与 [XmlParamTypeResolver] 同口径）。 */
    private const val PARAM_ANNOTATION = "org.apache.ibatis.annotations.Param"

    // ------------------------------------------------------------------
    // namespace 接口
    // ------------------------------------------------------------------

    /** 根 `<mapper namespace>` → Java 接口（不可解析 → null）。 */
    fun mapperClass(project: Project, statementTag: XmlTag): PsiClass? = runCatching {
        val namespace = mapperNamespace(statementTag) ?: return null
        JavaPsiFacade.getInstance(project).findClass(namespace, GlobalSearchScope.allScope(project))
    }.getOrNull()

    /** 根 `<mapper namespace>` 值（无根标签/无 namespace → null）。 */
    fun mapperNamespace(statementTag: XmlTag): String? = runCatching {
        rootTag(statementTag).getAttributeValue("namespace")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** namespace 接口全部方法名（语句 `id` 属性值候选；W4-2）。 */
    fun mapperMethodNames(project: Project, statementTag: XmlTag): List<String> = runCatching {
        mapperClass(project, statementTag)?.methods.orEmpty()
            .mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }
            .distinct()
            .take(CAP)
    }.getOrElse { emptyList() }

    // ------------------------------------------------------------------
    // 类名候选（有界）
    // ------------------------------------------------------------------

    /**
     * 类名候选（W4-2 的 `resultType`/`type`/`ofType`/`parameterType`/`typeHandler`/`javaType`）：
     *
     * 来源（全部有界）：
     *  1. `EntityResolver` 已发现实体候选（[SourceSqlContext.candidates] 的实体简单名）；
     *  2. namespace 接口自身 + 其方法签名里出现过的类型（返回类型 / 参数类型 / 继承的父接口）；
     *  3. MyBatis 常用类型别名（`string`/`long` 等）与常见 JDK 类型简单名。
     *
     * **明确不做**：全项目 `PsiShortNamesCache` 空名搜索（无界）。已 import 类型清单暂不纳入
     * （需要解析 statement 所在文件导入表，收益小于成本；在报告中登记为边界）。
     */
    fun classCandidates(project: Project, statementTag: XmlTag, context: SourceSqlContext): List<String> {
        val out = LinkedHashSet<String>()
        context.candidates.forEach { table ->
            table.entityClassName.takeIf { it.isNotBlank() }?.let { out += it }
        }
        runCatching {
            mapperClass(project, statementTag)?.let { mapper ->
                mapper.name?.takeIf { it.isNotBlank() }?.let { out += it }
                mapper.superTypes.forEach { addTypeSimpleName(it, out) }
                mapper.methods.forEach { method ->
                    addTypeSimpleName(method.returnType, out)
                    method.parameterList.parameters.forEach { addTypeSimpleName(it.type, out) }
                }
            }
        }
        out += MYBATIS_TYPE_ALIASES
        return out.toList().take(CAP)
    }

    /** 类型 → 简单名（剥泛型/包前缀；空白忽略）。 */
    private fun addTypeSimpleName(type: PsiType?, out: MutableSet<String>) {
        if (type == null) return
        val simple = type.canonicalText.substringBefore('<').trim().substringAfterLast('.')
        if (simple.isNotBlank() && simple != "void") out += simple
    }

    /**
     * `resultType`/`parameterType`/`type` 类 → 属性（Java 字段）名（W4-2 的 `property`、W5-7 投影位）。
     *
     * 解析顺序：`resultType` → `parameterType` → `type` 属性值（FQN 或简单名，简单名走
     * `PsiShortNamesCache`——**有界**：单名查询非全项目扫描）。解析不到 → [fallback]。
     */
    fun classPropertyNames(project: Project, ownerTag: XmlTag, fallback: List<String>): List<String> {
        val typeName = listOf("resultType", "parameterType", "type")
            .firstNotNullOfOrNull { ownerTag.getAttributeValue(it)?.takeIf { v -> v.isNotBlank() } }
        val fields = typeName?.let { fieldsOfClass(project, it) }.orEmpty()
        return if (fields.isNotEmpty()) fields else fallback
    }

    /** 类名（FQN 或简单名）→ 全部字段名；不可解析 → 空。 */
    fun fieldsOfClass(project: Project, typeName: String): List<String> = runCatching {
        val clazz = resolveClass(project, typeName) ?: return emptyList()
        clazz.allFields.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }.distinct().take(CAP)
    }.getOrElse { emptyList() }

    /** 类名（FQN 优先，简单名兜底）→ PsiClass（不可解析 → null）。 */
    fun resolveClass(project: Project, typeName: String): PsiClass? = runCatching {
        val trimmed = typeName.trim().substringBefore('<').trim()
        if (trimmed.isEmpty()) return null
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        if (trimmed.contains('.')) return facade.findClass(trimmed, scope)
        PsiShortNamesCache.getInstance(project).getClassesByName(trimmed, scope).firstOrNull()
    }.getOrNull()

    /**
     * resultMap 上下文（`result`/`id`/`collection`/`association`/`arg`/`idArg`/…）的属性候选来源：
     * 最近 `<resultMap type="X">` 的类字段（不可解析 → 空）。
     */
    fun resultMapTypeFields(ownerTag: XmlTag): List<String> = runCatching {
        var cur: XmlTag? = ownerTag
        while (cur != null && cur.name != "resultMap") cur = cur.parentTag
        val type = cur?.getAttributeValue("type")?.takeIf { it.isNotBlank() } ?: return emptyList()
        fieldsOfClass(ownerTag.project, type)
    }.getOrElse { emptyList() }

    /**
     * foreach item 的元素类型字段（W5 验收：`<foreach item="x">` 后 `#{x.` 出字段候选）：
     * 由最近 `<foreach item=x collection="C">` 的 `C` 反查语句方法参数类型 `List<E>` 的 `E` 字段。
     * 反查不到（无类型信息/集合表达式非参数名）→ 空（调用方回退主实体字段）。
     */
    fun foreachItemFields(enclosingTag: XmlTag, caretElement: PsiElement, itemName: String): List<String> {
        if (itemName.isBlank()) return emptyList()
        return runCatching {
            val foreach = enclosingForeach(caretElement, enclosingTag, itemName) ?: return emptyList()
            val collectionExpr = foreach.getAttributeValue("collection")?.trim()?.takeIf { it.isNotBlank() }
                ?: return emptyList()
            val project = enclosingTag.project
            val mapper = mapperClass(project, enclosingTag) ?: return emptyList()
            val statementId = enclosingTag.getAttributeValue("id") ?: return emptyList()
            val method = mapper.findMethodsByName(statementId, false).firstOrNull() ?: return emptyList()
            val parameter = matchParameter(method.parameterList.parameters, collectionExpr) ?: return emptyList()
            val classType = parameter.type as? PsiClassType ?: return emptyList()
            val element = classType.parameters.singleOrNull() as? PsiClassType ?: return emptyList()
            element.resolve()?.allFields?.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }?.distinct().orEmpty()
        }.getOrElse { emptyList() }
    }

    /** 光标祖先链上最近的、`item` 与 [itemName] 相同的 `<foreach>`（不越过 [enclosingTag]）。 */
    private fun enclosingForeach(caretElement: PsiElement, enclosingTag: XmlTag, itemName: String): XmlTag? {
        var current: XmlTag? = PsiTreeUtil.getParentOfType(caretElement, XmlTag::class.java, false)
        while (current != null) {
            if (current.name == "foreach" && current.getAttributeValue("item") == itemName) return current
            if (current === enclosingTag) break
            current = current.parentTag
        }
        return null
    }

    /**
     * 集合表达式 → 方法参数：
     *  - 表达式等于某参数名或其 `@Param` 值 → 该参数；
     *  - `list`/`collection`/`array`（MyBatis 集合包装别名）→ 单参数方法的那一个参数。
     */
    private fun matchParameter(parameters: Array<PsiParameter>, expr: String): PsiParameter? {
        parameters.firstOrNull { it.name == expr }?.let { return it }
        parameters.firstOrNull { annotationParamValue(it) == expr }?.let { return it }
        if (expr == "list" || expr == "collection" || expr == "array") return parameters.singleOrNull()
        return null
    }

    /** `@Param("x")` 的显式值（无注解/值非字面量 → null）。 */
    private fun annotationParamValue(parameter: PsiParameter): String? = runCatching {
        val annotations = parameter.modifierList?.annotations.orEmpty()
        val annotation = annotations.firstOrNull { it.qualifiedName == PARAM_ANNOTATION }
            ?: annotations.firstOrNull { it.nameReferenceElement?.referenceName == "Param" }
            ?: return null
        val memberValue = annotation.findAttributeValue("value")
        (memberValue as? PsiLiteralExpression)?.value as? String
    }.getOrNull()

    // ------------------------------------------------------------------
    // 当前文件标签 id 引用（W4-2）
    // ------------------------------------------------------------------

    /** 当前 mapper 文件里 `tagName` 标签的 `id` 属性值（`<sql id>` / `<resultMap id>` / `<parameterMap id>`）。 */
    fun localIds(statementTag: XmlTag, tagName: String): List<String> = runCatching {
        rootTag(statementTag).findSubTags(tagName)
            .mapNotNull { it.getAttributeValue("id")?.takeIf { id -> id.isNotBlank() } }
            .distinct()
            .take(CAP)
    }.getOrElse { emptyList() }

    /** 当前文件的语句标签 id（`arg`/`association`/`collection` 的 `select` 属性值候选）。 */
    fun localStatementIds(statementTag: XmlTag): List<String> = runCatching {
        val root = rootTag(statementTag)
        MyBatisXmlCompletionData.STATEMENT_TAGS
            .flatMap { tag -> root.findSubTags(tag).mapNotNull { it.getAttributeValue("id") } }
            .filter { it.isNotBlank() }
            .distinct()
            .take(CAP)
    }.getOrElse { emptyList() }

    /**
     * 跨文件 `<sql id>` 候选（**带 `namespace.` 前缀**形态，W4-2 的 `refid` 跨文件）：
     * 项目内其它 XML 文件里根 `<mapper>` 的 `<sql id>`。
     *
     * 索引来源 `FileTypeIndex`（XML 文件类型，**非全项目类扫描**）；dumb 态或索引不可用 → 空。
     */
    fun crossFileSqlIds(project: Project, currentFile: XmlFile?): List<String> = runCatching {
        if (DumbService.isDumb(project)) return emptyList()
        val currentVf = currentFile?.virtualFile
        val psiManager = PsiManager.getInstance(project)
        FileTypeIndex.getFiles(XmlFileType.INSTANCE, GlobalSearchScope.projectScope(project))
            .asSequence()
            .filter { it != currentVf }
            .take(CROSS_FILE_CAP)
            .mapNotNull { psiManager.findFile(it) as? XmlFile }
            .mapNotNull { it.rootTag }
            .filter { it.name == "mapper" }
            .flatMap { root ->
                val ns = root.getAttributeValue("namespace")?.takeIf { it.isNotBlank() }
                root.findSubTags("sql").mapNotNull { sql ->
                    sql.getAttributeValue("id")?.takeIf { it.isNotBlank() }
                        ?.let { id -> if (ns != null) "$ns.$id" else id }
                }
            }
            .distinct()
            .take(CAP)
            .toList()
    }.getOrElse { emptyList() }

    // ------------------------------------------------------------------
    // 主实体字段名 / resultMap 引用列（W5-8 / W5-10）
    // ------------------------------------------------------------------

    /** 主实体 Java 字段名（OGNL / `property` 的兜底来源）：`tableName == main.tableName` 的候选实体。 */
    fun entityFieldNames(context: SourceSqlContext): List<String> {
        val mainTableName = context.main?.tableName ?: return emptyList()
        val table = context.candidates.firstOrNull { it.tableName == mainTableName }
        return table?.columns?.mapNotNull { it.fieldName?.takeIf { n -> n.isNotBlank() } }?.distinct().orEmpty()
    }

    /** 主表列名（`keyColumn`/`columnPrefix` 兜底来源）。 */
    fun entityColumnNames(context: SourceSqlContext): List<String> =
        context.main?.columns?.distinct().orEmpty()

    // ------------------------------------------------------------------
    // mybatis-config.xml `<properties>` key（W5-4，全项目聚合 + 缓存）
    // ------------------------------------------------------------------

    @Volatile
    private var propsCacheStamp: Long = Long.MIN_VALUE

    @Volatile
    private var propsCacheValue: List<String> = emptyList()

    /**
     * 全项目 mybatis-config.xml `<properties>` 的 key 聚合（**带缓存**：按
     * `PsiModificationTracker.modificationCount` 失效）。
     *
     * 来源：`<configuration><properties>` 下的每个子标签名即 key（MyBatis config 语义）。
     * dumb 态/索引不可用 → 空。单文件纯函数 [propertyKeysOf] 供测试直测。
     */
    fun configPropertyKeys(project: Project): List<String> = runCatching {
        if (DumbService.isDumb(project)) return emptyList()
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        if (stamp == propsCacheStamp) return propsCacheValue
        val psiManager = PsiManager.getInstance(project)
        val value = FileTypeIndex.getFiles(XmlFileType.INSTANCE, GlobalSearchScope.projectScope(project))
            .asSequence()
            .take(CROSS_FILE_CAP)
            .mapNotNull { psiManager.findFile(it) as? XmlFile }
            .flatMap { propertyKeysOf(it).asSequence() }
            .distinct()
            .take(CAP)
            .toList()
        propsCacheStamp = stamp
        propsCacheValue = value
        value
    }.getOrElse { emptyList() }

    /** 单文件纯函数：`<configuration><properties>` 下子标签名（key）。 */
    fun propertyKeysOf(xmlFile: XmlFile): List<String> = runCatching {
        val root = xmlFile.rootTag ?: return emptyList()
        if (root.name != "configuration") return emptyList()
        root.findSubTags("properties")
            .flatMap { props -> props.subTags.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } } }
            .distinct()
    }.getOrElse { emptyList() }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun rootTag(tag: XmlTag): XmlTag {
        var cur = tag
        while (cur.parentTag != null) cur = cur.parentTag!!
        return cur
    }

    /** MyBatis 常用类型别名（`org.apache.ibatis.type.TypeAliasRegistry.registerAlias` 的既有别名子集）。 */
    private val MYBATIS_TYPE_ALIASES: List<String> = listOf(
        "string", "byte", "long", "short", "int", "integer", "double", "float", "boolean",
        "date", "decimal", "bigdecimal", "biginteger", "object", "map", "hashmap", "list", "arraylist",
    )

    /** 跨文件扫描上限（防大项目遍历成本）。 */
    private const val CROSS_FILE_CAP = 500
}
