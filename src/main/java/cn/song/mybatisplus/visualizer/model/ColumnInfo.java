package cn.song.mybatisplus.visualizer.model;

import java.util.Objects;

/**
 * 实体字段与数据库列的映射。
 *
 * @param fieldName  实体字段名（如 shareSchemeId）
 * @param columnName 数据库列名（如 share_scheme_id）
 * @param isId       是否主键（@TableId）
 * @param idTypeAuto T-M23-04：主键是否自增（@TableId(type = IdType.AUTO)）——INSERT 模板
 *                   默认不勾选主键列（对齐 MP：自增主键不进 INSERT 列清单）；非 AUTO 恒勾选
 * @param fieldType  实体字段类型全限定名（M13，如 java.lang.String / java.lang.Long），
 *                   用于参数回填的引号推断；PSI 解析失败时为 null
 * @param fill       自动填充标注（@TableField(fill = FieldFill.XXX) 的文本，T-M15-08）：
 *                   INSERT / INSERT_UPDATE 参与插入、UPDATE / INSERT_UPDATE 参与更新；null 表示无填充
 * @param comment    字段注释（M16：注解 &gt; javadoc &gt; 行注释，四级优先级含 schema 预留）
 * @param role       T-M23.3：字段角色（角色分组排序键，默认 ColumnRole.BUSINESS——
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
 * @param embeddedParent 展平本列的<b>嵌入对象字段名</b>（{@code @Embedded} 展平时写入，非嵌入列为 null）。
 *                   嵌入对象没有自己的表，其字段直接落在宿主表的列上，所以 HQL 路径
 *                   {@code 别名.嵌入对象字段.内层字段} 必须映射为 {@code 别名.内层列名}（去掉嵌入段）——保留嵌入段会产出
 *                   无效 SQL（SQL 语义下 {@code 别名.嵌入对象} 被当成「库.表」）。路径映射据此判断该不该丢中间段，
 *                   而不是靠「中间段不是本表字段就丢掉」这种猜测（后者会误删别的合法中间段）
 * @param embeddedPath 展平本列的<b>完整嵌入段驼峰路径</b>（实体根起算，各级嵌入字段名首字母大写，如
 *                   {@code address.zipcode.zip} → {@code addressZipcodeZip}；非嵌入列为 null）。
 *                   与 {@code embeddedParent} 的区别：后者只记最外层嵌入字段名，只够一层拼接
 *                   （{@code updateInfoCreatedTime}）；两级以上嵌入链（{@code findByAddressZipcodeZip}
 *                   形态）需要中间段才能还原（见 {@code TableInfo#columnByEmbeddedPath}）。
 */
public final class ColumnInfo {
    private final String fieldName;
    private final String columnName;
    private final boolean isId;
    private final boolean idTypeAuto;
    private final String fieldType;
    private final String fill;
    private final String comment;
    private final ColumnRole role;
    private final boolean insertable;
    private final boolean updatable;
    private final String generatedValueStrategy;
    private final boolean embedded;
    private final boolean converted;
    private final String embeddedParent;
    private final String embeddedPath;

    public ColumnInfo(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                      String fieldType, String fill, String comment, ColumnRole role,
                      boolean insertable, boolean updatable, String generatedValueStrategy,
                      boolean embedded, boolean converted, String embeddedParent, String embeddedPath) {
        this.fieldName = fieldName;
        this.columnName = columnName;
        this.isId = isId;
        this.idTypeAuto = idTypeAuto;
        this.fieldType = fieldType;
        this.fill = fill;
        this.comment = comment;
        this.role = role;
        this.insertable = insertable;
        this.updatable = updatable;
        this.generatedValueStrategy = generatedValueStrategy;
        this.embedded = embedded;
        this.converted = converted;
        this.embeddedParent = embeddedParent;
        this.embeddedPath = embeddedPath;
    }

    public ColumnInfo(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                      String fieldType, String fill, String comment, ColumnRole role,
                      boolean insertable, boolean updatable, String generatedValueStrategy,
                      boolean embedded, boolean converted, String embeddedParent) {
        this(fieldName, columnName, isId, idTypeAuto, fieldType, fill, comment, role,
                insertable, updatable, generatedValueStrategy, embedded, converted, embeddedParent, null);
    }

    public ColumnInfo(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                      String fieldType, String fill, String comment, ColumnRole role,
                      boolean insertable, boolean updatable, String generatedValueStrategy,
                      boolean embedded, boolean converted) {
        this(fieldName, columnName, isId, idTypeAuto, fieldType, fill, comment, role,
                insertable, updatable, generatedValueStrategy, embedded, converted, null);
    }

    public ColumnInfo(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                      String fieldType, String fill, String comment, ColumnRole role) {
        this(fieldName, columnName, isId, idTypeAuto, fieldType, fill, comment, role,
                true, true, null, false, false, null);
    }

    public ColumnInfo(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                      String fieldType, String fill, String comment) {
        this(fieldName, columnName, isId, idTypeAuto, fieldType, fill, comment, ColumnRole.BUSINESS);
    }

    public ColumnInfo(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                      String fieldType, String fill) {
        this(fieldName, columnName, isId, idTypeAuto, fieldType, fill, null, ColumnRole.BUSINESS);
    }

