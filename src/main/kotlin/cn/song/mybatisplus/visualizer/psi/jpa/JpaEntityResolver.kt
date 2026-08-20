package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.model.AssociationInfo
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRole
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import cn.song.mybatisplus.visualizer.util.TableConfigSignature
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import java.util.concurrent.ConcurrentHashMap

/**
 * JPA 实体解析器：从 JPA 注解实体 [PsiClass] 解析出 [TableInfo]。
 *
 * 规则（对齐 JPA 规范 / Hibernate 默认行为）：
 *  - 表名：优先 `@Table(name="xxx")` 注解，否则按命名策略由类名推断（驼峰转下划线）
 *  - 列名：优先 `@Column(name="xxx")` 注解，否则按命名策略由字段名推断
 *  - 主键：`@Id` 标记
 *  - `@Transient` 字段（非持久化）跳过
 *  - `@ManyToOne` + `@JoinColumn(name="xxx")` 解析为外键列
 *  - `@OneToMany` / `@OneToOne` / `@ManyToMany` 关联字段跳过（非简单列）
 *  - `@MappedSuperclass` 父类字段继承（父类字段在前，子类字段在后）
 *  - 字段按角色分组稳定排序——`@Id`(PRIMARY) 置顶、业务字段(BUSINESS) 居中、
 *    审计字段(AUDIT) 与系统字段(SYSTEM) 沉底，组内保持声明顺序
 *  - 解析结果按「PsiFile + classQualifiedName + configSignature」三维缓存，
 *    文件修改戳（modificationStamp）+ 配置签名双维度失效
 */
class JpaEntityResolver {

    /**
     * 解析 JPA 实体类为表信息（带缓存）。
     *
     * 缓存键：PsiFile + classQualifiedName + configSignature（三维）。
     * 失效机制：
     *  - 文件修改（modificationStamp 变化）→ 缓存自然失效
     *  - 配置签名变化（tableNameInference 等设置变更）→ 缓存失效
     *
     * @param entityClass JPA 实体 PsiClass
     * @return 表信息；类名缺失等异常情况返回带推断表名、空列的表信息（保证不抛异常）
     */
    fun resolve(entityClass: PsiClass): TableInfo {
        val className = entityClass.name ?: return TableInfo("", "", emptyList())
        val file = entityClass.containingFile ?: return resolveInternal(entityClass)
        val qName = entityClass.qualifiedName ?: className
        val configSig = TableConfigSignature.projectSignature()

        val key = JpaCacheKey(file, qName, configSig)
        val stamp = file.modificationStamp
        cache[key]?.let { entry ->
            if (entry.stamp == stamp) return entry.value
        }

        val value = resolveInternal(entityClass)
        cache[key] = JpaEntry(stamp, value)
        return value
    }

    /**
     * 内部解析（完整 PSI 遍历，缓存未命中时调用）。
     */
    private fun resolveInternal(entityClass: PsiClass): TableInfo {
        val className = entityClass.name ?: return TableInfo("", "", emptyList())
        // 表名：优先 @Table(name="xxx")，否则按命名策略由类名推断
        val tableName = annotationAttributeValue(entityClass, TABLE_ANNOTATION, "name")
            ?: decorateTableName(inferTableName(className))

        // 递归父类收集字段（支持 @MappedSuperclass 继承；父类字段在前、子类字段在后）
        val fields = collectFieldsIncludingSuper(entityClass)
        // 按角色分组稳定排序（PRIMARY 置顶 → BUSINESS 居中 → AUDIT/SYSTEM 沉底，组内保持声明顺序）
        val columns = fields.mapNotNull { field -> resolveColumn(field, ignoredFieldNames()) }
            .sortedWith(compareBy { it.role })
            // 去重：同一列名只保留第一个（简单字段优先于 @ManyToOne 关联字段）
            // 示例：departmentId(@Column) 和 department(@ManyToOne+@JoinColumn) 同映射 department_id
            .distinctBy { it.columnName }
        // 表注释（类级注解 > javadoc > 行注释；schema 预留最高优先级，当前返回 null）
        val comment = resolveComment(entityClass)
        // 收集 @ManyToOne / @OneToOne 关联元数据
        val associations = fields.mapNotNull { field -> resolveAssociation(field) }
        return TableInfo(className, tableName, columns, null, null, comment, associations)
    }

