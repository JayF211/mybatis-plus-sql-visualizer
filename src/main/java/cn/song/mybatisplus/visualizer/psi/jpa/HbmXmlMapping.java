package cn.song.mybatisplus.visualizer.psi.jpa;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Hibernate XML 映射文件（.hbm.xml）数据模型。
 *
 * 用于解析 &lt;class&gt;, &lt;id&gt;, &lt;property&gt;, &lt;many-to-one&gt;, &lt;one-to-one&gt; 等映射元素，
 * 提取实体类名、表名、字段列映射、关联关系等元数据。
 */
public final class HbmXmlMapping {

    /** 单例实例（Kotlin object 语义兼容，供外部 Xxx.method() 调用路由）。 */
    public static final HbmXmlMapping INSTANCE = new HbmXmlMapping();

    private HbmXmlMapping() {
    }

    /** 类映射信息（对应 &lt;class&gt; 元素）。 */
    public static final class ClassMapping {
    /** 简单类名（如 "RuleTableItem"）。 */
    private final @NotNull String entityClassName;
    /** 全限定名（如 "com.vtradex.rule.server.model.rule.RuleTableItem"）。 */
    private final @NotNull String fullClassName;
    /** 数据库表名（如 "THORN_RULE_TABLE_ITEM"）。 */
    private final @NotNull String tableName;
    /** 普通属性映射列表。 */
    private final @NotNull List<PropertyMapping> properties;
    /** 关联映射列表（many-to-one / one-to-one）。 */
    private final @NotNull List<AssociationMapping> associations;

    public ClassMapping(@NotNull String entityClassName, @NotNull String fullClassName, @NotNull String tableName, @NotNull List<PropertyMapping> properties, @NotNull List<AssociationMapping> associations) {
        this.entityClassName = entityClassName;
        this.fullClassName = fullClassName;
        this.tableName = tableName;
        this.properties = properties;
        this.associations = associations;
    }

