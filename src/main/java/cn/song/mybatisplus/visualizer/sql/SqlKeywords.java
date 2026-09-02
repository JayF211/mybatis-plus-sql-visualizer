package cn.song.mybatisplus.visualizer.sql;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 数据库保留字集合（T-M23.4）。
 *
 * <p>按方言判定（用户确认：数据库方言有多种——判定与符号都随方言走）：
 * 每方言一个独立保留字集合（小写），{@link Dialect#quoteIfKeyword} 只查<b>本方言</b>集合——
 * 例如 {@code number} 是 Oracle 保留字但 MySQL 合法：MySQL 场景不包裹、Oracle 场景包裹。
 *
 * <p>集合构成（尽量完整，来源为各数据库官方关键字文档）：
 *  - {@link #MYSQL_RESERVED}：MySQL 8.0 官方保留字全量
 *  - {@link #SQLITE_RESERVED}：SQLite 官方关键字全量
 *  - {@link #PG_RESERVED}：PostgreSQL 官方保留字全量
 *  - {@link #ORACLE_RESERVED}：Oracle 官方保留字全量
 *  - {@link #SQLSERVER_RESERVED}：SQL Server 官方保留字全量
 *  - {@link #H2_RESERVED}：H2 2.x 官方关键字全量
 *  - {@link #DM_RESERVED}：DM8 官方附录 1「带 * 系统保留字」全量
 *  - {@link #KINGBASE_RESERVED}：KingbaseES V9 官方保留字全量
 *  - {@link #MARIA_RESERVED}：MariaDB 官方保留字全量
 *
 * <p>判定：大小写不敏感。纯函数，无 IDE 依赖，可独立单测。
 */
public final class SqlKeywords {

    private SqlKeywords() {}

    /** MySQL 8.0 官方保留字全量。 */
    public static final Set<String> MYSQL_RESERVED = Set.of(
            "accessible", "add", "all", "alter", "analyze", "and", "as", "asc", "asensitive",
            "before", "between", "bigint", "binary", "blob", "both", "by", "call", "cascade",
            "case", "change", "char", "character", "check", "collate", "column", "condition",
            "constraint", "continue", "convert", "create", "cross", "cube", "cume_dist",
            "current_date", "current_time", "current_timestamp", "current_user", "cursor",
            "database", "databases", "day_hour", "day_microsecond", "day_minute", "day_second",
            "dec", "decimal", "declare", "default", "delayed", "delete", "dense_rank", "desc",
            "describe", "deterministic", "distinct", "distinctrow", "div", "double", "drop",
            "dual", "each", "else", "elseif", "empty", "enclosed", "escaped", "except",
            "exists", "exit", "explain", "false", "fetch", "first_value", "float", "float4",
            "float8", "for", "force", "foreign", "from", "fulltext", "function", "generated",
            "get", "grant", "group", "grouping", "groups", "having", "high_priority",
            "hour_microsecond", "hour_minute", "hour_second", "if", "ignore", "in", "index",
            "infile", "inner", "inout", "insensitive", "insert", "int", "int1", "int2",
            "int3", "int4", "int8", "integer", "interval", "into", "io_after_gtids",
            "io_before_gtids", "is", "iterate", "join", "json_table", "key", "keys", "kill",
            "lag", "last_value", "lateral", "lead", "leading", "leave", "left", "like",
            "limit", "linear", "lines", "load", "localtime", "localtimestamp", "lock",
            "long", "longblob", "longtext", "loop", "low_priority", "master_bind",
            "master_ssl_verify_server_cert", "match", "maxvalue", "mediumblob", "mediumint",
            "mediumtext", "middleint", "minute_microsecond", "minute_second", "mod",
            "modifies", "natural", "not", "no_write_to_binlog", "nth_value", "ntile", "null",
            "numeric", "of", "on", "optimize", "optimizer_costs", "option", "optionally",
            "or", "order", "out", "outer", "outfile", "over", "partition", "percent_rank",
            "precision", "primary", "procedure", "purge", "range", "rank", "read", "reads",
            "read_write", "real", "recursive", "references", "regexp", "release", "rename",
            "repeat", "replace", "require", "resignal", "restrict", "return", "revoke",
            "right", "rlike", "row", "rows", "row_number", "schema", "schemas",
            "second_microsecond", "select", "sensitive", "separator", "set", "show",
            "signal", "smallint", "spatial", "specific", "sql", "sqlexception", "sqlstate",
            "sqlwarning", "sql_big_result", "sql_calc_found_rows", "sql_small_result", "ssl",
            "starting", "stored", "straight_join", "system", "table", "terminated", "then",
            "tinyblob", "tinyint", "tinytext", "to", "trailing", "trigger", "true", "undo",
            "union", "unique", "unlock", "unsigned", "update", "usage", "use", "user",
            "using",
            "utc_date", "utc_time", "utc_timestamp", "values", "varbinary", "varchar",
            "varcharacter", "varying", "virtual", "when", "where", "while", "window",
            "with", "write", "xor", "year_month", "zerofill"
    );

    /** SQLite 官方关键字全量。 */
    public static final Set<String> SQLITE_RESERVED = Set.of(
            "abort", "action", "add", "after", "all", "alter", "always", "analyze", "and",
            "as", "asc", "attach", "autoincrement", "before", "begin", "between", "by",
            "cascade", "case", "cast", "check", "collate", "column", "commit", "conflict",
            "constraint", "create", "cross", "current", "current_date", "current_time",
            "current_timestamp", "database", "default", "deferrable", "deferred", "delete",
            "desc", "detach", "distinct", "do", "drop", "each", "else", "end", "escape",
            "except", "exclude", "exclusive", "exists", "explain", "fail", "filter", "first",
            "following", "for", "foreign", "from", "full", "generated", "glob", "group",
            "groups", "having", "if", "ignore", "immediate", "in", "index", "indexed",
            "initially", "inner", "insert", "instead", "intersect", "into", "is", "isnull",
            "join", "key", "last", "left", "like", "limit", "match", "materialized",
            "natural", "no", "not", "nothing", "notnull", "null", "nulls", "of", "offset",
            "on", "or", "order", "others", "outer", "over", "partition", "plan", "pragma",
            "preceding", "primary", "query", "raise", "range", "recursive", "references",
            "regexp", "reindex", "release", "rename", "replace", "restrict", "returning",
            "right", "rollback", "row", "rows", "savepoint", "select", "set", "table",
            "temp", "temporary", "then", "ties", "to", "transaction", "trigger",
            "unbounded", "union", "unique", "update", "using", "vacuum", "values", "view",
            "virtual", "when", "where", "window", "with", "without"
    );

    /** PostgreSQL 官方保留字全量。P-立项-05：补齐「reserved (can be function or type name)」7 词。 */
    public static final Set<String> PG_RESERVED = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric",
            "authorization", "binary", "both", "case", "cast", "check", "collate", "column",
            "concurrently", "constraint", "create", "current_catalog", "current_date",
            "current_role", "current_time", "current_timestamp", "current_user", "default",
            "deferrable", "desc", "distinct", "do", "else", "end", "except", "false",
            "fetch", "for", "foreign", "freeze", "from", "grant", "group", "having", "in",
            "initially", "intersect", "into", "isnull", "lateral", "leading", "limit",
            "localtime", "localtimestamp", "not", "notnull", "null", "offset", "on", "only",
            "or", "order", "placing", "primary", "references", "returning", "select",
            "session_user", "similar", "some", "symmetric", "table", "then", "to",
            "trailing", "true", "union", "unique", "user", "using", "variadic", "when",
            "where", "window", "with"
    );

    /** Oracle 官方保留字全量。 */
    public static final Set<String> ORACLE_RESERVED = Set.of(
            "access", "add", "all", "alter", "and", "any", "as", "asc", "audit", "between",
            "by", "char", "check", "cluster", "column", "comment", "compress", "connect",
            "create", "current", "date", "decimal", "default", "delete", "desc", "distinct",
            "drop", "else", "exclusive", "exists", "file", "float", "for", "from", "grant",
            "group", "having", "identified", "immediate", "in", "increment", "index",
            "initial", "insert", "integer", "intersect", "into", "is", "level", "like",
            "lock", "long", "maxextents", "minus", "mlslabel", "mode", "modify", "noaudit",
            "nocompress", "not", "nowait", "null", "number", "of", "offline", "on",
            "online", "option", "or", "order", "pctfree", "prior", "privileges", "public",
            "raw", "rename", "resource", "revoke", "row", "rowid", "rownum", "rows",
            "select", "session", "set", "share", "size", "smallint", "start", "successful",
            "synonym", "sysdate", "table", "then", "to", "trigger", "uid", "union",
            "unique", "update", "user", "validate", "values", "varchar", "varchar2",
            "view", "whenever", "where", "with"
    );

    /** SQL Server 官方保留字全量。 */
    public static final Set<String> SQLSERVER_RESERVED = Set.of(
            "add", "all", "alter", "and", "any", "as", "asc", "authorization", "backup",
            "begin", "between", "break", "browse", "bulk", "by", "cascade", "case",
            "check", "checkpoint", "close", "clustered", "coalesce", "collate", "column",
            "commit", "compute", "constraint", "contains", "containstable", "continue",
            "convert", "create", "cross", "current", "current_date", "current_time",
            "current_timestamp", "current_user", "cursor", "database", "dbcc", "deallocate",
            "declare", "default", "delete", "deny", "desc", "disk", "distinct",
            "distributed", "double", "drop", "dump", "else", "end", "errlvl", "escape",
            "except", "exec", "execute", "exists", "exit", "external", "fetch", "file",
            "fillfactor", "for", "foreign", "freetext", "freetexttable", "from", "full",
            "function", "goto", "grant", "group", "having", "holdlock", "identity",
            "identity_insert", "identitycol", "if", "in", "index", "inner", "insert",
            "intersect", "into", "is", "join", "key", "kill", "left", "like", "lineno",
            "load", "merge", "national", "nocheck", "nonclustered", "not", "null",
            "nullif", "of", "off", "offsets", "on", "open", "opendatasource", "openquery",
            "openrowset", "openxml", "option", "or", "order", "outer", "over", "percent",
            "pivot", "plan", "precision", "primary", "print", "proc", "procedure",
            "public", "raiserror", "read", "readtext", "reconfigure", "references",
            "replication", "restore", "restrict", "return", "revert", "revoke", "right",
            "rollback", "rowcount", "rowguidcol", "rule", "save", "schema", "select",
            "session_user", "set", "setuser", "shutdown", "some", "statistics",
            "system_user", "table", "tablesample", "textsize", "then", "to", "top",
            "tran", "transaction", "trigger", "truncate", "try_convert", "tsequal",
            "union", "unique", "unpivot", "update", "updatetext", "use", "user", "values",
            "varying", "view", "waitfor", "when", "where", "while", "with", "writetext"
    );

    /** H2 2.x 官方关键字全量（P-立项-05 逐词核对 + 引擎实测）。 */
    public static final Set<String> H2_RESERVED = Set.of(
            "all", "and", "any", "array", "as", "asymmetric", "authorization", "between",
            "both", "case", "cast", "check", "constraint", "cross", "current_catalog",
            "current_date", "current_path", "current_role", "current_schema", "current_time",
            "current_timestamp", "current_user", "day", "default", "distinct", "else", "end",
            "except", "exists", "false", "fetch", "for", "foreign", "from", "full", "grant",
            "group", "groups", "having", "hour", "if", "ilike", "in", "inner", "intersect",
            "interval", "is", "join", "key", "leading", "left", "like", "limit", "localtime",
            "localtimestamp", "minus", "minute", "month", "natural", "not", "null", "offset",
            "on", "or", "order", "over", "partition", "primary", "qualify", "range",
            "regexp", "right", "row", "rownum", "rows", "second", "select", "session_user",
            "set", "some", "symmetric", "system_user", "table", "to", "top", "trailing",
            "true", "uescape", "union", "unique", "unknown", "user", "using", "value",
            "values", "when", "where", "window", "with", "year"
    );

    /** DM8 官方附录 1「带 * 系统保留字」全量（P-立项-05 逐词核对）。 */
    public static final Set<String> DM_RESERVED = Set.of(
            "absolute", "abstract", "add", "admin", "all", "alter", "and", "any", "array",
            "arraylen", "as", "asc", "assign", "audit", "auto_increment",
            "begin", "between", "bigdatediff", "binary", "bool", "both", "break", "bstring",
            "by", "byte",
            "call", "case", "cast", "char", "check", "cluster", "clusterbtr", "collation",
            "column", "comment", "commit", "commitwork", "connect", "connect_by_root",
            "const", "constraint", "contains", "context", "continue", "convert", "create",
            "cross", "crypto", "cube", "current", "current_date", "current_timestamp",
            "dateadd", "datediff", "datepart", "dbtimezone", "declare", "decode", "default",
            "delete", "desc", "dimension", "disable", "diskspace", "distance", "distinct",
            "distributed", "do", "domain", "double", "drop",
            "efconstruction", "else", "elseif", "elsif", "enable", "end", "equ", "exchange",
            "exec", "execute", "exists", "exit", "explain", "extern", "extract",
            "fetch", "final", "finally", "first", "flashback", "float", "for", "foreign",
            "from", "full", "fully", "function",
            "get", "goto", "grant", "group", "grouping",
            "having",
            "identity", "if", "ifnull", "immediate", "in", "index", "inner", "inline",
            "insert", "int", "intersect", "interval", "into", "is",
            "join", "json_arrayagg", "json_table", "json_transform",
            "keep",
            "large", "leading", "left", "less", "lexer", "like", "list", "lnnvl",
            "localtimestamp", "loop",
            "member", "minus", "model", "multiset",
            "natural", "new", "next", "nocopy", "nocycle", "nosort", "not", "null",
            "object", "of", "on", "optimize", "or", "order", "out", "over", "overlay",
            "override",
            "parameters", "partition", "pendant", "percent", "pipe", "primary", "print",
            "prior", "private", "privileges", "procedure", "protected", "public",
            "raise", "record", "ref", "reference", "references", "referencing",
            "regexp_split_to_table", "relative", "replicate", "return", "returning",
            "reverse", "revoke", "right", "rollback", "rollup", "row", "rownum", "rows",
            "savepoint", "sbyte", "schema", "section", "select", "sessiontimezone", "set",
            "sets", "short", "some", "sql_calc_found_rows", "static", "struct",
            "subpartition", "switch", "synonym", "sysdate", "systimestamp",
            "table", "than", "timestampadd", "timestampdiff", "to", "top", "to_vector",
            "trailing", "treat", "trigger", "trim", "truncate", "try", "typedef", "typeof",
            "uint", "ulong", "union", "unique", "until", "update", "user", "ushort", "using",
            "values", "varray", "verify", "versions", "view", "virtual", "void",
            "when", "whenever", "where", "while", "window", "with", "within", "without",
            "xml", "xmlagg", "xmlattributes", "xmlcast", "xmlelement", "xmlnamespaces",
            "xmlparse", "xmlquery", "xmlserialize", "xmltable"
    );

    /** KingbaseES V9 官方保留字全量（P-立项-05 逐词核对；PG 兼容模式）。 */
    public static final Set<String> KINGBASE_RESERVED = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric",
            "authorization", "auto_increment", "binary", "both", "case", "cast", "check",
            "collate", "collation", "column", "concurrently", "connect",
            "connect_by_iscycle", "connect_by_isleaf", "connect_by_root", "constraint",
            "create", "cross", "current_catalog", "current_date", "current_role",
            "current_schema", "current_time", "current_timestamp", "current_user",
            "default", "deferrable", "desc", "distinct", "do", "else", "end", "except",
            "false", "fetch", "for", "foreign", "freeze", "from", "full", "grant", "group",
            "having", "ilike", "in", "initially", "inner", "intersect", "into", "is",
            "isnull", "join", "lateral", "leading", "left", "like", "limit", "localtime",
            "localtimestamp", "minus", "natural", "nocycle", "not", "notnull", "null",
            "nvl", "nvl2", "offset", "on", "online", "only", "or", "order", "outer",
            "overlaps", "placing", "primary", "references", "return", "returning", "right",
            "rowid", "rownum", "select", "session_user", "similar", "some", "symmetric",
            "synonym", "sys_connect_by_path", "systimestamp", "table", "tablesample",
            "then", "to", "top", "trailing", "true", "union", "unique", "user", "using",
            "variadic", "verbose", "when", "where", "window", "with"
    );

    /**
     * MariaDB 官方保留字全量（P-立项-05 逐词核对）。
     *
     * <p>= MySQL 8.0 保留字基线（MariaDB 同标保留的部分）+ MariaDB 特有保留字。
     */
    public static final Set<String> MARIA_RESERVED = union(MYSQL_RESERVED, Set.of(
            "binary", "current_role", "cursor", "delete_domain_id", "do_domain_ids",
            "except", "general", "ignore_domain_ids", "ignore_server_ids", "intersect",
            "master_heartbeat_period", "offset", "page_checksum", "parse_vcol_expr",
            "portion", "ref_system_id", "returning", "row_number", "slow",
            "stats_auto_recalc", "stats_persistent", "stats_sample_pages", "vector"
    ));

    /** 集合并集（不可变）。 */
    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> result = new LinkedHashSet<>(a);
        result.addAll(b);
        return Collections.unmodifiableSet(result);
    }
}
