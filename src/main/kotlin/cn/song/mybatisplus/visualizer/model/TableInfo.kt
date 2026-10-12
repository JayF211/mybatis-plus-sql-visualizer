package cn.song.mybatisplus.visualizer.model

/**
 * 字段角色（T-M23.3 角色分组排序键）：PRIMARY(0) < BUSINESS(1) < AUDIT(2) < SYSTEM(3)，
 * 组内保持声明顺序（稳定排序）——`id, 业务字段…, create_time, update_time, deleted`。
 */
enum class ColumnRole {
    /** @TableId 主键——置顶。 */
    PRIMARY,

    /** 普通业务字段——居中。 */
    BUSINESS,

    /** @TableField(fill = ...) 自动填充字段——沉底组 1（审计字段惯例）。 */
    AUDIT,

    /** @TableLogic / @Version——沉底组 2。 */
    SYSTEM,
}

/**
 * 关联信息（JPA @ManyToOne / @OneToOne 关联）。
 *
 * @param fieldName    关联字段名（Java 字段名，如 "address"）
 * @param joinColumn   外键列名（主表侧，如 "address_id"）；复合外键时为首列（完整清单见 joinColumns）
 * @param targetTable  目标表名（副表，如 "address"）
 * @param targetAlias  建议别名（实体类名首字母小写，如 "address"）
 * @param referencedColumnName 被引用列（T-J7 @JoinColumn referencedColumnName）；未指定为 null（默认对方主键）
 * @param joinColumns  外键列完整清单（T-J7 @JoinColumns 复合外键；单外键时为单元素列表，
 *                     无 @JoinColumn 时为推断名单元素列表）
 * @param mappedBy     对方实体持有关联字段名（T-J7 mappedBy）；null 表示本侧为持有方
 * @param hasForeignKey 本表是否持有外键（T-J7 侧别语义）：mappedBy 侧为 false——无 FK 列，
 *                     查询语义为按主键二次 SELECT；joinColumn 字段此时仅为占位推断值
 * @param joinTable    中间表信息（T-J7 @JoinTable；@ManyToOne/@OneToOne 恒为 null，@ManyToMany 见 CollectionAssociationInfo）
 * @param notFoundIgnore Hibernate @NotFound(action = IGNORE) 标记（T-J7）：目标行缺失时置 null 而非抛异常
 * @param mapsId       @MapsId 标记（T-J7）：外键列与引用实体的 @EmbeddedId/@Id 共享
 */
data class AssociationInfo(
    val fieldName: String,
    val joinColumn: String,
    val targetTable: String,
    val targetAlias: String,
    // ---- M39/T-J7 元数据增强（尾部追加带默认值，既有位置构造零影响）----
    val referencedColumnName: String? = null,
    val joinColumns: List<JoinColumnRef> = emptyList(),
    val mappedBy: String? = null,
    val hasForeignKey: Boolean = true,
    val joinTable: JoinTableInfo? = null,
    val notFoundIgnore: Boolean = false,
    val mapsId: Boolean = false,
)

/**
 * 表信息（F-13 实体解析产物）。
 *
 * 由实体类解析得到：表名（@TableName 注解或命名策略推断）、字段列映射（@TableField 或命名策略）。
 *
 * @param entityClassName 实体类的简单类名（如 DeviceInfoEntity）
 * @param tableName       数据库表名（如 device_info）
 * @param columns         实体字段到列的映射（含主键标记）
 * @param logicDeleteColumn 逻辑删除列（@TableLogic 标注字段的列名，T-M15-07）；null 表示无逻辑删除
 * @param versionColumn     乐观锁版本列（@Version 标注字段的列名，T-M22-02）；null 表示无乐观锁
 * @param comment         表注释（M16：类级注解/javadoc/行注释提取，schema 预留最高优先级）
 * @param associations    关联信息列表（@ManyToOne / @OneToOne 关联元数据，用于 JOIN 构建）
 * @param schema          表 schema（T-J18 @Table(schema)）；未指定为 null
 * @param catalog         表 catalog（T-J18 @Table(catalog)）；未指定为 null
 * @param restrictionClause 静态限制子句（T-J8：Hibernate @Where clause / @SQLRestriction value，
 *                          后者优先——6.3 起替代前者）；类比 MP @TableLogic 的全查询注入点
 * @param softDelete      Hibernate 软删除（T-J8 @SoftDelete，6.4+）；null 表示无
 * @param customSqlScripts 自定义 SQL 脚本（T-J8 @SQLInsert/@SQLDelete/@SQLDeleteAll/@SQLUpdate）；
 *                          四者皆缺省为 null
 * @param sequenceGenerators 序列生成器清单（T-J14 @SequenceGenerator(s)，类级+字段级扫描，按 name 去重）
 * @param tableGenerators  序列表生成器清单（T-J14 @TableGenerator(s)，同上）
 * @param collectionAssociations 集合关联元数据（T-J7/T-J18：@OneToMany/@ManyToMany/@ElementCollection——
 *                          独立第二查询/中间表语义，不参与主表 JOIN 列）
 * @param secondaryTables  副表清单（T-J18 @SecondaryTable(s)）
 * @param primaryKeyJoinColumns 类级主键关联列（T-J18 @PrimaryKeyJoinColumn(s)，JOINED 继承场景）
 * @param inheritanceStrategy 继承策略文本（T-J18 @Inheritance(strategy)：SINGLE_TABLE/JOINED/TABLE_PER_CLASS；
 *                          注解未指定 strategy 时为默认 SINGLE_TABLE）；null 表示无 @Inheritance
 * @param discriminatorColumn 判别列名（T-J18 @DiscriminatorColumn(name)，注解存在而未指定 name 时为规范默认 "DTYPE"）；
 *                          null 表示无 @DiscriminatorColumn
 * @param discriminatorValue  本类判别值（T-J18 @DiscriminatorValue）；null 表示无
 * @param compositeIdColumns   复合主键列清单（T-J18 @EmbeddedId / @IdClass / 多 @Id，声明顺序）；
 *                          单主键时为空列表（单主键直接看 columns 中 isId）
 * @param idClassType      @IdClass 引用的复合主键类简单名（T-J18）；null 表示无
 */