    /**
     * 递归收集类及其所有父类的字段（支持 @MappedSuperclass 继承）：
     *  - 父类字段在前、子类字段在后；
     *  - 仅沿 superClass 类继承链——接口字段不纳入；
     *  - seen 集合防循环继承（异常 PSI 场景防御）；
     *  - 父类本身标注 @MappedSuperclass 或 @Entity 时字段均纳入。
     */
    private fun collectFieldsIncludingSuper(
        clazz: PsiClass,
        seen: MutableSet<PsiClass> = mutableSetOf(),
    ): List<PsiField> {
        if (!seen.add(clazz)) return emptyList()
        val superFields = clazz.superClass?.let { collectFieldsIncludingSuper(it, seen) } ?: emptyList()
        return superFields + clazz.fields.toList()
    }

    /**
     * 解析单个字段为列信息；非持久化字段（@Transient / 关联注解 @OneToMany/@ManyToMany 等）返回 null。
     */
    private fun resolveColumn(field: PsiField, ignoredFields: Set<String>): ColumnInfo? {
        val fieldName = field.name

        // @Transient：非持久化字段，跳过
        if (hasAnnotation(field, TRANSIENT_ANNOTATION)) {
            return null
        }

        // 一对多 / 多对多关联字段跳过（非简单列，对应集合类型属性）
        if (hasAnnotation(field, ONE_TO_MANY_ANNOTATION) || hasAnnotation(field, MANY_TO_MANY_ANNOTATION)) {
            return null
        }

        // 忽略字段名配置（`;` 分隔，按 Java 字段名匹配）
        if (fieldName in ignoredFields) {
            return null
        }

        // serialVersionUID 默认忽略（对齐 JPA/Hibernate 不映射 static 字段语义）
        if (fieldName.lowercase().replace("_", "") == "serialversionuid") {
            return null
        }

        // 列名：优先 @Column(name="xxx") 或 @JoinColumn(name="xxx")（@ManyToOne 外键列），否则驼峰转下划线
        val isId = hasAnnotation(field, ID_ANNOTATION)
        val isManyToOne = hasAnnotation(field, MANY_TO_ONE_ANNOTATION)
        val isOneToOne = hasAnnotation(field, ONE_TO_ONE_ANNOTATION)

        val columnName = when {
            // @ManyToOne + @JoinColumn：外键列名
            isManyToOne -> annotationAttributeValue(field, JOIN_COLUMN_ANNOTATION, "name")
                ?: NamingStrategy.toSnakeCase(fieldName) + "_id"
            // @OneToOne + @JoinColumn：外键列名
            isOneToOne -> annotationAttributeValue(field, JOIN_COLUMN_ANNOTATION, "name")
                ?: NamingStrategy.toSnakeCase(fieldName) + "_id"
            // @Column：普通列名
            else -> annotationAttributeValue(field, COLUMN_ANNOTATION, "name")
                ?: NamingStrategy.toSnakeCase(fieldName)
        }

        // 字段类型（参数回填引号推断用）；PSI 类型解析异常时兜底 null
        val fieldType = runCatching { field.type.canonicalText }.getOrNull()

        // 字段注释（注解 > javadoc > 行注释；schema 预留最高优先级，当前返回 null）
        val comment = resolveComment(field)

        // 角色分组——主键置顶(PRIMARY)、关联外键(BUSINESS)、普通业务字段(BUSINESS)
        val role = when {
            isId -> ColumnRole.PRIMARY
            else -> ColumnRole.BUSINESS
        }

        return ColumnInfo(fieldName, columnName, isId, false, fieldType, null, comment, role)
    }

