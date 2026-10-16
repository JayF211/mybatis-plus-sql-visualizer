package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiElement
import cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep

/**
 * JOIN ON 条件真实化解析器（J7，覆盖矩阵 §3「JOIN [LEFT/INNER] ... ON」行）。
 *
 * 此前 JOIN FETCH / JOIN 的 ON 条件是固定猜测 `副表别名.id = 主表别名.<relation>_id`
 * 并标注「推断的ON条件，请验证」。本解析器按三级优先产出真实 ON 条件：
 *
 *  1. **PSI 注解级**（[resolveWithPsi]，需调用方传入 Project）：
 *     - `@ManyToOne` / `@OneToOne`（持有外键侧）：读 `@JoinColumn(name)` 外键列 +
 *       `referencedColumnName`（缺省时读目标实体 `@Id` 字段的真实主键列名）→
 *       `副表别名.<被引用列> = 主表别名.<外键列>`
 *     - `@OneToMany(mappedBy = "x")`（外键在副表侧）：反查目标实体 `x` 字段的
 *       `@JoinColumn(name)` → `主表别名.<被引用列> = 副表别名.<外键列>`（方向正确的真实 ON）
 *  2. **关联元数据级**（[resolve]，纯字符串管道可用）：
 *     `TableInfo.associations`（JpaEntityResolver 解析 `@JoinColumn(name)` 的产物）中的
 *     外键列是真实的，副表主键列按惯例取 `id`（注解级主键列名须走第 1 级）
 *  3. **降级**（[fallback]）：无任何元数据（`@MapsId` 派生标识、复合外键 `@JoinColumns({..})`、
 *     `@ManyToMany`/`@JoinTable` 中间表、实体类不可解析）→ 维持既有猜测文本 +
 *     「推断的ON条件，请验证」注释，绝不抛异常。
 *
 * PSI 读取只封装在本文件内（converter/jpa 自有文件），只读不修改 psi/jpa/ 下任何文件。
 *
 * 无状态单例，可独立单测。
 */
object JpaJoinOnResolver {

    /**
     * JOIN ON 解析产物。
     *
     * @param onCondition ON 条件文本（直接拼入 `JOIN 表 别名 ON <onCondition>`）
     * @param resolved    true = 基于真实注解/关联元数据；false = 降级猜测（附请验证注释）
     * @param note        降级原因说明（resolved = true 时为 null）
     * @param targetTable 副表表名（PSI 注解级解析时携带，供 JOIN 改写取真实副表名；
     *                    关联元数据/降级路径为 null——改写方自有表名来源）
     */
    data class OnResolution(
        val onCondition: String,
        val resolved: Boolean,
        val note: String? = null,
        val targetTable: String? = null,
    )

    /**
     * 关联元数据级解析（第 2 级）：从 [TableInfo.associations] 取真实外键列。
     *
     * @param table         主表信息（含 associations）
     * @param rootAlias     主表（根实体）别名，如 `u`
     * @param relationField 关联属性名（Java 字段名），如 `department`
     * @param joinAlias     副表别名，如 `d`
     */
    fun resolve(table: TableInfo, rootAlias: String, relationField: String, joinAlias: String): OnResolution {
        val association = table.associationByField(relationField)
            ?: return fallback(rootAlias, relationField, joinAlias)
        // association.joinColumn 由 JpaEntityResolver 从 @JoinColumn(name) 读取，是真实外键列；
        // 副表主键列无元数据，按惯例取 id（注解级主键列名 / referencedColumnName 走 resolveWithPsi）
        return OnResolution(
            onCondition = "$joinAlias.id = $rootAlias.${association.joinColumn}",
            resolved = true,
        )
    }

