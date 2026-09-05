package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.model.CollectionAssociationInfo
import cn.song.mybatisplus.visualizer.model.CollectionKind
import cn.song.mybatisplus.visualizer.model.CustomSqlScripts
import cn.song.mybatisplus.visualizer.model.GeneratorInfo
import cn.song.mybatisplus.visualizer.model.JoinColumnRef
import cn.song.mybatisplus.visualizer.model.JoinTableInfo
import cn.song.mybatisplus.visualizer.model.SecondaryTableInfo
import cn.song.mybatisplus.visualizer.model.SequenceGeneratorInfo
import cn.song.mybatisplus.visualizer.model.SoftDeleteInfo
import cn.song.mybatisplus.visualizer.model.TableGeneratorInfo
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiModifierListOwner

/**
 * JPA 元数据纯函数读取器（M39：T-J7 关联增强 / T-J8 软删除与动态 SQL / T-J14 主键生成值 / T-J18 映射深水区）。
 *
 * 职责边界：只读 PSI、产出纯数据；不做缓存（随 TableInfo 三维缓存一并缓存）、
 * 不抛异常（所有 PSI 访问 runCatching 包裹，解析不出即优雅降级为空/null）。
 *
 * 注解识别三轨（T-J14 双包名要求）：
 *  1. jakarta.persistence 全限定名精确匹配；
 *  2. javax.persistence 全限定名精确匹配（Spring Boot 2 / Hibernate 5 老工程）；
 *  3. 短名兜底（注解类不可解析 / 混用 import 场景）。
 * Hibernate 专有注解（org.hibernate.annotations）为 FQN + 短名双轨。
 *
 * 属性读取容错：注解类不可解析时 `findAttributeValue` 走 PSI 本地参数列表回退，
 * 字面量属性照常可读；枚举属性取引用文本末段（`GenerationType.IDENTITY` → IDENTITY）。
 */
internal object JpaMetadataReader {

    // ------------------------------------------------------------------
    // 注解全限定名常量（jakarta 为主，javax 双轨由 findFlex 内部处理；Hibernate 专有单列）
    // ------------------------------------------------------------------

    const val FQN_TABLE = "jakarta.persistence.Table"
    const val FQN_COLUMN = "jakarta.persistence.Column"
    const val FQN_TRANSIENT = "jakarta.persistence.Transient"
    const val FQN_ONE_TO_ONE = "jakarta.persistence.OneToOne"
    const val FQN_ONE_TO_MANY = "jakarta.persistence.OneToMany"
    const val FQN_MANY_TO_MANY = "jakarta.persistence.ManyToMany"
    const val FQN_JOIN_COLUMNS = "jakarta.persistence.JoinColumns"
    const val FQN_JOIN_TABLE = "jakarta.persistence.JoinTable"
    const val FQN_GENERATED_VALUE = "jakarta.persistence.GeneratedValue"
    const val FQN_SEQUENCE_GENERATORS = "jakarta.persistence.SequenceGenerators"
    const val FQN_TABLE_GENERATORS = "jakarta.persistence.TableGenerators"
    const val FQN_VERSION = "jakarta.persistence.Version"
    const val FQN_ELEMENT_COLLECTION = "jakarta.persistence.ElementCollection"
    const val FQN_COLLECTION_TABLE = "jakarta.persistence.CollectionTable"
    const val FQN_EMBEDDED = "jakarta.persistence.Embedded"
    const val FQN_EMBEDDED_ID = "jakarta.persistence.EmbeddedId"
    const val FQN_EMBEDDABLE = "jakarta.persistence.Embeddable"
    const val FQN_ID_CLASS = "jakarta.persistence.IdClass"
    const val FQN_INHERITANCE = "jakarta.persistence.Inheritance"
    const val FQN_DISCRIMINATOR_COLUMN = "jakarta.persistence.DiscriminatorColumn"
    const val FQN_DISCRIMINATOR_VALUE = "jakarta.persistence.DiscriminatorValue"
    const val FQN_SECONDARY_TABLES = "jakarta.persistence.SecondaryTables"
    const val FQN_PRIMARY_KEY_JOIN_COLUMNS = "jakarta.persistence.PrimaryKeyJoinColumns"
    const val FQN_ORDER_BY = "jakarta.persistence.OrderBy"
    const val FQN_ORDER_COLUMN = "jakarta.persistence.OrderColumn"
    const val FQN_MAPS_ID = "jakarta.persistence.MapsId"
    const val FQN_CONVERT = "jakarta.persistence.Convert"

