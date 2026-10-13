package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.xml.ParamKind

/**
 * T-XmlGap W4/W5：MyBatis mapper XML 补全的**硬编码表**（纯数据 + 纯函数，可表驱动测试）。
 *
 * 用户铁律：方法名/枚举只看本地 jar。本文件每张表都在下方标注**核对命令与出处**，
 * 清单来自实际读取结果而非记忆或竞品转述：
 *
 * - `#{}` 结构属性键：[PARAM_ATTRIBUTE_KEYS] —— MyBatis 3.5.19 `SqlSourceBuilder.buildParameterMapping`
 *   实际消费的 key 白名单（sources jar 逐项读，见常量注释）。
 * - jdbcType 值：[JDBC_TYPE_VALUES] —— `org.apache.ibatis.type.JdbcType` 枚举常量全量
 *   （`javap -cp mybatis-3.5.19.jar org.apache.ibatis.type.JdbcType`）。
 * - mode 值：[PARAM_MODE_VALUES] —— `org.apache.ibatis.mapping.ParameterMode`
 *   （`javap` 实证 IN/OUT/INOUT）。
 * - statementType 值：[STATEMENT_TYPE_VALUES] —— `org.apache.ibatis.mapping.StatementType`
 *   （`javap` 实证 STATEMENT/PREPARED/CALLABLE）。
 * - resultSetType 值：[RESULT_SET_TYPE_VALUES] —— `org.apache.ibatis.mapping.ResultSetType`（`javap`）。
 * - fetchType 值：[FETCH_TYPE_VALUES] —— `org.apache.ibatis.mapping.FetchType`（`javap`）。
 * - 标签属性名表 [TAG_ATTRIBUTES] —— MyBatis 3.5.19 自带 `mybatis-3-mapper.dtd`
 *   （`unzip -p mybatis-3.5.19.jar org/apache/ibatis/builder/xml/mybatis-3-mapper.dtd`），逐 ATTLIST 抄录。
 *
 * **已知与计划的偏差（如实登记，不冒充实测）**：
 *  1. 计划 §六 W4 写「16 项 jdbcType」——该 16 项是竞品 `view.c.d` 内部表，内部调研报告未复现其内容。
 *     本轮按调研报告 §3.0 第 2 条与计划 §五 前提 8 的口径改为**照 MyBatis 自己的
 *     `org.apache.ibatis.type.JdbcType` 全枚举**（41 项，本地 jar 可逐项复核），而非竞品的 16 项子集。
 *  2. 单字母捷径表 [SINGLE_LETTER_KEYWORDS] 与函数表 [SQL_FUNCTIONS] 的具体名单，调研报告只给了
 *     「27 键」「19 项」的数量，未复现内容——本轮按 `SqlLexer.KEYWORDS` 首字母分组整理
 *     （24 键）与通用 SQL 函数整理（19 项），**未照抄竞品原表**（原表内容不可得）。
 *  3. 运算符表 [OGNL_OPERATORS] 含 OGNL 原生 `in`/`not in`；竞品另有一个 `nin` **live template**
 *     （展开为 `not in {...}`），`nin` 不是 OGNL 运算符——本轮**不产 `nin`**（避免建议不存在的语法）。
 */
internal object MyBatisXmlCompletionData {

    // ------------------------------------------------------------------
    // `#{}` 结构属性（W4-1）
    // ------------------------------------------------------------------

