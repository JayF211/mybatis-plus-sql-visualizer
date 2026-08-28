package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.sql.DM_RESERVED
import cn.song.mybatisplus.visualizer.sql.DmDialect
import cn.song.mybatisplus.visualizer.sql.H2_RESERVED
import cn.song.mybatisplus.visualizer.sql.H2Dialect
import cn.song.mybatisplus.visualizer.sql.KINGBASE_RESERVED
import cn.song.mybatisplus.visualizer.sql.KingBaseDialect
import cn.song.mybatisplus.visualizer.sql.MARIA_RESERVED
import cn.song.mybatisplus.visualizer.sql.MYSQL_RESERVED
import cn.song.mybatisplus.visualizer.sql.MariaDbDialect
import cn.song.mybatisplus.visualizer.sql.PG_RESERVED
import cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3（Tier 3）：DM（达梦）/ KingBase（人大金仓）**官方文档黄金断言**（P-立项-05）。
 *
 * 两款国产商业库无公开容器镜像可自动化（真实环境测试后续进行），本测试把官方文档逐词核对的
 * 结论固化为黄金断言，防止未来改动悄悄破坏已验证行为：
 *
 *  - DM8：官方《DM8 SQL 语言使用手册》（eco.dameng.com/document/dm/zh-cn/pm/）
 *      - 分页文法（check-phrases.html 4.10）：`LIMIT M,N` / `LIMIT M OFFSET N` / `OFFSET..FETCH` 均
 *        为核心语法（COMPATIBLE_MODE=0 默认模式即可用）——插件取 `LIMIT [offset,] count` ✓
 *      - 附录 1 保留字（sql-appendix.html）：带 * 系统保留字全量 → [DM_RESERVED]（黄金清单逐词对齐）
 *      - FAQ（faq-sql-gramm.html）：仅双引号可屏蔽关键字/引用标识符；CASE_SENSITIVE=Y 默认下
 *        双引号内大小写敏感
 *      - UPSERT：官方仅 MERGE INTO（社区文章《浅谈达梦与 MySQL 的差异》明确不支持
 *        ON DUPLICATE KEY UPDATE；词表无 CONFLICT）→ 降级纯 INSERT ✓
 *  - KingbaseES V9：官方手册（bbs.kingbase.com.cn/kingbase-html/v9/）
 *      - 《SQL保留字和关键字》章节 22：分类「保留」的全量词 → [KINGBASE_RESERVED]（黄金清单对齐）
 *      - 《SQL 语句:INSERT》（help.kingbase.com.cn V8 SQL_Statements_9.html）：on_conflict_clause
 *        官方文法 + 官方示例 `ON CONFLICT (did) DO UPDATE SET dname = EXCLUDED.dname` ✓
 *      - 分页：PG 兼容 `LIMIT count OFFSET offset` ✓
 *
 * 另含 H2 / MariaDB / PG 保留字集合的官方对齐断言（P-立项-05 修正的回归防护）。
 */
class DialectDocGoldenTest {

    // ------------------------------------------------------------------
    // DM（达梦）：官方附录 1 保留字黄金清单（带 * 系统保留字，逐词核对于 2026-08-28）
    // ------------------------------------------------------------------

    /** 黄金清单与 [DM_RESERVED] 独立维护——任何一侧改动不一致立即红。 */
    private val dmOfficialReserved: Set<String> = setOf(
        "absolute", "abstract", "add", "admin", "all", "alter", "and", "any", "array",
        "arraylen", "as", "asc", "assign", "audit", "auto_increment",
        "begin", "between", "bigdatediff", "binary", "bool", "both", "break", "bstring", "by", "byte",
        "call", "case", "cast", "char", "check", "cluster", "clusterbtr", "collation", "column",
        "comment", "commit", "commitwork", "connect", "connect_by_root", "const", "constraint",
        "contains", "context", "continue", "convert", "create", "cross", "crypto", "cube",
        "current", "current_date", "current_timestamp",
        "dateadd", "datediff", "datepart", "dbtimezone", "declare", "decode", "default", "delete",
        "desc", "dimension", "disable", "diskspace", "distance", "distinct", "distributed", "do",
        "domain", "double", "drop",
        "efconstruction", "else", "elseif", "elsif", "enable", "end", "equ", "exchange", "exec",
        "execute", "exists", "exit", "explain", "extern", "extract",
        "fetch", "final", "finally", "first", "flashback", "float", "for", "foreign", "from",
        "full", "fully", "function",
        "get", "goto", "grant", "group", "grouping",
        "having",
        "identity", "if", "ifnull", "immediate", "in", "index", "inner", "inline", "insert",
        "int", "intersect", "interval", "into", "is",
        "join", "json_arrayagg", "json_table", "json_transform",
        "keep",
        "large", "leading", "left", "less", "lexer", "like", "list", "lnnvl", "localtimestamp", "loop",
        "member", "minus", "model", "multiset",
        "natural", "new", "next", "nocopy", "nocycle", "nosort", "not", "null",
        "object", "of", "on", "optimize", "or", "order", "out", "over", "overlay", "override",
        "parameters", "partition", "pendant", "percent", "pipe", "primary", "print", "prior",
        "private", "privileges", "procedure", "protected", "public",
        "raise", "record", "ref", "reference", "references", "referencing",
        "regexp_split_to_table", "relative", "replicate", "return", "returning", "reverse",
        "revoke", "right", "rollback", "rollup", "row", "rownum", "rows",
        "savepoint", "sbyte", "schema", "section", "select", "sessiontimezone", "set", "sets",
        "short", "some", "sql_calc_found_rows", "static", "struct", "subpartition", "switch",
        "synonym", "sysdate", "systimestamp",
        "table", "than", "timestampadd", "timestampdiff", "to", "top", "to_vector", "trailing",
        "treat", "trigger", "trim", "truncate", "try", "typedef", "typeof",
        "uint", "ulong", "union", "unique", "until", "update", "user", "ushort", "using",
        "values", "varray", "verify", "versions", "view", "virtual", "void",
        "when", "whenever", "where", "while", "window", "with", "within", "without",
        "xml", "xmlagg", "xmlattributes", "xmlcast", "xmlelement", "xmlnamespaces", "xmlparse",
        "xmlquery", "xmlserialize", "xmltable",
    )