    const val FQN_HIBERNATE_WHERE = "org.hibernate.annotations.Where"
    const val FQN_HIBERNATE_SQL_RESTRICTION = "org.hibernate.annotations.SQLRestriction"
    const val FQN_HIBERNATE_SOFT_DELETE = "org.hibernate.annotations.SoftDelete"
    const val FQN_HIBERNATE_SQL_DELETE = "org.hibernate.annotations.SQLDelete"
    const val FQN_HIBERNATE_SQL_DELETE_ALL = "org.hibernate.annotations.SQLDeleteAll"
    const val FQN_HIBERNATE_SQL_INSERT = "org.hibernate.annotations.SQLInsert"
    const val FQN_HIBERNATE_SQL_UPDATE = "org.hibernate.annotations.SQLUpdate"
    const val FQN_HIBERNATE_NOT_FOUND = "org.hibernate.annotations.NotFound"

    // ------------------------------------------------------------------
    // 注解查找与属性读取（纯函数）
    // ------------------------------------------------------------------

    /**
     * 三轨注解查找：jakarta FQN → javax FQN（jakarta.persistence 系）→ 短名兜底。
     */
    fun findFlex(owner: PsiModifierListOwner, annotationFqn: String): PsiAnnotation? {
        val list = owner.modifierList ?: return null
        list.findAnnotation(annotationFqn)?.let { return it }
        if (annotationFqn.startsWith("jakarta.persistence.")) {
            list.findAnnotation("javax.persistence." + annotationFqn.substringAfterLast('.'))?.let { return it }
        }
        val simple = annotationFqn.substringAfterLast('.')
        return list.annotations.firstOrNull { it.nameReferenceElement?.referenceName == simple }
    }

