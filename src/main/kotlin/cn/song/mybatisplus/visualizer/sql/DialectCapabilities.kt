package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.SqlStatementSplitter

/**
 * R52 方言能力矩阵 + 执行前自检（O-106 产品化，V79 三前提全过）。
 *
 * 方言能力差「只在撞上时可见」：PG 项目给 DELETE 拼 `last("LIMIT 10")` 时预览静默省略该尾段
 * （[Dialect.supportsDeleteLimit] 基类 false，仅 MySQL 族覆写），用户照预览去控制台执行即无界删除；
 * Oracle/SQL Server/DM 无原生 upsert 渲染 → 预览降级纯 INSERT，用户也无从得知「本方言不支持」。
 * 本对象把「当前方言支持什么」变成可枚举的事实，供两处消费：
 *  1. **呈现层**（矩阵本身）：预览面板 meta 悬停 + 诊断报告（R50）的方言能力段；
 *  2. **执行确认链**（[gapsFor]）：SQL 用到了本方言不支持的形态时，确认弹窗追加一行发现。
 *
 * ## 与 [ExplainGate] 的分工（V79③ 证伪结论）
 * [ExplainGate.planExplainSubmit] 只覆盖 EXPLAIN 一件事（首关键字白名单 + 方言是否接入），
 * **不是**通用能力闸门——DELETE LIMIT / UPSERT / identity 包装三类缺口它一概不看。故本对象
 * 提供 [gapsFor] 承接非 EXPLAIN 缺口的执行前自检；EXPLAIN 缺口沿用 ExplainGate 既有提示
 * （点「执行计划」时的轻通知），[matrix] 仍单列 EXPLAIN 行以保矩阵完整，但不重复进确认弹窗。
 *
 * ## 能力判定口径（全部读 [Dialect] 接口自身的真值，不另造表——防伪能力）
 *  - EXPLAIN：读 [ExplainSupport] 表的 `supported`（R23 起 11/11 全接入，故现存方言均为 true，
 *    但新方言缺登记时矩阵如实显示 false）；
 *  - DELETE_LIMIT：[Dialect.supportsDeleteLimit]；
 *  - UPSERT：[Dialect.renderUpsert] 或 [Dialect.renderUpsertStatement] 非 null；
 *  - IDENTITY_WRAP：[Dialect.renderInsertWrapper] 非 null（DM8/SQL Server 需要包装）。
 *
 * 纯逻辑层：只依赖 [Dialect]/[ExplainSupport]（无 IDE 类型），普通 JUnit 可直接覆盖。
 */
object DialectCapabilities {

    /** 方言能力项（矩阵列）。 */
    enum class Capability {
        /** 查询执行计划（EXPLAIN）。 */
        EXPLAIN,

        /** DELETE 语句支持 `LIMIT n` 尾段。 */
        DELETE_LIMIT,

        /** 有原生 upsert 渲染形态（后缀或整语句）。 */
        UPSERT,

        /** 显式写自增列需要 identity 包装语句。 */
        IDENTITY_WRAP,
    }

    /**
     * 单行能力。
     *
     * @param capability 能力项
     * @param supported  本方言是否支持
     * @param reasonKey  不支持时的原因文案键（支持的方言为 null）；键值见 bundle `dialect.capability.reason.*`
     */
    data class CapabilityRow(val capability: Capability, val supported: Boolean, val reasonKey: String?)

    /**
     * 执行前自检发现的能力缺口。
     *
     * @param capability  缺口能力
     * @param dialectName 当前方言显示名（进文案）
     * @param messageKey  确认弹窗文案键（`execute.confirm.capability.*`）
     */
    data class CapabilityGap(val capability: Capability, val dialectName: String, val messageKey: String)

    /**
     * 建当前方言的能力矩阵（顺序稳定＝[Capability] 声明序）。
     *
     * 判定全部读接口真值（见类 KDoc），故矩阵不可能列出「实现里没有」的伪能力——新增方言只实现
     * 接口的一部分时，矩阵如实显示缺口。
     */
    fun matrix(dialect: Dialect): List<CapabilityRow> = Capability.entries.map { cap ->
        val supported = isSupported(cap, dialect)
        CapabilityRow(cap, supported, if (supported) null else reasonKey(cap))
    }