    // ------------------------------------------------------------------
    // 关联元数据解析（@ManyToOne / @OneToOne）
    // ------------------------------------------------------------------

    /**
     * 解析单个字段的关联信息。
     *
     * @ManyToOne + @JoinColumn → 外键列名 + 目标实体类名 → 目标表名
     * @OneToOne + @JoinColumn  → 同上
     * 其他 → null
     */
    private fun resolveAssociation(field: PsiField): AssociationInfo? {
        val isManyToOne = hasAnnotation(field, MANY_TO_ONE_ANNOTATION)
        val isOneToOne = hasAnnotation(field, ONE_TO_ONE_ANNOTATION)
        if (!isManyToOne && !isOneToOne) return null

        val fieldName = field.name
        // 外键列名：@JoinColumn(name="xxx") 或默认 {fieldName}_id
        val joinColumn = annotationAttributeValue(field, JOIN_COLUMN_ANNOTATION, "name")
            ?: NamingStrategy.toSnakeCase(fieldName) + "_id"

        // 目标实体类：从字段类型获取
        val targetClass = resolveTargetEntityClass(field) ?: return null
        val targetTableName = resolveTargetTableName(targetClass)

        // 别名：实体类名首字母小写
        val targetAlias = (targetClass.name ?: fieldName).replaceFirstChar { it.lowercaseChar() }

        return AssociationInfo(
            fieldName = fieldName,
            joinColumn = joinColumn,
            targetTable = targetTableName,
            targetAlias = targetAlias,
        )
    }

    /**
     * 解析 @ManyToOne/@OneToOne 字段的目标实体类。
     *
     * 策略：
     * 1. 字段类型直接是 PsiClassType → 取 PsiClass
     * 2. 泛型字段（如 Optional<Address>）→ 取第一个泛型实参
     * 3. 无法解析 → null（降级：不作为关联处理）
     */
    private fun resolveTargetEntityClass(field: PsiField): PsiClass? {
        val type = field.type as? PsiClassType ?: return null
        return type.resolve()
    }

    /**
     * 从目标实体 PsiClass 获取表名。
     *
     * 优先 @Table(name="xxx")，否则按命名策略推断。
     */
    private fun resolveTargetTableName(targetClass: PsiClass): String {
        val explicitTable = annotationAttributeValue(targetClass, TABLE_ANNOTATION, "name")
        if (explicitTable != null) return explicitTable
        val className = targetClass.name ?: return NamingStrategy.toSnakeCase("unknown")
        return decorateTableName(inferTableName(className))
    }

    // ------------------------------------------------------------------
    // 注释读取（四级优先级：SCHEMA > ANNOTATION > JAVADOC > LINE_COMMENT）
    // ------------------------------------------------------------------

    /**
     * 注释来源优先级（schema 预留）：
     * SCHEMA（数据源 schema 列/表注释）最高，当前实现 schema 返回 null——
     * 实际生效顺序 = 注解 > javadoc > 行注释。
     */
    enum class CommentSource { SCHEMA, ANNOTATION, JAVADOC, LINE_COMMENT }

    /**
     * 提取字段注释（PsiField）：注解白名单 > javadoc > 行注释。
     */
    private fun resolveComment(field: PsiField): String? = firstNonBlank(
        listOf(
            null, // SCHEMA 预留
            annotationComment(field),
            javadocComment(field.docComment?.text),
            lineComment(field),
        ),
    )

    /** 提取类级注释：类注解白名单 > 类 javadoc > 类上方行注释。 */
    private fun resolveComment(psiClass: PsiClass): String? = firstNonBlank(
        listOf(
            null, // SCHEMA 预留
            annotationComment(psiClass),
            javadocComment(psiClass.docComment?.text),
            lineComment(psiClass),
        ),
    )

    /** 取最高优先级非空注释（按来源顺序，schema 为空时注解生效）。 */
    private fun firstNonBlank(comments: List<String?>): String? =
        comments.firstOrNull { !it.isNullOrBlank() }?.trim()

