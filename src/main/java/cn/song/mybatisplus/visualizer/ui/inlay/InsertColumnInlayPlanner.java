// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inlay/InsertColumnInlayPlanner.kt 移植
package cn.song.mybatisplus.visualizer.ui.inlay;

import cn.song.mybatisplus.visualizer.ui.highlight.SqlLexer;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlTokenTypes;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * INSERT 目标列 inlay 规划器（T-InsertColumnInlay O-44b）：对 SQL 全文规划「值表达式 → 目标列名」提示位点。
 *
 * <p>规划对象（仅 INSERT 语句，语句类型闸门在 provider 侧按预览模型 {@code xmlModel.kind} 判定）：
 * <ul>
 *  <li>{@code INSERT INTO t (c1, c2) VALUES (v1, v2)}：每组 VALUES 元组内<strong>逐表达式</strong>挂
 *      {@code c1} / {@code c2}；</li>
 *  <li>{@code INSERT INTO t (c1, c2) SELECT p1, p2 FROM …}：逐投影挂目标列名；</li>
 *  <li><strong>多组元组各自挂</strong>（{@code foreach} 生成的批量 INSERT 逐行标注）——与
 *      {@link cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFoldPlanner} 的「每组元组独立判定」
 *      口径一致；行内提示是<strong>只读显示层</strong>，重复列名不改变 SQL 语义。</li>
 * </ul>
 *
 * <p><strong>投影去重规则（照抄 DBX {@code insertValueHints.ts:286} {@code selectProjectionAlias}
 * 的语义，判据按我方词法形态重定义）</strong>：投影项已自带<strong>同名列别名</strong>时丢弃该条提示，
 * 避免「投影本身就写着 {@code AS a}，旁边再标一个 {@code a}」的重复。别名 = 尾随 {@code AS <name>}，
 * 或<strong>隐式别名</strong>（表达式后跟一个独立标识符，如 {@code count(*) cnt}）。
 * <strong>点名不算别名</strong>：裸列名（{@code SELECT a}）与限定名末段（{@code SELECT t.a}）都
 * <strong>不是</strong>别名——前者无别名，后者的 {@code a} 是限定名的一部分（若按「末段即别名」判定，
 * {@code SELECT t.a} 会被误当成自带别名 {@code a} 而丢掉提示）。
 *
 * <p><strong>静态不可判一律不提示</strong>（宁缺勿错，与 {@code ParamKind} 的 UNKNOWN 口径一致），
 * 本规划器覆盖的放弃面：
 * <ul>
 *  <li>无列清单（{@code INSERT INTO t VALUES …} / {@code INSERT INTO t SELECT …}）——目标列不可知；</li>
 *  <li>列清单元素非纯列名（函数/表达式/嵌套括号，如 {@code INSERT INTO t (a, CONCAT(b,c))}）；</li>
 *  <li>VALUES 元组元素数与列数不等（畸形/动态列清单）；</li>
 *  <li>{@code INSERT … SELECT} 的投影数与列数不等；</li>
 *  <li>投影为 {@code *} / {@code t.*}（展开列数不可判）；</li>
 *  <li>元组/列清单未闭合（截断文本）。</li>
 * </ul>
 *
 * <p>纯函数零平台依赖（{@link SqlLexer} 为普通类）→ 单测无需平台沙箱。
 */
public final class InsertColumnInlayPlanner {

    /** Kotlin object 单例等价。 */
    public static final InsertColumnInlayPlanner INSTANCE = new InsertColumnInlayPlanner();

    private InsertColumnInlayPlanner() {
    }

    /**
     * 单条提示：{@code offset} = 值表达式 / 投影在全文中的起始偏移（inlay 落点），
     * {@code columnName} = 目标列名。
     */
    public record Hint(int offset, String columnName) {
    }