    /**
     * 列出本 SQL 用到的、而当前方言不支持的能力缺口（执行确认链用）。
     *
     * 只在**语句确实用到了该形态**时才报——`DELETE ... LIMIT 10` 撞上非 MySQL 族方言、
     * `ON DUPLICATE KEY UPDATE`/`ON CONFLICT`/`MERGE INTO` 撞上无 upsert 的方言、
     * identity 包装语句撞上无包装的方言。三判据见 [hasDeleteLimitTail]/[hasUpsertClause]/
     * [hasIdentityWrapper]。
     *
     * 不在册：EXPLAIN 缺口（见类 KDoc 的分工说明）。
     *
     * @return 按 [Capability] 声明序去重后的缺口清单；无缺口返回空清单
     */
    fun gapsFor(sql: String, dialect: Dialect): List<CapabilityGap> {
        if (sql.isBlank()) return emptyList()
        val gaps = LinkedHashMap<Capability, CapabilityGap>()
        if (!dialect.supportsDeleteLimit && hasDeleteLimitTail(sql)) {
            gaps[Capability.DELETE_LIMIT] = gap(Capability.DELETE_LIMIT, dialect, "execute.confirm.capability.deleteLimit")
        }
        if (!isSupported(Capability.UPSERT, dialect) && hasUpsertClause(sql)) {
            gaps[Capability.UPSERT] = gap(Capability.UPSERT, dialect, "execute.confirm.capability.upsert")
        }
        if (!isSupported(Capability.IDENTITY_WRAP, dialect) && hasIdentityWrapper(sql)) {
            gaps[Capability.IDENTITY_WRAP] =
                gap(Capability.IDENTITY_WRAP, dialect, "execute.confirm.capability.identityWrap")
        }
        return gaps.values.toList()
    }

    /** 本方言是否支持某能力（矩阵与缺口判定唯一入口）。 */
    fun isSupported(capability: Capability, dialect: Dialect): Boolean = when (capability) {
        Capability.EXPLAIN -> ExplainSupport.entryFor(dialect.name)?.supported == true
        Capability.DELETE_LIMIT -> dialect.supportsDeleteLimit
        Capability.UPSERT ->
            dialect.renderUpsert("k", emptyList(), IdentifierCaseSource.ENTITY) != null ||
                dialect.renderUpsertStatement("t", emptyList(), "k", IdentifierCaseSource.ENTITY) != null
        Capability.IDENTITY_WRAP -> dialect.renderInsertWrapper("t", "", IdentifierCaseSource.ENTITY) != null
    }

    /**
     * 矩阵呈现文本（多行，供 meta 悬停 / 诊断报告的方言能力段直接引用）。
     *
     * 每行 `- <能力名>: <state>`，`state` = 支持说明或按 [CapabilityRow.reasonKey] 取的降级原因
     * （i18n 走 bundle，双语随语言切换）——呈现层不自行拼词，单一事实源在本对象。
     */
    fun summaryText(dialect: Dialect): String = matrix(dialect).joinToString("\n") { row ->
        val label = MpSqlVisualizerBundle.message(labelKey(row.capability))
        val state = row.reasonKey?.let { MpSqlVisualizerBundle.message(it) }
            ?: MpSqlVisualizerBundle.message("dialect.capability.supported")
        "- $label: $state"
    }

    /** 能力名文案键（矩阵呈现用）。 */
    private fun labelKey(capability: Capability): String = when (capability) {
        Capability.EXPLAIN -> "dialect.capability.label.explain"
        Capability.DELETE_LIMIT -> "dialect.capability.label.deleteLimit"
        Capability.UPSERT -> "dialect.capability.label.upsert"
        Capability.IDENTITY_WRAP -> "dialect.capability.label.identityWrap"
    }

    /**
     * 语句里是否出现 DELETE 的 `LIMIT n` 尾段。
     *
     * 逐条拆分（[SqlStatementSplitter]，注释/字符串内分号安全）后只看首关键字为 DELETE 的语句，
     * 且要求 `LIMIT` 后紧随数字（词边界）——`UPDATE ... LIMIT` 不在本能力范围（MySQL 亦不支持），
     * `LIMIT` 作为列名/字面量（`WHERE note = 'limit'`）由引号内文本自然排除在词边界判定外。
     */
    fun hasDeleteLimitTail(sql: String): Boolean {
        for (statement in SqlStatementSplitter.splitStripped(sql)) {
            if (!firstWordIs(statement, "DELETE")) continue
            if (DELETE_LIMIT_TAIL.containsMatchIn(maskLiterals(statement))) return true
        }
        return false
    }

