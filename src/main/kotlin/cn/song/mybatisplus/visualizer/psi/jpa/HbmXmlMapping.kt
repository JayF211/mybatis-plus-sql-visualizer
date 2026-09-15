package cn.song.mybatisplus.visualizer.psi.jpa

/**
 * Hibernate XML 映射文件（.hbm.xml）数据模型。
 *
 * 用于解析 `<class>`, `<id>`, `<property>`, `<many-to-one>`, `<one-to-one>` 等映射元素，
 * 提取实体类名、表名、字段列映射、关联关系等元数据。
 */
object HbmXmlMapping {

    /**
     * 类映射信息（对应 `<class>` 元素）。
     *
     * @param entityClassName 简单类名（如 "RuleTableItem"）
     * @param fullClassName   全限定名（如 "com.vtradex.rule.server.model.rule.RuleTableItem"）
     * @param tableName       数据库表名（如 "THORN_RULE_TABLE_ITEM"）
     * @param properties      普通属性映射列表
     * @param associations    关联映射列表（many-to-one / one-to-one）
     */
    data class ClassMapping(
        val entityClassName: String,
        val fullClassName: String,
        val tableName: String,
        val properties: List<PropertyMapping>,
        val associations: List<AssociationMapping>,
    )

    /**
     * 属性映射（对应 `<id>` 或 `<property>` 元素）。
     *
     * @param fieldName  Java 字段名（如 "lineNO"）
     * @param columnName 数据库列名（如 "LINE_NO"）
     * @param isId       是否主键（`<id>` 元素）
     */
    data class PropertyMapping(
        val fieldName: String,
        val columnName: String,
        val isId: Boolean = false,
    )

    /**
     * 关联映射（对应 `<many-to-one>` 或 `<one-to-one>` 元素）。
     *
     * @param fieldName       Java 字段名（如 "ruleTable"）
     * @param targetClassName 目标实体全限定名（如 "com.xxx.ThornRuleTable"）
     * @param joinColumn      外键列名（从 `<column name="...">` 提取）
     */
    data class AssociationMapping(
        val fieldName: String,
        val targetClassName: String,
        val joinColumn: String,
    )

    /**
     * hbm 命名查询（T-H1）：对应 `<hibernate-mapping>` 下的 `<query>` / `<sql-query>` 元素。
     *
     * @param name      完整查询名（`name` 属性，惯例 `<实体简单名>.<查询名>`）
     * @param kind      HQL（`<query>`）或原生 SQL（`<sql-query>`）
     * @param queryText 查询文本（直属 XmlText，CDATA 展开；`<sql-query>` 内
     *                  `<return-scalar>`/`<return>` 等子标签内容排除）
     */
    data class NamedQuery(
        val name: String,
        val kind: QueryKind,
        val queryText: String,
    )

    /** hbm 命名查询种类。 */
    enum class QueryKind { HQL, NATIVE }
}
