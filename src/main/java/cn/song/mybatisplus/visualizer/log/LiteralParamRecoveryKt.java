package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.sql.ParamHint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字面量参数恢复顶层函数（Kotlin LiteralParamRecovery.kt 顶层函数落位惯例，同包 public）。
 * 详见 {@link LiteralParamRecovery}。
 */
public final class LiteralParamRecoveryKt {

    private LiteralParamRecoveryKt() {}

    /** 字面量恢复上限：超过（IN 大列表等）整体放弃，防参数行爆炸。 */
    public static final int MAX_RECOVERED_PARAMS = 32;

    /** 候选：替换范围（字符串含引号，end 含尾引号；数值为 token 本身）+ 值 + 回填引号提示。 */
    private record Candidate(int start, int end, String value, boolean quote) {}

    /**
     * 字面量参数恢复（T-HistParam F4）：rawSql 无 `?` 占位符（裸 SQL 捕获 / MyBatis `${}` /
     * 上游日志已内联参数值）时，把字面量原位改写为 `?`，让既有的值可见即可编辑。
     *
     * <p>识别范围两类：
     * <ul>
     * <li>单引号字符串字面量全量识别——双引号/反引号标识符是另一套引号体系天然不误伤；</li>
     * <li>数值字面量限定谓词语境——前一 token 是比较运算符（{@code = != <> > < >= <=}，{@code = -1} 的负号
     * 保留比较上下文）、IN 列表与 VALUES 列表括号内、BETWEEN 双界。LIMIT/OFFSET/SELECT 投影
     * （{@code SELECT 1}、EXISTS 子查询）/函数实参一律不识别——MP 分页查询 LIMIT 恒为字面量，
     * 全量识别会给每条分页查询多出噪音参数行。</li>
     * </ul>
     *
     * <p>其余收敛与防御：
     * <ul>
     * <li>跳过空白值字面量（{@code ''} / 纯空白）与以反斜杠结尾的歧义片段（扫描器只认 {@code ''} 转义，
     * MySQL {@code 'don\'t'} 会错切出尾反斜杠片段）——面板空值输入渲染为 `?`，无法逐字符往返；</li>
     * <li>非纯数字 token 不识别（{@code 1abc}/{@code 0x1F}/{@code 1E5} 后随词字符）；</li>
     * <li>超过 MAX_RECOVERED_PARAMS 个候选整体返回 null；</li>
     * <li>注释（{@code --} 行注释 / 块注释）与字符串内的引号不参与（扫描状态机与
     * {@link cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt#placeholderPositions(String)} 同形态），
     * 未闭合字符串到行尾丢弃。</li>
     * </ul>
     */
    public static LiteralParamRecovery recoverLiteralParams(String sql) {
        boolean hasCandidateChar = false;
        for (int k = 0; k < sql.length(); k++) {
            char ch = sql.charAt(k);
            if (ch == '\'' || Character.isDigit(ch)) {
                hasCandidateChar = true;
                break;
            }
        }
        if (!hasCandidateChar) return null;

        List<Candidate> candidates = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        // —— 字符串/注释状态（与 placeholderPositions 同口径）——
        boolean inString = false;
        int stringOpen = -1;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        // —— 数值字面量的谓词语境状态机 ——
        int parenDepth = 0;
        boolean lastWasComparison = false; // 前一显著 token 是比较运算符
        boolean inListArmed = false;       // 见到 IN，等待列表 (
        int inListMark = -1;               // 列表 ( 的嵌套深度（活动中即处于列表内）
        boolean valuesArmed = false;       // 见到 VALUES，等待值列表 (
        int valuesMark = -1;
        int betweenState = 0;              // 0 无；1 待首界；2 待 AND 后第二界

        while (i < n) {
            char c = sql.charAt(i);
            if (inLineComment) {
                if (c == '\n') inLineComment = false;
            } else if (inBlockComment) {
                if (c == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++;
                }
            } else if (inString) {
                if (c == '\'') {
                    if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                        i++; // `''` 转义：跳过第二个引号
                    } else {
                        inString = false;
                        candidates.add(new Candidate(
                            stringOpen, i,
                            sql.substring(stringOpen + 1, i).replace("''", "'"),
                            true));
                    }
                }
            } else if (c == '\'') {
                inString = true;
                stringOpen = i;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                inLineComment = true;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                inBlockComment = true;
                i++;
            } else if (c == '(') {
                parenDepth++;
                if (inListArmed) {
                    inListArmed = false;
                    inListMark = parenDepth;
                }
                if (valuesArmed) {
                    valuesArmed = false;
                    valuesMark = parenDepth;
                }
                lastWasComparison = false;
            } else if (c == ')') {
                if (parenDepth == inListMark) inListMark = -1;
                if (parenDepth == valuesMark) valuesMark = -1;
                if (parenDepth > 0) parenDepth--;
                lastWasComparison = false;
                betweenState = 0;
            } else if (c == ',' || c == ';') {
                lastWasComparison = false;
                betweenState = 0;
                if (c == ';') {
                    inListArmed = false;
                    valuesArmed = false;
                }
            } else if (c == '<' || c == '>' || c == '!' || c == '=') {
                // 比较运算符 run：= / != / <> / < / > / <= / >=
                int j = i;
                while (j < n && isComparisonChar(sql.charAt(j))) j++;
                lastWasComparison = true;
                betweenState = 0;
                i = j;
                continue;
            } else if (c == '+' || c == '-') {
                // 符号保留比较上下文（`= -1` 的值是 1，负号留在原文随 `-?` 往返无损）
            } else if (c == '*' || c == '/' || c == '%') {
                lastWasComparison = false;
                betweenState = 0;
            } else if (c == '.') {
                // 词边界，无上下文效果
            } else if (Character.isDigit(c)) {
                // 数值 token：数字 + 至多一个小数点（点后必须有数字，`1.` 视作 `1` 与 `.`）；
                // 后随词字符则非纯数字（`1abc`/`0x1F`/`1E5`）不识别
                int j = i;
                boolean dotSeen = false;
                while (j < n) {
                    char d = sql.charAt(j);
                    if (Character.isDigit(d)) {
                        j++;
                    } else if (d == '.' && !dotSeen && j + 1 < n && Character.isDigit(sql.charAt(j + 1))) {
                        dotSeen = true;
                        j++;
                    } else {
                        break;
                    }
                }
                boolean qualified = lastWasComparison || betweenState == 1 || inListMark != -1 || valuesMark != -1;
                if (qualified && (j >= n || !isWordChar(sql.charAt(j)))) {
                    candidates.add(new Candidate(i, j - 1, sql.substring(i, j), false));
                }
                lastWasComparison = false;
                betweenState = betweenState == 1 ? 2 : 0;
                i = j;
                continue;
            } else if (isWordChar(c)) {
                int j = i;
                while (j < n && isWordChar(sql.charAt(j))) j++;
                switch (sql.substring(i, j).toUpperCase()) {
                    case "IN" -> inListArmed = true;
                    case "BETWEEN" -> betweenState = 1;
                    case "VALUES" -> valuesArmed = true;
                    case "AND" -> betweenState = betweenState == 2 ? 1 : 0;
                    default -> betweenState = 0;
                }
                lastWasComparison = false;
                i = j;
                continue;
            }
            i++;
        }

