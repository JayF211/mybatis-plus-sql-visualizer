package cn.song.mybatisplus.visualizer.ui.diff

/**
 * T-Perf-24 死代码标注：本对象当前**无任何调用方**（diff 入口未接线）。
 */
/**
 * SQL 行级 diff 引擎（O-4 Phase B）。
 *
 * 对比两次预览的 SQL 文本，输出行级差异列表（新增 / 删除 / 变更 / 相同）。
 * 算法：基于最长公共子序列（LCS）的动态规划，时间复杂度 O(M*N)，
 * 满足 SQL < 1000 行 < 100ms 性能红线。
 */
object SqlDiffer {

    /** 差异类型。 */
    enum class DiffType { INSERT, DELETE, CHANGE, EQUAL }

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
        // LCS 动态规划表
        val lcs = computeLCS(oldLines, newLines)
        // 回溯构造差异序列（仅返回非 EQUAL 条目，减少下游处理负担）
        return backtrack(lcs, oldLines, newLines).filter { it.type != DiffType.EQUAL }
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
