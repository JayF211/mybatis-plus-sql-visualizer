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
    public record ClassMapping(
            /** 简单类名（如 "RuleTableItem"）。 */
            @NotNull String entityClassName,
            /** 全限定名（如 "com.vtradex.rule.server.model.rule.RuleTableItem"）。 */
            @NotNull String fullClassName,
            /** 数据库表名（如 "THORN_RULE_TABLE_ITEM"）。 */
            @NotNull String tableName,
            /** 普通属性映射列表。 */
            @NotNull List<PropertyMapping> properties,
            /** 关联映射列表（many-to-one / one-to-one）。 */
            @NotNull List<AssociationMapping> associations
    ) {
    }

    /** 属性映射（对应 &lt;id&gt; 或 &lt;property&gt; 元素）。 */
    public record PropertyMapping(
            /** Java 字段名（如 "lineNO"）。 */
            @NotNull String fieldName,
            /** 数据库列名（如 "LINE_NO"）。 */
            @NotNull String columnName,
            /** 是否主键（&lt;id&gt; 元素）。 */
            boolean isId
    ) {
    }

    /** 关联映射（对应 &lt;many-to-one&gt; 或 &lt;one-to-one&gt; 元素）。 */
    public record AssociationMapping(
            /** Java 字段名（如 "ruleTable"）。 */
            @NotNull String fieldName,
            /** 目标实体全限定名（如 "com.xxx.ThornRuleTable"）。 */
            @NotNull String targetClassName,
            /** 外键列名（从 &lt;column name="..."&gt; 提取）。 */
            @NotNull String joinColumn
    ) {
    }
}
