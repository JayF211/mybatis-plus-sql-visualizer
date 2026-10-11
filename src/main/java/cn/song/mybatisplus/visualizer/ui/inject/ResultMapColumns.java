// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/ResultMapColumns.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-XmlSynHi D5 后半：resultMap {@code column} 属性补全的候选提取与排序（纯函数）。
 *
 * <p>候选来源 = mapper 文本中全部语句（select）的**投影列名**提取：
 * <ul>
 *  <li>{@code SELECT a, b, c FROM …} 逗号分段取末段标识符（{@code t.col}/{@code a AS x} 取别名或末段）；</li>
 *  <li>{@code SELECT *} 不可枚举（跳过）；</li>
 *  <li>{@code t.col} 形态剥前缀取 {@code col}，同时保留 {@code t.col} 原形作为次选。</li>
 * </ul>
 *
 * <p>排序 = 前缀精确命中 > 前缀前缀命中 > 其余；同级按与已输入的 Levenshtein 距离升序
 * （计划 §六 D5「下划线驼峰建议」：{@code order_id} 与 {@code orderId} 双向互为近邻建议）。
 *
 * <p>移植注记：Kotlin object → 全静态 final 类 + {@link #INSTANCE}（k2j 惯例）。
 */
public final class ResultMapColumns {

    public static final ResultMapColumns INSTANCE = new ResultMapColumns();

    private ResultMapColumns() {
    }

    // T-PerfSweep16 F40：Regex 提为伴生对象单例（原每模式每次调用现场编译——主正则每补全一次、
    // 段内 `[\w*]+` 每投影列分段一次）
    private static final Pattern SELECT_PROJECTION = Pattern.compile("(?is)\\bselect\\b(.*?)(?:\\bfrom\\b|$)");
    private static final Pattern COLUMN_IDENT = Pattern.compile("[\\w*]+");

    /** 从 mapper 全文中提取 SELECT 投影列名（去重保序）。 */
    public static List<String> extractColumns(String mapperText) {
        Set<String> out = new LinkedHashSet<>();
        Matcher projectionM = SELECT_PROJECTION.matcher(mapperText);
        while (projectionM.find()) {
            String proj = projectionM.group(1);
            for (String rawSeg : proj.split(",")) {
                String seg = rawSeg.trim();
                // 末 token：AS 别名或列本身（取别名语义）
                String lastToken = null;
                for (String tok : seg.split(" ")) {
                    if (!tok.isBlank()) {
                        lastToken = tok;
                    }
                }
                if (lastToken == null) {
                    continue;
                }
                // `t.col` → `col`
                int dot = lastToken.lastIndexOf('.');
                String col = (dot >= 0 ? lastToken.substring(dot + 1) : lastToken).trim();
                if (!col.isEmpty() && COLUMN_IDENT.matcher(col).matches() && !col.equals("*")) {
                    out.add(col);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** 补全建议：前缀过滤 + 排序（下划线/驼峰双向近邻提升）。 */
    public static List<String> suggest(List<String> columns, String typed) {
        String t = typed.trim();
        if (t.isEmpty()) {
            return columns;
        }
        List<Object[]> scored = new ArrayList<>(); // [0]=column, [1]=rank
        for (String col : columns) {
            String lower = col.toLowerCase();
            String target = t.toLowerCase();
            Integer rank;
            if (lower.startsWith(target)) {
                rank = 0;
            } else if (camelSnakeMatch(col, t)) {
                rank = 1;
            } else {
                rank = null;
            }
            if (rank != null) {
                scored.add(new Object[]{col, rank});
            }
        }
        scored.sort((a, b) -> {
            int byRank = Integer.compare((Integer) a[1], (Integer) b[1]);
            if (byRank != 0) {
                return byRank;
            }
            int byDistance = Integer.compare(
                    levenshtein(((String) a[0]).toLowerCase(), t.toLowerCase()),
                    levenshtein(((String) b[0]).toLowerCase(), t.toLowerCase()));
            if (byDistance != 0) {
                return byDistance;
            }
            return ((String) a[0]).compareTo((String) b[0]);
        });
        List<String> out = new ArrayList<>();
        for (Object[] entry : scored) {
            out.add((String) entry[0]);
        }
        return out;
    }

    /** 下划线-驼峰近邻：{@code order_id} vs {@code orderId} 同形（归一化 {@code _} 与大小写后比较前缀）。 */
    private static boolean camelSnakeMatch(String col, String typed) {
        String normCol = col.replace("_", "").toLowerCase();
        String normTyped = typed.replace("_", "").toLowerCase();
        return normCol.startsWith(normTyped);
    }

    private static int levenshtein(String a, String b) {
        int m = a.length();
        int n = b.length();
        if (m == 0) {
            return n;
        }
        if (n == 0) {
            return m;
        }
        int[] prev = new int[n + 1];
        int[] cur = new int[n + 1];
        for (int k = 0; k <= n; k++) {
            prev[k] = k;
        }
        for (int i = 1; i <= m; i++) {
            cur[0] = i;
            for (int j = 1; j <= n; j++) {
                int substitute = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + substitute);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[n];
    }
}