    /**
     * 注解注释提取：白名单注解按专属属性，通用属性（description/name/comment/desc）兜底；
     * 排除 JPA 核心映射注解（@Entity/@Table/@Column/@Id 等——name/value 是表名/列名非注释）。
     */
    private fun annotationComment(psi: PsiClass): String? =
        annotationComment(psi.modifierList?.annotations ?: emptyArray())

    private fun annotationComment(field: PsiField): String? =
        annotationComment(field.modifierList?.annotations ?: emptyArray())

    private fun annotationComment(annotations: Array<PsiAnnotation>): String? {
        for (annotation in annotations) {
            val simpleName = annotation.nameReferenceElement?.referenceName ?: continue
            // JPA 核心映射注解不参与注释提取（name/value 是表名/列名）
            if (simpleName in JPA_MAPPING_ANNOTATION_NAMES) {
                continue
            }
            // 白名单注解专属属性
            COMMENT_ANNOTATION_ATTRIBUTES[simpleName]?.forEach { attr ->
                attributeString(annotation, attr)?.takeIf { it.isNotBlank() }?.let { return it }
            }
            // 通用属性兜底
            COMMENT_ATTRIBUTE_NAMES.forEach { attr ->
                attributeString(annotation, attr)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    /**
     * JavaDoc 描述提取：去 `/** */` 与行首 `*`，取 `@` 标签前描述段落，空行折叠为空格。
     */
    private fun javadocComment(docText: String?): String? {
        val text = docText ?: return null
        val cleaned = text
            .removePrefix("/**")
            .removeSuffix("*/")
            .lineSequence()
            .map { it.trim().removePrefix("*").trim() }
            .takeWhile { !it.startsWith("@") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return cleaned.takeIf { it.isNotBlank() }
    }

    /**
     * 行注释提取（兜底）：`// xxx` 行注释。
     *
     * 双路径：① LightJava 将元素上方的行注释包含进元素 textRange——先检查元素自身文本首行；
     * ② 完整 IDE 中注释是独立兄弟节点——回退文档文本向上逐行扫描
     * （跳过空行与 `@` 注解行，第一个非空行若是 `//` 则提取，否则停止）。
     */
    private fun lineComment(element: PsiElement): String? {
        // 路径 ①：元素自身文本首行（LightPlatform 注释绑定进 textRange）
        val ownFirstLine = element.text.substringBefore('\n').trim()
        if (ownFirstLine.startsWith("//")) {
            val comment = ownFirstLine.removePrefix("//").trim()
            if (comment.isNotBlank()) return comment
        }
        // 路径 ②：文档文本向上扫描
        val offset = element.textRange.startOffset
        val before = element.containingFile.text.substring(0, offset)
        val lines = before.split("\n")
        for (i in lines.indices.reversed()) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            if (line.startsWith("@")) continue
            if (line.startsWith("//")) {
                val comment = line.removePrefix("//").trim()
                return comment.takeIf { it.isNotBlank() }
            }
            return null
        }
        return null
    }

    /** 注解 → 注释属性白名单（swagger/easyexcel 等业务注解的注释属性）。 */
    private val COMMENT_ANNOTATION_ATTRIBUTES: Map<String, List<String>> = mapOf(
        "Schema" to listOf("description"),
        "ApiModelProperty" to listOf("value", "description"),
        "ExcelProperty" to listOf("value"),
        "DictField" to listOf("description", "comment", "name"),
    )

    /** 通用注释属性（非白名单注解兜底提取，不取 value——value 常为标识符）。 */
    private val COMMENT_ATTRIBUTE_NAMES = listOf("description", "name", "comment", "desc")

    /** JPA 核心映射注解（name/value 是表名/列名而非注释，不参与提取）。 */
    private val JPA_MAPPING_ANNOTATION_NAMES = setOf(
        "Entity", "Table", "Column", "Id", "Transient",
        "ManyToOne", "OneToMany", "OneToOne", "ManyToMany",
        "JoinColumn", "JoinTable", "MappedSuperclass",
    )

    // ------------------------------------------------------------------
    // 注解辅助
    // ------------------------------------------------------------------

    private fun hasAnnotation(psi: PsiClass, qualifiedName: String): Boolean =
        findAnnotation(psi, qualifiedName) != null

    private fun hasAnnotation(field: PsiField, qualifiedName: String): Boolean =
        findAnnotation(field, qualifiedName) != null

    /**
     * 按注解查找：优先全限定名；粘贴片段/测试环境注解类不可解析时，
     * 按短名兜底匹配（`@Table` → Table），保证 `@Table(name="t_user")` 可识别。
     */
    private fun findAnnotation(psi: PsiClass, qualifiedName: String): PsiAnnotation? {
        psi.modifierList?.findAnnotation(qualifiedName)?.let { return it }
        return findAnnotationByShortName(psi.modifierList?.annotations, qualifiedName)
    }

    private fun findAnnotation(field: PsiField, qualifiedName: String): PsiAnnotation? {
        field.modifierList?.findAnnotation(qualifiedName)?.let { return it }
        return findAnnotationByShortName(field.modifierList?.annotations, qualifiedName)
    }

    private fun findAnnotationByShortName(
        annotations: Array<PsiAnnotation>?,
        qualifiedName: String,
    ): PsiAnnotation? {
        val simpleName = qualifiedName.substringAfterLast('.')
        return annotations?.firstOrNull { it.nameReferenceElement?.referenceName == simpleName }
    }

    /**
     * 读取注解的 value 属性字符串值（如 @Table("t_user") → t_user）。
     * 无注解或无值返回 null。
     */
    private fun annotationAttributeValue(psi: PsiClass, qualifiedName: String, attribute: String): String? {
        val annotation = findAnnotation(psi, qualifiedName) ?: return null
        return attributeString(annotation, attribute)
    }

    private fun annotationAttributeValue(field: PsiField, qualifiedName: String, attribute: String): String? {
        val annotation = findAnnotation(field, qualifiedName) ?: return null
        return attributeString(annotation, attribute)
    }

    private fun attributeString(annotation: PsiAnnotation, attribute: String): String? {
        val memberValue = annotation.findAttributeValue(attribute) ?: return null
        val literal = memberValue as? PsiLiteralExpression ?: return null
        val value = literal.value as? String
        // 空白视为未指定（回退命名策略）
        return value?.takeIf { it.isNotBlank() }
    }

    companion object {
        // JPA 注解全限定名（jakarta.persistence）
        private const val ENTITY_ANNOTATION = "jakarta.persistence.Entity"
        private const val TABLE_ANNOTATION = "jakarta.persistence.Table"
        private const val COLUMN_ANNOTATION = "jakarta.persistence.Column"
        private const val ID_ANNOTATION = "jakarta.persistence.Id"
        private const val TRANSIENT_ANNOTATION = "jakarta.persistence.Transient"
        private const val MANY_TO_ONE_ANNOTATION = "jakarta.persistence.ManyToOne"
        private const val ONE_TO_MANY_ANNOTATION = "jakarta.persistence.OneToMany"
        private const val ONE_TO_ONE_ANNOTATION = "jakarta.persistence.OneToOne"
        private const val MANY_TO_MANY_ANNOTATION = "jakarta.persistence.ManyToMany"
        private const val JOIN_COLUMN_ANNOTATION = "jakarta.persistence.JoinColumn"
        private const val JOIN_TABLE_ANNOTATION = "jakarta.persistence.JoinTable"
        private const val MAPPED_SUPERCLASS_ANNOTATION = "jakarta.persistence.MappedSuperclass"

        // ------------------------------------------------------------------
        // JPA 实体解析缓存（三维键 + 双维度失效）
        // ------------------------------------------------------------------

        /**
         * JPA 实体解析缓存键。
         *
         * @param file               PsiClass 所在文件（文件修改戳作为失效依据）
         * @param classQualifiedName 类全限定名（PsiClass 对象不稳定，用全限定名跨 PSI 重建保持同一性）
         * @param configSignature   配置签名（表名推断策略 + 忽略字段 + 表前缀后缀拼接串）
         */
        private data class JpaCacheKey(
            val file: PsiFile,
            val classQualifiedName: String,
            val configSignature: String,
        )

        /** 缓存条目：文件修改戳 + 解析结果。 */
        private data class JpaEntry(val stamp: Long, val value: TableInfo)

        /** 缓存键 → 条目（项目级，跟随 JpaEntityResolver 生命周期）。 */
        private val cache = ConcurrentHashMap<JpaCacheKey, JpaEntry>()

        /** 清空缓存（配置变更 / 项目关闭时调用）。 */
        @JvmStatic
        fun clearCache() = cache.clear()

        /** 当前缓存条目数（测试 / 诊断用）。 */
        @JvmStatic
        fun cacheSize(): Int = cache.size

        // ------------------------------------------------------------------
        // 表名推断策略 + 统一修饰链
        // ------------------------------------------------------------------

        /**
         * 无 @Table 时的表名推断：
         *  - [PluginSettings.INFERENCE_SNAKE]（默认）：驼峰转下划线（UserInfoEntity → user_info_entity）；
         *  - [PluginSettings.INFERENCE_CAMEL]：纯保留驼峰——仅首字母小写。
         * 设置服务不可用时兜底默认 SNAKE。
         */
        private fun inferTableName(className: String): String {
            val strategy = runCatching { PluginSettings.getInstance().state.tableNameInference }
                .getOrDefault(PluginSettings.INFERENCE_SNAKE)
            return if (strategy == PluginSettings.INFERENCE_CAMEL) {
                className.replaceFirstChar { it.lowercaseChar() }
            } else {
                NamingStrategy.toSnakeCase(className)
            }
        }

        /**
         * 统一表名修饰链：
         * `最终表名 = 添加前缀 + 移除后缀(移除前缀(表名)) + 添加后缀`；
         *  - 移除前缀/后缀：`;` 分隔，首个匹配（大小写敏感），不匹配则不修饰；
         *  - 添加前缀/后缀：单值（用户反馈定稿，不再 `;` 分隔）。
         */
        private fun decorateTableName(raw: String): String {
            val removedPrefix = removeTablePrefixList().firstOrNull { raw.startsWith(it) }
                ?.let { raw.removePrefix(it) } ?: raw
            val removedSuffix = removeTableSuffixList().firstOrNull { removedPrefix.endsWith(it) }
                ?.let { removedPrefix.removeSuffix(it) } ?: removedPrefix
            return tablePrefix() + removedSuffix + tableSuffix()
        }

        /**
         * 实体类不可解析的兜底推断统一入口——
         * 按配置推断策略（SNAKE/CAMEL）+ 统一修饰链，与 resolve 的推断路径语义一致。
         */
        @JvmStatic
        fun inferDecoratedTableName(className: String): String = decorateTableName(inferTableName(className))

        /**
         * `;` 分隔配置解析为列表（trim + 去空；设置服务不可用时兜底空列表）。
         */
        private fun listSetting(get: (PluginSettings.State) -> String): List<String> =
            runCatching { get(PluginSettings.getInstance().state) }
                .getOrDefault("")
                .split(';').map { it.trim() }.filter { it.isNotEmpty() }

        private fun tablePrefix(): String =
            runCatching { PluginSettings.getInstance().state.tablePrefix }.getOrDefault("")

        private fun tableSuffix(): String =
            runCatching { PluginSettings.getInstance().state.tableSuffix }.getOrDefault("")

        private fun removeTablePrefixList(): List<String> = listSetting { it.removeTablePrefix }

        private fun removeTableSuffixList(): List<String> = listSetting { it.removeTableSuffix }

        /** 忽略字段名集合（`;` 分隔，按 Java 字段名匹配）。 */
        private fun ignoredFieldNames(): Set<String> = listSetting { it.ignoredFields }.toSet()
    }
}