        // 空白值与尾反斜杠歧义片段只出现在字符串候选（数值 token 不可能空白、必无反斜杠）
        List<Candidate> kept = new ArrayList<>();
        for (Candidate cand : candidates) {
            if (!(cand.quote() && (cand.value().isBlank() || cand.value().endsWith("\\")))) {
                kept.add(cand);
            }
        }
        if (kept.isEmpty() || kept.size() > MAX_RECOVERED_PARAMS) return null;

        StringBuilder sb = new StringBuilder(sql.length());
        int prev = 0;
        for (Candidate cand : kept) {
            sb.append(sql, prev, cand.start());
            sb.append('?');
            prev = cand.end() + 1;
        }
        sb.append(sql, prev, sql.length());
        String placeholderSql = sb.toString();
        // 改写后的 SQL 里若还残留非参数 `?`（注释内等），说明字面量改写与占位符口径不自洽——
        // 直接放弃（保守保险；T-PSG 起面板回填已改串感知、不再因注释内 `?` 错位，但本闸门
        // 守的是「改写结果自洽」这条输入侧不变式，维持不放宽）
        int qCount = 0;
        for (int k = 0; k < placeholderSql.length(); k++) {
            if (placeholderSql.charAt(k) == '?') qCount++;
        }
        if (qCount != kept.size()) return null;

        List<String> sources = new ArrayList<>();
        Map<String, String> initialValues = new LinkedHashMap<>();
        List<ParamHint> paramHints = new ArrayList<>();
        for (int k = 0; k < kept.size(); k++) {
            String source = "param" + (k + 1);
            sources.add(source);
            initialValues.put(source, kept.get(k).value());
            paramHints.add(new ParamHint(kept.get(k).quote()));
        }
        return new LiteralParamRecovery(placeholderSql, sources, initialValues, paramHints);
    }

    /**
     * 运行期熔断（T-HistParam F4）：占位符化 SQL 用原值按面板引号规则渲染回原文，必须逐字符一致。
     * 与面板 renderSingleValue 同规则（quote=true 引号+`''` 转义；quote=false 原样 trim），
     * 不一致（其他未知方言盲区）即放弃恢复维持现状。
     */
    public static boolean verifyLiteralRoundTrip(String originalSql, LiteralParamRecovery recovery) {
        StringBuilder sb = new StringBuilder(originalSql.length());
        int paramIndex = 0;
        for (int k = 0; k < recovery.placeholderSql().length(); k++) {
            char c = recovery.placeholderSql().charAt(k);
            if (c == '?') {
                String value = recovery.initialValues().get(recovery.unresolvedParams().get(paramIndex));
                if (value == null) return false;
                if (Boolean.TRUE.equals(recovery.paramHints().get(paramIndex).getQuote())) {
                    sb.append('\'').append(value.replace("'", "''")).append('\'');
                } else {
                    sb.append(value.trim());
                }
                paramIndex++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString().equals(originalSql);
    }

    private static boolean isComparisonChar(char c) {
        return c == '<' || c == '>' || c == '!' || c == '=';
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
