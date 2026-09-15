package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * T-UnboundedWriteGuard：执行确认消息构建（纯函数，零 Swing 依赖可直接测）。
 *
 * 消费方三处确认流共用（LogToSqlPanel.executeStatement / SqlPreviewPanel.executeSql /
 * SqlPreviewPanel.executeAdhocSql）——同一对话框形态只换 message 文案：
 *  - 无危险发现：文案逐字节走既有 `execute.confirm.dml.message`（频率/形态零变化）；
 *  - 有危险发现：摘要行 + 按类别去重的发现清单（每类一行，文案中性说事实）。
 *
 * 不新增弹窗形态、不新增步骤（弹窗克制红线 `docs/设计规范-弹窗克制红线.md`）。
 */
object ExecuteConfirmMessages {

    /** 无危险发现 → 既有文案；有 → 升级为发现清单。 */
    fun build(sql: String, keyword: String): String {
        val risk = UnboundedWriteDetector.detect(sql)
        if (!risk.dangerous) return MpSqlVisualizerBundle.message("execute.confirm.dml.message", keyword)
        val lines = risk.kinds().sorted().joinToString("\n") { kind ->
            "• " + MpSqlVisualizerBundle.message(messageKeyOf(kind))
        }
        return MpSqlVisualizerBundle.message("execute.confirm.unbounded.summary", keyword, lines)
    }

    /** 发现类别 → 文案键（单一映射点；键值见 bundle execute.confirm.unbounded.* 段）。 */
    private fun messageKeyOf(kind: UnboundedWriteDetector.FindingKind): String = when (kind) {
        UnboundedWriteDetector.FindingKind.MISSING_WHERE -> "execute.confirm.unbounded.missingWhere"
        UnboundedWriteDetector.FindingKind.TAUTOLOGY -> "execute.confirm.unbounded.tautology"
        UnboundedWriteDetector.FindingKind.OR_TAUTOLOGY -> "execute.confirm.unbounded.orTautology"
        UnboundedWriteDetector.FindingKind.LIKE_WILDCARD -> "execute.confirm.unbounded.likeWildcard"
        UnboundedWriteDetector.FindingKind.NO_COLUMN_REF -> "execute.confirm.unbounded.noColumnRef"
        UnboundedWriteDetector.FindingKind.SUBQUERY -> "execute.confirm.unbounded.subquery"
        UnboundedWriteDetector.FindingKind.PARSE_FAILED -> "execute.confirm.unbounded.parseFailed"
    }
}
