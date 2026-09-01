package cn.song.mybatisplus.visualizer.ui.diff;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SQL 行级 diff 引擎（O-4 Phase B）。
 *
 * <p>对比两次预览的 SQL 文本，输出行级差异列表（新增 / 删除 / 变更 / 相同）。
 * 算法：基于最长公共子序列（LCS）的动态规划，时间复杂度 O(M*N)，
 * 满足 SQL &lt; 1000 行 &lt; 100ms 性能红线。
 */
public final class SqlDiffer {

    /** Kotlin object 单例等价。 */
    public static final SqlDiffer INSTANCE = new SqlDiffer();

    private SqlDiffer() {
    }

    /** 差异类型。 */
    public enum DiffType { INSERT, DELETE, CHANGE, EQUAL }

    /** 单条差异记录。 */
    public record DiffEntry(
            /** 差异类型 */
            DiffType type,
            /** 旧 SQL 中的行号（1-based）；新增行时为 null */
            @Nullable Integer oldLineNumber,
            /** 新 SQL 中的行号（1-based）；删除行时为 null */
            @Nullable Integer newLineNumber,
            /** 旧行内容（新增行为 null） */
            @Nullable String oldLine,
            /** 新行内容（删除行为 null） */
            @Nullable String newLine
    ) {
    }

    /**
     * 计算两段 SQL 文本的行级差异。
     *
     * @param oldSql 旧 SQL（null 视为空字符串）
     * @param newSql 新 SQL
     * @return 差异条目列表（按行序）
     */
    @NotNull
    public static List<DiffEntry> computeDiff(@Nullable String oldSql, @NotNull String newSql) {
        List<String> oldLines = (oldSql == null || oldSql.isEmpty()) ? List.of() : Arrays.asList(oldSql.split("\n"));
        List<String> newLines = newSql.isEmpty() ? List.of() : Arrays.asList(newSql.split("\n"));
        // LCS 动态规划表
        int[][] lcs = computeLCS(oldLines, newLines);
        // 回溯构造差异序列（仅返回非 EQUAL 条目，减少下游处理负担）
        return backtrack(lcs, oldLines, newLines).stream()
                .filter(e -> e.type() != DiffType.EQUAL)
                .collect(Collectors.toList());
    }

    /** 计算 LCS 长度表（动态规划）。 */
    private static int[][] computeLCS(@NotNull List<String> old, @NotNull List<String> newL) {
        int m = old.size();
        int n = newL.size();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                dp[i][j] = old.get(i - 1).equals(newL.get(j - 1))
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        return dp;
    }

    /** 回溯 LCS 表，构造差异条目列表。 */
    @NotNull
    private static List<DiffEntry> backtrack(int[][] lcs, @NotNull List<String> old, @NotNull List<String> newL) {
        List<DiffEntry> result = new ArrayList<>();
        int i = old.size();
        int j = newL.size();
        // 从右下角回溯到左上角
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && old.get(i - 1).equals(newL.get(j - 1))) {
                // 相等 → EQUAL
                result.add(new DiffEntry(DiffType.EQUAL, i, j, old.get(i - 1), newL.get(j - 1)));
                i--;
                j--;
            } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
                // 来自左侧 → INSERT（新行新增）
                result.add(new DiffEntry(DiffType.INSERT, null, j, null, newL.get(j - 1)));
                j--;
            } else {
                // 来自上方 → DELETE（旧行删除）
                result.add(new DiffEntry(DiffType.DELETE, i, null, old.get(i - 1), null));
                i--;
            }
        }
        // 回溯是逆序的，翻转
        Collections.reverse(result);
        return result;
    }
}