    /**
     * 语句里是否出现 upsert 冲突子句形态：`ON DUPLICATE KEY UPDATE`（MySQL 族/openGauss）、
     * `ON CONFLICT`（PG 族）、或整语句 `MERGE INTO`（H2 形态；DM/Oracle 的 MERGE 也归此）。
     *
     * 词面判定前先屏蔽字符串字面量（`'…'` / `"…"` / 反引号 / `[…]`）——否则 `WHERE note = 'on conflict'`
     * 这类常量值会误判（诊断测试实证：`'limit 10'` 曾误命中 DELETE LIMIT 尾段规则）。
     */
    fun hasUpsertClause(sql: String): Boolean {
        val masked = maskLiterals(sql)
        if (ON_DUPLICATE.containsMatchIn(masked) || ON_CONFLICT.containsMatchIn(masked)) return true
        return SqlStatementSplitter.splitStripped(sql).any { firstWordIs(it, "MERGE") }
    }

    /** identity 包装语句是否在场（复用既有精确判据，只认插件自产的 `SET IDENTITY_INSERT <表> ON|OFF;`）。 */
    fun hasIdentityWrapper(sql: String): Boolean = IdentityInsertStatements.containsStatement(sql)

    /** 不支持原因文案键（矩阵呈现用）。 */
    private fun reasonKey(capability: Capability): String = when (capability) {
        Capability.EXPLAIN -> "dialect.capability.reason.explain"
        Capability.DELETE_LIMIT -> "dialect.capability.reason.deleteLimit"
        Capability.UPSERT -> "dialect.capability.reason.upsert"
        Capability.IDENTITY_WRAP -> "dialect.capability.reason.identityWrap"
    }

    private fun gap(capability: Capability, dialect: Dialect, key: String): CapabilityGap =
        CapabilityGap(capability, dialect.name, key)

    /** 首关键字（大写）判定；首词取连续字母段（与执行链分类器同口径的轻量版，够用即可）。 */
    private fun firstWordIs(statement: String, upper: String): Boolean {
        val trimmed = statement.trimStart()
        val word = trimmed.takeWhile { it.isLetter() }
        return word.equals(upper, ignoreCase = true)
    }

    /**
     * 屏蔽字符串字面量与引号标识符（内容替换为空格，保持偏移稳定）——词面规则只在结构文本上跑，
     * 避免 `WHERE note = 'limit 10'` / `= 'on conflict'` 这类常量值误命中。
     * 与 [cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier] 的 maskQuoted 同口径（各自私有副本，
     * 因两处模块边界不同——本对象在 sql 包，该分类器在 execute 包）。
     */
    private fun maskLiterals(sql: String): String {
        val sb = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            val close = when (c) {
                '\'' -> '\''
                '"' -> '"'
                '`' -> '`'
                '[' -> ']'
                else -> null
            }
            if (close == null) {
                sb.append(c)
                i++
                continue
            }
            sb.append(' ')
            i++
            while (i < sql.length) {
                if (close != '[' && sql[i] == close && i + 1 < sql.length && sql[i + 1] == close) {
                    sb.append("  ")
                    i += 2
                    continue
                }
                if (sql[i] == close) {
                    sb.append(' ')
                    i++
                    break
                }
                sb.append(' ')
                i++
            }
        }
        return sb.toString()
    }

    /** DELETE 的 LIMIT 尾段：`LIMIT` 后可选空白再跟数字（词边界，避免 LIKE 'limit' 误判）。 */
    private val DELETE_LIMIT_TAIL = Regex("\\bLIMIT\\s+\\d", RegexOption.IGNORE_CASE)

    /** MySQL 族 upsert 后缀。 */
    private val ON_DUPLICATE = Regex("\\bON\\s+DUPLICATE\\s+KEY\\s+UPDATE\\b", RegexOption.IGNORE_CASE)

    /** PG 族 upsert 后缀。 */
    private val ON_CONFLICT = Regex("\\bON\\s+CONFLICT\\b", RegexOption.IGNORE_CASE)
}