    /** 语句起始关键字：扫描中遇到即认定上一条语句已结束（分号缺失的相邻语句防御）。 */
    private static final Set<String> STATEMENT_STARTERS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "REPLACE", "CREATE", "DROP", "ALTER", "WITH");

    /** {@code INSERT … SELECT} 投影列表的终止关键字（达到即封口，不含本 token）。 */
    private static final Set<String> SELECT_TERMINATORS = Set.of(
            "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION", "WINDOW");

    /**
     * 不能作为「隐式别名前驱」的关键字：这些关键字本身是投影的<strong>前导修饰</strong>而非表达式，
     * 其后紧跟的标识符是投影主体（{@code SELECT DISTINCT a} 的 {@code a} 不是别名）。
     */
    private static final Set<String> NON_VALUE_ENDER_KEYWORDS = Set.of("DISTINCT", "ALL", "UNIQUE", "TOP", "AS");

    /** 投影 / 元组的 token 下标闭区间（Kotlin {@code IntRange} 等价，{@code first}..{@code last}）。 */
    private record Span(int first, int last) {
    }

    private static final class Tok {
        final IElementType type;
        final int start;
        final int end;

        Tok(IElementType type, int start, int end) {
            this.type = type;
            this.start = start;
            this.end = end;
        }
    }

    /** 规划提示位点；按扫描顺序天然升序。 */
    public static List<Hint> plan(String sql) {
        if (sql.isEmpty()) return List.of();
        List<Tok> toks = tokenize(sql);
        if (toks.isEmpty()) return List.of();
        List<Hint> hints = new ArrayList<>();
        int i = 0;
        while (i < toks.size()) {
            int next = isWord(sql, toks.get(i), "INSERT") ? parseInsert(sql, toks, i, hints) : i + 1;
            i = next > i ? next : i + 1;
        }
        return hints;
    }

    // ------------------------------------------------------------------
    // 语句级
    // ------------------------------------------------------------------

    /**
     * 解析一条 INSERT；命中列清单 + VALUES/SELECT 才写入 {@code out}（局部收集，半途放弃不留残条）。
     *
     * @return 继续扫描的 token 下标（语句已结束处；不可判时返回已消费到的位置）
     */
    private static int parseInsert(String sql, List<Tok> toks, int insertIdx, List<Hint> out) {
        // INSERT 之后必须是 `INTO` 或直接是表名（`INSERT INTO` / `INSERT IGNORE INTO`）——
        // 排除「标识符恰好叫 insert」（如 `SELECT insert, count(x) FROM t`）造成的假语句头
        int first = insertIdx + 1;
        if (first >= toks.size()) return first;
        if (!isWord(sql, toks.get(first), "INTO") && nameOf(sql, toks.get(first)) == null) return first;

        // 列清单 = INSERT 之后第一个括号组（表名与 INSERT 修饰关键字都不含括号）
        int openIdx = -1;
        int i = first;
        while (i < toks.size()) {
            Tok t = toks.get(i);
            if (isParen(sql, t, '(')) {
                openIdx = i;
                break;
            }
            if (isStatementBoundary(sql, t)) return i;
            i++;
        }
        if (openIdx < 0) return i;

        SimpleEntry<List<String>, Integer> read = readNameList(sql, toks, openIdx);
        if (read == null) return openIdx + 1;
        List<String> columns = read.getKey();
        int closeIdx = read.getValue();
        if (columns.isEmpty()) return closeIdx + 1;

        int next = closeIdx + 1;
        if (next >= toks.size()) return closeIdx + 1;
        List<Hint> local = new ArrayList<>();
        int consumed;
        switch (wordUpper(sql, toks.get(next))) {
            case "VALUES":
            case "VALUE":
                consumed = readValuesTuples(sql, toks, next, columns, local);
                break;
            case "SELECT":
                consumed = readSelectProjections(sql, toks, next, columns, local);
                break;
            default:
                return closeIdx + 1;
        }
        out.addAll(local);
        return consumed;
    }

    /** 列清单：元素必须是<strong>单 token 纯列名</strong>，否则返回 null（整条 INSERT 放弃提示）。 */
    private static SimpleEntry<List<String>, Integer> readNameList(String sql, List<Tok> toks, int openIdx) {
        List<String> names = new ArrayList<>();
        String elementText = null;
        int i = openIdx + 1;
        while (i < toks.size()) {
            Tok t = toks.get(i);
            if (isParen(sql, t, '(')) {
                return null; // 列清单内嵌套括号（如 PARTITION (p0)）→ 非纯列名清单
            } else if (isParen(sql, t, ')')) {
                String name = elementText;
                if (name == null) return null;
                names.add(name);
                return new SimpleEntry<>(names, i);
            } else if (isComma(sql, t)) {
                String name = elementText;
                if (name == null) return null;
                names.add(name);
                elementText = null;
            } else {
                if (elementText != null) return null; // 多 token 元素（函数/表达式）→ 放弃
                String name = nameOf(sql, t);
                if (name == null) return null;
                elementText = name;
            }
            i++;
        }
        return null; // 未闭合
    }

    /**
     * VALUES 元组：每组独立判定；元素数与列数相等才产出提示，否则跳过该组（不产出）。
     *
     * @return 继续扫描的 token 下标
     */
    private static int readValuesTuples(
            String sql,
            List<Tok> toks,
            int valuesIdx,
            List<String> columns,
            List<Hint> out) {
        int i = valuesIdx + 1;
        while (i < toks.size()) {
            if (!isParen(sql, toks.get(i), '(')) break;
            SimpleEntry<List<Integer>, Integer> tuple = readValueTuple(sql, toks, i);
            if (tuple == null) break;
            List<Integer> starts = tuple.getKey();
            if (starts.size() == columns.size()) {
                for (int k = 0; k < starts.size(); k++) {
                    out.add(new Hint(starts.get(k), columns.get(k)));
                }
            }
            i = tuple.getValue();
            if (i < toks.size() && isComma(sql, toks.get(i))) {
                i++;
                continue;
            }
            break;
        }
        return i;
    }

    /**
     * 单个 VALUES 元组：返回各顶层元素的起始偏移与「闭括号之后」的下标；空元素/未闭合返回 null。
     */
    private static SimpleEntry<List<Integer>, Integer> readValueTuple(String sql, List<Tok> toks, int openIdx) {
        List<Integer> starts = new ArrayList<>();
        int elementStart = -1;
        int depth = 1;
        int i = openIdx + 1;
        while (i < toks.size()) {
            Tok t = toks.get(i);
            if (isParen(sql, t, '(')) {
                if (elementStart < 0) elementStart = t.start;
                depth++;
            } else if (isParen(sql, t, ')')) {
                depth--;
                if (depth == 0) {
                    if (elementStart < 0) return null;
                    starts.add(elementStart);
                    return new SimpleEntry<>(starts, i + 1);
                }
            } else if (isComma(sql, t) && depth == 1) {
                if (elementStart < 0) return null;
                starts.add(elementStart);
                elementStart = -1;
            } else {
                if (elementStart < 0) elementStart = t.start;
            }
            i++;
        }
        return null;
    }

    /**
     * {@code INSERT … SELECT} 投影列表：投影数与列数相等才产出；逐投影按别名去重、{@code *} 不产出。
     *
     * @return 继续扫描的 token 下标
     */
    private static int readSelectProjections(
            String sql,
            List<Tok> toks,
            int selectIdx,
            List<String> columns,
            List<Hint> out) {
        List<Span> elements = new ArrayList<>();
        int start = -1;
        int depth = 0;
        int i = selectIdx + 1;
        while (i < toks.size()) {
            Tok t = toks.get(i);
            if (isParen(sql, t, '(')) {
                if (start < 0) start = i;
                depth++;
            } else if (isParen(sql, t, ')')) {
                if (depth == 0) break; // 意外右括号（外层已收口）→ 列表结束
                depth--;
            } else if (depth == 0 && isComma(sql, t)) {
                if (start < 0) return i; // 空投影 → 放弃
                elements.add(new Span(start, i - 1));
                start = -1;
            } else if (depth == 0 && (SELECT_TERMINATORS.contains(wordUpper(sql, t)) || isStatementBoundary(sql, t))) {
                break;
            } else {
                if (start < 0) start = i;
            }
            i++;
        }
        if (start >= 0) elements.add(new Span(start, i - 1));
        if (elements.isEmpty() || elements.size() != columns.size()) return i;

        for (int k = 0; k < elements.size(); k++) {
            Span element = elements.get(k);
            if (isStarProjection(sql, toks, element)) continue;
            String alias = aliasOf(sql, toks, element);
            // 投影已自带同名列别名 → 丢弃该条（其余投影照常提示）
            if (alias != null && alias.equalsIgnoreCase(columns.get(k))) continue;
            out.add(new Hint(toks.get(element.first()).start, columns.get(k)));
        }
        return i;
    }

    // ------------------------------------------------------------------
    // 投影别名（去重判据）
    // ------------------------------------------------------------------

    /**
     * 投影自带别名（{@code AS x} 或隐式别名 {@code expr x}）；无别名返回 null。
     *
     * <p>隐式别名要求前驱 token 与末尾标识符<strong>之间有空白</strong>，且前驱是「值结尾」——
     * {@code t.a} 的 {@code .} 不是值结尾，故限定名末段不会被当成别名（点名不算）。
     */
    private static String aliasOf(String sql, List<Tok> toks, Span element) {
        if (element.last() == element.first()) return null; // 单 token 投影无别名
        Tok last = toks.get(element.last());
        String lastText = nameOf(sql, last);
        if (lastText == null) return null;
        Tok prev = toks.get(element.last() - 1);
        if (isWord(sql, prev, "AS")) return lastText;
        if (!sql.substring(prev.end, last.start).isBlank()) return null; // 无空白 → 同一 token 序列（如 `t.a`）
        return isValueEnder(sql, prev) ? lastText : null;
    }

    /** 前驱 token 是否可作「隐式别名」的表达式结尾。 */
    private static boolean isValueEnder(String sql, Tok t) {
        if (t.type == SqlTokenTypes.STRING || t.type == SqlTokenTypes.NUMBER
                || t.type == SqlTokenTypes.IDENTIFIER || t.type == SqlTokenTypes.TABLE) {
            return true;
        }
        if (t.type == SqlTokenTypes.PAREN) return sql.charAt(t.start) == ')';
        if (t.type == SqlTokenTypes.OPERATOR) {
            return VALUE_ENDER_OPERATORS.contains(sql.substring(t.start, t.end));
        }
        if (t.type == SqlTokenTypes.KEYWORD) return !NON_VALUE_ENDER_KEYWORDS.contains(wordUpper(sql, t));
        return false;
    }

    /** {@code *} / {@code t.*} 投影：展开列数不可判，不提示。 */
    private static boolean isStarProjection(String sql, List<Tok> toks, Span element) {
        Tok last = toks.get(element.last());
        return last.type == SqlTokenTypes.OPERATOR && sql.substring(last.start, last.end).equals("*");
    }

    // ------------------------------------------------------------------
    // 词法工具
    // ------------------------------------------------------------------

    private static List<Tok> tokenize(String sql) {
        List<Tok> out = new ArrayList<>(sql.length() / 4 + 8);
        SqlLexer lexer = new SqlLexer();
        lexer.start(sql, 0, sql.length(), 0);
        while (true) {
            IElementType type = lexer.getTokenType();
            if (type == null) break;
            int start = lexer.getTokenStart();
            int end = lexer.getTokenEnd();
            lexer.advance();
            if (type == TokenType.WHITE_SPACE || type == SqlTokenTypes.COMMENT) continue;
            if (end <= start) continue;
            out.add(new Tok(type, start, end));
        }
        return out;
    }

    private static String text(String sql, Tok t) {
        return sql.substring(t.start, t.end);
    }

    private static String wordUpper(String sql, Tok t) {
        // Kotlin `uppercase()` 为区域无关（JVM 侧按 Locale.ROOT 口径），故显式给 Locale.ROOT
        return text(sql, t).toUpperCase(Locale.ROOT);
    }

    private static boolean isWord(String sql, Tok t, String word) {
        return wordUpper(sql, t).equals(word);
    }

    private static boolean isParen(String sql, Tok t, char c) {
        return t.type == SqlTokenTypes.PAREN && t.end - t.start == 1 && sql.charAt(t.start) == c;
    }

    private static boolean isComma(String sql, Tok t) {
        return t.type == SqlTokenTypes.OPERATOR && t.end - t.start == 1 && sql.charAt(t.start) == ',';
    }

    private static boolean isStatementBoundary(String sql, Tok t) {
        return (t.type == SqlTokenTypes.OPERATOR && t.end - t.start == 1 && sql.charAt(t.start) == ';')
                || STATEMENT_STARTERS.contains(wordUpper(sql, t));
    }

    /**
     * token 文本作为列名 / 别名的取值：剥反引号与方括号（词法器把 {@code `x`} / {@code [x]} 整段作一个
     * token）、去双引号；不是「裸标识符形态」返回 null（数字、字符串、运算符、含空白的段）。
     *
     * <p>不按 token 类型判定：{@link SqlLexer} 的表名启发式会把 {@code INTO t (a, …)} 里的 {@code a}
     * 标成 TABLE，关键字集合内的裸列名（如 {@code key}）会被标成 KEYWORD——两者都是合法列名，
     * 须一并接受。
     */
    private static String nameOf(String sql, Tok t) {
        String raw = text(sql, t).trim();
        if (raw.isEmpty()) return null;
        String stripped;
        if (raw.length() >= 2
                && (raw.charAt(0) == '`' || raw.charAt(0) == '[')
                && (raw.charAt(raw.length() - 1) == '`' || raw.charAt(raw.length() - 1) == ']')) {
            stripped = raw.substring(1, raw.length() - 1);
        } else if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            stripped = raw.substring(1, raw.length() - 1);
        } else {
            stripped = raw;
        }
        if (stripped.isEmpty()) return null;
        if (!Character.isLetter(stripped.charAt(0)) && stripped.charAt(0) != '_' && stripped.charAt(0) != '$') {
            return null;
        }
        for (int i = 0; i < stripped.length(); i++) {
            char ch = stripped.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '$') return null;
        }
        return stripped;
    }

    /** 可作表达式结尾的运算符：{@code *}（{@code count(*) x}）、{@code ?}（未回填占位符）、{@code %}（{@code a % b x}）。 */
    private static final Set<String> VALUE_ENDER_OPERATORS = Set.of("*", "?", "%");
}