    public ColumnInfo(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                      String fieldType) {
        this(fieldName, columnName, isId, idTypeAuto, fieldType, null, null, ColumnRole.BUSINESS);
    }

    public ColumnInfo(String fieldName, String columnName, boolean isId, boolean idTypeAuto) {
        this(fieldName, columnName, isId, idTypeAuto, null, null, null, ColumnRole.BUSINESS);
    }

    public ColumnInfo(String fieldName, String columnName, boolean isId) {
        this(fieldName, columnName, isId, false, null, null, null, ColumnRole.BUSINESS);
    }

    public ColumnInfo(String fieldName, String columnName) {
        this(fieldName, columnName, false, false, null, null, null, ColumnRole.BUSINESS);
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getColumnName() {
        return columnName;
    }

    public boolean isId() {
        return isId;
    }

    public boolean isIdTypeAuto() {
        return idTypeAuto;
    }

    public String getFieldType() {
        return fieldType;
    }

    public String getFill() {
        return fill;
    }

    public String getComment() {
        return comment;
    }

    public ColumnRole getRole() {
        return role;
    }

    public boolean isInsertable() {
        return insertable;
    }

    public boolean isUpdatable() {
        return updatable;
    }

    public String getGeneratedValueStrategy() {
        return generatedValueStrategy;
    }

    public boolean isEmbedded() {
        return embedded;
    }

    public boolean isConverted() {
        return converted;
    }

    /** 展平本列的嵌入对象字段名（{@code @Embedded} 展平时写入，非嵌入列为 null）。 */
    public String getEmbeddedParent() {
        return embeddedParent;
    }

    /** 展平本列的完整嵌入段驼峰路径（实体根起算，各级嵌入字段名首字母大写；非嵌入列为 null）。 */
    public String getEmbeddedPath() {
        return embeddedPath;
    }

    public ColumnInfo copy(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                           String fieldType, String fill, String comment, ColumnRole role,
                           boolean insertable, boolean updatable, String generatedValueStrategy,
                           boolean embedded, boolean converted, String embeddedParent, String embeddedPath) {
        return new ColumnInfo(fieldName, columnName, isId, idTypeAuto, fieldType, fill, comment, role,
                insertable, updatable, generatedValueStrategy, embedded, converted, embeddedParent, embeddedPath);
    }

    public ColumnInfo copy(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                           String fieldType, String fill, String comment, ColumnRole role,
                           boolean insertable, boolean updatable, String generatedValueStrategy,
                           boolean embedded, boolean converted, String embeddedParent) {
        return new ColumnInfo(fieldName, columnName, isId, idTypeAuto, fieldType, fill, comment, role,
                insertable, updatable, generatedValueStrategy, embedded, converted, embeddedParent, embeddedPath);
    }

    public ColumnInfo copy(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                           String fieldType, String fill, String comment, ColumnRole role) {
        return new ColumnInfo(fieldName, columnName, isId, idTypeAuto, fieldType, fill, comment, role,
                insertable, updatable, generatedValueStrategy, embedded, converted, embeddedParent, embeddedPath);
    }

    public ColumnInfo copy(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                           String fieldType, String fill, String comment, ColumnRole role,
                           boolean insertable, boolean updatable, String generatedValueStrategy,
                           boolean embedded, boolean converted) {
        return new ColumnInfo(fieldName, columnName, isId, idTypeAuto, fieldType, fill, comment, role,
                insertable, updatable, generatedValueStrategy, embedded, converted, embeddedParent, embeddedPath);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ColumnInfo)) {
            return false;
        }
        ColumnInfo that = (ColumnInfo) o;
        return isId == that.isId
                && idTypeAuto == that.idTypeAuto
                && insertable == that.insertable
                && updatable == that.updatable
                && embedded == that.embedded
                && converted == that.converted
                && Objects.equals(fieldName, that.fieldName)
                && Objects.equals(columnName, that.columnName)
                && Objects.equals(fieldType, that.fieldType)
                && Objects.equals(fill, that.fill)
                && Objects.equals(comment, that.comment)
                && Objects.equals(generatedValueStrategy, that.generatedValueStrategy)
                && Objects.equals(embeddedParent, that.embeddedParent)
                && Objects.equals(embeddedPath, that.embeddedPath)
                && role == that.role;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, columnName, isId, idTypeAuto, fieldType, fill, comment, role,
                insertable, updatable, generatedValueStrategy, embedded, converted, embeddedParent, embeddedPath);
    }

    @Override
    public String toString() {
        return "ColumnInfo(fieldName=" + fieldName + ", columnName=" + columnName + ", isId=" + isId
                + ", idTypeAuto=" + idTypeAuto + ", fieldType=" + fieldType + ", fill=" + fill
                + ", comment=" + comment + ", role=" + role + ", insertable=" + insertable
                + ", updatable=" + updatable + ", generatedValueStrategy=" + generatedValueStrategy
                + ", embedded=" + embedded + ", converted=" + converted
                + ", embeddedParent=" + embeddedParent + ", embeddedPath=" + embeddedPath + ")";
    }
}