    /**
     * PSI 注解级解析（第 1 级）：直接读取实体字段上的 JPA 关联注解。
     *
     * 任何一步不可得（实体类找不到 / 注解读不到 / @MapsId / 复合外键 / @JoinTable）
     * 都降级到 [resolve]（有 associations 时）或 [fallback]，绝不抛异常。
     *
     * @param project         项目（PSI 查找用）
     * @param entityClassName 根实体类简单名，如 `UserEntity`
     * @param table           主表信息（降级到关联元数据级时使用；可为 null）
     */
    fun resolveWithPsi(
        project: Project,
        entityClassName: String,
        rootAlias: String,
        relationField: String,
        joinAlias: String,
        table: TableInfo? = null,
        context: PsiElement? = null,
    ): OnResolution {
        val entityClass = recoverJpaPsi { findEntityClass(project, entityClassName, context) }
            ?: return degrade(rootAlias, relationField, joinAlias, table, "实体类不可解析")
        return resolveWithPsi(entityClass, rootAlias, relationField, joinAlias, table)
    }

    /**
     * PSI 注解级解析——PsiClass 直入形态（核心实现）。
     *
     * 与名称查找形态 [resolveWithPsi] 等价；调用方已有 PsiClass 时用本形态，
     * 免一次按名检索（LightPlatform 测试环境也无需依赖索引）。
     */
    fun resolveWithPsi(
        entityClass: PsiClass,
        rootAlias: String,
        relationField: String,
        joinAlias: String,
        table: TableInfo? = null,
    ): OnResolution {
        return recoverJpaPsi {
            val field = entityClass.allFields.firstOrNull { it.name == relationField }
                ?: return degrade(rootAlias, relationField, joinAlias, table, "关联字段不可解析")

            val joinColumns = readJoinColumns(field)
            val mappedBy = stringAttribute(field, ONE_TO_MANY, "mappedBy")
            val hasJoinTable = findAnnotationByShortName(field, JOIN_TABLE) != null
            val hasMapsId = findAnnotationByShortName(field, MAPS_ID) != null

            // 降级形态：@MapsId 派生标识 / 复合外键 / @JoinTable 中间表 / @ManyToMany
            if (hasMapsId) {
                return degrade(rootAlias, relationField, joinAlias, table, "@MapsId 派生标识")
            }
            if (joinColumns.size > 1) {
                return degrade(rootAlias, relationField, joinAlias, table, "复合外键（多 @JoinColumn）")
            }
            if (hasJoinTable || findAnnotationByShortName(field, MANY_TO_MANY) != null) {
                return degrade(rootAlias, relationField, joinAlias, table, "@JoinTable 中间表关联")
            }

            val isManyToOne = findAnnotationByShortName(field, MANY_TO_ONE) != null
            val isOneToOne = findAnnotationByShortName(field, ONE_TO_ONE) != null
            val isOneToMany = findAnnotationByShortName(field, ONE_TO_MANY) != null

            return when {
                // 外键在主表侧：@ManyToOne / @OneToOne（无 mappedBy 的持有方）
                isManyToOne || (isOneToOne && mappedBy == null) -> {
                    val targetClass = resolveTargetEntityClass(field)
                        ?: return degrade(rootAlias, relationField, joinAlias, table, "目标实体不可解析")
                    val fkName = joinColumns.firstOrNull()?.name
                        ?: "${NamingStrategy.toSnakeCase(relationField)}_id"
                    val referenced = joinColumns.firstOrNull()?.referencedColumnName
                        ?: primaryKeyColumn(targetClass)
                        ?: return degrade(rootAlias, relationField, joinAlias, table, "目标实体主键不可解析")
                    OnResolution(
                        onCondition = "$joinAlias.$referenced = $rootAlias.$fkName",
                        resolved = true,
                        targetTable = tableNameOf(targetClass),
                    )
                }
                // 外键在副表侧：@OneToMany(mappedBy = "x")，反查目标实体 x 字段的 @JoinColumn
                isOneToMany && mappedBy != null -> {
                    val targetClass = resolveTargetEntityClass(field)
                        ?: return degrade(rootAlias, relationField, joinAlias, table, "目标实体不可解析")
                    val inverseField = targetClass.allFields.firstOrNull { it.name == mappedBy }
                        ?: return degrade(rootAlias, relationField, joinAlias, table, "mappedBy 反向字段不可解析")
                    val inverseJoinColumns = readJoinColumns(inverseField)
                    if (inverseJoinColumns.size > 1) {
                        return degrade(rootAlias, relationField, joinAlias, table, "反向复合外键")
                    }
                    val fkName = inverseJoinColumns.firstOrNull()?.name
                        ?: "${NamingStrategy.toSnakeCase(mappedBy)}_id"
                    val referenced = inverseJoinColumns.firstOrNull()?.referencedColumnName
                        ?: primaryKeyColumn(entityClass)
                        ?: return degrade(rootAlias, relationField, joinAlias, table, "主表主键不可解析")
                    OnResolution(
                        onCondition = "$rootAlias.$referenced = $joinAlias.$fkName",
                        resolved = true,
                        targetTable = tableNameOf(targetClass),
                    )
                }
                // 单向 @OneToMany 无 mappedBy（JPA 规范默认 @JoinTable）→ 降级
                else -> degrade(rootAlias, relationField, joinAlias, table, "无关联注解元数据")
            }
        } ?: degrade(rootAlias, relationField, joinAlias, table, "PSI 读取异常")
    }