    @Test
    fun `DM保留字集合与官方附录黄金清单一致`() {
        val missingInImpl = dmOfficialReserved - DM_RESERVED
        val extraInImpl = DM_RESERVED - dmOfficialReserved
        assertTrue("DM_RESERVED 缺失官方保留字: $missingInImpl", missingInImpl.isEmpty())
        assertTrue("DM_RESERVED 含官方附录之外的词: $extraInImpl", extraInImpl.isEmpty())
    }

    @Test
    fun `DM方言行为锚点_官方文档结论`() {
        // 分页：官方文法 LIMIT M,N（官方示例 LIMIT 4,3）与 LIMIT N
        assertEquals("LIMIT 10", DmDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 5, 10", DmDialect.renderLimit(LimitClause(offset = 5, count = 10)))
        // 引用：仅双引号（官方 FAQ：双引号屏蔽关键字；反引号需 8.1.3.37+ 且 MySQL 兼容模式，不采用）
        assertEquals("\"order\"", DmDialect.quoteIdentifier("order"))
        // 保留字判定（官方附录实证的双向差异）：
        //   DM 保留而原 Oracle 集合没有 → 必须包裹（漏包即语法错误）
        assertEquals("\"top\"", DmDialect.quoteIfKeyword("top"))
        assertEquals("\"domain\"", DmDialect.quoteIfKeyword("domain"))
        assertEquals("\"section\"", DmDialect.quoteIfKeyword("section"))
        assertEquals("\"identity\"", DmDialect.quoteIfKeyword("identity"))
        assertEquals("\"int\"", DmDialect.quoteIfKeyword("int"))
        //   Oracle 保留而 DM 仅关键字（NUMBER/SIZE/LEVEL/RAW/DATE）→ 不包裹
        //   （DM CASE_SENSITIVE=Y 下错包 + 小写原文 = 双引号大小写敏感失配风险）
        assertEquals("number", DmDialect.quoteIfKeyword("number"))
        assertEquals("size", DmDialect.quoteIfKeyword("size"))
        assertEquals("level", DmDialect.quoteIfKeyword("level"))
        // UPSERT：官方仅 MERGE INTO 复杂形态 → 降级纯 INSERT
        assertNull(DmDialect.renderUpsert("id", listOf("name")))
        assertNull(DmDialect.renderUpsertStatement("t", listOf("id", "name"), "id"))
    }

    // ------------------------------------------------------------------
    // KingBase（人大金仓）：官方 V9 保留字黄金清单 + 行为锚点
    // ------------------------------------------------------------------

    /** 黄金清单：V9 手册《SQL保留字和关键字》章节 22 分类「保留」的词（逐词核对于 2026-08-28）。 */
    private val kingbaseOfficialReserved: Set<String> = setOf(
        "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric",
        "authorization", "auto_increment", "binary", "both", "case", "cast", "check",
        "collate", "collation", "column", "concurrently", "connect", "connect_by_iscycle",
        "connect_by_isleaf", "connect_by_root", "constraint", "create", "cross",
        "current_catalog", "current_date", "current_role", "current_schema", "current_time",
        "current_timestamp", "current_user", "default", "deferrable", "desc", "distinct",
        "do", "else", "end", "except", "false", "fetch", "for", "foreign", "freeze",
        "from", "full", "grant", "group", "having", "ilike", "in", "initially", "inner",
        "intersect", "into", "is", "isnull", "join", "lateral", "leading", "left", "like",
        "limit", "localtime", "localtimestamp", "minus", "natural", "nocycle", "not",
        "notnull", "null", "nvl", "nvl2", "offset", "on", "online", "only", "or", "order",
        "outer", "overlaps", "placing", "primary", "references", "return", "returning",
        "right", "rowid", "rownum", "select", "session_user", "similar", "some",
        "symmetric", "synonym", "sys_connect_by_path", "systimestamp", "table",
        "tablesample", "then", "to", "top", "trailing", "true", "union", "unique", "user",
        "using", "variadic", "verbose", "when", "where", "window", "with",
    )

    @Test
    fun `KingBase保留字集合与官方V9黄金清单一致`() {
        val missingInImpl = kingbaseOfficialReserved - KINGBASE_RESERVED
        val extraInImpl = KINGBASE_RESERVED - kingbaseOfficialReserved
        assertTrue("KINGBASE_RESERVED 缺失官方保留字: $missingInImpl", missingInImpl.isEmpty())
        assertTrue("KINGBASE_RESERVED 含官方清单之外的词: $extraInImpl", extraInImpl.isEmpty())
    }

    @Test
    fun `KingBase方言行为锚点_官方文档结论`() {
        // 分页：PG 兼容形态
        assertEquals("LIMIT 10", KingBaseDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 10 OFFSET 5", KingBaseDialect.renderLimit(LimitClause(offset = 5, count = 10)))
        // 引用：双引号（PG 内核）
        assertEquals("\"order\"", KingBaseDialect.quoteIdentifier("order"))
        // 保留字判定（官方清单实证的金仓特有保留字——原 ≈PG 集合漏包项）
        assertEquals("\"rowid\"", KingBaseDialect.quoteIfKeyword("rowid"))
        assertEquals("\"rownum\"", KingBaseDialect.quoteIfKeyword("rownum"))
        assertEquals("\"top\"", KingBaseDialect.quoteIfKeyword("top"))
        assertEquals("\"binary\"", KingBaseDialect.quoteIfKeyword("binary"))
        assertEquals("\"minus\"", KingBaseDialect.quoteIfKeyword("minus"))
        //   常见业务词在金仓非保留（裸用合法）→ 不包裹
        assertEquals("value", KingBaseDialect.quoteIfKeyword("value"))
        assertEquals("name", KingBaseDialect.quoteIfKeyword("name"))
        assertEquals("status", KingBaseDialect.quoteIfKeyword("status"))
        // UPSERT：官方 INSERT 文法 on_conflict_clause + 官方示例 EXCLUDED 用法
        assertEquals(
            "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name",
            KingBaseDialect.renderUpsert("id", listOf("name")),
        )
        assertNull(KingBaseDialect.renderUpsertStatement("t", listOf("id", "name"), "id"))
    }

    // ------------------------------------------------------------------
    // H2 / MariaDB / PG：P-立项-05 修正的回归防护（引擎实测 + 官方关键字表）
    // ------------------------------------------------------------------

    @Test
    fun `H2保留字集合_引擎实测项回归防护`() {
        // H2 2.3.232 引擎实测裸用即语法错误的词（原 ≈PG 集合全部漏包）——必须恒在集合内
        listOf(
            "key", "value", "values", "day", "year", "interval", "row", "if", "minus",
            "qualify", "rownum", "top", "unknown", "uescape", "current_catalog",
            "current_schema", "user", "order", "group", "limit", "offset", "table",
        ).forEach { assertTrue("H2 保留字缺失: $it", it in H2_RESERVED) }
        // 常见业务词 H2 非保留（引擎实测裸用合法）→ 不包裹
        assertEquals("name", H2Dialect.quoteIfKeyword("name"))
        assertEquals("status", H2Dialect.quoteIfKeyword("status"))
        // UPSERT 整语句形态（H2 原生 MERGE..KEY；ON CONFLICT 在 H2 任何模式语法错误）
        assertNull(H2Dialect.renderUpsert("id", listOf("name")))
    }

    @Test
    fun `MariaDB保留字集合_官方差量回归防护`() {
        // MariaDB 官方保留字含 MySQL 8.0 非保留词（原 = MySQL 集合漏包项）
        listOf(
            "except", "intersect", "binary", "offset", "returning", "current_role",
            "cursor", "row_number", "stats_persistent",
        ).forEach { assertTrue("MariaDB 保留字缺失: $it", it in MARIA_RESERVED) }
        // MySQL 8.0 保留字基线全部保留（复用关系不回退）
        assertTrue(MYSQL_RESERVED.all { it in MARIA_RESERVED })
        // UPSERT 同 MySQL 形态
        assertEquals(
            "ON DUPLICATE KEY UPDATE name = VALUES(name)",
            MariaDbDialect.renderUpsert("id", listOf("name")),
        )
    }

    @Test
    fun `PG保留字集合_官方补齐项回归防护`() {
        // PG 官方附录「reserved (can be function or type name)」类别（原集合漏包项）
        listOf(
            "authorization", "binary", "concurrently", "freeze", "isnull", "notnull", "similar",
        ).forEach { assertTrue("PG 保留字缺失: $it", it in PG_RESERVED) }
        // UPSERT：PG 9.5+ 官方文法（真 PG 容器验证在 Docker 测试）
        assertEquals(
            "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name",
            PostgreSQLDialect.renderUpsert("id", listOf("name")),
        )
    }
}
