package cn.song.mybatisplus.visualizer.log

/**
 * 控制台 filter 重放守卫（T-PreviewFlash，纯逻辑，不依赖任何平台类）。
 *
 * 背景（平台契约，反编译出处见开发计划 §3.4）：
 * 平台 `ConsoleViewImpl.rehighlightHyperlinksAndFoldings()` 会对控制台整篇文档从第 0 行起重跑全部
 * message filter（退出 dumb mode、配色变更、插件动态加载等都会触发）——即插件挂载的 filter 对同一行
 * 会被调用多次。filter 必须幂等：重跑应被识别并丢弃，而不是把历史行当作全新输出重新入库。
 *
 * 判定依据的两个平台事实：
 * - `applyFilter(line, entireLength)` 的 `entireLength` 实为该行在整篇文档中的行尾偏移
 *   （`AsyncFilterRunner$HighlighterJob.analyzeLine`：`endOffset = lineStart + lineText.length()`），
 *   正常流式打印时单调递增；
 * - 重放不修改文档，`getModificationStamp()` 不变；控制台 clear/截断则 stamp 变。
 *
 * 四态判定（`decide` 返回密封结果）：
 *
 * | 输入特征 | 结果 | 说明 |
 * |---|---|---|
 * | endOffset > watermark | Process | 流式新增，watermark 前移 |
 * | endOffset ≤ watermark 且 stamp 未变 | Replay | 平台重放，丢弃 |
 * | endOffset ≤ watermark 且 stamp 变、行文本 ≠ 行 0 文本 | Reset | 控制台重置，watermark 归位后处理 |
 * | endOffset ≤ watermark 且 stamp 变、行文本 = 行 0 文本 | Replay | 重放叠加新增混合态，判重放丢弃 |
 */
internal class ConsoleReplayGuard {

    sealed interface Decision {
        /** 流式新增，正常处理。 */
        data object Process : Decision

        /** 平台重放，丢弃（调用方应计数并 debug 日志）。 */
        data object Replay : Decision

        /** 控制台重置（clear/截断），重置水位后按流式处理。 */
        data object Reset : Decision
    }

    /** 已处理的最大行尾偏移（文档级位置游标）。 */
    var watermark: Int = 0
        private set

    /** 上次观测到的文档 modificationStamp（Long.MIN_VALUE 表示未初始化）。 */
    private var lastDocStamp: Long = Long.MIN_VALUE

    /**
     * @param endOffset 行在整篇文档中的行尾偏移（`applyFilter` 的 entireLength 参数）。
     * @param docStamp  控制台文档当前 modificationStamp（守卫不可得时传 Long.MIN_VALUE → 按未初始化处理）。
     * @param lineText  当前行文本。
     * @param line0Text 控制台首行文本（`endOffset ≤ watermark` 且 stamp 变时才参与判定；混合态区分用）。
     */
    fun decide(endOffset: Int, docStamp: Long, lineText: String, line0Text: String?): Decision {
        if (endOffset > watermark) {
            watermark = endOffset
            lastDocStamp = docStamp
            return Decision.Process
        }
        val stampChanged = docStamp != Long.MIN_VALUE && docStamp != lastDocStamp
        if (!stampChanged) {
            // 纯重放：endOffset 回落且文档未变
            return Decision.Replay
        }
        // stamp 变了：控制台可能被 clear/截断。用首行文本比对区分「重置」与「重放叠加新增的混合态」。
        lastDocStamp = docStamp
        return if (line0Text == null || lineText != line0Text) {
            watermark = endOffset
            Decision.Reset
        } else {
            Decision.Replay
        }
    }

    /** 控制台文档被外部重置（如面板清空）时应显式复位，避免脏水位移到新内容。 */
    fun reset() {
        watermark = 0
        lastDocStamp = Long.MIN_VALUE
    }
}