    /**
     * `#{}` 内 `,` 之后的属性键白名单（7 项）。
     *
     * 核对命令（MyBatis 3.5.19 sources jar，`SqlSourceBuilder.buildParameterMapping`）：
     * ```
     * unzip -p mybatis-3.5.19-sources.jar org/apache/ibatis/builder/SqlSourceBuilder.java | grep -n '"javaType"\|"jdbcType"\|"mode"\|"numericScale"\|"resultMap"\|"typeHandler"\|"jdbcTypeName"'
     * ```
     * 实证消费的键 = javaType / jdbcType / mode / numericScale / resultMap / typeHandler / jdbcTypeName
     * （另有首 token 的 `property` 与 `expression`，非 `key=value` 形态，不入本表）。
     * 与调研报告 §3.2「`#{}` 属性键（7 项）」逐项一致。
     */
    val PARAM_ATTRIBUTE_KEYS: List<String> = listOf(
        "javaType", "jdbcType", "typeHandler", "resultMap", "mode", "numericScale", "jdbcTypeName",
    )

    /** `#{}` mode 值（`org.apache.ibatis.mapping.ParameterMode`，javap 实证）。 */
    val PARAM_MODE_VALUES: List<String> = listOf("IN", "OUT", "INOUT")

    /**
     * jdbcType 值（`org.apache.ibatis.type.JdbcType` 枚举常量全量，41 项）。
     *
     * 核对命令：`javap -cp mybatis-3.5.19.jar org.apache.ibatis.type.JdbcType`。
     * **注意**：是 MyBatis 自己的枚举，**不是** JDK 的 `java.sql.JDBCType`（调研报告 §3.0 第 2 条勘误）。
     */
    val JDBC_TYPE_VALUES: List<String> = listOf(
        "ARRAY", "BIT", "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "FLOAT", "REAL", "DOUBLE",
        "NUMERIC", "DECIMAL", "CHAR", "VARCHAR", "LONGVARCHAR", "DATE", "TIME", "TIMESTAMP",
        "BINARY", "VARBINARY", "LONGVARBINARY", "NULL", "OTHER", "BLOB", "CLOB", "BOOLEAN",
        "CURSOR", "UNDEFINED", "NVARCHAR", "NCHAR", "NCLOB", "STRUCT", "JAVA_OBJECT", "DISTINCT",
        "REF", "DATALINK", "ROWID", "LONGNVARCHAR", "SQLXML", "DATETIMEOFFSET",
        "TIME_WITH_TIMEZONE", "TIMESTAMP_WITH_TIMEZONE",
    )

    /**
     * `#{}` 结构属性键 `key` 对应的**有界值候选**。
     * `jdbcType` → 枚举；`mode` → 三值；其余键（javaType/typeHandler/resultMap/numericScale/jdbcTypeName）
     * 无有界候选 → 空（宁缺勿错，不给空串项）。
     */
    fun paramAttributeValues(key: String): List<String> = when (key) {
        "jdbcType" -> JDBC_TYPE_VALUES
        "mode" -> PARAM_MODE_VALUES
        else -> emptyList()
    }

    /** `#{...,` 逗号后的属性键提示（`key=` 形态，含尾 `=`）。 */
    fun paramAttributeKeyHints(): List<String> = PARAM_ATTRIBUTE_KEYS.map { "$it=" }

    // ------------------------------------------------------------------
    // 属性值枚举（W4-2）
    // ------------------------------------------------------------------

    /** `statementType` 值（`org.apache.ibatis.mapping.StatementType`，javap 实证）。 */
    val STATEMENT_TYPE_VALUES: List<String> = listOf("PREPARED", "STATEMENT", "CALLABLE")

    /** `resultSetType` 值（`org.apache.ibatis.mapping.ResultSetType`，javap 实证；DTD 同形）。 */
    val RESULT_SET_TYPE_VALUES: List<String> = listOf("FORWARD_ONLY", "SCROLL_INSENSITIVE", "SCROLL_SENSITIVE", "DEFAULT")

    /** `fetchType` 值（`org.apache.ibatis.mapping.FetchType`；DTD 限 (lazy|eager)）。 */
    val FETCH_TYPE_VALUES: List<String> = listOf("lazy", "eager")

    /** `<selectKey order>` 值（DTD `(BEFORE|AFTER)`）。 */
    val ORDER_VALUES: List<String> = listOf("BEFORE", "AFTER")