    /** 首个非空白字符串属性（按给定顺序尝试多个属性名，如 @Where 的 clause → value 兜底）。 */
    fun attrString(annotation: PsiAnnotation, vararg attributes: String): String? {
        for (attribute in attributes) {
            val value = annotation.findAttributeValue(attribute) ?: continue
            val text = (value as? PsiLiteralExpression)?.value as? String
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    /** 布尔属性；未显式指定时依赖解析到的注解默认值，解析不出返回 null（由调用方兜底）。 */
    fun attrBoolean(annotation: PsiAnnotation, attribute: String): Boolean? =
        (annotation.findAttributeValue(attribute) as? PsiLiteralExpression)?.value as? Boolean

    /** 整型属性文本（allocationSize 等；字符串数字形式容错）。 */
    fun attrIntText(annotation: PsiAnnotation, attribute: String): String? {
        val value = (annotation.findAttributeValue(attribute) as? PsiLiteralExpression)?.value ?: return null
        return when (value) {
            is Int -> value.toString()
            is Long -> value.toString()
            is String -> value.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /** 枚举属性文本末段（`GenerationType.IDENTITY` / `jakarta…IDENTITY` / `IDENTITY` → IDENTITY，统一大写）。 */
    fun attrEnumText(annotation: PsiAnnotation, attribute: String): String? {
        val text = annotation.findAttributeValue(attribute)?.text ?: return null
        val last = text.substringAfterLast('.').uppercase()
        return last.takeIf { it.isNotBlank() }
    }

    /** 注解型属性（@AttributeOverride 的 column 等）。 */
    fun attrAnnotation(annotation: PsiAnnotation, attribute: String): PsiAnnotation? =
        annotation.findAttributeValue(attribute) as? PsiAnnotation

    /** 注解数组属性（@JoinColumns({…}) 的 value 等；花括号缺省的单元素形式同样兼容）。 */
    fun attrAnnotationArray(annotation: PsiAnnotation, attribute: String): List<PsiAnnotation> =
        when (val value: PsiAnnotationMemberValue? = annotation.findAttributeValue(attribute)) {
            is PsiArrayInitializerMemberValue -> value.initializers.filterIsInstance<PsiAnnotation>()
            is PsiAnnotation -> listOf(value)
            else -> emptyList()
        }

    /** 按 short 名收集 owner 上直接标注的注解（repeatable 注解无容器重复标注形态）。 */
    private fun directAnnotations(owner: PsiModifierListOwner, simpleName: String): List<PsiAnnotation> =
        owner.modifierList?.annotations
            ?.filter { it.nameReferenceElement?.referenceName == simpleName }
            ?: emptyList()

    // ------------------------------------------------------------------
    // 类级元数据（T-J8 / T-J14 / T-J18）
    // ------------------------------------------------------------------

    /** 类级元数据聚合体（全部字段带默认值，解析失败整体降级为空元数据）。 */
    data class ClassMeta(
        val schema: String? = null,
        val catalog: String? = null,
        val restrictionClause: String? = null,
        val softDelete: SoftDeleteInfo? = null,
        val customSqlScripts: CustomSqlScripts? = null,
        val sequenceGenerators: List<SequenceGeneratorInfo> = emptyList(),
        val tableGenerators: List<TableGeneratorInfo> = emptyList(),
        val secondaryTables: List<SecondaryTableInfo> = emptyList(),
        val primaryKeyJoinColumns: List<JoinColumnRef> = emptyList(),
        val inheritanceStrategy: String? = null,
        val discriminatorColumn: String? = null,
        val discriminatorValue: String? = null,
        val idClassType: String? = null,
    )

    /** 读取类级元数据；任何 PSI 异常整体降级为空元数据，绝不抛出。 */
    fun readClassMeta(clazz: PsiClass): ClassMeta =
        runCatching { readClassMetaInternal(clazz) }.getOrDefault(ClassMeta())

    private fun readClassMetaInternal(clazz: PsiClass): ClassMeta {
        // @Table(schema/catalog)（T-J18）
        val tableAnnotation = findFlex(clazz, FQN_TABLE)
        val schema = tableAnnotation?.let { attrString(it, "schema") }
        val catalog = tableAnnotation?.let { attrString(it, "catalog") }

        // 静态限制子句（T-J8）：@SQLRestriction（6.3+）优先——它取代了 @Where；@Where 读 clause（Hibernate 5/6）value 兜底
        val restrictionClause = findFlex(clazz, FQN_HIBERNATE_SQL_RESTRICTION)?.let { attrString(it, "value") }
            ?: findFlex(clazz, FQN_HIBERNATE_WHERE)?.let { attrString(it, "clause", "value") }

        // 软删除（T-J8 @SoftDelete）：默认列 deleted / 默认策略 DELETED（Hibernate 6.4 规范默认）
        val softDelete = findFlex(clazz, FQN_HIBERNATE_SOFT_DELETE)?.let { sd ->
            SoftDeleteInfo(
                columnName = attrString(sd, "columnName") ?: "deleted",
                strategy = attrEnumText(sd, "strategy") ?: "DELETED",
            )
        }

        // 自定义 SQL 脚本（T-J8）：@SQLDelete 族属性名为 sql（value 兜底容错）
        val customSqlScripts = CustomSqlScripts(
            sqlInsert = findFlex(clazz, FQN_HIBERNATE_SQL_INSERT)?.let { attrString(it, "sql", "value") },
            sqlDelete = findFlex(clazz, FQN_HIBERNATE_SQL_DELETE)?.let { attrString(it, "sql", "value") },
            sqlDeleteAll = findFlex(clazz, FQN_HIBERNATE_SQL_DELETE_ALL)?.let { attrString(it, "sql", "value") },
            sqlUpdate = findFlex(clazz, FQN_HIBERNATE_SQL_UPDATE)?.let { attrString(it, "sql", "value") },
        ).takeIf {
            it.sqlInsert != null || it.sqlDelete != null || it.sqlDeleteAll != null || it.sqlUpdate != null
        }

        // 生成器（T-J14）：类级 repeatable + 容器注解 + 字段级三处扫描，按 name 归并（先见者优先）
        val sequenceGenerators = collectGenerators(clazz, "SequenceGenerator", FQN_SEQUENCE_GENERATORS) { anno ->
            val name = attrString(anno, "name") ?: return@collectGenerators null
            SequenceGeneratorInfo(
                name = name,
                sequenceName = attrString(anno, "sequenceName"),
                allocationSize = attrIntText(anno, "allocationSize"),
                initialValue = attrIntText(anno, "initialValue"),
                schema = attrString(anno, "schema"),
            )
        }
        val tableGenerators = collectGenerators(clazz, "TableGenerator", FQN_TABLE_GENERATORS) { anno ->
            val name = attrString(anno, "name") ?: return@collectGenerators null
            TableGeneratorInfo(
                name = name,
                table = attrString(anno, "table"),
                pkColumnName = attrString(anno, "pkColumnName"),
                valueColumnName = attrString(anno, "valueColumnName"),
                pkColumnValue = attrString(anno, "pkColumnValue"),
                allocationSize = attrIntText(anno, "allocationSize"),
            )
        }

        // 副表（T-J18 @SecondaryTable(s)）
        val secondaryTables = (
            directAnnotations(clazz, "SecondaryTable") +
                (findFlex(clazz, FQN_SECONDARY_TABLES)?.let { attrAnnotationArray(it, "value") } ?: emptyList())
            ).mapNotNull { st ->
            val name = attrString(st, "name") ?: return@mapNotNull null
            SecondaryTableInfo(
                tableName = name,
                schema = attrString(st, "schema"),
                catalog = attrString(st, "catalog"),
                pkJoinColumns = attrAnnotationArray(st, "pkJoinColumns").map { toPkJoinColumnRef(it) },
            )
        }

        // 类级 @PrimaryKeyJoinColumn(s)（T-J18，JOINED 继承场景）
        val primaryKeyJoinColumns = (
            directAnnotations(clazz, "PrimaryKeyJoinColumn") +
                (findFlex(clazz, FQN_PRIMARY_KEY_JOIN_COLUMNS)?.let { attrAnnotationArray(it, "value") } ?: emptyList())
            ).map { toPkJoinColumnRef(it) }

        // 继承与判别（T-J18）：strategy 缺省即规范默认 SINGLE_TABLE；判别列缺省名 DTYPE
        val inheritanceStrategy = findFlex(clazz, FQN_INHERITANCE)?.let { attrEnumText(it, "strategy") ?: "SINGLE_TABLE" }
        val discriminatorColumn = findFlex(clazz, FQN_DISCRIMINATOR_COLUMN)?.let { attrString(it, "name") ?: "DTYPE" }
        val discriminatorValue = findFlex(clazz, FQN_DISCRIMINATOR_VALUE)?.let { attrString(it, "value") }

        // @IdClass 复合主键类（T-J18）：`UserPk.class` / `com.x.UserPk.class` → UserPk
        val idClassType = findFlex(clazz, FQN_ID_CLASS)?.let { anno ->
            anno.findAttributeValue("value")?.text
                ?.removeSuffix(".class")
                ?.substringAfterLast('.')
                ?.takeIf { it.isNotBlank() }
        }

        return ClassMeta(
            schema = schema,
            catalog = catalog,
            restrictionClause = restrictionClause,
            softDelete = softDelete,
            customSqlScripts = customSqlScripts,
            sequenceGenerators = sequenceGenerators,
            tableGenerators = tableGenerators,
            secondaryTables = secondaryTables,
            primaryKeyJoinColumns = primaryKeyJoinColumns,
            inheritanceStrategy = inheritanceStrategy,
            discriminatorColumn = discriminatorColumn,
            discriminatorValue = discriminatorValue,
            idClassType = idClassType,
        )
    }

    /**
     * 生成器统一收集：类级直接标注 + 容器注解 + 字段级标注，
     * 按 [GeneratorInfo.name] 归并去重（同名生成器先见者优先——收集顺序即类级直接标注在容器之前）。
     */
    private fun <T : GeneratorInfo> collectGenerators(
        clazz: PsiClass,
        simpleName: String,
        containerFqn: String,
        toInfo: (PsiAnnotation) -> T?,
    ): List<T> {
        val annotations = buildList {
            addAll(directAnnotations(clazz, simpleName))
            findFlex(clazz, containerFqn)?.let { addAll(attrAnnotationArray(it, "value")) }
            clazz.allFields.forEach { field -> addAll(directAnnotations(field, simpleName)) }
        }
        return annotations.mapNotNull(toInfo)
            .distinctBy { it.name }
    }

    private fun toPkJoinColumnRef(annotation: PsiAnnotation): JoinColumnRef = JoinColumnRef(
        name = attrString(annotation, "name") ?: "",
        referencedColumnName = attrString(annotation, "referencedColumnName"),
    )

    // ------------------------------------------------------------------
    // 字段级：主键生成值 / 乐观锁 / 嵌入 / 转换（T-J14 / T-J8 / T-J18）
    // ------------------------------------------------------------------

    /** @GeneratedValue 策略文本；注解存在而未指定 strategy 时为规范默认 AUTO。 */
    fun generatedValueStrategy(field: PsiField): String? {
        val generated = findFlex(field, FQN_GENERATED_VALUE) ?: return null
        return attrEnumText(generated, "strategy") ?: "AUTO"
    }

    /** jakarta/javax @Version（T-J8，与 MP @Version 同列语义）。 */
    fun hasVersionAnnotation(field: PsiField): Boolean = findFlex(field, FQN_VERSION) != null

    /** @Convert 标记（T-J18，仅标记不改 SQL 形态）。 */
    fun hasConvertAnnotation(field: PsiField): Boolean = findFlex(field, FQN_CONVERT) != null

    /** @MapsId 标记（T-J7，仅标记）。 */
    fun hasMapsId(field: PsiField): Boolean = findFlex(field, FQN_MAPS_ID) != null

    /** Hibernate @NotFound(action = IGNORE) 标记（T-J7）；默认 action 为 EXCEPTION → false。 */
    fun isNotFoundIgnore(field: PsiField): Boolean {
        val notFound = findFlex(field, FQN_HIBERNATE_NOT_FOUND) ?: return false
        return attrEnumText(notFound, "action") == "IGNORE"
    }

    /** mappedBy 属性值（OneToOne/OneToMany/ManyToMany；null = 未声明反向侧）。 */
    fun mappedByOf(field: PsiField): String? {
        for (fqn in listOf(FQN_ONE_TO_ONE, FQN_ONE_TO_MANY, FQN_MANY_TO_MANY)) {
            val annotation = findFlex(field, fqn) ?: continue
            attrString(annotation, "mappedBy")?.let { return it }
        }
        return null
    }

    /**
     * 字段是否为嵌入对象字段（T-J18）：@Embedded / @EmbeddedId 标注，
     * 或字段类型类标注 @Embeddable（JPA 默认嵌入语义）。
     */
    fun isEmbeddedField(field: PsiField): Boolean = runCatching {
        if (findFlex(field, FQN_EMBEDDED) != null || findFlex(field, FQN_EMBEDDED_ID) != null) {
            return@runCatching true
        }
        val typeClass = (field.type as? PsiClassType)?.resolve() ?: return@runCatching false
        findFlex(typeClass, FQN_EMBEDDABLE) != null
    }.getOrDefault(false)

    /** @EmbeddedId 标注（T-J18 复合主键）。 */
    fun hasEmbeddedIdAnnotation(field: PsiField): Boolean = findFlex(field, FQN_EMBEDDED_ID) != null

    /** @Column(insertable)，未指定时规范默认 true。 */
    fun columnInsertableOf(field: PsiField): Boolean =
        findFlex(field, FQN_COLUMN)?.let { attrBoolean(it, "insertable") } ?: true

    /** @Column(updatable)，未指定时规范默认 true。 */
    fun columnUpdatableOf(field: PsiField): Boolean =
        findFlex(field, FQN_COLUMN)?.let { attrBoolean(it, "updatable") } ?: true

    /**
     * @AttributeOverride(s) 覆写清单（T-J18）：嵌入字段名 → 覆写列名。
     * 兼容 repeatable 直接重复标注与 @AttributeOverrides 容器两种形态。
     */
    fun attributeOverrides(owner: PsiModifierListOwner): Map<String, String> = runCatching {
        val result = linkedMapOf<String, String>()
        val annotations = owner.modifierList?.annotations ?: return@runCatching emptyMap<String, String>()
        for (annotation in annotations) {
            when (annotation.nameReferenceElement?.referenceName) {
                "AttributeOverride" -> putAttributeOverride(annotation, result)
                "AttributeOverrides" -> attrAnnotationArray(annotation, "value").forEach { putAttributeOverride(it, result) }
            }
        }
        result
    }.getOrDefault(emptyMap())

    private fun putAttributeOverride(annotation: PsiAnnotation, into: MutableMap<String, String>) {
        val attributeName = attrString(annotation, "name") ?: return
        val column = attrAnnotation(annotation, "column") ?: return
        attrString(column, "name")?.let { into[attributeName] = it }
    }

    /**
     * 嵌入对象展平产物（T-J18）。
     *
     * @param field      嵌入对象内部字段（注释提取用）
     * @param fieldName  内部字段名（ColumnInfo.fieldName；非完整路径——嵌套路径为已知降级边界）
     * @param columnName 展平列名（@AttributeOverride > 内部字段 @Column > 驼峰转下划线）
     * @param fieldType  内部字段类型文本（参数回填引号推断用）
     * @param insertable 内部字段 @Column(insertable)
     * @param updatable  内部字段 @Column(updatable)
     */
    data class EmbeddedColumnSpec(
        val field: PsiField,
        val fieldName: String,
        val columnName: String,
        val fieldType: String?,
        val insertable: Boolean,
        val updatable: Boolean,
    )

    /**
     * 展平嵌入对象字段为列清单（T-J18）：逐字段解析 @AttributeOverride 覆写 →
     * 内部字段 @Column → 驼峰转下划线；嵌套 @Embedded 递归展平（JPA 无隐式列名前缀）。
     * 嵌入类型不可解析时返回空列表（调用方降级为单列形态）。
     */
    fun flattenEmbeddedColumns(field: PsiField, overrides: Map<String, String>): List<EmbeddedColumnSpec> {
        val clazz = runCatching { (field.type as? PsiClassType)?.resolve() }.getOrNull() ?: return emptyList()
        return flattenEmbeddableClass(clazz, overrides, mutableSetOf())
    }

    private fun flattenEmbeddableClass(
        clazz: PsiClass,
        overrides: Map<String, String>,
        seen: MutableSet<PsiClass>,
    ): List<EmbeddedColumnSpec> {
        if (!seen.add(clazz)) return emptyList()
        val result = mutableListOf<EmbeddedColumnSpec>()
        for (field in clazz.allFields) {
            val fieldName = runCatching { field.name }.getOrNull() ?: continue
            if (fieldName.lowercase().replace("_", "") == "serialversionuid") continue
            if (runCatching { field.hasModifierProperty("static") }.getOrDefault(false)) continue
            if (findFlex(field, FQN_TRANSIENT) != null) continue
            // 嵌套 @Embedded / @Embeddable：递归展平，覆写清单取该字段自身的 @AttributeOverride(s)
            if (isEmbeddedField(field)) {
                val nestedClass = runCatching { (field.type as? PsiClassType)?.resolve() }.getOrNull() ?: continue
                result += flattenEmbeddableClass(nestedClass, attributeOverrides(field), seen)
                continue
            }
            val columnAnnotation = findFlex(field, FQN_COLUMN)
            result += EmbeddedColumnSpec(
                field = field,
                fieldName = fieldName,
                columnName = overrides[fieldName]
                    ?: columnAnnotation?.let { attrString(it, "name") }
                    ?: NamingStrategy.toSnakeCase(fieldName),
                fieldType = runCatching { field.type.canonicalText }.getOrNull(),
                insertable = columnAnnotation?.let { attrBoolean(it, "insertable") } ?: true,
                updatable = columnAnnotation?.let { attrBoolean(it, "updatable") } ?: true,
            )
        }
        return result
    }

    // ------------------------------------------------------------------
    // 关联外键列（T-J7）
    // ------------------------------------------------------------------

    /**
     * @ToOne 字段的外键列清单（T-J7）：
     *  - @JoinColumns 容器 → 复合外键多列；
     *  - repeatable @JoinColumn 直接重复标注 ≥ 2 个 → 复合外键；
     *  - 单个 @JoinColumn（或无）→ 单列（无注解时推断 `字段名_id`，与既有列名语义一致）。
     */
    fun readToOneJoinColumns(field: PsiField, fieldName: String): List<JoinColumnRef> = runCatching {
        val container = findFlex(field, FQN_JOIN_COLUMNS)
        if (container != null) {
            val columns = attrAnnotationArray(container, "value")
            if (columns.isNotEmpty()) return@runCatching columns.map { toJoinColumnRef(it, fieldName) }
        }
        val direct = directAnnotations(field, "JoinColumn")
        if (direct.size > 1) return@runCatching direct.map { toJoinColumnRef(it, fieldName) }
        listOf(toJoinColumnRef(direct.firstOrNull(), fieldName))
    }.getOrDefault(listOf(JoinColumnRef(name = NamingStrategy.toSnakeCase(fieldName) + "_id")))

    /** 是否存在复合外键声明（@JoinColumns 容器或多个 @JoinColumn）——多列外键不落单列。 */
    fun hasCompositeJoinColumns(field: PsiField): Boolean {
        if (findFlex(field, FQN_JOIN_COLUMNS) != null) return true
        return directAnnotations(field, "JoinColumn").size > 1
    }

    private fun toJoinColumnRef(annotation: PsiAnnotation?, fieldName: String): JoinColumnRef {
        if (annotation == null) return JoinColumnRef(name = NamingStrategy.toSnakeCase(fieldName) + "_id")
        return JoinColumnRef(
            name = attrString(annotation, "name") ?: (NamingStrategy.toSnakeCase(fieldName) + "_id"),
            referencedColumnName = attrString(annotation, "referencedColumnName"),
            insertable = attrBoolean(annotation, "insertable") ?: true,
            updatable = attrBoolean(annotation, "updatable") ?: true,
        )
    }

    // ------------------------------------------------------------------
    // 集合关联（T-J7 / T-J18：@OneToMany / @ManyToMany / @ElementCollection）
    // ------------------------------------------------------------------

    /**
     * 读取集合关联元数据；非集合关联字段返回 null。
     *
     * @param targetTableNameOf 元素实体 → 表名回调（复用 JpaEntityResolver 的 @Table/命名策略推断）
     * @param entityTableName   主表名（@CollectionTable 缺省名 = 主表名_字段名 推断用）
     */
    fun readCollectionAssociation(
        field: PsiField,
        targetTableNameOf: (PsiClass) -> String,
        entityTableName: String,
    ): CollectionAssociationInfo? = runCatching {
        val fieldName = field.name ?: return@runCatching null
        val kind = when {
            findFlex(field, FQN_ELEMENT_COLLECTION) != null -> CollectionKind.ELEMENT_COLLECTION
            findFlex(field, FQN_ONE_TO_MANY) != null -> CollectionKind.ONE_TO_MANY
            findFlex(field, FQN_MANY_TO_MANY) != null -> CollectionKind.MANY_TO_MANY
            else -> return@runCatching null
        }
        val mappedBy = mappedByOf(field)
        val orderBy = findFlex(field, FQN_ORDER_BY)?.let { attrString(it, "value") }
        val orderColumn = findFlex(field, FQN_ORDER_COLUMN)?.let { attrString(it, "name") }

        // @ElementCollection：集合表（@CollectionTable）为目标表
        if (kind == CollectionKind.ELEMENT_COLLECTION) {
            val collectionTable = findFlex(field, FQN_COLLECTION_TABLE)
            val tableName = collectionTable?.let { attrString(it, "name") }
                ?: (entityTableName + "_" + NamingStrategy.toSnakeCase(fieldName))
            val joinColumns = collectionTable
                ?.let { attrAnnotationArray(it, "joinColumns") }
                ?.map { toJoinColumnRef(it, fieldName) }
                ?: emptyList()
            val joinTable = collectionTable?.let {
                JoinTableInfo(
                    tableName = tableName,
                    schema = attrString(it, "schema"),
                    catalog = attrString(it, "catalog"),
                    joinColumns = joinColumns,
                )
            }
            return@runCatching CollectionAssociationInfo(
                fieldName = fieldName,
                kind = kind,
                targetTable = tableName,
                mappedBy = mappedBy,
                joinTable = joinTable,
                joinColumns = joinColumns,
                orderBy = orderBy,
                orderColumn = orderColumn,
            )
        }

        // @OneToMany / @ManyToMany：元素实体表；元素类型不可解析时按泛型文本推断（优雅降级）
        val targetTable = elementEntityTableName(field, targetTableNameOf) ?: return@runCatching null
        // @JoinTable 中间表（ManyToMany 常规、OneToMany 少见合法形态）
        val joinTable = findFlex(field, FQN_JOIN_TABLE)?.let { jt ->
            JoinTableInfo(
                tableName = attrString(jt, "name"),
                schema = attrString(jt, "schema"),
                catalog = attrString(jt, "catalog"),
                joinColumns = attrAnnotationArray(jt, "joinColumns").map { toJoinColumnRef(it, fieldName) },
                inverseJoinColumns = attrAnnotationArray(jt, "inverseJoinColumns").map { toJoinColumnRef(it, fieldName) },
            )
        }
        // 单向 @OneToMany + @JoinColumn：外键列在目标表（独立 UPDATE 子表语义）
        val joinColumns = if (kind == CollectionKind.ONE_TO_MANY && mappedBy == null && hasJoinColumnsAnnotation(field)) {
            readToOneJoinColumns(field, fieldName)
        } else {
            emptyList()
        }
        CollectionAssociationInfo(
            fieldName = fieldName,
            kind = kind,
            targetTable = targetTable,
            mappedBy = mappedBy,
            joinTable = joinTable,
            joinColumns = joinColumns,
            orderBy = orderBy,
            orderColumn = orderColumn,
        )
    }.getOrNull()

    /** 字段是否存在任何 @JoinColumn 声明（容器或直接标注）。 */
    private fun hasJoinColumnsAnnotation(field: PsiField): Boolean =
        findFlex(field, FQN_JOIN_COLUMNS) != null || directAnnotations(field, "JoinColumn").isNotEmpty()

    /** 集合字段元素实体表名：元素类可解析走回调，否则按泛型文本推断蛇形表名。 */
    private fun elementEntityTableName(
        field: PsiField,
        targetTableNameOf: (PsiClass) -> String,
    ): String? {
        val type = runCatching { field.type as? PsiClassType }.getOrNull() ?: return null
        val elementType = type?.parameters?.firstOrNull() as? PsiClassType
        val elementClass = elementType?.resolve()
        if (elementClass != null) return targetTableNameOf(elementClass)
        val text = runCatching { field.type.canonicalText }.getOrNull() ?: return null
        val elementText = text.substringAfter('<', "").substringBefore('>', "").substringAfterLast('.')
        return elementText.takeIf { it.isNotBlank() }?.let { NamingStrategy.toSnakeCase(it) }
    }
}
