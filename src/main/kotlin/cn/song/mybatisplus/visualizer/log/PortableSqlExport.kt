package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.model.BatchKind
import cn.song.mybatisplus.visualizer.model.BatchModel
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator
import java.util.Locale

/**
 * O-91 CRUD/回滚 SQL 可移植导出（缩范围首版，边界=核实报告 20261001-批次9核实道2 §2.5 定案）。
 *
 * **做什么**：把当前预览的 CRUD/回滚 SQL 串成可移植脚本——语句本体已由方言链
 * （[BatchModel.renderSql] / [RollbackSqlGenerator]）生成，导出**不改写 SQL 本体**，
 * 只做两件事：①每条语句前加注释头标注方言形态；②导出格式走 R4 既有三格式通道
 * （[SqlExportService.formatPortable]，不新造文件格式）。
 *
 * **不做什么**（定案边界，勿顺手扩张）：DDL 导出、INSERT ALL 合成（#156 只登记）、
 * O-9 闸门改动（UPSERT 不进确认流属现状语义）、PG ON CONFLICT 回滚行为探针（留后续）。
 *
 * upsert 形态标注值（对齐核实报告「原生简洁/整语句/降级 INSERT」三态＋非 upsert）：
 *  - [UPSERT_STATEMENT]：方言链整语句形态（H2 `MERGE INTO..KEY..VALUES`），文本以 MERGE 起头；
 *  - [UPSERT_SUFFIX]：INSERT+后缀形态（MySQL `ON DUPLICATE KEY UPDATE` / PG `ON CONFLICT`）；
 *  - [UPSERT_DEGRADED]：方言无任何原生简洁 upsert（Oracle/SQL Server/DM）→ UPSERT 意图渲染降级纯 INSERT；
 *  - [UPSERT_NONE]：非 upsert（含「UPSERT 模型主键未勾选 → 新记录路径纯 INSERT」——方言链有能力
 *    而文本无冲突子句，说明走的是新记录路径而非降级，两者以链能力区分，勿混标）。
 */
object PortableSqlExport {

    /** 整语句形态标注（H2 MERGE..KEY）。 */
    const val UPSERT_STATEMENT = "statement"

    /** INSERT+后缀形态标注（MySQL ON DUPLICATE / PG ON CONFLICT）。 */
    const val UPSERT_SUFFIX = "suffix"

    /** 降级纯 INSERT 标注（方言链无原生简洁 upsert，UPSERT 意图落成 INSERT）。 */
    const val UPSERT_DEGRADED = "insert"

    /** 非 upsert 标注。 */
    const val UPSERT_NONE = "no"

    /** 能力探测桩值（仅取方言链返回 null 与否；值本身不进产物）。 */
    private const val PROBE = "__sqlvis_probe__"

    /**
     * upsert 形态判定（与 [BatchModel.renderSql] UPSERT 分支的三级降级决策同源）：
     *  1. 文本以 MERGE 起头且方言链整语句形态有能力 → [UPSERT_STATEMENT]；
     *  2. 文本含冲突子句标记且方言链后缀形态有能力 → [UPSERT_SUFFIX]；
     *  3. 模型是 UPSERT 意图而方言链两形态均无（renderUpsert/renderUpsertStatement 恒 null）
     *     → [UPSERT_DEGRADED]（渲染必然降级纯 INSERT）；
     *  4. 其余（含 UPSERT 模型但方言链有能力而文本是纯 INSERT = 主键未勾选的新记录路径）→ [UPSERT_NONE]。
     *
     * [model] 可 null（无模型上下文的文本级判定，如 MCP/历史条目）——此时第 3 条不可判，降 none。
     */
    fun upsertFormOf(dialect: Dialect, sql: String, model: BatchModel? = null): String {
        val normalized = sql.trim().trimEnd(';').trim().uppercase(Locale.ROOT)
        val statementCapable = dialect.renderUpsertStatement(PROBE, listOf(PROBE), PROBE) != null
        val suffixCapable = dialect.renderUpsert(PROBE, listOf(PROBE)) != null
        return when {
            statementCapable && normalized.startsWith("MERGE") -> UPSERT_STATEMENT
            suffixCapable && ("ON DUPLICATE KEY UPDATE" in normalized || "ON CONFLICT" in normalized) -> UPSERT_SUFFIX
            model?.kind == BatchKind.UPSERT && !statementCapable && !suffixCapable -> UPSERT_DEGRADED
            else -> UPSERT_NONE
        }
    }

    /**
     * 导出串联（首版核心）：当前 SQL 作 CRUD 行 + 回滚链产物逐条作 ROLLBACK 行。
     *
     * 回滚链对 UPDATE/DELETE 无 before-image、UPSERT 三形态等场景**诚实拒绝**
     * （statements 恒空，不产危险半成品）——导出面延续同口径：拒绝时不造回滚行、不降级造半成品。
     * 空输入返回空列表（调用方不发导出）。
     */
    fun collectRows(sql: String, dialect: Dialect, model: BatchModel? = null): List<PortableSqlRow> {
        val cleaned = sql.trim()
        if (cleaned.isEmpty()) return emptyList()
        val rows = mutableListOf(
            PortableSqlRow(
                dialect = dialect.name,
                kind = PortableSqlKind.CRUD,
                upsert = upsertFormOf(dialect, cleaned, model),
                sql = cleaned,
            ),
        )
        RollbackSqlGenerator.generate(cleaned).statements.forEach { stmt ->
            rows += PortableSqlRow(dialect.name, PortableSqlKind.ROLLBACK, UPSERT_NONE, stmt)
        }
        return rows
    }
}

/** 可移植导出语句种类（注释头 `kind:` 段取枚举名）。 */
enum class PortableSqlKind {
    /** 当前预览的 CRUD 语句（本体出自方言生成链）。 */
    CRUD,

    /** 回滚链推导的可执行回滚语句。 */
    ROLLBACK,
}

/** 可移植导出行：一条语句 + 方言形态标注三字段（[cn.song.mybatisplus.visualizer.log.SqlExportService.formatPortable] 消费）。 */
data class PortableSqlRow(
    /** 来源方言名（[Dialect.name]，如 `MySQL` / `H2`）。 */
    val dialect: String,
    /** 语句种类。 */
    val kind: PortableSqlKind,
    /** upsert 形态标注（[PortableSqlExport] 四常量之一）。 */
    val upsert: String,
    /** 语句本体（导出不改写）。 */
    val sql: String,
)
