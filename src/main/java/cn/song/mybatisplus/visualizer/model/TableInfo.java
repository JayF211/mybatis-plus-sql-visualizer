package cn.song.mybatisplus.visualizer.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
public final class TableInfo {
    private final String entityClassName;
    private final String tableName;
    private final List<ColumnInfo> columns;
    private final String logicDeleteColumn;
    private final String versionColumn;
    private final String comment;
    private final List<AssociationInfo> associations;
    private final String schema;
    private final String catalog;
    private final String restrictionClause;
    private final SoftDeleteInfo softDelete;
    private final CustomSqlScripts customSqlScripts;
    private final List<SequenceGeneratorInfo> sequenceGenerators;
    private final List<TableGeneratorInfo> tableGenerators;
    private final List<CollectionAssociationInfo> collectionAssociations;
    private final List<SecondaryTableInfo> secondaryTables;
    private final List<JoinColumnRef> primaryKeyJoinColumns;
    private final String inheritanceStrategy;
    private final String discriminatorColumn;
    private final String discriminatorValue;
    private final List<String> compositeIdColumns;
    private final String idClassType;

    public TableInfo(String entityClassName, String tableName, List<ColumnInfo> columns,
                     String logicDeleteColumn, String versionColumn, String comment,
                     List<AssociationInfo> associations, String schema, String catalog,
                     String restrictionClause, SoftDeleteInfo softDelete, CustomSqlScripts customSqlScripts,
                     List<SequenceGeneratorInfo> sequenceGenerators, List<TableGeneratorInfo> tableGenerators,
                     List<CollectionAssociationInfo> collectionAssociations, List<SecondaryTableInfo> secondaryTables,
                     List<JoinColumnRef> primaryKeyJoinColumns, String inheritanceStrategy,
                     String discriminatorColumn, String discriminatorValue, List<String> compositeIdColumns,
                     String idClassType) {
        this.entityClassName = entityClassName;
        this.tableName = tableName;
        this.columns = columns;
        this.logicDeleteColumn = logicDeleteColumn;
        this.versionColumn = versionColumn;
        this.comment = comment;
        this.associations = associations;
        this.schema = schema;
        this.catalog = catalog;
        this.restrictionClause = restrictionClause;
        this.softDelete = softDelete;
        this.customSqlScripts = customSqlScripts;
        this.sequenceGenerators = sequenceGenerators;
        this.tableGenerators = tableGenerators;
        this.collectionAssociations = collectionAssociations;
        this.secondaryTables = secondaryTables;
        this.primaryKeyJoinColumns = primaryKeyJoinColumns;
        this.inheritanceStrategy = inheritanceStrategy;
        this.discriminatorColumn = discriminatorColumn;
        this.discriminatorValue = discriminatorValue;
        this.compositeIdColumns = compositeIdColumns;
        this.idClassType = idClassType;
    }

    public TableInfo(String entityClassName, String tableName, List<ColumnInfo> columns,
                     String logicDeleteColumn, String versionColumn, String comment,
                     List<AssociationInfo> associations) {
        this(entityClassName, tableName, columns, logicDeleteColumn, versionColumn, comment, associations,
                null, null, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, List.of(), null);
    }

    public TableInfo(String entityClassName, String tableName, List<ColumnInfo> columns,
                     String logicDeleteColumn, String versionColumn, String comment) {
        this(entityClassName, tableName, columns, logicDeleteColumn, versionColumn, comment, List.of());
    }

    public TableInfo(String entityClassName, String tableName, List<ColumnInfo> columns,
                     String logicDeleteColumn, String versionColumn) {
        this(entityClassName, tableName, columns, logicDeleteColumn, versionColumn, null, List.of());
    }

    public TableInfo(String entityClassName, String tableName, List<ColumnInfo> columns,
                     String logicDeleteColumn) {
        this(entityClassName, tableName, columns, logicDeleteColumn, null, null, List.of());
    }