data class TableInfo(
    val entityClassName: String,
    val tableName: String,
    val columns: List<ColumnInfo>,
    val logicDeleteColumn: String? = null,
    val versionColumn: String? = null,
    val comment: String? = null,
    val associations: List<AssociationInfo> = emptyList(),
    // ---- M39/T-J7、T-J8、T-J14、T-J18 元数据扩展（尾部追加带默认值，既有位置构造零影响）----
    val schema: String? = null,
    val catalog: String? = null,
    val restrictionClause: String? = null,
    val softDelete: SoftDeleteInfo? = null,
    val customSqlScripts: CustomSqlScripts? = null,
    val sequenceGenerators: List<SequenceGeneratorInfo> = emptyList(),
    val tableGenerators: List<TableGeneratorInfo> = emptyList(),
    val collectionAssociations: List<CollectionAssociationInfo> = emptyList(),
    val secondaryTables: List<SecondaryTableInfo> = emptyList(),
    val primaryKeyJoinColumns: List<JoinColumnRef> = emptyList(),
    val inheritanceStrategy: String? = null,
    val discriminatorColumn: String? = null,
    val discriminatorValue: String? = null,
    val compositeIdColumns: List<String> = emptyList(),
    val idClassType: String? = null,
) {
    /** 便捷查找：按实体字段名取列信息，找不到返回 null。 */
    fun columnByField(fieldName: String): ColumnInfo? = columns.firstOrNull { it.fieldName == fieldName }

    /**
     * T-Embedded：按嵌入段驼峰路径查展平列。
     *
     * Spring Data 派生查询把 `updateInfo.createdTime` 穿透 @Embedded 的属性路径整体解析成单个
     * 驼峰名 `updateInfoCreatedTime`（无点号；**每段首字母大写**——`UpdateInfo` + `CreatedTime`
     * 驼峰化的产物），`columnByField` 查不中（它不是任何字段的裸名），消费方据此 snake 兜底会产出
     * `update_info_created_time` 伪列。
     *
     * 匹配按两条轨（G2 修复：两级嵌入链）：
     *  - **[ColumnInfo.embeddedPath] 全路径等值**——展平时逐段落盘（`address.zipcode.zip` →
     *    `addressZipcodeZip`），两级以上嵌入链（address + zipcode + zip）由此命中覆写列
     *    `cust_zip`，不再因「只认一层拼接」落 snake 兜底产出 `address_zipcode_zip` 伪列；
     *  - 既有单层拼接兜底（`embeddedParent + 首字母大写的 fieldName == path`）——手搓 TableInfo
     *    夹具 / 类型不可解析的降级单列形态仍按此命中，行为不回归。
     */
    fun columnByEmbeddedPath(path: String): ColumnInfo? =
        columns.firstOrNull { it.embedded && it.embeddedPath == path }
            ?: columns.firstOrNull {
                it.embedded && it.embeddedParent != null &&
                    it.embeddedParent + it.fieldName.replaceFirstChar { c -> c.uppercaseChar() } == path
            }

    /** 全部列名（用于 SELECT * 展开）。 */
    val allColumnNames: List<String> get() = columns.map { it.columnName }

    /** 按字段名查找关联信息。 */
    fun associationByField(fieldName: String): AssociationInfo? =
        associations.firstOrNull { it.fieldName == fieldName }

    /** 按字段名查找集合关联信息（T-J7/T-J18），找不到返回 null。 */
    fun collectionAssociationByField(fieldName: String): CollectionAssociationInfo? =
        collectionAssociations.firstOrNull { it.fieldName == fieldName }
}

