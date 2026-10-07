package cn.song.mybatisplus.visualizer.ui.diff

/**
 * T-Perf-24 死代码标注：本对象当前**无任何调用方**（diff 入口未接线）。
 */
/**
 * SQL 行级 diff 引擎（O-4 Phase B）。
 *
 * 对比两次预览的 SQL 文本，输出行级差异列表（新增 / 删除 / 相同）。
 * 算法：基于最长公共子序列（LCS）的动态规划，时间复杂度 O(M*N)，
 * 满足 SQL < 1000 行 < 100ms 性能红线。
 */
object SqlDiffer {

    /**
     * F61（T-PerfSweep18）：LCS 行数上限护栏——千行级阈值。
     *
     * DP 表成本 O(M*N)（时间与 int 表内存都随行数平方膨胀）：千行级内是类 KDoc 既有
     * 性能红线覆盖区（<1000 行 <100ms）；超限输入（外部粘入的超大 SQL/全文对比）走
     * [sideBySideDiff] 逐行并排降级——按下标对齐、同位相等记 EQUAL、否则 DELETE+INSERT
     * 成对，不做全局对齐。**computeDiff(old, new) → List<DiffEntry> 契约不变**
     * （R31「恰两条可用」历史对比入口与 DiffEntry 形态照旧消费）。
     */
    internal val LCS_LINE_LIMIT = 1000
    /**
     * 差异类型。
     *
     * 刻意不设 CHANGE（行被修改）：差异类型的唯一消费形态是按类型分派的渲染，而 O-4 的自研
     * 高亮渲染器 DiffHighlighter 已按「行内边框无视觉效果」的产品决策整体移除，实际对比入口
     * （SqlPreviewPanel.showSqlDiff）改走 IDEA 原生 Diff，行级 diff 由平台自行计算、不消费本类型，
     * 本对象因此也无任何调用方。既无渲染分派，合并出来的 CHANGE 就无人可读，纯属不可达常量，
     * 故「修改一行」保持 DELETE + INSERT 成对表达（相邻两行的 oldLineNumber/newLineNumber 已能还原改写关系）。
     * 将来若重新接线行级渲染，应连同渲染一并补一次「相邻删增合并」后处理，而不是先加常量。
     */
    enum class DiffType { INSERT, DELETE, EQUAL }

    /** 单条差异记录。 */
    data class DiffEntry(
        val type: DiffType,
        /** 旧 SQL 中的行号（1-based）；新增行时为 null。 */
        val oldLineNumber: Int?,
        /** 新 SQL 中的行号（1-based）；删除行时为 null。 */
        val newLineNumber: Int?,
        /** 旧行内容（新增行为 null）。 */
        val oldLine: String?,
        /** 新行内容（删除行为 null）。 */
        val newLine: String?,
    )

    /**
     * 计算两段 SQL 文本的行级差异。
     *
     * @param oldSql 旧 SQL（null 视为空字符串）
     * @param newSql 新 SQL
     * @return 差异条目列表（按行序）
     */
    fun computeDiff(oldSql: String?, newSql: String): List<DiffEntry> {
        val oldLines = if (oldSql.isNullOrEmpty()) emptyList() else oldSql.split("\n")
        val newLines = if (newSql.isEmpty()) emptyList() else newSql.split("\n")
        // F61：超限降级逐行并排（契约不变，仅内部算法切换）
        if (oldLines.size > LCS_LINE_LIMIT || newLines.size > LCS_LINE_LIMIT) {
            return sideBySideDiff(oldLines, newLines)
        }
        // LCS 动态规划表
        val lcs = computeLCS(oldLines, newLines)
        // 回溯构造差异序列（仅返回非 EQUAL 条目，减少下游处理负担）
        return backtrack(lcs, oldLines, newLines).filter { it.type != DiffType.EQUAL }
    }

    /**
     * F61：降级路径——逐行并排（同下标相等跳过，否则 DELETE+INSERT 成对；单侧越界只出存在侧）。
     * 与主路径同口径：EQUAL 不产出（下游只消费差异条目）。
     */
    private fun sideBySideDiff(old: List<String>, new: List<String>): List<DiffEntry> {
        val result = mutableListOf<DiffEntry>()
        for (idx in 0 until maxOf(old.size, new.size)) {
            val o = old.getOrNull(idx)
            val n = new.getOrNull(idx)
            if (o != null && n != null && o == n) continue
            if (o != null) result.add(DiffEntry(DiffType.DELETE, idx + 1, null, o, null))
            if (n != null) result.add(DiffEntry(DiffType.INSERT, null, idx + 1, null, n))
        }
        return result
    }

    /** 计算 LCS 长度表（动态规划）。 */
    private fun computeLCS(old: List<String>, new: List<String>): Array<IntArray> {
        val m = old.size
        val n = new.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (old[i - 1] == new[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        return dp
    }

    /** 回溯 LCS 表，构造差异条目列表。 */
    private fun backtrack(lcs: Array<IntArray>, old: List<String>, new: List<String>): List<DiffEntry> {
        val result = mutableListOf<DiffEntry>()
        var i = old.size
        var j = new.size
        // 从右下角回溯到左上角
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && old[i - 1] == new[j - 1] -> {
                    // 相等 → EQUAL
                    result.add(DiffEntry(DiffType.EQUAL, i, j, old[i - 1], new[j - 1]))
                    i--
                    j--
                }
                j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j]) -> {
                    // 来自左侧 → INSERT（新行新增）
                    result.add(DiffEntry(DiffType.INSERT, null, j, null, new[j - 1]))
                    j--
                }
                else -> {
                    // 来自上方 → DELETE（旧行删除）
                    result.add(DiffEntry(DiffType.DELETE, i, null, old[i - 1], null))
                    i--
                }
            }
        }
        // 回溯是逆序的，翻转
        result.reverse()
        return result
    }
}