    public TableInfo(String entityClassName, String tableName, List<ColumnInfo> columns) {
        this(entityClassName, tableName, columns, null, null, null, List.of());
    }

    public String getEntityClassName() {
        return entityClassName;
    }

    public String getTableName() {
        return tableName;
    }

    public List<ColumnInfo> getColumns() {
        return columns;
    }

    public String getLogicDeleteColumn() {
        return logicDeleteColumn;
    }

    public String getVersionColumn() {
        return versionColumn;
    }

    public String getComment() {
        return comment;
    }

    public List<AssociationInfo> getAssociations() {
        return associations;
    }

    public String getSchema() {
        return schema;
    }

    public String getCatalog() {
        return catalog;
    }

    public String getRestrictionClause() {
        return restrictionClause;
    }

    public SoftDeleteInfo getSoftDelete() {
        return softDelete;
    }

    public CustomSqlScripts getCustomSqlScripts() {
        return customSqlScripts;
    }

    public List<SequenceGeneratorInfo> getSequenceGenerators() {
        return sequenceGenerators;
    }

    public List<TableGeneratorInfo> getTableGenerators() {
        return tableGenerators;
    }

    public List<CollectionAssociationInfo> getCollectionAssociations() {
        return collectionAssociations;
    }

    public List<SecondaryTableInfo> getSecondaryTables() {
        return secondaryTables;
    }

    public List<JoinColumnRef> getPrimaryKeyJoinColumns() {
        return primaryKeyJoinColumns;
    }

    public String getInheritanceStrategy() {
        return inheritanceStrategy;
    }

    public String getDiscriminatorColumn() {
        return discriminatorColumn;
    }

    public String getDiscriminatorValue() {
        return discriminatorValue;
    }

    public List<String> getCompositeIdColumns() {
        return compositeIdColumns;
    }

    public String getIdClassType() {
        return idClassType;
    }

    /** 便捷查找：按实体字段名取列信息，找不到返回 null。 */
    public ColumnInfo columnByField(String fieldName) {
        for (ColumnInfo c : columns) {
            if (c.getFieldName().equals(fieldName)) {
                return c;
            }
        }
        return null;
    }

    /**
     * T-Embedded：按「嵌入字段名 + 内部字段名」的驼峰拼接路径查展平列。
     *
     * <p>Spring Data 派生查询把 {@code updateInfo.createdTime} 穿透 @Embedded 的属性路径整体解析成单个
     * 驼峰名 {@code updateInfoCreatedTime}（无点号；<b>内层字段首字母大写</b>——{@code UpdateInfo} +
     * {@code CreatedTime} 驼峰化的产物），{@link #columnByField} 查不中（它不是任何字段的裸名），消费方
     * 据 snake 兜底会产出 {@code update_info_created_time} 伪列。拼接匹配展平列（{@code embeddedParent +
     * 首字母大写的 fieldName == path}）即可还原真实列名 {@code created_time}。
     */
    public ColumnInfo columnByEmbeddedPath(String path) {
        for (ColumnInfo c : columns) {
            if (!c.isEmbedded() || c.getEmbeddedParent() == null) {
                continue;
            }
            String fieldName = c.getFieldName();
            String capped = fieldName.isEmpty()
                    ? fieldName
                    : Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            if ((c.getEmbeddedParent() + capped).equals(path)) {
                return c;
            }
        }
        return null;
    }

    /** 全部列名（用于 SELECT * 展开）。 */
    public List<String> getAllColumnNames() {
        List<String> result = new ArrayList<>();
        for (ColumnInfo c : columns) {
            result.add(c.getColumnName());
        }
        return result;
    }

    /** 按字段名查找关联信息。 */
    public AssociationInfo associationByField(String fieldName) {
        for (AssociationInfo a : associations) {
            if (a.getFieldName().equals(fieldName)) {
                return a;
            }
        }
        return null;
    }