    /** 布尔属性值（DTD `(true|false)`）。 */
    val BOOLEAN_VALUES: List<String> = listOf("true", "false")

    /**
     * 布尔型属性名集合（DTD `(true|false)` 形态；`nullable` 来自 foreach ATTLIST）。
     * 命中即给 [BOOLEAN_VALUES]。`timeout`/`fetchSize`/`numericScale`/`resultSets` 等
     * 无合理候选的属性**不在本表**（明确不产）。
     */
    val BOOLEAN_ATTRIBUTES: Set<String> = setOf(
        "useGeneratedKeys", "flushCache", "useCache", "resultOrdered", "affectData",
        "autoMapping", "nullable", "readOnly", "blocking",
    )

    /**
     * 类名候选属性（W4-2）：`resultType`/`type`/`ofType`/`parameterType`/`typeHandler`/`javaType`。
     * 候选来源由 [XmlCompletionSymbols.classCandidates] 提供（**有界**，禁全项目扫描）。
     */
    val CLASS_NAME_ATTRIBUTES: Set<String> = setOf(
        "resultType", "type", "ofType", "parameterType", "typeHandler", "javaType",
    )

    // ------------------------------------------------------------------
    // 标签 → 属性名（W4-3）
    // ------------------------------------------------------------------

    /**
     * 标签名 → 属性名清单（属性名位置补全）。
     *
     * 出处：MyBatis 3.5.19 自带 `mybatis-3-mapper.dtd` 的逐条 `<!ATTLIST>`（命令见类 KDoc）。
     * 动态标签（if/foreach/bind/include/trim/choose/when/sql/where/set）在此**重列**并补齐
     * DTD 有而我方旧表缺的两项（foreach 的 `index`/`nullable`）——旧表由
     * [SourceSqlSuggestionFactory.DYNAMIC_TAG_ATTRIBUTES] 保留（注解 `<script>` 链复用）。
     */
    val TAG_ATTRIBUTES: Map<String, List<String>> = mapOf(
        // --- 动态标签（DTD 动态段）---
        "if" to listOf("test"),
        "when" to listOf("test"),
        "otherwise" to emptyList(),
        "choose" to emptyList(),
        "where" to emptyList(),
        "set" to emptyList(),
        "foreach" to listOf("collection", "item", "index", "separator", "nullable", "open", "close"),
        "bind" to listOf("name", "value"),
        "include" to listOf("refid"),
        "trim" to listOf("prefix", "prefixOverrides", "suffix", "suffixOverrides"),
        "sql" to listOf("id", "lang", "databaseId"),
        // --- 语句标签（DTD select/insert/update/delete/selectKey）---
        "select" to listOf(
            "id", "parameterMap", "parameterType", "resultMap", "resultType", "resultSetType",
            "statementType", "fetchSize", "timeout", "flushCache", "useCache", "databaseId",
            "lang", "resultOrdered", "resultSets", "affectData",
        ),
        "insert" to listOf(
            "id", "parameterMap", "parameterType", "timeout", "flushCache", "statementType",
            "keyProperty", "useGeneratedKeys", "keyColumn", "databaseId", "lang",
        ),
        "update" to listOf(
            "id", "parameterMap", "parameterType", "timeout", "flushCache", "statementType",
            "keyProperty", "useGeneratedKeys", "keyColumn", "databaseId", "lang",
        ),
        "delete" to listOf(
            "id", "parameterMap", "parameterType", "timeout", "flushCache", "statementType",
            "databaseId", "lang",
        ),
        "selectKey" to listOf("resultType", "statementType", "keyProperty", "keyColumn", "order", "databaseId"),
        // --- resultMap 族（DTD resultMap/id/result/idArg/arg/collection/association/discriminator/case）---
        "resultMap" to listOf("id", "type", "extends", "autoMapping"),
        "id" to listOf("property", "javaType", "column", "jdbcType", "typeHandler"),
        "result" to listOf("property", "javaType", "column", "jdbcType", "typeHandler"),
        "idArg" to listOf("javaType", "column", "jdbcType", "typeHandler", "select", "resultMap", "name", "columnPrefix"),
        "arg" to listOf("javaType", "column", "jdbcType", "typeHandler", "select", "resultMap", "name", "columnPrefix"),
        "collection" to listOf(
            "property", "column", "javaType", "ofType", "jdbcType", "select", "resultMap",
            "typeHandler", "notNullColumn", "columnPrefix", "resultSet", "foreignColumn",
            "autoMapping", "fetchType",
        ),
        "association" to listOf(
            "property", "column", "javaType", "jdbcType", "select", "resultMap", "typeHandler",
            "notNullColumn", "columnPrefix", "resultSet", "foreignColumn", "autoMapping", "fetchType",
        ),
        "discriminator" to listOf("column", "javaType", "jdbcType", "typeHandler"),
        "case" to listOf("value", "resultMap", "resultType"),
        // --- 其余 mapper 标签（DTD 根段）---
        "mapper" to listOf("namespace"),
        "parameterMap" to listOf("id", "type"),
        "parameter" to listOf("property", "javaType", "jdbcType", "mode", "resultMap", "scale", "typeHandler"),
        "cache" to listOf("type", "eviction", "flushInterval", "size", "readOnly", "blocking"),
        "cache-ref" to listOf("namespace"),
        "property" to listOf("name", "value"),
        "typeAlias" to listOf("alias", "type"),
    )

