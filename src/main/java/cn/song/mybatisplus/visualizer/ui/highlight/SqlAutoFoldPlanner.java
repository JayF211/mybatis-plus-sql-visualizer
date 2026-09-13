package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.psi.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SQL 自动折叠规划器（T-AutoFold F1）：对 SQL 全文按「顶层字段列表」规划折叠区域。
 *
 * <p>折叠对象（需求 §3.1，仅顶层——嵌套子查询内的列表不折叠，外层区域命中后天然覆盖内层）：
 * <ul>
 *  <li>SELECT 列清单（{@code SELECT} 之后到 {@code FROM}/其他子句关键字前）；</li>
 *  <li>INSERT 列清单（表名后的括号组内）；</li>
 *  <li>VALUES 每组元组（各自独立判定）；</li>
 *  <li>UPDATE 的 SET 清单（{@code SET} 之后到 {@code WHERE}/{@code ON} 前）。</li>
 * </ul>
 *
 * <p>触发条件（需求 §3.2）：列表<strong>顶层元素数</strong>（自身括号深度内的逗号分段数）≥ 阈值，且区域跨 ≥ 2 行
 * （同行无折叠价值——压缩单行 SQL 因此自动零区域，NORMAL 模式与竖排的语义一致按元素数判定）。
 *
 * <p>区域边界（需求 §3.3）：从列表首元素起、止于末元素行尾（不含换行符）——首行关键字行
 * （SELECT/VALUES 行）保持可见，折叠后 {@code FROM}/{@code )} 等结构仍在原位。末元素行内若同行还有
 * 实质内容（如压缩单行的 {@code FROM} 紧随），区域止于末元素，不吞后续 token。
 *
 * <p>输出按扫描顺序天然升序、互不重叠（单遍状态机：收集中更深的 token 不记录、外层封口后
 * 才可能开启下一列表）——{@link com.intellij.openapi.editor.FoldingModel#addFoldRegion} 对相交区域
 * 返回 null，规划器自保证互斥比依赖返回值可靠。
 *
 * <p>纯函数零平台依赖（{@link SqlLexer} 为普通类）→ 单测无需平台沙箱。
 */
public final class SqlAutoFoldPlanner {

    /** Kotlin object 单例等价。 */
    public static final SqlAutoFoldPlanner INSTANCE = new SqlAutoFoldPlanner();

    private SqlAutoFoldPlanner() {
    }

    /** 单个折叠区域：文档偏移区间 [startOffset, endOffset) + 收起占位符（如 {@code … 23 列}）。 */
    public static final class FoldSpec {
        private final int startOffset;
        private final int endOffset;
        private final String placeholder;

        public FoldSpec(int startOffset, int endOffset, String placeholder) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.placeholder = placeholder;
        }

        public int getStartOffset() { return startOffset; }
        public int getEndOffset() { return endOffset; }
        public String getPlaceholder() { return placeholder; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FoldSpec)) return false;
            FoldSpec that = (FoldSpec) o;
            return startOffset == that.startOffset && endOffset == that.endOffset
                    && java.util.Objects.equals(placeholder, that.placeholder);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(startOffset, endOffset, placeholder);
        }

        @Override
        public String toString() {
            return "FoldSpec[startOffset=" + startOffset + ", endOffset=" + endOffset + ", placeholder=" + placeholder + "]";
        }
    }

    private enum State {
        IDLE, SELECT_LIST, INSERT_STMT, INSERT_COLS, AFTER_INSERT_COLS,
        VALUES_TUPLE, AFTER_VALUES, UPDATE_STMT, SET_LIST,
    }

    /** SELECT 列清单的终止关键字（达到即列表封口；不含本 token）。 */
    private static final Set<String> SELECT_TERMINATORS = Set.of(
            "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION", "WINDOW"
    );

    /** 语句起始关键字兜底：未正常封口的收集遇到下一条语句立即丢弃/封口，防跨语句错位。 */
    private static final Set<String> STATEMENT_STARTERS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "REPLACE", "CREATE", "DROP", "ALTER", "WITH"
    );

    /** 收集过程可变状态（对应 Kotlin plan() 内的局部变量与本地函数 resetCollect/finish）。 */
    private static final class Collect {
        int listStart = -1;
        int lastEnd = -1;
        int commas = 0;

        void resetCollect() {
            listStart = -1;
            lastEnd = -1;
            commas = 0;
        }

        /** 列表封口：元素数与跨行条件均满足才产出区域（占位符列数 = 顶层逗号分段数）。 */
        void finish(String text, List<Integer> lineStarts, int threshold, String placeholderTemplate, List<FoldSpec> specs) {
            int count = commas + 1;
            if (listStart >= 0 && lastEnd > listStart && count >= threshold) {
                int end = expandToLineEnd(text, lastEnd);
                if (lineOf(lineStarts, listStart) != lineOf(lineStarts, end)) {
                    specs.add(new FoldSpec(listStart, end, placeholderTemplate.replace("{0}", Integer.toString(count))));
                }
            }
            resetCollect();
        }
    }

    private static boolean isCollecting(State state) {
        return state == State.SELECT_LIST || state == State.INSERT_COLS
                || state == State.VALUES_TUPLE || state == State.SET_LIST;
    }

    /**
     * 规划折叠区域。
     *
     * @param text                SQL 全文（编辑器当前文本）
     * @param threshold           顶层元素数阈值（&lt; 该值不折叠；≤ 0 防御性返回空）
     * @param placeholderTemplate 占位符模板，{@code {0}} 为元素数（如 {@code … {0} 列}）
     */
    public static List<FoldSpec> plan(String text, int threshold, String placeholderTemplate) {
        if (threshold <= 0 || text.isEmpty()) return List.of();
        List<Integer> lineStarts = new ArrayList<>(text.length() / 32 + 2);
        lineStarts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lineStarts.add(i + 1);
        }

        List<FoldSpec> specs = new ArrayList<>();
        State state = State.IDLE;
        int depth = 0;
        int baseDepth = 0;
        Collect collect = new Collect();

        SqlLexer lexer = new SqlLexer();
        lexer.start(text, 0, text.length(), 0);
        while (true) {
            com.intellij.psi.tree.IElementType type = lexer.getTokenType();
            if (type == null) break;
            int ts = lexer.getTokenStart();
            int te = lexer.getTokenEnd();
            lexer.advance();
            if (type == TokenType.WHITE_SPACE || type == SqlTokenTypes.COMMENT) continue;

            if (type == SqlTokenTypes.PAREN && te - ts == 1) {
                switch (text.charAt(ts)) {
                    case '(' -> {
                        depth++;
                        if (depth == 1) {
                            // VALUES 每组元组 / INSERT 表名后列清单：深度 0→1 的括号开启收集
                            switch (state) {
                                case INSERT_STMT -> {
                                    state = State.INSERT_COLS;
                                    baseDepth = 1;
                                    collect.resetCollect();
                                }
                                case AFTER_VALUES -> {
                                    state = State.VALUES_TUPLE;
                                    baseDepth = 1;
                                    collect.resetCollect();
                                }
                                default -> {
                                }
                            }
                        }
                    }
                    case ')' -> {
                        boolean closingTuple = state == State.INSERT_COLS || state == State.VALUES_TUPLE;
                        if (closingTuple && depth == baseDepth) {
                            collect.finish(text, lineStarts, threshold, placeholderTemplate, specs);
                            state = (state == State.INSERT_COLS) ? State.AFTER_INSERT_COLS : State.AFTER_VALUES;
                        }
                        if (depth > 0) depth--;
                    }
                    default -> {
                    }
                }
                continue;
            }

            if (type == SqlTokenTypes.OPERATOR && te - ts == 1 && text.charAt(ts) == ',') {
                if (isCollecting(state) && depth == baseDepth) collect.commas++;
                continue;
            }

            // 分号 = 语句边界：未封口的收集直接丢弃（跨语句折叠没有语义）
            if (type == SqlTokenTypes.OPERATOR && te - ts == 1 && text.charAt(ts) == ';') {
                if (isCollecting(state)) collect.resetCollect();
                state = State.IDLE;
                continue;
            }

            boolean isKw = type == SqlTokenTypes.KEYWORD;
            String upper = isKw ? text.substring(ts, te).toUpperCase(java.util.Locale.ROOT) : "";

            if (isCollecting(state)) {
                if (depth != baseDepth) continue;
                // 终止关键字不入列表（FROM 吞进区域即吃掉结构），先封口再回落语句状态机
                boolean terminates = isKw && (
                        (state == State.SELECT_LIST && SELECT_TERMINATORS.contains(upper))
                                || (state == State.SET_LIST && (upper.equals("WHERE") || upper.equals("ON")))
                                || STATEMENT_STARTERS.contains(upper)
                );
                if (terminates) {
                    collect.finish(text, lineStarts, threshold, placeholderTemplate, specs);
                    state = State.IDLE;
                    if (!isKw) continue;
                } else {
                    if (collect.listStart < 0) collect.listStart = ts;
                    collect.lastEnd = te;
                    continue;
                }
            }

            if (isKw) {
                // 语句起始关键字统一重置（AFTER_VALUES 卡死防御：分号缺失的相邻语句）
                if (STATEMENT_STARTERS.contains(upper)) state = State.IDLE;
                switch (state) {
                    case IDLE -> {
                        switch (upper) {
                            case "SELECT", "REPLACE" -> state = State.SELECT_LIST;
                            case "INSERT" -> state = State.INSERT_STMT;
                            case "UPDATE" -> state = State.UPDATE_STMT;
                            case "SET" -> {
                                baseDepth = 0;
                                collect.resetCollect();
                                state = State.SET_LIST;
                            }
                            default -> {
                            }
                        }
                    }
                    case INSERT_STMT, AFTER_INSERT_COLS, AFTER_VALUES -> {
                        switch (upper) {
                            case "VALUES" -> state = State.AFTER_VALUES;
                            case "ON" -> state = State.IDLE;
                            case "SELECT" -> state = State.SELECT_LIST;
                            default -> {
                            }
                        }
                    }
                    case UPDATE_STMT -> {
                        switch (upper) {
                            case "SET" -> {
                                baseDepth = 0;
                                collect.resetCollect();
                                state = State.SET_LIST;
                            }
                            case "WHERE" -> state = State.IDLE;
                            default -> {
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        // EOF 收口：SELECT/SET 列表无终止关键字直达文末（REPLACED INTO 展示片段等）仍产出区域；
        // 括号型列表（INSERT 列/VALUES 元组）正常由 ')' 封口，EOF 到达即畸形 SQL，同样封口兜底
        if (isCollecting(state)) collect.finish(text, lineStarts, threshold, placeholderTemplate, specs);
        return specs;
    }

    /**
     * 末元素行尾扩展：末元素之后同行只剩空白到换行 → 吸收行尾空白（不含换行符），
     * 折叠后下一行原样显示 {@code FROM}/{@code )} 等结构；同行还有实质内容（压缩单行）→ 不扩展。
     */
    private static int expandToLineEnd(String text, int from) {
        int i = from;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        return (i >= text.length() || text.charAt(i) == '\n' || text.charAt(i) == '\r') ? i : from;
    }

    /** offset 所在行号（lineStarts 升序，二分）。 */
    private static int lineOf(List<Integer> lineStarts, int offset) {
        int lo = 0;
        int hi = lineStarts.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts.get(mid) <= offset) lo = mid; else hi = mid - 1;
        }
        return lo;
    }
}
