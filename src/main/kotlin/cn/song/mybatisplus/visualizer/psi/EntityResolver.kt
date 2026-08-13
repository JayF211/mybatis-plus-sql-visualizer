package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiWhiteSpace

/**
 * 实体类解析器（F-13）：从实体 [PsiClass] 解析出 [TableInfo]。
 *
 * 规则（对齐 MyBatis-Plus）：
 *  - 表名：优先 `@TableName("xxx")` 注解，否则按命名策略由类名推断（DeviceInfoEntity → device_info）
 *  - 列名：优先 `@TableField("xxx")` 注解，否则按命名策略由字段名推断
 *  - 主键：`@TableId` 标记
 *  - `@TableField(exist = false)` 的字段（非表字段）跳过
 */
class EntityResolver {

    /**
     * 解析实体类为表信息。
     *
     * @param entityClass 实体 PsiClass（如 DeviceInfoEntity）
     * @return 表信息；类名缺失等异常情况返回带推断表名、空列的表信息（保证不抛异常）
     */
    fun resolve(entityClass: PsiClass): TableInfo {
        val className = entityClass.name ?: return TableInfo("", "", emptyList())
        val tableName = annotationValue(entityClass, TABLE_NAME) ?: NamingStrategy.toSnakeCase(className)

        val columns = entityClass.fields.mapNotNull { field -> resolveColumn(field) }
        // T-M15-07：逻辑删除列（@TableLogic 标注字段，X07——真实项目 BaseEntity.deleted + 全局 logic-delete-field）
        val logicDeleteColumn = entityClass.fields
            .firstOrNull { hasAnnotation(it, TABLE_LOGIC) }
            ?.let { annotationValue(it, TABLE_FIELD) ?: NamingStrategy.toSnakeCase(it.name) }
        // T-M16-05：表注释（类级注解 > javadoc > 行注释；schema 预留最高优先级，当前返回 null）
        val comment = resolveComment(entityClass)
        return TableInfo(className, tableName, columns, logicDeleteColumn, comment)
    }

    /**
     * 解析单个字段为列信息；非表字段（@TableField(exist=false)）返回 null。
     */
    private fun resolveColumn(field: PsiField): ColumnInfo? {
        val fieldName = field.name
        val tableFieldAnnotation = findAnnotation(field, TABLE_FIELD)
        // @TableField(exist = false)：非数据库字段，跳过
        if (annotationAttributeBoolean(tableFieldAnnotation, "exist") == false) {
            return null
        }
        val columnName = annotationValue(field, TABLE_FIELD)
            ?: NamingStrategy.toSnakeCase(fieldName)
        val isId = hasAnnotation(field, TABLE_ID)
        // M13：记录字段类型（参数回填引号推断用）；PSI 类型解析异常时兜底 null
        val fieldType = runCatching { field.type.canonicalText }.getOrNull()
        // T-M15-08：自动填充标注（@TableField(fill = FieldFill.INSERT_UPDATE) 的 fill 属性文本，
        // 枚举引用非字面量，取 attributeValue 文本；先确认含 FieldFill 再取短名；
        // 批量 INSERT 模板据此排除 UPDATE 填充列）
        val fill = tableFieldAnnotation?.findAttributeValue("fill")?.text
            ?.takeIf { it.contains("FieldFill") }
            ?.substringAfterLast('.')
            ?.removePrefix("FieldFill")
        // T-M16-01：字段注释（注解 > javadoc > 行注释；schema 预留最高优先级，当前返回 null）
        val comment = resolveComment(field)
        return ColumnInfo(fieldName, columnName, isId, fieldType, fill, comment)
    }

    // ------------------------------------------------------------------
    // M16：注释读取（四级优先级：SCHEMA > ANNOTATION > JAVADOC > LINE_COMMENT）
    // ------------------------------------------------------------------

    /**
     * 注释来源优先级（M16，schema 预留）：
     * SCHEMA（数据源 schema 列/表注释，方案 B 接入后自动覆盖一切代码内注释）最高，
     * 当前实现 schema 源返回 null——实际生效顺序 = 注解 > javadoc > 行注释。
     */
    enum class CommentSource { SCHEMA, ANNOTATION, JAVADOC, LINE_COMMENT }

    /**
     * 提取字段注释（PsiField）：注解白名单 > javadoc > 行注释。
     * schema 源未接入（返回 null），未来方案 B 将 [CommentSource.SCHEMA] 结果注入合并链。
     */
    private fun resolveComment(field: PsiField): String? =
        firstNonBlank(listOf(
            null, // SCHEMA 预留：方案 B 接入后注入
            annotationComment(field),
            javadocComment(field.docComment?.text),
            lineComment(field),
        ))

    /** 提取类级注释（T-M16-05）：类注解白名单 > 类 javadoc > 类上方行注释。 */
    private fun resolveComment(psiClass: PsiClass): String? =
        firstNonBlank(listOf(
            null, // SCHEMA 预留
            annotationComment(psiClass),
            javadocComment(psiClass.docComment?.text),
            lineComment(psiClass),
        ))