    /** 语句标签名（id 属性值 → 接口方法名等分流的判据）。 */
    val STATEMENT_TAGS: Set<String> = setOf("select", "insert", "update", "delete")

    /** 标签的属性名清单（含动态标签与语句标签的统一查表入口）。 */
    fun tagAttributes(tagName: String): List<String> =
        TAG_ATTRIBUTES[tagName] ?: SourceSqlSuggestionFactory.DYNAMIC_TAG_ATTRIBUTES[tagName].orEmpty()

    // ------------------------------------------------------------------
    // SQL 函数表 + 单字母捷径（W5-9）
    // ------------------------------------------------------------------

    /**
     * SQL 函数表（19 项形态）。调研报告 §3.2 只给了「19 项」数量、未复现名单——
     * 本轮按通用 SQL 函数整理，与 [SqlLexer.KEYWORDS] 混排的九个函数名（COUNT/SUM/MAX/MIN/AVG/
     * COALESCE/NULLIF/CAST/CONVERT）**重复项由补全器去重**，不照抄竞品原表。
     */
    val SQL_FUNCTIONS: List<String> = listOf(
        "COUNT", "SUM", "AVG", "MAX", "MIN", "COALESCE", "NULLIF", "CAST", "CONVERT",
        "ABS", "ROUND", "FLOOR", "CEIL", "UPPER", "LOWER", "LENGTH", "TRIM", "SUBSTR", "NOW",
    )