/**
 * 实体字段与数据库列的映射。
 *
 * @param fieldName  实体字段名（如 shareSchemeId）
 * @param columnName 数据库列名（如 share_scheme_id）
 * @param isId       是否主键（@TableId）
 * @param idTypeAuto T-M23-04：主键是否自增（`@TableId(type = IdType.AUTO)`）——INSERT 模板
 *                   默认不勾选主键列（对齐 MP：自增主键不进 INSERT 列清单）；非 AUTO 恒勾选
 * @param fieldType  实体字段类型全限定名（M13，如 java.lang.String / java.lang.Long），
 *                   用于参数回填的引号推断；PSI 解析失败时为 null
 * @param fill       自动填充标注（@TableField(fill = FieldFill.XXX) 的文本，T-M15-08）：
 *                   INSERT / INSERT_UPDATE 参与插入、UPDATE / INSERT_UPDATE 参与更新；null 表示无填充
 * @param comment    字段注释（M16：注解 > javadoc > 行注释，四级优先级含 schema 预留）
 * @param role       T-M23.3：字段角色（角色分组排序键，默认 [ColumnRole.BUSINESS]——
 *                   存量构造零影响；排序由 EntityResolver 计算并稳定排序）
 * @param insertable 是否参与 INSERT（T-J18 @Column(insertable)/@JoinColumn(insertable)，默认 true——
 *                   =false 时该列从 INSERT 列清单消失）
 * @param updatable  是否参与 UPDATE（T-J18 @Column(updatable)/@JoinColumn(updatable)，默认 true——
 *                   =false 时该列不出现在 UPDATE SET 子句）
 * @param generatedValueStrategy 主键生成策略文本（T-J14 @GeneratedValue(strategy)：IDENTITY/SEQUENCE/TABLE/UUID/AUTO，
 *                   未指定 strategy 时为规范默认 AUTO）；非主键列或无 @GeneratedValue 时为 null。
 *                   存 String 而非枚举——对未知/未来策略值容错降级
 * @param embedded   列是否展平自 @Embedded/@Embeddable/@EmbeddedId 嵌入对象字段（T-J18）；
 *                   fieldName 为嵌入对象内部字段名
 * @param converted  @Convert 标记（T-J18）：字段经 AttributeConverter 转换绑定值（不改 SQL 形态，仅标记）
 */
data class ColumnInfo(
    val fieldName: String,
    val columnName: String,
    val isId: Boolean = false,
    val idTypeAuto: Boolean = false,
    val fieldType: String? = null,
    val fill: String? = null,
    val comment: String? = null,
    val role: ColumnRole = ColumnRole.BUSINESS,
    // ---- M39/T-J14、T-J18 元数据扩展（尾部追加带默认值，既有位置构造零影响）----
    val insertable: Boolean = true,
    val updatable: Boolean = true,
    val generatedValueStrategy: String? = null,
    val embedded: Boolean = false,
    val converted: Boolean = false,
    /**
     * 展平本列的**嵌入对象字段名**（`@Embedded` 展平时写入，非嵌入列为 null）。
     *
     * 存在的理由：嵌入对象没有自己的表，其字段直接落在宿主表的列上，所以 HQL 路径
     * `别名.嵌入对象字段.内层字段` 必须映射为 `别名.内层列名`（去掉嵌入段）——保留嵌入段会产出
     * 无效 SQL（SQL 语义下 `别名.嵌入对象` 被当成「库.表」）。路径映射据此判断该不该丢中间段，
     * 而不是靠「中间段不是本表字段就丢掉」这种猜测（后者会误删别的合法中间段）。
     */
    val embeddedParent: String? = null,
    /**
     * 展平本列的**完整嵌入段驼峰路径**（实体根起算，各级嵌入字段名首字母大写，如
     * `address.zipcode.zip` → `addressZipcodeZip`；非嵌入列为 null）。
     *
     * 与 [embeddedParent] 的区别：后者只记最外层嵌入字段名，只够一层拼接
     * （`updateInfoCreatedTime`）；两级以上嵌入链（`findByAddressZipcodeZip` 形态）
     * 需要中间段才能还原（见 [TableInfo.columnByEmbeddedPath]）。
     */
    val embeddedPath: String? = null,
)
