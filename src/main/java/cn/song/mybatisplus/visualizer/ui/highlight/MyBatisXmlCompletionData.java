// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/MyBatisXmlCompletionData.kt
package cn.song.mybatisplus.visualizer.ui.highlight;

import cn.song.mybatisplus.visualizer.xml.ParamKind;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T-XmlGap W4/W5：MyBatis mapper XML 补全的<b>硬编码表</b>（纯数据 + 纯函数，可表驱动测试）。
 *
 * <p>用户铁律：方法名/枚举只看本地 jar。本文件每张表都在下方标注<b>核对命令与出处</b>，
 * 清单来自实际读取结果而非记忆或竞品转述：</p>
 * <ul>
 *   <li>{@code #{}} 结构属性键：{@link #PARAM_ATTRIBUTE_KEYS} —— MyBatis 3.5.19
 *       {@code SqlSourceBuilder.buildParameterMapping} 实际消费的 key 白名单（sources jar 逐项读）；</li>
 *   <li>jdbcType 值：{@link #JDBC_TYPE_VALUES} —— {@code org.apache.ibatis.type.JdbcType} 枚举常量全量；</li>
 *   <li>mode 值：{@link #PARAM_MODE_VALUES} —— {@code org.apache.ibatis.mapping.ParameterMode}；</li>
 *   <li>statementType 值：{@link #STATEMENT_TYPE_VALUES} —— {@code org.apache.ibatis.mapping.StatementType}；</li>
 *   <li>resultSetType 值：{@link #RESULT_SET_TYPE_VALUES} —— {@code org.apache.ibatis.mapping.ResultSetType}；</li>
 *   <li>fetchType 值：{@link #FETCH_TYPE_VALUES} —— {@code org.apache.ibatis.mapping.FetchType}；</li>
 *   <li>标签属性名表 {@link #TAG_ATTRIBUTES} —— MyBatis 3.5.19 自带 {@code mybatis-3-mapper.dtd}，逐 ATTLIST 抄录。</li>
 * </ul>
 *
 * <p><b>已知与计划的偏差（如实登记，不冒充实测）</b>：</p>
 * <ol>
 *   <li>计划 §六 W4 写「16 项 jdbcType」——该 16 项是竞品 {@code view.c.d} 内部表，内部调研报告未复现其内容。
 *       本轮按调研报告 §3.0 第 2 条与计划 §五 前提 8 的口径改为<b>照 MyBatis 自己的
 *       {@code org.apache.ibatis.type.JdbcType} 全枚举</b>（41 项，本地 jar 可逐项复核），而非竞品的 16 项子集。</li>
 *   <li>单字母捷径表 {@link #SINGLE_LETTER_KEYWORDS} 与函数表 {@link #SQL_FUNCTIONS} 的具体名单，调研报告只给了
 *       「27 键」「19 项」的数量，未复现内容——本轮按 {@code SqlLexer.KEYWORDS} 首字母分组整理
 *       （24 键）与通用 SQL 函数整理（19 项），<b>未照抄竞品原表</b>（原表内容不可得）。</li>
 *   <li>运算符表 {@link #OGNL_OPERATORS} 含 OGNL 原生 {@code in}/{@code not in}；竞品另有一个 {@code nin}
 *       live template（展开为 {@code not in {...}}），{@code nin} 不是 OGNL 运算符——本轮<b>不产 {@code nin}</b>
 *       （避免建议不存在的语法）。</li>
 * </ol>
 */
public final class MyBatisXmlCompletionData {

    private MyBatisXmlCompletionData() {
    }

    // ------------------------------------------------------------------
    // `#{}` 结构属性（W4-1）
    // ------------------------------------------------------------------

    /**
     * {@code #{}} 内 `,` 之后的属性键白名单（7 项）。
     *
     * <p>核对命令（MyBatis 3.5.19 sources jar，{@code SqlSourceBuilder.buildParameterMapping}）：
     * {@code unzip -p mybatis-3.5.19-sources.jar org/apache/ibatis/builder/SqlSourceBuilder.java | grep -n '"javaType"|...'}。
     * 实证消费的键 = javaType / jdbcType / mode / numericScale / resultMap / typeHandler / jdbcTypeName
     * （另有首 token 的 {@code property} 与 {@code expression}，非 {@code key=value} 形态，不入本表）。
     * 与调研报告 §3.2「{@code #{}} 属性键（7 项）」逐项一致。</p>
     */
    public static final List<String> PARAM_ATTRIBUTE_KEYS = List.of(
            "javaType", "jdbcType", "typeHandler", "resultMap", "mode", "numericScale", "jdbcTypeName");

    /** {@code #{}} mode 值（{@code org.apache.ibatis.mapping.ParameterMode}，javap 实证）。 */
    public static final List<String> PARAM_MODE_VALUES = List.of("IN", "OUT", "INOUT");

    /**
     * jdbcType 值（{@code org.apache.ibatis.type.JdbcType} 枚举常量全量，41 项）。
     *
     * <p>核对命令：{@code javap -cp mybatis-3.5.19.jar org.apache.ibatis.type.JdbcType}。
     * <b>注意</b>：是 MyBatis 自己的枚举，<b>不是</b> JDK 的 {@code java.sql.JDBCType}
     * （调研报告 §3.0 第 2 条勘误）。</p>
     */
    public static final List<String> JDBC_TYPE_VALUES = List.of(
            "ARRAY", "BIT", "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "FLOAT", "REAL", "DOUBLE",
            "NUMERIC", "DECIMAL", "CHAR", "VARCHAR", "LONGVARCHAR", "DATE", "TIME", "TIMESTAMP",
            "BINARY", "VARBINARY", "LONGVARBINARY", "NULL", "OTHER", "BLOB", "CLOB", "BOOLEAN",
            "CURSOR", "UNDEFINED", "NVARCHAR", "NCHAR", "NCLOB", "STRUCT", "JAVA_OBJECT", "DISTINCT",
            "REF", "DATALINK", "ROWID", "LONGNVARCHAR", "SQLXML", "DATETIMEOFFSET",
            "TIME_WITH_TIMEZONE", "TIMESTAMP_WITH_TIMEZONE");

    /**
     * {@code #{}} 结构属性键 {@code key} 对应的<b>有界值候选</b>。
     * jdbcType → 枚举；mode → 三值；其余键（javaType/typeHandler/resultMap/numericScale/jdbcTypeName）
     * 无有界候选 → 空（宁缺勿错，不给空串项）。
     */
    public static List<String> paramAttributeValues(String key) {
        switch (key) {
            case "jdbcType":
                return JDBC_TYPE_VALUES;
            case "mode":
                return PARAM_MODE_VALUES;
            default:
                return List.of();
        }
    }

    /** {@code #{...,} 逗号后的属性键提示（{@code key=} 形态，含尾 {@code =}）。 */
    public static List<String> paramAttributeKeyHints() {
        return PARAM_ATTRIBUTE_KEYS.stream().map(key -> key + "=").toList();
    }

    // ------------------------------------------------------------------
    // 属性值枚举（W4-2）
    // ------------------------------------------------------------------

    /** {@code statementType} 值（{@code org.apache.ibatis.mapping.StatementType}，javap 实证）。 */
    public static final List<String> STATEMENT_TYPE_VALUES = List.of("PREPARED", "STATEMENT", "CALLABLE");

    /** {@code resultSetType} 值（{@code org.apache.ibatis.mapping.ResultSetType}，javap 实证；DTD 同形）。 */
    public static final List<String> RESULT_SET_TYPE_VALUES =
            List.of("FORWARD_ONLY", "SCROLL_INSENSITIVE", "SCROLL_SENSITIVE", "DEFAULT");

    /** {@code fetchType} 值（{@code org.apache.ibatis.mapping.FetchType}；DTD 限 (lazy|eager)）。 */
    public static final List<String> FETCH_TYPE_VALUES = List.of("lazy", "eager");

    /** {@code <selectKey order>} 值（DTD {@code (BEFORE|AFTER)}）。 */
    public static final List<String> ORDER_VALUES = List.of("BEFORE", "AFTER");

    /** 布尔属性值（DTD {@code (true|false)}）。 */
    public static final List<String> BOOLEAN_VALUES = List.of("true", "false");

    /**
     * 布尔型属性名集合（DTD {@code (true|false)} 形态；{@code nullable} 来自 foreach ATTLIST）。
     * 命中即给 {@link #BOOLEAN_VALUES}。{@code timeout}/{@code fetchSize}/{@code numericScale}/{@code resultSets} 等
     * 无合理候选的属性<b>不在本表</b>（明确不产）。
     */
    public static final Set<String> BOOLEAN_ATTRIBUTES = Set.of(
            "useGeneratedKeys", "flushCache", "useCache", "resultOrdered", "affectData",
            "autoMapping", "nullable", "readOnly", "blocking");

    /**
     * 类名候选属性（W4-2）：{@code resultType}/{@code type}/{@code ofType}/{@code parameterType}/
     * {@code typeHandler}/{@code javaType}。候选来源由 {@code XmlCompletionSymbols.classCandidates} 提供
     * （<b>有界</b>，禁全项目扫描）。
     */
    public static final Set<String> CLASS_NAME_ATTRIBUTES = Set.of(
            "resultType", "type", "ofType", "parameterType", "typeHandler", "javaType");

    // ------------------------------------------------------------------
    // 标签 → 属性名（W4-3）
    // ------------------------------------------------------------------

    /**
     * 标签名 → 属性名清单（属性名位置补全）。
     *
     * <p>出处：MyBatis 3.5.19 自带 {@code mybatis-3-mapper.dtd} 的逐条 {@code <!ATTLIST>}（命令见类 KDoc）。
     * 动态标签（if/foreach/bind/include/trim/choose/when/sql/where/set）在此<b>重列</b>并补齐
     * DTD 有而我方旧表缺的两项（foreach 的 {@code index}/{@code nullable}）——旧表由
     * {@link SourceSqlSuggestionFactory#DYNAMIC_TAG_ATTRIBUTES} 保留（注解 {@code <script>} 链复用）。</p>
     */
    public static final Map<String, List<String>> TAG_ATTRIBUTES = Map.ofEntries(
            // --- 动态标签（DTD 动态段）---
            Map.entry("if", List.of("test")),
            Map.entry("when", List.of("test")),
            Map.entry("otherwise", List.of()),
            Map.entry("choose", List.of()),
            Map.entry("where", List.of()),
            Map.entry("set", List.of()),
            Map.entry("foreach", List.of("collection", "item", "index", "separator", "nullable", "open", "close")),
            Map.entry("bind", List.of("name", "value")),
            Map.entry("include", List.of("refid")),
            Map.entry("trim", List.of("prefix", "prefixOverrides", "suffix", "suffixOverrides")),
            Map.entry("sql", List.of("id", "lang", "databaseId")),
            // --- 语句标签（DTD select/insert/update/delete/selectKey）---
            Map.entry("select", List.of(
                    "id", "parameterMap", "parameterType", "resultMap", "resultType", "resultSetType",
                    "statementType", "fetchSize", "timeout", "flushCache", "useCache", "databaseId",
                    "lang", "resultOrdered", "resultSets", "affectData")),
            Map.entry("insert", List.of(
                    "id", "parameterMap", "parameterType", "timeout", "flushCache", "statementType",
                    "keyProperty", "useGeneratedKeys", "keyColumn", "databaseId", "lang")),
            Map.entry("update", List.of(
                    "id", "parameterMap", "parameterType", "timeout", "flushCache", "statementType",
                    "keyProperty", "useGeneratedKeys", "keyColumn", "databaseId", "lang")),
            Map.entry("delete", List.of(
                    "id", "parameterMap", "parameterType", "timeout", "flushCache", "statementType",
                    "databaseId", "lang")),
            Map.entry("selectKey", List.of("resultType", "statementType", "keyProperty", "keyColumn", "order", "databaseId")),
            // --- resultMap 族（DTD resultMap/id/result/idArg/arg/collection/association/discriminator/case）---
            Map.entry("resultMap", List.of("id", "type", "extends", "autoMapping")),
            Map.entry("id", List.of("property", "javaType", "column", "jdbcType", "typeHandler")),
            Map.entry("result", List.of("property", "javaType", "column", "jdbcType", "typeHandler")),
            Map.entry("idArg", List.of("javaType", "column", "jdbcType", "typeHandler", "select", "resultMap", "name", "columnPrefix")),
            Map.entry("arg", List.of("javaType", "column", "jdbcType", "typeHandler", "select", "resultMap", "name", "columnPrefix")),
            Map.entry("collection", List.of(
                    "property", "column", "javaType", "ofType", "jdbcType", "select", "resultMap",
                    "typeHandler", "notNullColumn", "columnPrefix", "resultSet", "foreignColumn",
                    "autoMapping", "fetchType")),
            Map.entry("association", List.of(
                    "property", "column", "javaType", "jdbcType", "select", "resultMap", "typeHandler",
                    "notNullColumn", "columnPrefix", "resultSet", "foreignColumn", "autoMapping", "fetchType")),
            Map.entry("discriminator", List.of("column", "javaType", "jdbcType", "typeHandler")),
            Map.entry("case", List.of("value", "resultMap", "resultType")),
            // --- 其余 mapper 标签（DTD 根段）---
            Map.entry("mapper", List.of("namespace")),
            Map.entry("parameterMap", List.of("id", "type")),
            Map.entry("parameter", List.of("property", "javaType", "jdbcType", "mode", "resultMap", "scale", "typeHandler")),
            Map.entry("cache", List.of("type", "eviction", "flushInterval", "size", "readOnly", "blocking")),
            Map.entry("cache-ref", List.of("namespace")),
            Map.entry("property", List.of("name", "value")),
            Map.entry("typeAlias", List.of("alias", "type")));

    /** 语句标签名（id 属性值 → 接口方法名等分流的判据）。 */
    public static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    /** 标签的属性名清单（含动态标签与语句标签的统一查表入口）。 */
    public static List<String> tagAttributes(String tagName) {
        List<String> own = TAG_ATTRIBUTES.get(tagName);
        if (own != null) return own;
        List<String> dynamic = SourceSqlSuggestionFactory.DYNAMIC_TAG_ATTRIBUTES.get(tagName);
        return dynamic != null ? dynamic : List.of();
    }

    // ------------------------------------------------------------------
    // SQL 函数表 + 单字母捷径（W5-9）
    // ------------------------------------------------------------------

    /**
     * SQL 函数表（19 项形态）。调研报告 §3.2 只给了「19 项」数量、未复现名单——
     * 本轮按通用 SQL 函数整理，与 {@code SqlLexer.KEYWORDS} 混排的九个函数名（COUNT/SUM/MAX/MIN/AVG/
     * COALESCE/NULLIF/CAST/CONVERT）<b>重复项由补全器去重</b>，不照抄竞品原表。
     */
    public static final List<String> SQL_FUNCTIONS = List.of(
            "COUNT", "SUM", "AVG", "MAX", "MIN", "COALESCE", "NULLIF", "CAST", "CONVERT",
            "ABS", "ROUND", "FLOOR", "CEIL", "UPPER", "LOWER", "LENGTH", "TRIM", "SUBSTR", "NOW");

    /**
     * 单字母关键字捷径（已输入恰好 1 个字符时命中）。
     *
     * <p>调研报告 §3.2 只给了「27 键」数量、未复现名单——本轮按 {@code SqlLexer.KEYWORDS} 首字母分组整理
     * （24 键，每键多值）。前缀过滤已覆盖「同首字母」场景，本表是<b>按位置无关的常用词集合</b>，
     * 与 {@code SqlLexer.KEYWORDS} 去重后并入候选。</p>
     */
    public static final Map<Character, List<String>> SINGLE_LETTER_KEYWORDS = Map.ofEntries(
            Map.entry('a', List.of("AND", "AS", "ALL", "ASC", "ADD", "ALTER", "AVG")),
            Map.entry('b', List.of("BY", "BETWEEN")),
            Map.entry('c', List.of("CASE", "CREATE", "COLUMN", "CONSTRAINT", "CROSS", "CONVERT", "COUNT", "CAST", "COALESCE")),
            Map.entry('d', List.of("DELETE", "DISTINCT", "DESC", "DEFAULT", "DROP")),
            Map.entry('e', List.of("ELSE", "END", "EXISTS")),
            Map.entry('f', List.of("FROM", "FULL", "FOREIGN")),
            Map.entry('g', List.of("GROUP")),
            Map.entry('h', List.of("HAVING")),
            Map.entry('i', List.of("INSERT", "INTO", "INNER", "IS", "IN", "INDEX")),
            Map.entry('j', List.of("JOIN")),
            Map.entry('k', List.of("KEY")),
            Map.entry('l', List.of("LEFT", "LIKE", "LIMIT")),
            Map.entry('m', List.of("MAX", "MIN")),
            Map.entry('n', List.of("NOT", "NULL", "NULLIF")),
            Map.entry('o', List.of("ORDER", "ON", "OUTER", "OR", "OFFSET")),
            Map.entry('p', List.of("PRIMARY")),
            Map.entry('r', List.of("RIGHT", "REFERENCES")),
            Map.entry('s', List.of("SELECT", "SET", "SUM")),
            Map.entry('t', List.of("TABLE", "THEN")),
            Map.entry('u', List.of("UPDATE", "UNION", "USING")),
            Map.entry('v', List.of("VALUES")),
            Map.entry('w', List.of("WHERE", "WHEN")));

    /** 已输入恰好 1 个字符时的捷径候选（大小写不敏感；其余长度返回空）。 */
    public static List<String> singleLetterShortcuts(String prefix) {
        if (prefix.length() != 1) return List.of();
        List<String> words = SINGLE_LETTER_KEYWORDS.get(Character.toLowerCase(prefix.charAt(0)));
        return words != null ? words : List.of();
    }

    // ------------------------------------------------------------------
    // OGNL 运算符与智能片段（W5-5/W5-6）
    // ------------------------------------------------------------------

    /**
     * OGNL 运算符/字面量建议。{@code in}/{@code not in} 是 OGNL 原生运算符；
     * {@code and}/{@code or}/{@code not} 及 {@code eq}/{@code neq}/{@code lt}/{@code lte}/{@code gt}/{@code gte}
     * 为 OGNL 关键字别名；{@code true}/{@code false}/{@code null} 字面量。
     *
     * <p><b>不产 {@code nin}</b>：竞品的 {@code nin} 是 live template（展开 {@code not in {...}}），
     * 不是 OGNL 运算符（见类 KDoc 偏差登记第 3 条）。</p>
     */
    public static final List<String> OGNL_OPERATORS = List.of(
            "and", "or", "not", "in", "not in", "eq", "neq", "lt", "lte", "gt", "gte",
            "true", "false", "null");

    /**
     * 按可推断类型给 OGNL 智能片段（W5-5）：
     * <ul>
     *   <li>TEXT → {@code x != null} / {@code x != ''} / {@code x.length != 0} / {@code '%' + x + '%'}；</li>
     *   <li>COLLECTION → {@code x != null} / {@code x.size != 0}；</li>
     *   <li>NUMBER/TEMPORAL/OBJECT → {@code x != null}；</li>
     *   <li>推不出类型（UNKNOWN/null）→ 通用五条（含全部形态）。</li>
     * </ul>
     *
     * @param variable 已输入的变量名（空串不产片段——没有可引用的对象）
     * @param kind     类型类别（XmlParamTypeResolver / AnnotationSqlPreviewService 推断；null=推不出）
     */
    public static List<String> ognlSnippets(String variable, ParamKind kind) {
        if (variable.isEmpty()) return List.of();
        String nullCheck = variable + " != null";
        String emptyString = variable + " != ''";
        String lengthCheck = variable + ".length != 0";
        String sizeCheck = variable + ".size != 0";
        String likePattern = "'%' + " + variable + " + '%'";
        if (kind == ParamKind.TEXT) {
            return List.of(nullCheck, emptyString, lengthCheck, likePattern);
        }
        if (kind == ParamKind.COLLECTION) {
            return List.of(nullCheck, sizeCheck);
        }
        if (kind == ParamKind.NUMBER || kind == ParamKind.TEMPORAL || kind == ParamKind.OBJECT) {
            return List.of(nullCheck);
        }
        return List.of(nullCheck, emptyString, lengthCheck, sizeCheck, likePattern);
    }
}
