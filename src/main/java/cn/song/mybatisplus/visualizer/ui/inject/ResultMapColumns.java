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

    // T-XmlGap W5-10：`<select … resultMap="X" …>…</select>` 逐条匹配（属性段 + 标签体）
    private static final Pattern SELECT_STATEMENT = Pattern.compile("(?is)<select\\b([^>]*)>(.*?)</select>");
    private static final Pattern RESULT_MAP_ATTR = Pattern.compile("(?i)\\bresultMap\\s*=\\s*\"([^\"]*)\"");

    /** 从 mapper 全文中提取 SELECT 投影列名（去重保序）。 */
    public static List<String> extractColumns(String mapperText) {
        // F89（T-PerfSweep25）：文本长度熔断（对齐 XmlSynHiGate.MAX_TEXT_LENGTH 100K 口径）——
        // 超长 mapper 的全文 findAll 不再受理（补全候选提取的输入卡顿防线），超限一律空候选。
        if (mapperText.length() > XmlSynHiGate.MAX_TEXT_LENGTH) return List.of();
        Set<String> out = new LinkedHashSet<>();
        Matcher projectionM = SELECT_PROJECTION.matcher(mapperText);
        while (projectionM.find()) {
            out.addAll(projectionColumns(projectionM.group(1)));
        }
        return new ArrayList<>(out);
    }

    /**
     * T-XmlGap W5-10：<b>只</b>解析「引用了指定 resultMap 的那些 {@code <select>} 语句」的投影列
     * （候选来源修正：原实现取 mapper 全文 SELECT，会混入与该 resultMap 无关的列）。
     *
     * <p>引用判定：{@code <select … resultMap="X">} 中 {@code X} 精确等于 resultMapId，或为其
     * {@code namespace.X} 尾段形态（跨 namespace 引用时 XML 里写全限定 {@code ns.id}）。
     * 无任何 {@code <select>} 引用该 resultMap → 空（不再回退全文，避免无关列污染）。</p>
     */
    public static List<String> extractColumnsForResultMap(String mapperText, String resultMapId) {
        if (resultMapId.isBlank()) return List.of();
        // F89：同 extractColumns 的文本长度熔断（两条提取入口同口径）。
        if (mapperText.length() > XmlSynHiGate.MAX_TEXT_LENGTH) return List.of();
        Set<String> out = new LinkedHashSet<>();
        Matcher statementM = SELECT_STATEMENT.matcher(mapperText);
        while (statementM.find()) {
            String ref = null;
            Matcher attrM = RESULT_MAP_ATTR.matcher(statementM.group(1));
            if (attrM.find()) {
                ref = attrM.group(1).trim();
            }
            if (ref != null && refMatches(ref, resultMapId)) {
                // 语句体先按 SELECT…FROM 截出投影段（否则 `FROM t` 的 `t` 会被当列名）
                String projection = "";
                Matcher projectionM = SELECT_PROJECTION.matcher(statementM.group(2));
                if (projectionM.find()) {
                    projection = projectionM.group(1);
                }
                out.addAll(projectionColumns(projection));
            }
        }
        return new ArrayList<>(out);
    }

    /** resultMap 引用匹配：精确 / {@code ns.id} 尾段（{@code .} 分隔段的完整尾匹配）。 */
    private static boolean refMatches(String reference, String resultMapId) {
        return reference.equals(resultMapId) || reference.endsWith("." + resultMapId);
    }

    /** 投影段 → 列名（逗号分段取末 token 的末段标识符；{@code *} 跳过）。 */
    private static List<String> projectionColumns(String projection) {
        Set<String> out = new LinkedHashSet<>();
        for (String rawSeg : projection.split(",")) {
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