    /** 取最高优先级非空注释（按来源顺序，schema 为空时注解生效）。 */
    private fun firstNonBlank(comments: List<String?>): String? =
        comments.firstOrNull { !it.isNullOrBlank() }?.trim()

    /**
     * 注解注释提取：白名单注解按专属属性（@Schema(description)、@ApiModelProperty(value/description)、
     * @ExcelProperty(value) 等），通用属性（description/name/comment/desc）兜底；
     * **排除 MP 注解**（@TableName/@TableField/@TableId/@TableLogic——value 是表名/列名非注释；
     * @TableField(comment = ...) 的 comment 属性除外，MP 3.4+ 支持）。
     */
    private fun annotationComment(psi: PsiClass): String? = annotationComment(psi.modifierList?.annotations ?: emptyArray())

    private fun annotationComment(field: PsiField): String? = annotationComment(field.modifierList?.annotations ?: emptyArray())

    private fun annotationComment(annotations: Array<PsiAnnotation>): String? {
        for (annotation in annotations) {
            val simpleName = annotation.nameReferenceElement?.referenceName ?: continue
            if (simpleName in MP_ANNOTATION_NAMES) {
                // @TableField(comment = "...") 例外：comment 属性是字段注释
                if (simpleName == "TableField") {
                    attributeString(annotation, "comment")?.takeIf { it.isNotBlank() }?.let { return it }
                }
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
     * 双路径：① LightJava 将元素上方的行注释**包含进元素 textRange**（`field.text` 以 `//` 开头）——
     * 先检查元素自身文本首行；② 完整 IDE 中注释是独立兄弟节点——回退文档文本向上逐行扫描
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

    /** MP 注解（value 是表名/列名而非注释，不参与提取；@TableField(comment) 例外）。 */
    private val MP_ANNOTATION_NAMES = setOf("TableName", "TableField", "TableId", "TableLogic")

    // ------------------------------------------------------------------
    // 注解辅助
    // ------------------------------------------------------------------

    private fun hasAnnotation(psi: PsiClass, qualifiedName: String): Boolean =
        findAnnotation(psi, qualifiedName) != null

    private fun hasAnnotation(field: PsiField, qualifiedName: String): Boolean =
        findAnnotation(field, qualifiedName) != null

    /**
     * 按注解查找：优先全限定名；粘贴片段/测试环境注解类不可解析时，
     * 按短名兜底匹配（`@TableName` → TableName），保证 `@TableName("t_user")` 可识别。
     */
    private fun findAnnotation(psi: PsiClass, qualifiedName: String): PsiAnnotation? {
        psi.modifierList?.findAnnotation(qualifiedName)?.let { return it }
        return findAnnotationByShortName(psi.modifierList?.annotations, qualifiedName)
    }

    private fun findAnnotation(field: PsiField, qualifiedName: String): PsiAnnotation? {
        field.modifierList?.findAnnotation(qualifiedName)?.let { return it }
        return findAnnotationByShortName(field.modifierList?.annotations, qualifiedName)
    }

    private fun findAnnotationByShortName(annotations: Array<PsiAnnotation>?, qualifiedName: String): PsiAnnotation? {
        val simpleName = qualifiedName.substringAfterLast('.')
        return annotations?.firstOrNull { it.nameReferenceElement?.referenceName == simpleName }
    }

    /**
     * 读取注解的 value 属性字符串值（如 @TableName("t_user") → t_user）。
     * 无注解或无值返回 null。
     */
    private fun annotationValue(psi: PsiClass, qualifiedName: String): String? {
        val annotation = findAnnotation(psi, qualifiedName) ?: return null
        return attributeString(annotation, "value")
    }

    private fun annotationValue(field: PsiField, qualifiedName: String): String? {
        val annotation = findAnnotation(field, qualifiedName) ?: return null
        return attributeString(annotation, "value")
    }

    private fun attributeString(annotation: PsiAnnotation, attribute: String): String? {
        val memberValue = annotation.findAttributeValue(attribute) ?: return null
        val literal = memberValue as? PsiLiteralExpression ?: return null
        return literal.value as? String
    }

    private fun annotationAttributeBoolean(annotation: PsiAnnotation?, attribute: String): Boolean? {
        val memberValue = annotation?.findAttributeValue(attribute) ?: return null
        val literal = memberValue as? PsiLiteralExpression ?: return null
        return literal.value as? Boolean
    }

    companion object {
        private const val TABLE_NAME = "com.baomidou.mybatisplus.annotation.TableName"
        private const val TABLE_FIELD = "com.baomidou.mybatisplus.annotation.TableField"
        private const val TABLE_ID = "com.baomidou.mybatisplus.annotation.TableId"
        private const val TABLE_LOGIC = "com.baomidou.mybatisplus.annotation.TableLogic"
    }
}