    /**
     * 单字母关键字捷径（已输入恰好 1 个字符时命中）。
     *
     * 调研报告 §3.2 只给了「27 键」数量、未复现名单——本轮按 [SqlLexer.KEYWORDS] 首字母分组整理
     * （24 键，每键多值）。前缀过滤已覆盖「同首字母」场景，本表是**按位置无关的常用词集合**，
     * 与 [SqlLexer.KEYWORDS] 去重后并入候选。
     */
    val SINGLE_LETTER_KEYWORDS: Map<Char, List<String>> = mapOf(
        'a' to listOf("AND", "AS", "ALL", "ASC", "ADD", "ALTER", "AVG"),
        'b' to listOf("BY", "BETWEEN"),
        'c' to listOf("CASE", "CREATE", "COLUMN", "CONSTRAINT", "CROSS", "CONVERT", "COUNT", "CAST", "COALESCE"),
        'd' to listOf("DELETE", "DISTINCT", "DESC", "DEFAULT", "DROP"),
        'e' to listOf("ELSE", "END", "EXISTS"),
        'f' to listOf("FROM", "FULL", "FOREIGN"),
        'g' to listOf("GROUP"),
        'h' to listOf("HAVING"),
        'i' to listOf("INSERT", "INTO", "INNER", "IS", "IN", "INDEX"),
        'j' to listOf("JOIN"),
        'k' to listOf("KEY"),
        'l' to listOf("LEFT", "LIKE", "LIMIT"),
        'm' to listOf("MAX", "MIN"),
        'n' to listOf("NOT", "NULL", "NULLIF"),
        'o' to listOf("ORDER", "ON", "OUTER", "OR", "OFFSET"),
        'p' to listOf("PRIMARY"),
        'r' to listOf("RIGHT", "REFERENCES"),
        's' to listOf("SELECT", "SET", "SUM"),
        't' to listOf("TABLE", "THEN"),
        'u' to listOf("UPDATE", "UNION", "USING"),
        'v' to listOf("VALUES"),
        'w' to listOf("WHERE", "WHEN"),
    )

    /** 已输入恰好 1 个字符时的捷径候选（大小写不敏感；其余长度返回空）。 */
    fun singleLetterShortcuts(prefix: String): List<String> {
        if (prefix.length != 1) return emptyList()
        return SINGLE_LETTER_KEYWORDS[prefix[0].lowercaseChar()].orEmpty()
    }

    // ------------------------------------------------------------------
    // OGNL 运算符与智能片段（W5-5/W5-6）
    // ------------------------------------------------------------------

    /**
     * OGNL 运算符/字面量建议。`in`/`not in` 是 OGNL 原生运算符；
     * `and`/`or`/`not` 及 `eq`/`neq`/`lt`/`lte`/`gt`/`gte` 为 OGNL 关键字别名；
     * `true`/`false`/`null` 字面量。
     *
     * **不产 `nin`**：竞品的 `nin` 是 live template（展开 `not in {...}`），不是 OGNL 运算符
     * （见类 KDoc 偏差登记第 3 条）。
     */
    val OGNL_OPERATORS: List<String> = listOf(
        "and", "or", "not", "in", "not in", "eq", "neq", "lt", "lte", "gt", "gte",
        "true", "false", "null",
    )

    /**
     * 按可推断类型给 OGNL 智能片段（W5-5）：
     *  - TEXT → `x != null` / `x != ''` / `x.length != 0` / `'%' + x + '%'`；
     *  - COLLECTION → `x != null` / `x.size != 0`；
     *  - NUMBER/TEMPORAL/OBJECT → `x != null`；
     *  - 推不出类型（UNKNOWN/null）→ 通用五条（含全部形态）。
     *
     * @param variable 已输入的变量名（空串不产片段——没有可引用的对象）
     * @param kind     类型类别（[XmlParamTypeResolver] / [AnnotationSqlPreviewService] 推断；null=推不出）
     */
    fun ognlSnippets(variable: String, kind: ParamKind?): List<String> {
        if (variable.isEmpty()) return emptyList()
        val nullCheck = "$variable != null"
        val emptyString = "$variable != ''"
        val lengthCheck = "$variable.length != 0"
        val sizeCheck = "$variable.size != 0"
        val likePattern = "'%' + $variable + '%'"
        return when (kind) {
            ParamKind.TEXT -> listOf(nullCheck, emptyString, lengthCheck, likePattern)
            ParamKind.COLLECTION -> listOf(nullCheck, sizeCheck)
            ParamKind.NUMBER, ParamKind.TEMPORAL, ParamKind.OBJECT -> listOf(nullCheck)
            ParamKind.UNKNOWN, null -> listOf(nullCheck, emptyString, lengthCheck, sizeCheck, likePattern)
        }
    }
}
