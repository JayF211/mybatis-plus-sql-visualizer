package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.execute.ErrorPositionExtractor
import java.lang.reflect.InvocationTargetException

/**
 * T-McpToolset EXPLAIN 计划文本格式化（纯逻辑层，无 IDE 类型）。
 *
 * 输入为已读取的行列数据（适配层把 DBN RemoteResultSet 读成纯数据后再进本层），
 * 行数/单元格字符预算参照 DBX MCP 的上下文防撑爆口径（调研报告 §8：MAX_ALLOWED_ROWS /
 * 单元格 200 字符上限 4000）。
 */
object McpExplainFormatter {

    /** 返回行数默认预算（EXPLAIN 计划行通常 <100；超预算按截断处理）。 */
    const val DEFAULT_MAX_ROWS = 200

    /** 返回行数硬上限（T-McpRowBudget / O-36）：调用方给的 maxRows 再大也钳到此值，
     *  对齐 DBX `MAX_EXECUTE_QUERY_ROWS = 1000`（竞品第八轮报告 §8 #37）。 */
    const val HARD_MAX_ROWS = 1000

    /** 单元格字符预算。 */
    const val MAX_CELL_CHARS = 2000

    /** 已读取的查询结果（适配层采集；[truncated]=采集时已到行数预算上限）。
     *  [notices]=执行后取尽的驱动 NOTICE/WARNING（T-NoticeChannel；默认空清单，
     *  空 notices 的输出与无此字段时逐字节一致——防回归硬门禁）。 */
    data class RawResult(
        val headers: List<String>,
        val rows: List<List<String>>,
        val truncated: Boolean = false,
        val notices: List<String> = emptyList(),
    )

    /**
     * notices 节渲染（T-NoticeChannel / O-16）：驱动 NOTICE/WARNING 文本每条一行。
     * 空清单返回空串——拼接进工具输出时零追加，保证「空 notices 输出逐字节与现状一致」。
     */
    fun formatNotices(notices: List<String>): String {
        if (notices.isEmpty()) return ""
        return buildString {
            append("\n\nnotices:")
            notices.forEach { append("\n- ").append(flatNotice(it)) }
        }
    }

    /** 单条 notice 压成一行（节内每条一行的契约；换行不丢词只降形态）。 */
    private fun flatNotice(value: String): String =
        value.replace("\r\n", "\n").replace('\n', ' ').replace('\r', ' ').trim()

    /**
     * maxRows 限幅（T-McpRowBudget / T-MRB-02）：null/非正 → 默认 200；超硬上限 → 1000；其余原样。
     * 形态与语义照 [McpSchemaFormatter.clampMaxTables]（越界**钳制不抛错**——工具面报错会打断
     * agent 流程，钳制只是把预算收进合法区间）。
     */
    fun clampMaxRows(requested: Int?): Int = when {
        requested == null || requested <= 0 -> DEFAULT_MAX_ROWS
        requested > HARD_MAX_ROWS -> HARD_MAX_ROWS
        else -> requested
    }

    /**
     * 计划文本：列头 + 行 + 截断提示（截断标记来自 [RawResult.truncated]，行数已由适配层按
     * 生效预算采集裁剪）。
     *
     * [maxRows]=本次生效的行预算，只影响截断提示里的数字；默认 [DEFAULT_MAX_ROWS]——不传参时
     * 输出与 T-McpRowBudget 之前逐字节一致（防回归硬门禁，用例钉住该字面量）。
     */
    fun format(raw: RawResult, maxRows: Int = DEFAULT_MAX_ROWS): String {
        val out = StringBuilder()
        if (raw.headers.isNotEmpty()) {
            out.append(raw.headers.joinToString(" | ") { it.trim() }).append('\n')
        }
        for (row in raw.rows) {
            out.append(row.joinToString(" | ") { cell(it) }).append('\n')
        }
        if (raw.truncated) {
            out.append("\n[TRUNCATED] output limited to $maxRows rows; the actual plan may have more.")
        }
        return out.toString().trimEnd()
    }

    private fun cell(value: String): String {
        val flat = value.replace("\r\n", "\n").trim()
        return if (flat.length > MAX_CELL_CHARS) flat.take(MAX_CELL_CHARS) + "…" else flat
    }

    /**
     * T-Round10FU（独立复核 ⑨）：`sqlv_explain` 失败消息里的 `errorPosition` 文本行。
     *
     * 驱动错误文本携带位置信号（PG `Position: N` / MySQL `at line N` / Oracle 两种形态）时附一行
     * `errorPosition: char N`（字符位置）或 `errorPosition: line N`（行号），供外部 agent 定位再自修复；
     * **无信号（或 marker 不唯一而放弃归属）返回空串**——空串拼接即零追加，维持诚实降级。
     *
     * 抽出为纯函数的理由（⑨ 的症结）：这段文案原先内联在 [SqlVisualizerExplainTool.executeExplain]
     * 的 `getOrElse` 里，而该分支要真实数据源 + 项目上下文才可达，于是全仓**没有任何用例断言过这段文案形态**
     * （`McpToolGateTest` 只实例化工具类）——验收里那句「mcp 相关既有用例全绿」因此是空绿。
     * 本函数落在纯逻辑层（同 [formatNotices]：不 import 任何 mcpServer/com.intellij 类型），
     * 文案形态由 `McpErrorPositionTextTest` 逐字钉住；**接线**（工具确实走本函数）另由该用例的
     * 源码扫描断言钉住——只钉文案不钉接线的话，把内联文案改回来就又回到空绿。
     *
     * @param position [cn.song.mybatisplus.visualizer.execute.ErrorPositionExtractor.extractPosition]
     *                 的返回值：1-based 值 / 是否字符位置（false=行号）/ 来源标签；null = 无位置信号
     */
    fun formatErrorPosition(position: Triple<Int, Boolean, String>?): String =
        if (position == null) "" else "\nerrorPosition: ${if (position.second) "char" else "line"} ${position.first}"

    /** T-M19-McpErrorMeta：先拆反射包装，再保留驱动状态码、厂商码与原有位置文案；缺失字段不追加。 */
    fun formatFailure(error: Throwable, dialectName: String, explainSql: String): String {
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        val causeMsg = cause.message ?: cause.javaClass.simpleName
        val info = ErrorPositionExtractor.buildInfo(cause, causeMsg, explainSql.length)
        val pos = ErrorPositionExtractor.extractPosition(causeMsg, explainSql.length)
        val firstLine = causeMsg.lineSequence().firstOrNull { it.isNotBlank() } ?: causeMsg
        return McpToolErrors.code(
            McpToolErrors.DATASOURCE_UNREACHABLE,
            "EXPLAIN failed on '$dialectName' data source: $firstLine" +
                (info.sqlState?.let { "\nsqlState: $it" } ?: "") +
                (info.errorCode?.let { "\nerrorCode: $it" } ?: "") +
                formatErrorPosition(pos),
        )
    }
}
