package cn.song.mybatisplus.visualizer.model

/**
 * JPA 实体元数据扩展模型（M39：T-J7 关联增强 / T-J8 软删除与动态 SQL / T-J14 主键生成值 / T-J18 映射深水区）。
 *
 * 纯数据类型，只承载 JpaEntityResolver 从注解读取的元信息，供后续 SQL 注入与模板消费
 * （注入逻辑不在元数据层）。所有集合字段默认空集合、可空字段默认 null——
 * MP 侧 TableInfo 既有构造与既有行为零影响。
 */

/**
 * 单个外键列引用（T-J7/T-J18）。
 *
 * @param name                外键列名；未显式指定 @JoinColumn(name) 时按命名策略推断（字段名 + "_id"），
 *                            @PrimaryKeyJoinColumn 未显式指定时为空串（由消费者结合主键列决定）
 * @param referencedColumnName 被引用列（@JoinColumn(referencedColumnName)）；未指定为 null（默认引用对方主键）
 * @param insertable          是否参与 INSERT（默认 true）
 * @param updatable           是否参与 UPDATE（默认 true）
 */
data class JoinColumnRef(
    val name: String,
    val referencedColumnName: String? = null,
    val insertable: Boolean = true,
    val updatable: Boolean = true,
)

/**
 * 中间表信息（T-J7 @ManyToMany @JoinTable / T-J18 @CollectionTable）。
 *
 * @param tableName           中间表名；@JoinTable 未显式指定 name 时为 null（JPA 默认按双方实体名拼接，无法静态推断）
 * @param schema              schema（@CollectionTable/@JoinTable schema）
 * @param catalog             catalog
 * @param joinColumns         指向本表的关联列（joinColumns）
 * @param inverseJoinColumns  指向目标表的关联列（inverseJoinColumns；@CollectionTable 无此概念，恒为空）
 */
data class JoinTableInfo(
    val tableName: String?,
    val schema: String? = null,
    val catalog: String? = null,
    val joinColumns: List<JoinColumnRef> = emptyList(),
    val inverseJoinColumns: List<JoinColumnRef> = emptyList(),
)

/**
 * 副表信息（T-J18 @SecondaryTable）。
 *
 * @param tableName     副表名
 * @param schema        schema
 * @param catalog       catalog
 * @param pkJoinColumns 主键关联列（pkJoinColumns；语义：主表 PK = 副表关联列）
 */
data class SecondaryTableInfo(
    val tableName: String,
    val schema: String? = null,
    val catalog: String? = null,
    val pkJoinColumns: List<JoinColumnRef> = emptyList(),
)

/**
 * 生成器信息公共接口（T-J14）：name 即 @GeneratedValue(generator = "...") 的引用键，
 * 同时作为同名生成器去重键——类级 repeatable 直接标注、容器注解、字段级三处扫描后按 name 归并，
 * 先见者优先。
 */
interface GeneratorInfo {
    val name: String
}

/**
 * 序列生成器（T-J14 @SequenceGenerator）。
 *
 * @param name           生成器名（@GeneratedValue(generator = "...") 引用键）
 * @param sequenceName   数据库序列名
 * @param allocationSize 分配大小（默认 50；未显式指定为 null，留给消费者按方言决定）
 * @param initialValue   初始值（默认 1；未显式指定为 null）
 * @param schema         schema
 */
data class SequenceGeneratorInfo(
    override val name: String,
    val sequenceName: String?,
    val allocationSize: String? = null,
    val initialValue: String? = null,
    val schema: String? = null,
) : GeneratorInfo

/**
 * 序列表生成器（T-J14 @TableGenerator）。
 *
 * @param name           生成器名（@GeneratedValue(generator = "...") 引用键）
 * @param table          序列表名（默认 "hibernate_sequences"；未显式指定为 null）
 * @param pkColumnName   序列表主键列名（默认 "sequence_name"）
 * @param valueColumnName 序列表值列名（默认 "next_val"）
 * @param pkColumnValue  序列表主键值（默认实体名）
 * @param allocationSize 分配大小（默认 50；未显式指定为 null）
 */
data class TableGeneratorInfo(
    override val name: String,
    val table: String?,
    val pkColumnName: String? = null,
    val valueColumnName: String? = null,
    val pkColumnValue: String? = null,
    val allocationSize: String? = null,
) : GeneratorInfo

/**
 * Hibernate 软删除（T-J8 @SoftDelete，6.4+）。
 *
 * @param columnName 软删除列名（注解未指定时为 Hibernate 默认 "deleted"）
 * @param strategy   策略文本（DELETED / ACTIVE / VERSION，注解未指定时为默认 "DELETED"）
 */
data class SoftDeleteInfo(
    val columnName: String,
    val strategy: String?,
)

/**
 * 自定义 SQL 脚本（T-J8 @SQLInsert/@SQLDelete/@SQLDeleteAll/@SQLUpdate）。
 *
 * 全部字段为 null 时 TableInfo.customSqlScripts 为 null。
 *
 * @param sqlInsert    自定义 INSERT 脚本
 * @param sqlDelete    自定义 DELETE 脚本
 * @param sqlDeleteAll 自定义全量 DELETE 脚本
 * @param sqlUpdate    自定义 UPDATE 脚本
 */
data class CustomSqlScripts(
    val sqlInsert: String? = null,
    val sqlDelete: String? = null,
    val sqlDeleteAll: String? = null,
    val sqlUpdate: String? = null,
)

/**
 * 集合关联类型（T-J7/T-J18）。
 */
enum class CollectionKind {
    /** @OneToMany：独立 SELECT（默认 LAZY），单向 + @JoinColumn 时外键在目标表。 */
    ONE_TO_MANY,

    /** @ManyToMany：经中间表关联。 */
    MANY_TO_MANY,

    /** @ElementCollection：元素集合表（独立第二查询，N+1 高发面）。 */
    ELEMENT_COLLECTION,
}

/**
 * 集合关联信息（T-J7 @OneToMany/@ManyToMany 关联增强、T-J18 @ElementCollection/@CollectionTable）。
 *
 * 与 [AssociationInfo]（@ManyToOne/@OneToOne 主表侧 JOIN 语义）分开建模：
 * 集合关联是「独立第二查询/中间表」语义，不参与主表 JOIN 列推断。
 *
 * @param fieldName   集合字段名（Java 字段名）
 * @param kind        集合关联类型
 * @param targetTable 目标表名：ONE_TO_MANY/MANY_TO_MANY 为元素实体表；ELEMENT_COLLECTION 为集合表名
 *                    （@CollectionTable 未指定 name 时按「主表名_字段名」推断）
 * @param mappedBy    对方实体持有关联字段名（mappedBy；null 表示本侧为持有方/无反向声明）
 * @param joinTable   中间表信息（@JoinTable / @CollectionTable）
 * @param joinColumns 单向 @OneToMany + @JoinColumn 的关联列（外键在目标表）
 * @param orderBy     @OrderBy(value) 原文（如 "name ASC"；未指定为 null——默认按主键）
 * @param orderColumn @OrderColumn(name) 索引列名（未指定为 null）
 */
data class CollectionAssociationInfo(
    val fieldName: String,
    val kind: CollectionKind,
    val targetTable: String,
    val mappedBy: String? = null,
    val joinTable: JoinTableInfo? = null,
    val joinColumns: List<JoinColumnRef> = emptyList(),
    val orderBy: String? = null,
    val orderColumn: String? = null,
)
