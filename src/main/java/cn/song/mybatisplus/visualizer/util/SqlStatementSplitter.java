// T-SyncK2JMaster309 自 master util/SqlStatementSplitter.kt 移植
package cn.song.mybatisplus.visualizer.util;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 语句顶层切分单实现（T-SemicolonGuard / O-13）：字符串/注释感知状态机 + 括号深度 +
 * BEGIN/END 块感知，三处历史实现（LogToSqlPanel.splitSqlStatements、
 * SqlTypeClassifier.splitStatements、SqlToJavaConverter.splitStatements）收敛委托至此。
 * 回滚链维持 JSqlParser parseStatements 不动（parser 权威，见 RollbackSqlGenerator）。
 *
 * <p>吸收 DBX `sql_editability.rs` has_top_level_semicolon 的增量语义（quote 栈 + 括号深度）：
 * <ul>
 *  <li>单/双引号内 `;` 不切分（含反斜杠转义与 SQL 标准 `''` 加倍——加倍形态按开闭交替天然受保护）；</li>
 *  <li>`--` 行注释与块注释内的 `;` 不切分（{@link #split} 注释随语句保留——原生控制台接受注释；
 *      {@link #splitStripped} 连注释一并剥离——分类/判据消费）；</li>
 *  <li><b>括号深度</b>：`(…;…)` 括号内的 `;` 不切分（存储过程参数/函数实参形态）；</li>
 *  <li><b>BEGIN/END 块感知</b>（大小写不敏感，字符串/注释内不算）：块内 `;` 不切分——仅当后方
 *      存在配对 END 时启用（保守：识别不到配对 END 按顶层切分原样，不吞后续语句）；</li>
 *  <li><b>T-SQL 控制流批首合并</b>（T-TsqlBatchSplit / O-28，DBX 1a7b44f 批首识别同型）：
 *      首段以 `IF` 起头时整批不拆——裸 `IF … ELSE …` 形态（两分支各以 `;` 终结、无
 *      BEGIN/END 包裹）后续以 `ELSE` 起头的顶层段并入同批；批内 BEGIN/END 配对失衡
 *      （截断批）时继续吞后续段直到配对或批尾（宁整勿碎，不产出 ELSE 起头的碎句）。
 *      <b>批首限定 IF</b>：语句首 `IF` 无方言歧义（MySQL `IF()` 是函数不出现在语句首位）；
 *      裸 `BEGIN` 起头<b>不</b>并入——`BEGIN; …; COMMIT` 是 MySQL 事务语法，既有「无配对
 *      END 按顶层切分原样」保守承诺维持（形态良好的 T-SQL BEGIN 块已由上方块感知整段保护）；</li>
 *  <li><b>DELIMITER 指令感知</b>（T-DelimiterAware / O-39）：`DELIMITER &lt;token&gt;` 行首指令把
 *      语句界符由 `;` 切换为 `&lt;token&gt;`（`$$`/`//` 等多字符形态按整串匹配），直到下一条
 *      `DELIMITER` 指令或文本结束；指令行本身<b>不作为独立语句产出</b>（界符 token 一并丢弃），
 *      界符生效期内的顶层 `;` 不再切分（过程体内部 `;` 原样保留）。识别限定（对齐 MySQL
 *      客户端语义 + 保守不吞）：①仅行首指令形态（行内前导仅空白）；②仅待切段为空时（语句
 *      边界）——语句中间的 `DELIMITER` 词照旧按普通文本处理；③字符串/注释内不识别；
 *      ④无参数 `DELIMITER` 复位为 `;`。识别无方言条件——文本层面判定，仅出现指令才切换。</li>
 *  <li>顶层 `;` 为界：界符本身丢弃、段 trim、空段跳过；无分号尾段非空视为一条（容错）。</li>
 * </ul>
 *
 * <p>纯函数零依赖，普通 JUnit 可直接覆盖。
 */
public final class SqlStatementSplitter {

    private SqlStatementSplitter() {}

    /**
     * T-SQL 控制流批首关键字（批首识别，DBX `starts_with_sqlserver_control_flow_batch` 同型）。
     * 限定 IF：语句首 IF 无方言歧义；裸 BEGIN 是 MySQL 事务形态不并入（见类注释）。
     */
    private static final Set<String> CONTROL_FLOW_BATCH_HEADS = Set.of("IF");

    /** 默认语句界符（MySQL 客户端 {@code DELIMITER} 指令可切换；无参数指令复位为此值）。 */
    private static final String DEFAULT_DELIMITER = ";";

    /** {@code DELIMITER} 指令关键字（行首指令形态识别，大小写不敏感）。 */
    private static final String DELIMITER_DIRECTIVE = "DELIMITER";

    /** 标识符字符（BEGIN/END 词边界判定）：字母/数字/下划线/$/#。 */
    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    /**
     * 顶层切分（注释随语句保留，段尾分号丢弃，段 trim，空段跳过）——
     * 预览/执行/批量转换链契约（LogToSqlPanel / SqlToJavaConverter 迁移前行为）。
     */
    public static List<String> split(@NotNull String sql) {
        return splitInternal(sql, true);
    }

    /**
     * 顶层切分 + 引号感知去注释（空段跳过）——分类/确认流契约
     * （SqlTypeClassifier 迁移前「切分后去注释」的等价收口；修复其字符串内分号误切与
     * 字符串内 `--` 误剥两个历史缺陷，见开发计划 T-SG-01 差异清单）。
     */
    public static List<String> splitStripped(@NotNull String sql) {
        return splitInternal(sql, false);
    }

    private static List<String> splitInternal(@NotNull String sql, boolean keepComments) {
        List<String> parts = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inLine = false;
        boolean inBlock = false;
        int parenDepth = 0;
        int blockDepth = 0;
        // BEGIN/END 启用前视：见到 BEGIN 先看后方有无配对 END（保守启用）
        boolean beginModeEnabled = false;
        StringBuilder word = new StringBuilder();
        // 当前词的首字符下标与词起点处的缓冲长度（DELIMITER 指令的行首/语句边界判定用；
        // word 非空时有效）
        int wordStart = -1;
        int bufferLenAtWordStart = 0;
        // 当前生效的语句界符（T-DA-02：DELIMITER 指令切换；默认 `;`）
        String delimiter = DEFAULT_DELIMITER;
        int i = 0;

        while (i < sql.length()) {
            char c = sql.charAt(i);
            Character next = i + 1 < sql.length() ? sql.charAt(i + 1) : null;
            if (inLine) {
                if (keepComments) sb.append(c);
                if (c == '\n') inLine = false;
                i++;
            } else if (inBlock) {
                if (keepComments) sb.append(c);
                if (c == '*' && next != null && next == '/') {
                    if (keepComments) sb.append(next.charValue());
                    inBlock = false;
                    i++;
                }
                i++;
            } else if (inSingle) {
                sb.append(c);
                if (c == '\\' && next != null) {
                    sb.append(next.charValue());
                    i++;
                } else if (c == '\'') {
                    inSingle = false;
                }
                i++;
            } else if (inDouble) {
                sb.append(c);
                if (c == '\\' && next != null) {
                    sb.append(next.charValue());
                    i++;
                } else if (c == '"') {
                    inDouble = false;
                }
                i++;
            } else if (!delimiter.equals(DEFAULT_DELIMITER) && sql.startsWith(delimiter, i)) {
                // T-DA-02：自定义界符整串匹配——先于注释/引号/标识符分支（`$$` 的 `$` 是
                // 标识符字符，须先命中；`//` 与块注释起始 `/*` 字符不同故与注释分支不冲突）
                // flushWord
                if (word.length() > 0) {
                    String token = word.toString();
                    word.setLength(0);
                    if (beginModeEnabled) {
                        String upper = token.toUpperCase(Locale.ROOT);
                        if (upper.equals("BEGIN")) blockDepth++;
                        else if (upper.equals("END") && blockDepth > 0) blockDepth--;
                    }
                }
                if (parenDepth == 0 && blockDepth == 0) {
                    String part = sb.toString().trim();
                    if (!part.isEmpty()) parts.add(part);
                    sb.setLength(0);
                } else {
                    sb.append(delimiter);
                }
                i += delimiter.length();
            } else if (c == '-' && next != null && next == '-') {
                // flushWord
                if (word.length() > 0) {
                    String token = word.toString();
                    word.setLength(0);
                    if (beginModeEnabled) {
                        String upper = token.toUpperCase(Locale.ROOT);
                        if (upper.equals("BEGIN")) blockDepth++;
                        else if (upper.equals("END") && blockDepth > 0) blockDepth--;
                    }
                }
                if (keepComments) sb.append(c);
                inLine = true;
                i++;
            } else if (c == '/' && next != null && next == '*') {
                // flushWord
                if (word.length() > 0) {
                    String token = word.toString();
                    word.setLength(0);
                    if (beginModeEnabled) {
                        String upper = token.toUpperCase(Locale.ROOT);
                        if (upper.equals("BEGIN")) blockDepth++;
                        else if (upper.equals("END") && blockDepth > 0) blockDepth--;
                    }
                }
                if (keepComments) sb.append(c);
                inBlock = true;
                i++;
            } else if (c == '\'') {
                // flushWord
                if (word.length() > 0) {
                    String token = word.toString();
                    word.setLength(0);
                    if (beginModeEnabled) {
                        String upper = token.toUpperCase(Locale.ROOT);
                        if (upper.equals("BEGIN")) blockDepth++;
                        else if (upper.equals("END") && blockDepth > 0) blockDepth--;
                    }
                }
                inSingle = true;
                sb.append(c);
                i++;
            } else if (c == '"') {
                // flushWord
                if (word.length() > 0) {
                    String token = word.toString();
                    word.setLength(0);
                    if (beginModeEnabled) {
                        String upper = token.toUpperCase(Locale.ROOT);
                        if (upper.equals("BEGIN")) blockDepth++;
                        else if (upper.equals("END") && blockDepth > 0) blockDepth--;
                    }
                }
                inDouble = true;
                sb.append(c);
                i++;
            } else if (c == ';') {
                // flushWord
                if (word.length() > 0) {
                    String token = word.toString();
                    word.setLength(0);
                    if (beginModeEnabled) {
                        String upper = token.toUpperCase(Locale.ROOT);
                        if (upper.equals("BEGIN")) blockDepth++;
                        else if (upper.equals("END") && blockDepth > 0) blockDepth--;
                    }
                }
                if (delimiter.equals(DEFAULT_DELIMITER) && parenDepth == 0 && blockDepth == 0) {
                    String part = sb.toString().trim();
                    if (!part.isEmpty()) parts.add(part);
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
                i++;
            } else if (c == '(') {
                // flushWord
                if (word.length() > 0) {
                    String token = word.toString();
                    word.setLength(0);
                    if (beginModeEnabled) {
                        String upper = token.toUpperCase(Locale.ROOT);
                        if (upper.equals("BEGIN")) blockDepth++;
                        else if (upper.equals("END") && blockDepth > 0) blockDepth--;
                    }
                }
                parenDepth++;
                sb.append(c);
                i++;
            } else if (c == ')') {
                // flushWord
                if (word.length() > 0) {
                    String token = word.toString();
                    word.setLength(0);
                    if (beginModeEnabled) {
                        String upper = token.toUpperCase(Locale.ROOT);
                        if (upper.equals("BEGIN")) blockDepth++;
                        else if (upper.equals("END") && blockDepth > 0) blockDepth--;
                    }
                }
                parenDepth = Math.max(parenDepth - 1, 0);
                sb.append(c);
                i++;
            } else if (isIdentifierChar(c)) {
                if (word.length() == 0) {
                    wordStart = i;
                    bufferLenAtWordStart = sb.length();
                }
                word.append(c);
                sb.append(c);
                i++;
            } else {
                // 非标识符定界符：词终结（DELIMITER 指令 / BEGIN 前视在此触发）
                if (word.length() > 0
                        && word.toString().equalsIgnoreCase(DELIMITER_DIRECTIVE)
                        && Character.isWhitespace(c)
                        && isLineHead(sql, wordStart)
                        && sb.substring(0, bufferLenAtWordStart).isBlank()) {
                    // T-DA-02：行首 `DELIMITER <token>` 指令——切换界符并整行丢弃（不产出语句）
                    word.setLength(0);
                    sb.setLength(0);
                    DelimiterDirective directive = readDelimiterDirective(sql, i);
                    delimiter = directive.token();
                    i = directive.lineEnd();
                } else if (word.length() > 0 && word.toString().equalsIgnoreCase("BEGIN")
                        && !beginModeEnabled && hasMatchingEndAhead(sql, i)) {
                    beginModeEnabled = true;
                    blockDepth = 1;
                    word.setLength(0);
                    sb.append(c);
                    i++;
                } else {
                    // flushWord
                    if (word.length() > 0) {
                        String token = word.toString();
                        word.setLength(0);
                        if (beginModeEnabled) {
                            String upper = token.toUpperCase(Locale.ROOT);
                            if (upper.equals("BEGIN")) blockDepth++;
                            else if (upper.equals("END") && blockDepth > 0) blockDepth--;
                        }
                    }
                    sb.append(c);
                    i++;
                }
            }
        }
        // 尾部未终结词（无定界符收尾）：补一次 BEGIN 前视判定
        if (word.length() > 0 && word.toString().equalsIgnoreCase("BEGIN")
                && !beginModeEnabled && hasMatchingEndAhead(sql, sql.length())) {
            beginModeEnabled = true;
            blockDepth = 1;
            word.setLength(0);
        } else {
            // flushWord
            if (word.length() > 0) {
                String token = word.toString();
                word.setLength(0);
                if (beginModeEnabled) {
                    String upper = token.toUpperCase(Locale.ROOT);
                    if (upper.equals("BEGIN")) blockDepth++;
                    else if (upper.equals("END") && blockDepth > 0) blockDepth--;
                }
            }
        }
        String tail = sb.toString().trim();
        // 无分号结尾的残余段：非空视为一条语句（容错——用户手动删尾分号后仍可执行）
        if (!tail.isEmpty()) parts.add(tail);
        return mergeControlFlowBatches(parts);
    }

    /**
     * T-SQL 控制流批首合并（T-TsqlBatchSplit / O-28）：首段以 IF 起头 → 整批不拆。
     *
     * <p>两形态（宁整勿碎，识别不了整段保留）：
     * <ul>
     *  <li>裸 `IF …; ELSE …;`：两分支各自以顶层 `;` 终结且无 BEGIN/END 包裹，逐段切分会产出
     *      以 ELSE 起头的碎句（语法不成立）——后续以 ELSE 起头的顶层段（含 ELSE IF 链）并入同批；</li>
     *  <li>配对失衡（截断批）：IF 批内 BEGIN/END 计数未闭合（字符串/注释感知），继续吞后续
     *      段直到配对或批尾——碎半截控制流送进确认流/执行只会产出误导性语法错。</li>
     * </ul>
     *
     * <p>MySQL `IF()` 是函数不出现在语句首位（`SELECT IF(…)`），SELECT 起头不触发合并；
     * 裸 BEGIN 起头不并入（MySQL 事务形态，保守承诺维持）；批首段之后的独立语句
     * （非 ELSE 起头、已配对闭合）不受影响。
     */
    @NotNull
    private static List<String> mergeControlFlowBatches(@NotNull List<String> parts) {
        if (parts.size() < 2) return parts;
        List<String> out = new ArrayList<>(parts.size());
        int i = 0;
        while (i < parts.size()) {
            String batch = parts.get(i);
            String head = firstKeywordOf(batch);
            if (head != null && CONTROL_FLOW_BATCH_HEADS.contains(head)) {
                int j = i + 1;
                while (j < parts.size()) {
                    boolean unbalanced = beginEndBalance(batch) > 0;
                    String nextHead = firstKeywordOf(parts.get(j));
                    boolean nextIsElse = "ELSE".equals(nextHead);
                    if (!unbalanced && !nextIsElse) break;
                    batch += "; " + parts.get(j);
                    j++;
                }
                out.add(batch);
                i = j;
            } else {
                out.add(batch);
                i++;
            }
        }
        return out;
    }

    /**
     * 行首判定（T-DA-02）：{@code index} 之前到行首之间是否只有空白（无前导内容）。
     * 对齐 MySQL 客户端语义——{@code DELIMITER} 只在行首起指令作用，行内出现按普通文本处理。
     */
    private static boolean isLineHead(@NotNull String sql, int index) {
        int i = index - 1;
        while (i >= 0) {
            char c = sql.charAt(i);
            if (c == '\n') return true;
            if (!Character.isWhitespace(c)) return false;
            i--;
        }
        return true;
    }

    /** {@code DELIMITER} 指令读取结果（界符 token + 指令行尾下标）。 */
    private record DelimiterDirective(String token, int lineEnd) {
    }

    /**
     * 读取 {@code DELIMITER} 指令的界符 token（T-DA-02）：取指令所在行去除首尾空白后的首个
     * 空白分隔词（对齐 MySQL 客户端「行余内容即界符」语义，行尾多余内容忽略）；空 token
     * 复位为默认界符 {@code ;}。
     *
     * @param from 指令关键字之后的第一个字符下标（调用点已确认此处为空白）
     * @return 界符 token 与指令行尾下标——行尾下标指向 {@code \n} 或文本末尾（行本身整行丢弃）
     */
    private static DelimiterDirective readDelimiterDirective(@NotNull String sql, int from) {
        int end = from;
        while (end < sql.length() && sql.charAt(end) != '\n') end++;
        String rest = sql.substring(from, end).trim();
        int tokenEnd = 0;
        while (tokenEnd < rest.length() && !Character.isWhitespace(rest.charAt(tokenEnd))) tokenEnd++;
        String token = rest.substring(0, tokenEnd);
        return new DelimiterDirective(token.isEmpty() ? DEFAULT_DELIMITER : token, end);
    }

    /** 段首有效关键字（跳过空白与 `--` 行注释、`斜杠星` 块注释后的首个标识符词，大写；无则 null）。 */
    private static String firstKeywordOf(@NotNull String part) {
        int i = 0;
        while (i < part.length()) {
            char c = part.charAt(i);
            Character next = i + 1 < part.length() ? part.charAt(i + 1) : null;
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && next != null && next == '-') {
                while (i < part.length() && part.charAt(i) != '\n') i++;
            } else if (c == '/' && next != null && next == '*') {
                i += 2;
                while (i < part.length()
                        && !(part.charAt(i) == '*' && i + 1 < part.length() && part.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else if (isIdentifierChar(c)) {
                StringBuilder sb = new StringBuilder();
                while (i < part.length() && isIdentifierChar(part.charAt(i))) {
                    sb.append(part.charAt(i));
                    i++;
                }
                return sb.toString().toUpperCase(Locale.ROOT);
            } else {
                return null;
            }
        }
        return null;
    }

    /** 段内顶层 BEGIN/END 配对计数（字符串/注释感知；&gt;0 = 未闭合的截断批）。 */
    private static int beginEndBalance(@NotNull String part) {
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inLine = false;
        boolean inBlock = false;
        StringBuilder word = new StringBuilder();
        int i = 0;
        while (i < part.length()) {
            char c = part.charAt(i);
            Character next = i + 1 < part.length() ? part.charAt(i + 1) : null;
            if (inLine) {
                if (c == '\n') inLine = false;
            } else if (inBlock) {
                if (c == '*' && next != null && next == '/') {
                    inBlock = false;
                    i++;
                }
            } else if (inSingle) {
                if (c == '\\' && next != null) {
                    i++;
                } else if (c == '\'') {
                    inSingle = false;
                }
            } else if (inDouble) {
                if (c == '\\' && next != null) {
                    i++;
                } else if (c == '"') {
                    inDouble = false;
                }
            } else if (c == '-' && next != null && next == '-') {
                // flush
                if (word.length() > 0) {
                    String upper = word.toString().toUpperCase(Locale.ROOT);
                    if (upper.equals("BEGIN")) depth++;
                    else if (upper.equals("END") && depth > 0) depth--;
                    word.setLength(0);
                }
                inLine = true;
                i++;
            } else if (c == '/' && next != null && next == '*') {
                // flush
                if (word.length() > 0) {
                    String upper = word.toString().toUpperCase(Locale.ROOT);
                    if (upper.equals("BEGIN")) depth++;
                    else if (upper.equals("END") && depth > 0) depth--;
                    word.setLength(0);
                }
                inBlock = true;
                i++;
            } else if (c == '\'') {
                // flush
                if (word.length() > 0) {
                    String upper = word.toString().toUpperCase(Locale.ROOT);
                    if (upper.equals("BEGIN")) depth++;
                    else if (upper.equals("END") && depth > 0) depth--;
                    word.setLength(0);
                }
                inSingle = true;
            } else if (c == '"') {
                // flush
                if (word.length() > 0) {
                    String upper = word.toString().toUpperCase(Locale.ROOT);
                    if (upper.equals("BEGIN")) depth++;
                    else if (upper.equals("END") && depth > 0) depth--;
                    word.setLength(0);
                }
                inDouble = true;
            } else if (isIdentifierChar(c)) {
                word.append(c);
            } else {
                // flush
                if (word.length() > 0) {
                    String upper = word.toString().toUpperCase(Locale.ROOT);
                    if (upper.equals("BEGIN")) depth++;
                    else if (upper.equals("END") && depth > 0) depth--;
                    word.setLength(0);
                }
            }
            i++;
        }
        // flush（循环尾）
        if (word.length() > 0) {
            String upper = word.toString().toUpperCase(Locale.ROOT);
            if (upper.equals("BEGIN")) depth++;
            else if (upper.equals("END") && depth > 0) depth--;
        }
        return depth;
    }

    /**
     * 前视扫描：`from` 之后是否存在词边界形态的 END（字符串/注释感知，大小写不敏感）。
     * 保守判定——用于决定是否启用 BEGIN/END 块感知（识别不到配对 END 则按顶层切分原样）。
     */
    private static boolean hasMatchingEndAhead(@NotNull String sql, int from) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inLine = false;
        boolean inBlock = false;
        StringBuilder word = new StringBuilder();
        int i = from;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            Character next = i + 1 < sql.length() ? sql.charAt(i + 1) : null;
            if (inLine) {
                if (c == '\n') inLine = false;
            } else if (inBlock) {
                if (c == '*' && next != null && next == '/') {
                    inBlock = false;
                    i++;
                }
            } else if (inSingle) {
                if (c == '\\' && next != null) {
                    i++;
                } else if (c == '\'') {
                    inSingle = false;
                }
            } else if (inDouble) {
                if (c == '\\' && next != null) {
                    i++;
                } else if (c == '"') {
                    inDouble = false;
                }
            } else if (c == '-' && next != null && next == '-') {
                inLine = true;
                i++;
            } else if (c == '/' && next != null && next == '*') {
                inBlock = true;
                i++;
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (isIdentifierChar(c)) {
                word.append(c);
            } else {
                if (word.length() > 0 && word.toString().equalsIgnoreCase("END")) return true;
                word.setLength(0);
            }
            i++;
        }
        return word.length() > 0 && word.toString().equalsIgnoreCase("END");
    }
}
