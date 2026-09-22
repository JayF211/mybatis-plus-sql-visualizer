package cn.song.mybatisplus.visualizer.sql

/**
 * EXPLAIN 计划输出格式（T-ExplainChainHardening T-ECH-02 / O-48）。
 *
 * 引入原因（第十轮报告 §8 #51）：此前「格式」概念不存在——包装语句与输出形态都硬编码在
 * 各方言的 `renderExplainStatement` 覆写里，消费方（[cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor]）
 * 只能按**方言名**猜格式，于是 PG/KingBase 的 `FORMAT JSON` 被当成文本树解析 → 建议永不触发
 * （真缺陷，T-ECH-03 修复）。
 *
 * 取值按「计划文本的物理形态」分类，不按数据库品牌——同一品牌不同格式（如 PG 的
 * `FORMAT TEXT` 与 `FORMAT JSON`）分属不同取值，消费方按形态选解析器：
 *  - [TABLE]：行列二维表（MySQL 家族 `EXPLAIN` 的原生表格行）；
 *  - [TEXT]：单列文本计划树/文本行（DM8 `#NSET2`、H2 `PLAN FOR`、SQLite `QUERY PLAN`）；
 *  - [JSON]：单列 JSON 文本（PG/KingBase `FORMAT JSON`）；
 *  - [XML]：单列 XML 文本（SQL Server `SET SHOWPLAN_XML`——本插件**未接入**，仅占位枚举值，
 *    避免将来接入时再改枚举）。
 */
enum class ExplainOutputFormat {
    TABLE,
    TEXT,
    JSON,
    XML,
}

/**
 * EXPLAIN 支持面与输出格式的**显式单一事实源**（T-ECH-02 / O-48）。
 *
 * 此前支持面散在 7 处 `Dialect.renderExplainStatement` 覆写里（默认 null），Oracle/SQL Server
 * 靠「没覆写」隐式表达不支持——「没写」与「故意不接」在代码上同形，新增方言时也无从判定
 * 该不该有 EXPLAIN。本表把两件事一次说清：
 *  1. **支持面**：`template == null` 即不接入（调用方降级提示），Oracle/SQL Server **显式在册**；
 *  2. **输出格式**：[outputFormat]，供建议器等消费方按形态选解析器。
 *
 * 键是 [Dialect.name]（设置下拉显示名）——**不是**品牌短名：MCP 链的
 * [cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor.advise] 收到的就是显示名。
 * 查找大小写不敏感（兼容调用方传 `mysql` 这类小写形态）。
 *
 * 渲染产物**逐字节不变**是硬约束：模板里的 [SQL_PLACEHOLDER] 替换即旧覆写里的字符串拼接。
 * 守卫用例（`DialectExplainTest`）逐方言断言「本表渲染结果 == `Dialect.renderExplainStatement`
 * 结果」并钉住字面量，防止将来有人加回覆写后与表漂移。
 */
object ExplainSupport {

    /** 模板里的语句占位符（替换为字面量，非正则——SQL 里的 `$`/`\` 不受影响）。 */
    const val SQL_PLACEHOLDER = "{sql}"

    /**
     * 单方言支持项。
     *
     * @param dialectName 方言显示名（[Dialect.name]，表的键）
     * @param template    包装模板（含 [SQL_PLACEHOLDER]）；null = 本方言不接入 EXPLAIN
     * @param outputFormat 计划输出格式；null 与 `template == null` 同时成立（不支持即无格式）
     * @param evidence    支持形态的依据出处（真机实证来源 / 官方文法），随表留证便于复核
     */
    data class Entry(
        val dialectName: String,
        val template: String?,
        val outputFormat: ExplainOutputFormat?,
        val evidence: String,
    ) {
        /** 本方言是否接入 EXPLAIN。 */
        val supported: Boolean get() = template != null
    }