    /**
     * 降级：先尝试关联元数据级，再退到猜测文本（与既有输出完全一致，保证存量回归不变）。
     */
    private fun degrade(
        rootAlias: String,
        relationField: String,
        joinAlias: String,
        table: TableInfo?,
        reason: String,
    ): OnResolution {
        if (table != null) {
            val associationBased = resolve(table, rootAlias, relationField, joinAlias)
            if (associationBased.resolved) return associationBased
        }
        return fallback(rootAlias, relationField, joinAlias).copy(note = reason)
    }

    /**
     * 猜测降级（第 3 级）：维持 J7 之前的固定猜测文本——
     * `副表别名.id = 主表别名.<relation>_id /* 推断的ON条件，请验证 */`。
     * 存量测试（HqlConverterJoinFetchTest 等）按此文本断言，不可改动。
     */
    fun fallback(rootAlias: String, relationField: String, joinAlias: String): OnResolution = OnResolution(
        onCondition = "$joinAlias.id = $rootAlias.${relationField}_id /* 推断的ON条件，请验证 */",
        resolved = false,
    )

    // ------------------------------------------------------------------
    // PSI 读取辅助（本文件自有封装，只读；不依赖 psi/jpa 内部实现）
    // ------------------------------------------------------------------

    /**
     * 按简单类名查找实体 PsiClass。
     *
     * T-EntityNameScope：收口到 [EntityAliasSweep]——同名实体按**模块可见性**消歧
     * （此前是 projectScope 再 allScope 的 `firstOrNull()`，多模块同名时挑到哪个看索引顺序）。
     */
    private fun findEntityClass(project: Project, className: String, context: PsiElement?): PsiClass? =
        EntityAliasSweep.resolve(project, className, context).psiClass

    /**
     * 解析关联字段的目标实体类：
     *  - 非泛型（`private DepartmentEntity department`）→ 字段类型本身
     *  - 集合泛型（`private List<OrderEntity> orders`）→ 第一个泛型实参
     *  - 目标是 JDK 类型（java.util.* 等）/ 不可解析 → null
     */
    private fun resolveTargetEntityClass(field: PsiField): PsiClass? {
        val classType = field.type as? PsiClassType ?: return null
        val parameters = recoverJpaPsi { classType.parameters } ?: PsiClassType.EMPTY_ARRAY
        val candidate = if (parameters.isEmpty()) classType else parameters[0] as? PsiClassType
        val resolved = candidate?.resolve() ?: return null
        val qName = resolved.qualifiedName ?: return resolved
        return if (qName.startsWith("java.")) null else resolved
    }

    /**
     * 读取实体主键列名：`@Id` 字段的 `@Column(name)`，缺省按驼峰转下划线。
     * `@EmbeddedId` / 复合主键 / 找不到 `@Id` → null（由调用方降级）。
     */
    private fun primaryKeyColumn(clazz: PsiClass): String? {
        val idFields = clazz.allFields.filter { findAnnotationByShortName(it, ID) != null }
        if (idFields.size != 1) return null // 0 = 无主键元数据；>1 = 复合主键
        val idField = idFields.first()
        if (findAnnotationByShortName(idField, EMBEDDED_ID) != null) return null
        return stringAttribute(idField, COLUMN, "name")
            ?: NamingStrategy.toSnakeCase(idField.name)
    }

    /** 目标实体表名：`@Table(name)` 优先，缺省按类名驼峰转下划线（简化推断，无前缀后缀修饰）。 */
    private fun tableNameOf(clazz: PsiClass): String? {
        val explicit = recoverJpaPsi {
            clazz.modifierList?.annotations?.firstOrNull {
                it.nameReferenceElement?.referenceName == TABLE
            }
        }
        val fromAnnotation = explicit?.let { stringAttribute(it, "name") }
        return fromAnnotation ?: clazz.name?.let { NamingStrategy.toSnakeCase(it) }
    }

    /** 读取字段上的 @JoinColumn 列表（含 @JoinColumns({..}) 容器形态；无则空列表）。 */
    private fun readJoinColumns(field: PsiField): List<JoinColumnRef> {
        val direct = findAnnotationByShortName(field, JOIN_COLUMN)
        if (direct != null) return listOf(readJoinColumn(direct))

        val container = findAnnotationByShortName(field, JOIN_COLUMNS) ?: return emptyList()
        val value = recoverJpaPsi { container.findAttributeValue("value") } ?: return emptyList()
        // 数组形态注解值是 PsiArrayInitializerMemberValue（非 Kotlin Array），需单独分支
        val members = when (value) {
            is PsiArrayInitializerMemberValue -> value.initializers.filterIsInstance<PsiAnnotation>()
            is PsiAnnotation -> listOf(value)
            else -> emptyList()
        }
        return members.map { readJoinColumn(it) }
    }

    private fun readJoinColumn(annotation: PsiAnnotation): JoinColumnRef = JoinColumnRef(
        name = stringAttribute(annotation, "name"),
        referencedColumnName = stringAttribute(annotation, "referencedColumnName"),
    )

    /** @JoinColumn 解析产物（name / referencedColumnName 均可为 null = 未指定）。 */
    private data class JoinColumnRef(val name: String?, val referencedColumnName: String?)

    /** 读注解 String 属性值（非字面量 / 不可解析返回 null；空白视为未指定）。 */
    private fun stringAttribute(annotation: PsiAnnotation, attribute: String): String? {
        val memberValue = recoverJpaPsi { annotation.findAttributeValue(attribute) } ?: return null
        val value = (memberValue as? PsiLiteralExpression)?.value as? String ?: return null
        return value.takeIf { it.isNotBlank() }
    }

    /** 按短名查找注解（兼容 javax / jakarta 全限定名与裸短名，与 JpaEntityResolver 同策略）。 */
    private fun findAnnotationByShortName(field: PsiField, simpleName: String): PsiAnnotation? {
        return field.modifierList?.annotations?.firstOrNull {
            it.nameReferenceElement?.referenceName == simpleName
        }
    }

    private fun stringAttribute(field: PsiField, annotationShortName: String, attribute: String): String? {
        val annotation = findAnnotationByShortName(field, annotationShortName) ?: return null
        return stringAttribute(annotation, attribute)
    }

    // JPA 注解短名（短名匹配同时覆盖 javax.persistence / jakarta.persistence 两种 FQN）
    private const val MANY_TO_ONE = "ManyToOne"
    private const val ONE_TO_ONE = "OneToOne"
    private const val ONE_TO_MANY = "OneToMany"
    private const val MANY_TO_MANY = "ManyToMany"
    private const val JOIN_COLUMN = "JoinColumn"
    private const val JOIN_COLUMNS = "JoinColumns"
    private const val JOIN_TABLE = "JoinTable"
    private const val MAPS_ID = "MapsId"
    private const val ID = "Id"
    private const val EMBEDDED_ID = "EmbeddedId"
    private const val COLUMN = "Column"
    private const val TABLE = "Table"
}