    /** 按字段名查找集合关联信息（T-J7/T-J18），找不到返回 null。 */
    public CollectionAssociationInfo collectionAssociationByField(String fieldName) {
        for (CollectionAssociationInfo a : collectionAssociations) {
            if (a.getFieldName().equals(fieldName)) {
                return a;
            }
        }
        return null;
    }

    public TableInfo copy(String entityClassName, String tableName, List<ColumnInfo> columns,
                          String logicDeleteColumn, String versionColumn, String comment,
                          List<AssociationInfo> associations) {
        return new TableInfo(entityClassName, tableName, columns, logicDeleteColumn, versionColumn, comment,
                associations, schema, catalog, restrictionClause, softDelete, customSqlScripts,
                sequenceGenerators, tableGenerators, collectionAssociations, secondaryTables,
                primaryKeyJoinColumns, inheritanceStrategy, discriminatorColumn, discriminatorValue,
                compositeIdColumns, idClassType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TableInfo)) {
            return false;
        }
        TableInfo that = (TableInfo) o;
        return Objects.equals(entityClassName, that.entityClassName)
                && Objects.equals(tableName, that.tableName)
                && Objects.equals(columns, that.columns)
                && Objects.equals(logicDeleteColumn, that.logicDeleteColumn)
                && Objects.equals(versionColumn, that.versionColumn)
                && Objects.equals(comment, that.comment)
                && Objects.equals(associations, that.associations)
                && Objects.equals(schema, that.schema)
                && Objects.equals(catalog, that.catalog)
                && Objects.equals(restrictionClause, that.restrictionClause)
                && Objects.equals(softDelete, that.softDelete)
                && Objects.equals(customSqlScripts, that.customSqlScripts)
                && Objects.equals(sequenceGenerators, that.sequenceGenerators)
                && Objects.equals(tableGenerators, that.tableGenerators)
                && Objects.equals(collectionAssociations, that.collectionAssociations)
                && Objects.equals(secondaryTables, that.secondaryTables)
                && Objects.equals(primaryKeyJoinColumns, that.primaryKeyJoinColumns)
                && Objects.equals(inheritanceStrategy, that.inheritanceStrategy)
                && Objects.equals(discriminatorColumn, that.discriminatorColumn)
                && Objects.equals(discriminatorValue, that.discriminatorValue)
                && Objects.equals(compositeIdColumns, that.compositeIdColumns)
                && Objects.equals(idClassType, that.idClassType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityClassName, tableName, columns, logicDeleteColumn, versionColumn, comment,
                associations, schema, catalog, restrictionClause, softDelete, customSqlScripts,
                sequenceGenerators, tableGenerators, collectionAssociations, secondaryTables,
                primaryKeyJoinColumns, inheritanceStrategy, discriminatorColumn, discriminatorValue,
                compositeIdColumns, idClassType);
    }

    @Override
    public String toString() {
        return "TableInfo(entityClassName=" + entityClassName + ", tableName=" + tableName + ", columns=" + columns
                + ", logicDeleteColumn=" + logicDeleteColumn + ", versionColumn=" + versionColumn + ", comment=" + comment
                + ", associations=" + associations + ", schema=" + schema + ", catalog=" + catalog
                + ", restrictionClause=" + restrictionClause + ", softDelete=" + softDelete
                + ", customSqlScripts=" + customSqlScripts + ", sequenceGenerators=" + sequenceGenerators
                + ", tableGenerators=" + tableGenerators + ", collectionAssociations=" + collectionAssociations
                + ", secondaryTables=" + secondaryTables + ", primaryKeyJoinColumns=" + primaryKeyJoinColumns
                + ", inheritanceStrategy=" + inheritanceStrategy + ", discriminatorColumn=" + discriminatorColumn
                + ", discriminatorValue=" + discriminatorValue + ", compositeIdColumns=" + compositeIdColumns
                + ", idClassType=" + idClassType + ")";
    }
}
