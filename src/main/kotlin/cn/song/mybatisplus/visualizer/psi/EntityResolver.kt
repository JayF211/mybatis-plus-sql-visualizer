package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression

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
        return TableInfo(className, tableName, columns)
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
        return ColumnInfo(fieldName, columnName, isId, fieldType)
    }

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
    }
}