    /**
     * 全方言支持表（**覆盖 [DialectRegistry.dialects] 全量**——守卫用例断言不漏项）。
     *
     * 形态依据（沿用 Dialect 既有 KDoc 的真机实证留证，未新增未经验证的形态）：
     *  - MySQL 5.7/8.4、MariaDB：`EXPLAIN <sql>`，原生表格行（T-ExplainFirstNative 对齐 IDEA
     *    原生 `MysqlBaseRawPlanData`——`FORMAT=JSON` 属自创形态且全树无渲染器消费）；
     *  - PostgreSQL 16：`EXPLAIN (FORMAT JSON, VERBOSE)`；
     *  - KingbaseES V8/V9：`EXPLAIN (FORMAT JSON)`（PG 兼容）；
     *  - DM8：`EXPLAIN <sql>`，返回 `#NSET2` 计划文本；
     *  - H2：`EXPLAIN PLAN FOR`；SQLite：`EXPLAIN QUERY PLAN`（引擎级单测覆盖）；
     *  - Oracle（需两步链查 PLAN_TABLE）与 SQL Server（需会话级 `SET SHOWPLAN_XML`）
     *    **不接入**——各有独立前置，非本任务范围。
     */
    private val table: List<Entry> = listOf(
        Entry("MySQL", "EXPLAIN $SQL_PLACEHOLDER", ExplainOutputFormat.TABLE, "真机实证 MySQL 5.7/8.4（DBX 通道 2026-09-02）"),
        Entry("MariaDB", "EXPLAIN $SQL_PLACEHOLDER", ExplainOutputFormat.TABLE, "真机实证 MariaDB 10.x（DBX 通道 2026-09-02）"),
        Entry("PostgreSQL", "EXPLAIN (FORMAT JSON, VERBOSE) $SQL_PLACEHOLDER", ExplainOutputFormat.JSON, "真机实证 PG 16（2026-09-02）+ 2026-09-22 复验返回形态"),
        Entry("KingBase（人大金仓）", "EXPLAIN (FORMAT JSON) $SQL_PLACEHOLDER", ExplainOutputFormat.JSON, "真机实证 KingbaseES V8（PG 兼容）"),
        Entry("DM（达梦）", "EXPLAIN $SQL_PLACEHOLDER", ExplainOutputFormat.TEXT, "真机实证 DM8（返回 #NSET2 计划文本）"),
        Entry("H2", "EXPLAIN PLAN FOR $SQL_PLACEHOLDER", ExplainOutputFormat.TEXT, "H2 2.3.232 引擎级单测"),
        Entry("SQLite", "EXPLAIN QUERY PLAN $SQL_PLACEHOLDER", ExplainOutputFormat.TEXT, "SQLite 引擎级单测"),
        Entry("Oracle", null, null, "不接入：需两步链查 PLAN_TABLE（另议）"),
        Entry("SQL Server", null, null, "不接入：需会话级 SET SHOWPLAN_XML（另议）"),
    )

    /** 按方言显示名的查找索引（键小写——查找大小写不敏感）。 */
    private val byName: Map<String, Entry> = table.associateBy { it.dialectName.lowercase() }

    /** 全量支持项（守卫用例与文档生成用；顺序即表内声明顺序）。 */
    val entries: List<Entry> get() = table

    /**
     * 取方言支持项。
     *
     * @param dialectName 方言显示名（大小写不敏感）；未知方言返回 null
     */
    fun entryFor(dialectName: String?): Entry? =
        dialectName?.trim()?.takeIf { it.isNotEmpty() }?.let { byName[it.lowercase()] }

    /**
     * 渲染 EXPLAIN 语句。
     *
     * @return 包装后的语句；null = 方言未知或不接入 EXPLAIN（调用方降级提示）
     */
    fun render(dialectName: String?, sql: String): String? =
        entryFor(dialectName)?.template?.replace(SQL_PLACEHOLDER, sql)

    /** 计划输出格式；null = 方言未知或不接入（与 [render] 的 null 同源）。 */
    fun formatFor(dialectName: String?): ExplainOutputFormat? = entryFor(dialectName)?.outputFormat
}