    public @NotNull String getEntityClassName() { return entityClassName; }
    public @NotNull String entityClassName() { return entityClassName; }
    public @NotNull String getFullClassName() { return fullClassName; }
    public @NotNull String fullClassName() { return fullClassName; }
    public @NotNull String getTableName() { return tableName; }
    public @NotNull String tableName() { return tableName; }
    public @NotNull List<PropertyMapping> getProperties() { return properties; }
    public @NotNull List<PropertyMapping> properties() { return properties; }
    public @NotNull List<AssociationMapping> getAssociations() { return associations; }
    public @NotNull List<AssociationMapping> associations() { return associations; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClassMapping)) return false;
        ClassMapping that = (ClassMapping) o;
        return java.util.Objects.equals(this.entityClassName, that.entityClassName) && java.util.Objects.equals(this.fullClassName, that.fullClassName) && java.util.Objects.equals(this.tableName, that.tableName) && java.util.Objects.equals(this.properties, that.properties) && java.util.Objects.equals(this.associations, that.associations);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(entityClassName, fullClassName, tableName, properties, associations);
    }

    @Override
    public String toString() {
        return "ClassMapping[" + "entityClassName=" + entityClassName + ", " + "fullClassName=" + fullClassName + ", " + "tableName=" + tableName + ", " + "properties=" + properties + ", " + "associations=" + associations + "]";
    }
}

    /** 属性映射（对应 &lt;id&gt; 或 &lt;property&gt; 元素）。 */
    public static final class PropertyMapping {
    /** Java 字段名（如 "lineNO"）。 */
    private final @NotNull String fieldName;
    /** 数据库列名（如 "LINE_NO"）。 */
    private final @NotNull String columnName;
    /** 是否主键（&lt;id&gt; 元素）。 */
    private final boolean id;

    public PropertyMapping(@NotNull String fieldName, @NotNull String columnName, boolean isId) {
        this.fieldName = fieldName;
        this.columnName = columnName;
        this.id = isId;
    }

    public @NotNull String getFieldName() { return fieldName; }
    public @NotNull String fieldName() { return fieldName; }
    public @NotNull String getColumnName() { return columnName; }
    public @NotNull String columnName() { return columnName; }
    public boolean isId() { return id; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PropertyMapping)) return false;
        PropertyMapping that = (PropertyMapping) o;
        return java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.columnName, that.columnName) && java.util.Objects.equals(this.id, that.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldName, columnName, id);
    }

    @Override
    public String toString() {
        return "PropertyMapping[" + "fieldName=" + fieldName + ", " + "columnName=" + columnName + ", " + "isId=" + id + "]";
    }
}

    /** 关联映射（对应 &lt;many-to-one&gt; 或 &lt;one-to-one&gt; 元素）。 */
    public static final class AssociationMapping {
    /** Java 字段名（如 "ruleTable"）。 */
    private final @NotNull String fieldName;
    /** 目标实体全限定名（如 "com.xxx.ThornRuleTable"）。 */
    private final @NotNull String targetClassName;
    /** 外键列名（从 &lt;column name="..."&gt; 提取）。 */
    private final @NotNull String joinColumn;

    public AssociationMapping(@NotNull String fieldName, @NotNull String targetClassName, @NotNull String joinColumn) {
        this.fieldName = fieldName;
        this.targetClassName = targetClassName;
        this.joinColumn = joinColumn;
    }

    public @NotNull String getFieldName() { return fieldName; }
    public @NotNull String fieldName() { return fieldName; }
    public @NotNull String getTargetClassName() { return targetClassName; }
    public @NotNull String targetClassName() { return targetClassName; }
    public @NotNull String getJoinColumn() { return joinColumn; }
    public @NotNull String joinColumn() { return joinColumn; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AssociationMapping)) return false;
        AssociationMapping that = (AssociationMapping) o;
        return java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.targetClassName, that.targetClassName) && java.util.Objects.equals(this.joinColumn, that.joinColumn);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldName, targetClassName, joinColumn);
    }

    @Override
    public String toString() {
        return "AssociationMapping[" + "fieldName=" + fieldName + ", " + "targetClassName=" + targetClassName + ", " + "joinColumn=" + joinColumn + "]";
    }
}

    /**
     * hbm 命名查询（T-H1）：对应 {@code <hibernate-mapping>} 下的 {@code <query>} / {@code <sql-query>} 元素。
     */
    public static final class NamedQuery {
    /** 完整查询名（`name` 属性，惯例 `<实体简单名>.<查询名>`）。 */
    private final @NotNull String name;
    /** HQL（{@code <query>}）或原生 SQL（{@code <sql-query>}）。 */
    private final @NotNull QueryKind kind;
    /**
     * 查询文本（直属 XmlText，CDATA 展开；{@code <sql-query>} 内
     * {@code <return-scalar>}/{@code <return>} 等子标签内容排除）。
     */
    private final @NotNull String queryText;

    public NamedQuery(@NotNull String name, @NotNull QueryKind kind, @NotNull String queryText) {
        this.name = name;
        this.kind = kind;
        this.queryText = queryText;
    }

    public @NotNull String getName() { return name; }
    public @NotNull String name() { return name; }
    public @NotNull QueryKind getKind() { return kind; }
    public @NotNull QueryKind kind() { return kind; }
    public @NotNull String getQueryText() { return queryText; }
    public @NotNull String queryText() { return queryText; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NamedQuery)) return false;
        NamedQuery that = (NamedQuery) o;
        return java.util.Objects.equals(this.name, that.name) && java.util.Objects.equals(this.kind, that.kind) && java.util.Objects.equals(this.queryText, that.queryText);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, kind, queryText);
    }

    @Override
    public String toString() {
        return "NamedQuery[" + "name=" + name + ", " + "kind=" + kind + ", " + "queryText=" + queryText + "]";
    }
}

    /** hbm 命名查询种类。 */
    public enum QueryKind { HQL, NATIVE }
}
