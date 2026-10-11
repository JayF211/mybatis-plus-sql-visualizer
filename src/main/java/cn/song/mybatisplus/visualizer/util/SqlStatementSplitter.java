// T-SyncK2JMaster309 自 master util/SqlStatementSplitter.kt 移植
// T-SyncK2JMaster187 增量（c07b8e3 → bccbfc5）：O-60 T-STG-01 非块 BEGIN（BEGIN TRAN/TRANSACTION/…）
// 豁免武装 + T-STG-02 顶层 END 隐式语句边界（T-SQL 惯例 END 后分号可省）；T-PerfSweep7 T-PS7-01
// END 分支单次取词（nextWordToken）替代 5 次同段扫描 + DECLARE 头前视合并（hasBlockBodyAhead）；
// 测试缝 lookaheadProbeCount（Kotlin internal var → public 静态字段，Kotlin 测试直读属性语法）
// T-SyncK2JMaster482 增量（b7246f2）：T-PerfSweep16 F45 取词去重——nextWord/nextWordToken 合并为
// 单一核心（nextWord 原样返回、不计数；nextWordToken 降为计数薄包装），死代码 nextWordIs 移除
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
 *      存在配对 END 时启用（保守：识别不到配对 END 按顶层切分原样，不吞后续语句）；
 *      `BEGIN` 后随 TRAN/TRANSACTION/DISTRIBUTED/DIALOG/CONVERSATION 是事务/会话形态
 *      而非块，不武装（T-STG-01，DBX `SQLSERVER_NON_BLOCK_BEGIN_KEYWORDS` 同型；全表
 *      未逐词核对，保守 5 词）——否则后方任何 END 词（CASE/注释/游标声明）都会误武装且
 *      深度永不归零，后续顶层 `;` 全部失切；
 *      块深度归零的<b>顶层</b> `END`（1→0，且不在声明段吞并中）产生一次隐式语句边界
 *      （T-STG-02）：T-SQL 惯例 END 后分号可省，只认 `;` 会把后续语句误并入同段；
 *      `END` 后跟 `;`/界符走既有分支（Oracle 匿名块 `END;` 语义不变）；</li>
 *  <li><b>T-SQL 控制流批首合并</b>（T-TsqlBatchSplit / O-28，DBX 1a7b44f 批首识别同型）：
 *      首段以 `IF` 起头时整批不拆——裸 `IF … ELSE …` 形态（两分支各以 `;` 终结、无
 *      BEGIN/END 包裹）后续以 `ELSE` 起头的顶层段并入同批；批内 BEGIN/END 配对失衡
 *      （截断批）时继续吞后续段直到配对或批尾（宁整勿碎，不产出 ELSE 起头的碎句）。
 *      <b>批首限定 IF</b>：语句首 `IF` 无方言歧义（MySQL `IF()` 是函数不出现在语句首位）；
 *      裸 `BEGIN` 起头<b>不</b>并入——`BEGIN; …; COMMIT` 是 MySQL 事务语法，既有「无配对
 *      END 按顶层切分原样」保守承诺维持（形态良好的 T-SQL BEGIN 块已由上方块感知整段保护）；</li>
 *  <li><b>Oracle PL/SQL 匿名块感知</b>（T-SqlSplitterPlsql / O-47，T-SSP-01）：语句首 `DECLARE` 且
 *      后方存在配对 `END` 时进入<b>声明段</b>——段内顶层 `;` 是声明分隔符而非语句界符（前向声明
 *      `PROCEDURE p IS;`、局部例程体 `PROCEDURE p IS BEGIN … END;` 与块主体同属一条语句）。
 *      声明段边界（`DECLARE` 之后或声明段顶层 `;` 之后）的 `BEGIN` 即主体起始，此后按常规块
 *      计数，主体 `END` 使深度归零、后续顶层 `;` 恢复切分。吞并边界＝<b>深度归零处</b>而非「末个
 *      END」——后者会把紧随的第二个匿名块并进来（`DECLARE … END; BEGIN … END;` 实测 2 条）。</li>
 *  <li><b>CASE 层隔离</b>（T-SSP-02）：`CASE … END` / `CASE … END CASE` 自成一层，其 `END` 不递减
 *      块深度（否则块内 `CASE` 的 `END` 被当块尾 → 块被切碎）。`END CASE` 是双词终结形态，
 *      尾随 `CASE` 词不再开新层——否则层数被补回、块尾 `END` 反被 CASE 层吞掉（吞并过度）。</li>
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

    /**
     * T-SQL 非块 BEGIN 后随词表（T-STG-01，DBX {@code SQLSERVER_NON_BLOCK_BEGIN_KEYWORDS} 同型）：
     * {@code BEGIN} 后随这些词是事务/会话开启形态而非块，不武装块计数。仅当 {@code BEGIN} 处于武装
     * 候选（后方存在 END 词）时才有意义——此处恰是边缘 A 的误武装触发面。
     * 全表未逐词核对（DBX 仓不可达），按计划保守 5 词执行，拿不准的词不进表。
     */
    private static final Set<String> NON_BLOCK_BEGIN_TAILS =
            Set.of("TRAN", "TRANSACTION", "DISTRIBUTED", "DIALOG", "CONVERSATION");

    /**
     * {@code from} 之后首个词（跳过空白/注释，大小写不敏感）是否属于 {@link #NON_BLOCK_BEGIN_TAILS}。
     * F23（T-PerfSweep9）：单趟取词进 Set 比对——原 {@code NON_BLOCK_BEGIN_TAILS.any { nextWordIs(...) }}
     * 同段最多 5 次扫描（与 PS7-01 消灭的「同段重复扫描」同型）。
     * F45（T-PerfSweep16）：取词收敛至 {@link #nextWord} 单一核心（与 nextWordToken 合并），
     * 本路径不计入 {@link #lookaheadProbeCount}（诊断口径不变）。
     */
    private static boolean isNonBlockBeginTail(@NotNull String sql, int from) {
        String word = nextWord(sql, from);
        if (word == null) return false;
        for (String tail : NON_BLOCK_BEGIN_TAILS) {
            if (word.equalsIgnoreCase(tail)) return true;
        }
        return false;
    }

    /**
     * 单趟取词核心（F45 合并：F23 的 nextWord 与 T-PS7-01 的 nextWordToken 两份同构遍历
     * 收敛于此，唯一实现）：{@code from} 之后跳过空白与注释（行注释至换行、块注释至闭合）的
     * 首个标识符词，大小写<b>原样</b>返回，无则 null。合并前两份遍历逐位一致（同跳过规则、
     * 同词边界、同非标识符停机条件），唯一差异是旧 nextWord 额外做大写归一——其唯一
     * 调用方 {@link #isNonBlockBeginTail} 本就 ignoreCase 比对，归一与否不影响结果，故统一为
     * 原样返回（nextWordToken 的消费方各自 {@code .uppercase()}，亦不受影响）。
     *
     * <p><b>不累加 {@link #lookaheadProbeCount}</b>：T-PS7-01 诊断口径只统计 END 分支前视
     * （由 {@link #nextWordToken} 包装层累加），BEGIN TRAN 判定（{@link #isNonBlockBeginTail}）不计入
     * ——保原计数口径，合并前后计数逐位一致。
     */
    private static String nextWord(@NotNull String sql, int from) {
        int i = from;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            Character next = i + 1 < sql.length() ? sql.charAt(i + 1) : null;
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && next != null && next == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
            } else if (c == '/' && next != null && next == '*') {
                i += 2;
                while (i < sql.length()
                        && !(sql.charAt(i) == '*' && i + 1 < sql.length() && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else if (isIdentifierChar(c)) {
                StringBuilder sb = new StringBuilder();
                while (i < sql.length() && isIdentifierChar(sql.charAt(i))) {
                    sb.append(sql.charAt(i));
                    i++;
                }
                return sb.toString();
            } else {
                return null;
            }
        }
        return null;
    }

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
        lookaheadProbeCount = 0; // T-PS7-01 计数缝：每次切分清零
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
        // T-SSP-01：语句首 DECLARE 且后方存在配对 END → 处于声明段（段内顶层 `;` 不切分）
        boolean declareSection = false;
        // 声明段边界（`DECLARE` 之后或声明段顶层 `;` 之后）——紧随的 BEGIN 即块主体起始
        boolean bodyBeginExpected = false;
        // T-SSP-02：CASE 层计数（CASE…END / CASE…END CASE 不参与块配对）
        int caseDepth = 0;
        // `END CASE` 双词终结形态：END 前视到 CASE 词后置位，供紧随的 CASE 词消费
        boolean caseEndTailPending = false;
        // T-STG-02：块深度归零的顶层 END 已闭合——主循环下一个非空白内容（非 `;`/注释/
        // DELIMITER 指令）处产生一次隐式语句边界（T-SQL 惯例 END 后分号可省，只认 `;`
        // 会把后续语句误并入同段）
        boolean topBlockJustClosed = false;
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
                        switch (token.toUpperCase(Locale.ROOT)) {
                            // `END CASE` 的尾词：已由 END 分支按 CASE 终结符消费，不再开新层
                            case "CASE" -> {
                                if (caseEndTailPending) caseEndTailPending = false;
                                else caseDepth++;
                            }
                            case "BEGIN" -> blockDepth++;
                            // T-SSP-01 订正（独立复核 major③）：`END IF` / `END LOOP` / `END WHILE` /
                            // `END REPEAT` 是内层终结符，不减块深度——否则含 IF/LOOP 的 PL/SQL 块
                            // （匿名块与具名过程）依旧被切碎（复核实测 `BEGIN IF 1=1 THEN NULL; END IF; END;`
                            // 基线 2 段 → 本改动前仍 2 段）。与 CASE 层的差别：CASE 有裸 END 收尾形态，
                            // 故须 caseDepth 计数；而 IF/LOOP/WHILE/REPEAT 只有双词终结形态，直接跳过递减即可
                            // （它们也不自增任何层计数，故无需配对状态）。
                            case "END" -> {
                                // T-PS7-01：CASE/IF/LOOP/WHILE/REPEAT 五个候选词单次取词后各自比对
                                // （原为 nextWordIs 对同一空白注释段重复扫描 5 次；跳过语义逐位一致）
                                String upper = nextWordToken(sql, i);
                                if (upper != null) upper = upper.toUpperCase(Locale.ROOT);
                                if ("CASE".equals(upper)) caseEndTailPending = true;
                                boolean innerLayerTail = "IF".equals(upper) || "LOOP".equals(upper)
                                        || "WHILE".equals(upper) || "REPEAT".equals(upper);
                                if (!innerLayerTail) {
                                    if (caseDepth > 0) caseDepth--;        // CASE 收尾（裸 END / END CASE）
                                    else if (blockDepth > 0) {
                                        // T-STG-02：顶层块闭合（1→0 且不在声明段吞并中）——置隐式边界
                                        // 标记。声明段内例程体的 END 归零后声明段仍在吞并（T-SSP-01），
                                        // 边界由主体 BEGIN/END 接力，此处不得置位
                                        if (blockDepth == 1 && !declareSection) topBlockJustClosed = true;
                                        blockDepth--;
                                    }
                                }
                            }
                            default -> bodyBeginExpected = false;
                        }
                    }
                }
                if (parenDepth == 0 && blockDepth == 0) {
                    String part = sb.toString().trim();
                    if (!part.isEmpty()) parts.add(part);
                    sb.setLength(0);
                    // 语句边界：块/CASE/声明段状态随段重置（防跨语句状态残留）
                    declareSection = false;
                    bodyBeginExpected = false;
                    caseDepth = 0;
                    caseEndTailPending = false;
                    topBlockJustClosed = false; // T-STG-02：界符已兑现边界
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
                        switch (token.toUpperCase(Locale.ROOT)) {
                            // `END CASE` 的尾词：已由 END 分支按 CASE 终结符消费，不再开新层
                            case "CASE" -> {
                                if (caseEndTailPending) caseEndTailPending = false;
                                else caseDepth++;
                            }
                            case "BEGIN" -> blockDepth++;
                            // T-SSP-01 订正（独立复核 major③）：`END IF` / `END LOOP` / `END WHILE` /
                            // `END REPEAT` 是内层终结符，不减块深度——否则含 IF/LOOP 的 PL/SQL 块
                            // （匿名块与具名过程）依旧被切碎（复核实测 `BEGIN IF 1=1 THEN NULL; END IF; END;`
                            // 基线 2 段 → 本改动前仍 2 段）。与 CASE 层的差别：CASE 有裸 END 收尾形态，
                            // 故须 caseDepth 计数；而 IF/LOOP/WHILE/REPEAT 只有双词终结形态，直接跳过递减即可
                            // （它们也不自增任何层计数，故无需配对状态）。
                            case "END" -> {
                                // T-PS7-01：CASE/IF/LOOP/WHILE/REPEAT 五个候选词单次取词后各自比对
                                // （原为 nextWordIs 对同一空白注释段重复扫描 5 次；跳过语义逐位一致）
                                String upper = nextWordToken(sql, i);
                                if (upper != null) upper = upper.toUpperCase(Locale.ROOT);
                                if ("CASE".equals(upper)) caseEndTailPending = true;
                                boolean innerLayerTail = "IF".equals(upper) || "LOOP".equals(upper)
                                        || "WHILE".equals(upper) || "REPEAT".equals(upper);
                                if (!innerLayerTail) {
                                    if (caseDepth > 0) caseDepth--;        // CASE 收尾（裸 END / END CASE）
                                    else if (blockDepth > 0) {
                                        // T-STG-02：顶层块闭合（1→0 且不在声明段吞并中）——置隐式边界
                                        // 标记。声明段内例程体的 END 归零后声明段仍在吞并（T-SSP-01），
                                        // 边界由主体 BEGIN/END 接力，此处不得置位
                                        if (blockDepth == 1 && !declareSection) topBlockJustClosed = true;
                                        blockDepth--;
                                    }
                                }
                            }
                            default -> bodyBeginExpected = false;
                        }
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
                        switch (token.toUpperCase(Locale.ROOT)) {
                            // `END CASE` 的尾词：已由 END 分支按 CASE 终结符消费，不再开新层
                            case "CASE" -> {
                                if (caseEndTailPending) caseEndTailPending = false;
                                else caseDepth++;
                            }
                            case "BEGIN" -> blockDepth++;
                            // T-SSP-01 订正（独立复核 major③）：`END IF` / `END LOOP` / `END WHILE` /
                            // `END REPEAT` 是内层终结符，不减块深度——否则含 IF/LOOP 的 PL/SQL 块
                            // （匿名块与具名过程）依旧被切碎（复核实测 `BEGIN IF 1=1 THEN NULL; END IF; END;`
                            // 基线 2 段 → 本改动前仍 2 段）。与 CASE 层的差别：CASE 有裸 END 收尾形态，
                            // 故须 caseDepth 计数；而 IF/LOOP/WHILE/REPEAT 只有双词终结形态，直接跳过递减即可
                            // （它们也不自增任何层计数，故无需配对状态）。
                            case "END" -> {
                                // T-PS7-01：CASE/IF/LOOP/WHILE/REPEAT 五个候选词单次取词后各自比对
                                // （原为 nextWordIs 对同一空白注释段重复扫描 5 次；跳过语义逐位一致）
                                String upper = nextWordToken(sql, i);
                                if (upper != null) upper = upper.toUpperCase(Locale.ROOT);
                                if ("CASE".equals(upper)) caseEndTailPending = true;
                                boolean innerLayerTail = "IF".equals(upper) || "LOOP".equals(upper)
                                        || "WHILE".equals(upper) || "REPEAT".equals(upper);
                                if (!innerLayerTail) {
                                    if (caseDepth > 0) caseDepth--;        // CASE 收尾（裸 END / END CASE）
                                    else if (blockDepth > 0) {
                                        // T-STG-02：顶层块闭合（1→0 且不在声明段吞并中）——置隐式边界
                                        // 标记。声明段内例程体的 END 归零后声明段仍在吞并（T-SSP-01），
                                        // 边界由主体 BEGIN/END 接力，此处不得置位
                                        if (blockDepth == 1 && !declareSection) topBlockJustClosed = true;
                                        blockDepth--;
                                    }
                                }
                            }
                            default -> bodyBeginExpected = false;
                        }
                    }
                }
                if (keepComments) sb.append(c);
                inBlock = true;
                i++;
            } else if (c == '\'') {
                // T-STG-02：隐式边界兑现——已完成块作为独立段产出（空段跳过），残余内容续算
                if (topBlockJustClosed) {
                    topBlockJustClosed = false;
                    String boundaryPart = sb.toString().trim();
                    if (!boundaryPart.isEmpty()) parts.add(boundaryPart);
                    sb.setLength(0);
                }
                // flushWord
                if (word.length() > 0) {
                    String token = word.toString();
                    word.setLength(0);
                    if (beginModeEnabled) {
                        switch (token.toUpperCase(Locale.ROOT)) {
                            // `END CASE` 的尾词：已由 END 分支按 CASE 终结符消费，不再开新层
                            case "CASE" -> {
                                if (caseEndTailPending) caseEndTailPending = false;
                                else caseDepth++;
                            }
                            case "BEGIN" -> blockDepth++;
                            // T-SSP-01 订正（独立复核 major③）：`END IF` / `END LOOP` / `END WHILE` /
                            // `END REPEAT` 是内层终结符，不减块深度——否则含 IF/LOOP 的 PL/SQL 块
                            // （匿名块与具名过程）依旧被切碎（复核实测 `BEGIN IF 1=1 THEN NULL; END IF; END;`
                            // 基线 2 段 → 本改动前仍 2 段）。与 CASE 层的差别：CASE 有裸 END 收尾形态，
                            // 故须 caseDepth 计数；而 IF/LOOP/WHILE/REPEAT 只有双词终结形态，直接跳过递减即可
                            // （它们也不自增任何层计数，故无需配对状态）。
                            case "END" -> {
                                // T-PS7-01：CASE/IF/LOOP/WHILE/REPEAT 五个候选词单次取词后各自比对
                                // （原为 nextWordIs 对同一空白注释段重复扫描 5 次；跳过语义逐位一致）
                                String upper = nextWordToken(sql, i);
                                if (upper != null) upper = upper.toUpperCase(Locale.ROOT);
                                if ("CASE".equals(upper)) caseEndTailPending = true;
                                boolean innerLayerTail = "IF".equals(upper) || "LOOP".equals(upper)
                                        || "WHILE".equals(upper) || "REPEAT".equals(upper);
                                if (!innerLayerTail) {
                                    if (caseDepth > 0) caseDepth--;        // CASE 收尾（裸 END / END CASE）
                                    else if (blockDepth > 0) {
                                        // T-STG-02：顶层块闭合（1→0 且不在声明段吞并中）——置隐式边界
                                        // 标记。声明段内例程体的 END 归零后声明段仍在吞并（T-SSP-01），
                                        // 边界由主体 BEGIN/END 接力，此处不得置位
                                        if (blockDepth == 1 && !declareSection) topBlockJustClosed = true;
                                        blockDepth--;
                                    }
                                }
                            }
                            default -> bodyBeginExpected = false;
                        }
                    }
                }
                inSingle = true;
                sb.append(c);
                i++;
            } else if (c == '"') {
                // T-STG-02：隐式边界兑现——已完成块作为独立段产出（空段跳过），残余内容续算
                if (topBlockJustClosed) {
                    topBlockJustClosed = false;
                    String boundaryPart = sb.toString().trim();
                    if (!boundaryPart.isEmpty()) parts.add(boundaryPart);
                    sb.setLength(0);
                }
                // flushWord
                if (word.length() > 0) {
                    String token = word.toString();
                    word.setLength(0);
                    if (beginModeEnabled) {
                        switch (token.toUpperCase(Locale.ROOT)) {
                            // `END CASE` 的尾词：已由 END 分支按 CASE 终结符消费，不再开新层
                            case "CASE" -> {
                                if (caseEndTailPending) caseEndTailPending = false;
                                else caseDepth++;
                            }
                            case "BEGIN" -> blockDepth++;
                            // T-SSP-01 订正（独立复核 major③）：`END IF` / `END LOOP` / `END WHILE` /
                            // `END REPEAT` 是内层终结符，不减块深度——否则含 IF/LOOP 的 PL/SQL 块
                            // （匿名块与具名过程）依旧被切碎（复核实测 `BEGIN IF 1=1 THEN NULL; END IF; END;`
                            // 基线 2 段 → 本改动前仍 2 段）。与 CASE 层的差别：CASE 有裸 END 收尾形态，
                            // 故须 caseDepth 计数；而 IF/LOOP/WHILE/REPEAT 只有双词终结形态，直接跳过递减即可
                            // （它们也不自增任何层计数，故无需配对状态）。
                            case "END" -> {
                                // T-PS7-01：CASE/IF/LOOP/WHILE/REPEAT 五个候选词单次取词后各自比对
                                // （原为 nextWordIs 对同一空白注释段重复扫描 5 次；跳过语义逐位一致）
                                String upper = nextWordToken(sql, i);
                                if (upper != null) upper = upper.toUpperCase(Locale.ROOT);
                                if ("CASE".equals(upper)) caseEndTailPending = true;
                                boolean innerLayerTail = "IF".equals(upper) || "LOOP".equals(upper)
                                        || "WHILE".equals(upper) || "REPEAT".equals(upper);
                                if (!innerLayerTail) {
                                    if (caseDepth > 0) caseDepth--;        // CASE 收尾（裸 END / END CASE）
                                    else if (blockDepth > 0) {
                                        // T-STG-02：顶层块闭合（1→0 且不在声明段吞并中）——置隐式边界
                                        // 标记。声明段内例程体的 END 归零后声明段仍在吞并（T-SSP-01），
                                        // 边界由主体 BEGIN/END 接力，此处不得置位
                                        if (blockDepth == 1 && !declareSection) topBlockJustClosed = true;
                                        blockDepth--;
                                    }
                                }
                            }
                            default -> bodyBeginExpected = false;
                        }
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
                        switch (token.toUpperCase(Locale.ROOT)) {
                            // `END CASE` 的尾词：已由 END 分支按 CASE 终结符消费，不再开新层
                            case "CASE" -> {
                                if (caseEndTailPending) caseEndTailPending = false;
                                else caseDepth++;
                            }
                            case "BEGIN" -> blockDepth++;
                            // T-SSP-01 订正（独立复核 major③）：`END IF` / `END LOOP` / `END WHILE` /
                            // `END REPEAT` 是内层终结符，不减块深度——否则含 IF/LOOP 的 PL/SQL 块
                            // （匿名块与具名过程）依旧被切碎（复核实测 `BEGIN IF 1=1 THEN NULL; END IF; END;`
                            // 基线 2 段 → 本改动前仍 2 段）。与 CASE 层的差别：CASE 有裸 END 收尾形态，
                            // 故须 caseDepth 计数；而 IF/LOOP/WHILE/REPEAT 只有双词终结形态，直接跳过递减即可
                            // （它们也不自增任何层计数，故无需配对状态）。
                            case "END" -> {
                                // T-PS7-01：CASE/IF/LOOP/WHILE/REPEAT 五个候选词单次取词后各自比对
                                // （原为 nextWordIs 对同一空白注释段重复扫描 5 次；跳过语义逐位一致）
                                String upper = nextWordToken(sql, i);
                                if (upper != null) upper = upper.toUpperCase(Locale.ROOT);
                                if ("CASE".equals(upper)) caseEndTailPending = true;
                                boolean innerLayerTail = "IF".equals(upper) || "LOOP".equals(upper)
                                        || "WHILE".equals(upper) || "REPEAT".equals(upper);
                                if (!innerLayerTail) {
                                    if (caseDepth > 0) caseDepth--;        // CASE 收尾（裸 END / END CASE）
                                    else if (blockDepth > 0) {
                                        // T-STG-02：顶层块闭合（1→0 且不在声明段吞并中）——置隐式边界
                                        // 标记。声明段内例程体的 END 归零后声明段仍在吞并（T-SSP-01），
                                        // 边界由主体 BEGIN/END 接力，此处不得置位
                                        if (blockDepth == 1 && !declareSection) topBlockJustClosed = true;
                                        blockDepth--;
                                    }
                                }
                            }
                            default -> bodyBeginExpected = false;
                        }
                    }
                }
                if (delimiter.equals(DEFAULT_DELIMITER) && parenDepth == 0 && blockDepth == 0 && !declareSection) {
                    String part = sb.toString().trim();
                    if (!part.isEmpty()) parts.add(part);
                    sb.setLength(0);
                    // 语句边界：CASE 层状态随段重置（防截断 CASE 的层数泄漏到后续语句）
                    caseDepth = 0;
                    caseEndTailPending = false;
                    topBlockJustClosed = false; // T-STG-02：`;` 边界已兑现（END; 形态走此分支）
                } else {
                    // T-SSP-01：声明段顶层 `;` 是声明分隔符（不切分），且构成声明段边界
                    if (declareSection && parenDepth == 0 && blockDepth == 0) bodyBeginExpected = true;
                    sb.append(c);
                }
                i++;
            } else if (c == '(') {
                // T-STG-02：隐式边界兑现——已完成块作为独立段产出（空段跳过），残余内容续算
                if (topBlockJustClosed) {
                    topBlockJustClosed = false;
                    String boundaryPart = sb.toString().trim();
                    if (!boundaryPart.isEmpty()) parts.add(boundaryPart);
                    sb.setLength(0);
                }
                // flushWord
                if (word.length() > 0) {
                    String token = word.toString();
                    word.setLength(0);
                    if (beginModeEnabled) {
                        switch (token.toUpperCase(Locale.ROOT)) {
                            // `END CASE` 的尾词：已由 END 分支按 CASE 终结符消费，不再开新层
                            case "CASE" -> {
                                if (caseEndTailPending) caseEndTailPending = false;
                                else caseDepth++;
                            }
                            case "BEGIN" -> blockDepth++;
                            // T-SSP-01 订正（独立复核 major③）：`END IF` / `END LOOP` / `END WHILE` /
                            // `END REPEAT` 是内层终结符，不减块深度——否则含 IF/LOOP 的 PL/SQL 块
                            // （匿名块与具名过程）依旧被切碎（复核实测 `BEGIN IF 1=1 THEN NULL; END IF; END;`
                            // 基线 2 段 → 本改动前仍 2 段）。与 CASE 层的差别：CASE 有裸 END 收尾形态，
                            // 故须 caseDepth 计数；而 IF/LOOP/WHILE/REPEAT 只有双词终结形态，直接跳过递减即可
                            // （它们也不自增任何层计数，故无需配对状态）。
                            case "END" -> {
                                // T-PS7-01：CASE/IF/LOOP/WHILE/REPEAT 五个候选词单次取词后各自比对
                                // （原为 nextWordIs 对同一空白注释段重复扫描 5 次；跳过语义逐位一致）
                                String upper = nextWordToken(sql, i);
                                if (upper != null) upper = upper.toUpperCase(Locale.ROOT);
                                if ("CASE".equals(upper)) caseEndTailPending = true;
                                boolean innerLayerTail = "IF".equals(upper) || "LOOP".equals(upper)
                                        || "WHILE".equals(upper) || "REPEAT".equals(upper);
                                if (!innerLayerTail) {
                                    if (caseDepth > 0) caseDepth--;        // CASE 收尾（裸 END / END CASE）
                                    else if (blockDepth > 0) {
                                        // T-STG-02：顶层块闭合（1→0 且不在声明段吞并中）——置隐式边界
                                        // 标记。声明段内例程体的 END 归零后声明段仍在吞并（T-SSP-01），
                                        // 边界由主体 BEGIN/END 接力，此处不得置位
                                        if (blockDepth == 1 && !declareSection) topBlockJustClosed = true;
                                        blockDepth--;
                                    }
                                }
                            }
                            default -> bodyBeginExpected = false;
                        }
                    }
                }
                parenDepth++;
                sb.append(c);
                i++;
            } else if (c == ')') {
                // T-STG-02：隐式边界兑现——已完成块作为独立段产出（空段跳过），残余内容续算
                if (topBlockJustClosed) {
                    topBlockJustClosed = false;
                    String boundaryPart = sb.toString().trim();
                    if (!boundaryPart.isEmpty()) parts.add(boundaryPart);
                    sb.setLength(0);
                }
                // flushWord
                if (word.length() > 0) {
                    String token = word.toString();
                    word.setLength(0);
                    if (beginModeEnabled) {
                        switch (token.toUpperCase(Locale.ROOT)) {
                            // `END CASE` 的尾词：已由 END 分支按 CASE 终结符消费，不再开新层
                            case "CASE" -> {
                                if (caseEndTailPending) caseEndTailPending = false;
                                else caseDepth++;
                            }
                            case "BEGIN" -> blockDepth++;
                            // T-SSP-01 订正（独立复核 major③）：`END IF` / `END LOOP` / `END WHILE` /
                            // `END REPEAT` 是内层终结符，不减块深度——否则含 IF/LOOP 的 PL/SQL 块
                            // （匿名块与具名过程）依旧被切碎（复核实测 `BEGIN IF 1=1 THEN NULL; END IF; END;`
                            // 基线 2 段 → 本改动前仍 2 段）。与 CASE 层的差别：CASE 有裸 END 收尾形态，
                            // 故须 caseDepth 计数；而 IF/LOOP/WHILE/REPEAT 只有双词终结形态，直接跳过递减即可
                            // （它们也不自增任何层计数，故无需配对状态）。
                            case "END" -> {
                                // T-PS7-01：CASE/IF/LOOP/WHILE/REPEAT 五个候选词单次取词后各自比对
                                // （原为 nextWordIs 对同一空白注释段重复扫描 5 次；跳过语义逐位一致）
                                String upper = nextWordToken(sql, i);
                                if (upper != null) upper = upper.toUpperCase(Locale.ROOT);
                                if ("CASE".equals(upper)) caseEndTailPending = true;
                                boolean innerLayerTail = "IF".equals(upper) || "LOOP".equals(upper)
                                        || "WHILE".equals(upper) || "REPEAT".equals(upper);
                                if (!innerLayerTail) {
                                    if (caseDepth > 0) caseDepth--;        // CASE 收尾（裸 END / END CASE）
                                    else if (blockDepth > 0) {
                                        // T-STG-02：顶层块闭合（1→0 且不在声明段吞并中）——置隐式边界
                                        // 标记。声明段内例程体的 END 归零后声明段仍在吞并（T-SSP-01），
                                        // 边界由主体 BEGIN/END 接力，此处不得置位
                                        if (blockDepth == 1 && !declareSection) topBlockJustClosed = true;
                                        blockDepth--;
                                    }
                                }
                            }
                            default -> bodyBeginExpected = false;
                        }
                    }
                }
                parenDepth = Math.max(parenDepth - 1, 0);
                sb.append(c);
                i++;
            } else if (isIdentifierChar(c)) {
                if (word.length() == 0) {
                    // T-STG-02：词起点的隐式边界（先于词起点记录——fire 可能重置 sb）
                    if (topBlockJustClosed) {
                        topBlockJustClosed = false;
                        String boundaryPart = sb.toString().trim();
                        if (!boundaryPart.isEmpty()) parts.add(boundaryPart);
                        sb.setLength(0);
                    }
                    wordStart = i;
                    bufferLenAtWordStart = sb.length();
                }
                word.append(c);
                sb.append(c);
                i++;
            } else {
                // 非标识符定界符：词终结（DELIMITER 指令 / BEGIN 前视在此触发）
                // T-STG-02：非空白内容的隐式边界（`;` 分支自切、注释分支不算内容）
                if (!Character.isWhitespace(c) && topBlockJustClosed) {
                    topBlockJustClosed = false;
                    String boundaryPart = sb.toString().trim();
                    if (!boundaryPart.isEmpty()) parts.add(boundaryPart);
                    sb.setLength(0);
                }
                if (word.length() > 0
                        && word.toString().equalsIgnoreCase(DELIMITER_DIRECTIVE)
                        && Character.isWhitespace(c)
                        && isLineHead(sql, wordStart)
                        && sb.substring(0, bufferLenAtWordStart).isBlank()) {
                    // T-DA-02：行首 `DELIMITER <token>` 指令——切换界符并整行丢弃（不产出语句）
                    word.setLength(0);
                    sb.setLength(0);
                    topBlockJustClosed = false; // T-STG-02：指令行丢弃，挂起边界一并消费
                    DelimiterDirective directive = readDelimiterDirective(sql, i);
                    delimiter = directive.token();
                    i = directive.lineEnd();
                } else if (word.length() > 0 && !declareSection && blockDepth == 0
                        && word.toString().equalsIgnoreCase("DECLARE")
                        && isStatementHead(sb, bufferLenAtWordStart, word.toString())
                        // T-PS7-01：BEGIN/END 两个存在性判定合并为单趟全文前视（原为两次独立扫描）
                        && hasBlockBodyAhead(sql, i)) {
                    // T-SSP-01：语句首 DECLARE + 后方配对 END → 声明段（段内顶层 `;` 不切分）。
                    // 同时启用块计数（声明段内局部例程体 BEGIN/END 照常成对计数）
                    //
                    // T-SSP-01 订正（独立复核回归）：早期实现只要求「后方存在 END 词」即武装，
                    // 而 T-SQL `DECLARE @x INT;` 与 PG `DECLARE c CURSOR FOR SELECT … CASE … END …`
                    // 都是合法顶层语句且后方本就有 END 词 → 一旦武装，顶层 `;` 直到文本尾都不再切分
                    // （实测 `DECLARE @a INT;\nSELECT 1;\nSELECT CASE … END FROM t;` 3 段 → 1 段）。
                    // 故武装条件补上「后方必须存在 BEGIN 词」——PL/SQL 声明段之后必有块主体 BEGIN，
                    // 而 T-SQL/PG 的 DECLARE 语句没有，据此把两者分开。仍属保守判定（前视词扫描，
                    // 不做完整语法分析），与 hasMatchingEndAhead 同口径。
                    declareSection = true;
                    bodyBeginExpected = true;
                    beginModeEnabled = true;
                    word.setLength(0);
                    sb.append(c);
                    i++;
                } else if (declareSection && bodyBeginExpected && word.length() > 0
                        && word.toString().equalsIgnoreCase("BEGIN")) {
                    // T-SSP-01：声明段边界上的 BEGIN 即块主体起始——退出声明段（此后顶层 `;`
                    // 恢复切分；主体 END 使深度归零，故吞并边界＝深度归零处）
                    declareSection = false;
                    bodyBeginExpected = false;
                    // flushWord（BEGIN 入块计数，走上方主 flush 语义）
                    if (word.length() > 0) {
                        String token = word.toString();
                        word.setLength(0);
                        if (beginModeEnabled) {
                            switch (token.toUpperCase(Locale.ROOT)) {
                                case "CASE" -> {
                                    if (caseEndTailPending) caseEndTailPending = false;
                                    else caseDepth++;
                                }
                                case "BEGIN" -> blockDepth++;
                                case "END" -> {
                                    // T-PS7-01：CASE/IF/LOOP/WHILE/REPEAT 五个候选词单次取词后各自比对
                                    String upper = nextWordToken(sql, i);
                                    if (upper != null) upper = upper.toUpperCase(Locale.ROOT);
                                    if ("CASE".equals(upper)) caseEndTailPending = true;
                                    boolean innerLayerTail = "IF".equals(upper) || "LOOP".equals(upper)
                                            || "WHILE".equals(upper) || "REPEAT".equals(upper);
                                    if (!innerLayerTail) {
                                        if (caseDepth > 0) caseDepth--;
                                        else if (blockDepth > 0) {
                                            // T-STG-02：顶层块闭合（1→0 且不在声明段吞并中）——置隐式边界
                                            if (blockDepth == 1 && !declareSection) topBlockJustClosed = true;
                                            blockDepth--;
                                        }
                                    }
                                }
                                default -> bodyBeginExpected = false;
                            }
                        }
                    }
                    sb.append(c);
                    i++;
                } else if (word.length() > 0 && word.toString().equalsIgnoreCase("BEGIN")
                        && !beginModeEnabled && hasMatchingEndAhead(sql, i)
                        // T-STG-01：`BEGIN TRAN/…` 事务/会话形态不武装（否则 CASE 等任何 END 词
                        // 误武装后深度永不归零，后续顶层 `;` 全部失切）
                        && !isNonBlockBeginTail(sql, i)) {
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
                            switch (token.toUpperCase(Locale.ROOT)) {
                                // `END CASE` 的尾词：已由 END 分支按 CASE 终结符消费，不再开新层
                                case "CASE" -> {
                                    if (caseEndTailPending) caseEndTailPending = false;
                                    else caseDepth++;
                                }
                                case "BEGIN" -> blockDepth++;
                                case "END" -> {
                                    // 内层终结符判定同上（T-SSP-01/T-SSP-02）
                                    // T-PS7-01：CASE/IF/LOOP/WHILE/REPEAT 五个候选词单次取词后各自比对
                                    String upper = nextWordToken(sql, i);
                                    if (upper != null) upper = upper.toUpperCase(Locale.ROOT);
                                    if ("CASE".equals(upper)) caseEndTailPending = true;
                                    boolean innerLayerTail = "IF".equals(upper) || "LOOP".equals(upper)
                                            || "WHILE".equals(upper) || "REPEAT".equals(upper);
                                    if (!innerLayerTail) {
                                        if (caseDepth > 0) caseDepth--;        // CASE 收尾（裸 END / END CASE）
                                        else if (blockDepth > 0) {
                                            // T-STG-02：顶层块闭合（1→0 且不在声明段吞并中）——置隐式边界
                                            if (blockDepth == 1 && !declareSection) topBlockJustClosed = true;
                                            blockDepth--;
                                        }
                                    }
                                }
                                default -> bodyBeginExpected = false;
                            }
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
                    switch (token.toUpperCase(Locale.ROOT)) {
                        // `END CASE` 的尾词：已由 END 分支按 CASE 终结符消费，不再开新层
                        case "CASE" -> {
                            if (caseEndTailPending) caseEndTailPending = false;
                            else caseDepth++;
                        }
                        case "BEGIN" -> blockDepth++;
                        case "END" -> {
                            // 内层终结符判定同上（T-SSP-01/T-SSP-02）
                            // T-PS7-01：CASE/IF/LOOP/WHILE/REPEAT 五个候选词单次取词后各自比对
                            String upper = nextWordToken(sql, i);
                            if (upper != null) upper = upper.toUpperCase(Locale.ROOT);
                            if ("CASE".equals(upper)) caseEndTailPending = true;
                            boolean innerLayerTail = "IF".equals(upper) || "LOOP".equals(upper)
                                    || "WHILE".equals(upper) || "REPEAT".equals(upper);
                            if (!innerLayerTail) {
                                if (caseDepth > 0) caseDepth--;        // CASE 收尾（裸 END / END CASE）
                                else if (blockDepth > 0) {
                                    // T-STG-02：顶层块闭合（1→0 且不在声明段吞并中）——置隐式边界
                                    if (blockDepth == 1 && !declareSection) topBlockJustClosed = true;
                                    blockDepth--;
                                }
                            }
                        }
                        default -> bodyBeginExpected = false;
                    }
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

    /**
     * 段内顶层 BEGIN/END 配对计数（字符串/注释感知；&gt;0 = 未闭合的截断批）。
     *
     * <p>T-SSP-02：与主循环同口径隔离 {@code CASE … END} / {@code END CASE}（CASE 的 {@code END} 不充当块尾
     * 配对），否则批内一个 CASE 表达式会让截断批被误判为已闭合（后续段不再并入，破坏「宁整勿碎」）。</p>
     */
    private static int beginEndBalance(@NotNull String part) {
        int depth = 0;
        int caseDepth = 0;
        boolean caseEndTailPending = false;
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
                    if (upper.equals("CASE")) {
                        // `END CASE` 的尾词：已由 END 分支按 CASE 终结符消费，不再开新层
                        if (caseEndTailPending) caseEndTailPending = false;
                        else caseDepth++;
                    } else if (upper.equals("BEGIN")) {
                        depth++;
                    } else if (upper.equals("END")) {
                        // 同主状态机：`END IF` / `END LOOP` / `END WHILE` / `END REPEAT` 是内层终结符，
                        // 不减深度（T-SQL 批本身没有这些形态，此处只为与主路径口径一致、避免两处漂移）。
                        // T-PS7-01：与主循环同款单次取词（两处复制点同一提交同步改）
                        String nextToken = nextWordToken(part, i);
                        if (nextToken != null) nextToken = nextToken.toUpperCase(Locale.ROOT);
                        if ("CASE".equals(nextToken)) caseEndTailPending = true;
                        boolean innerLayerTail = "IF".equals(nextToken) || "LOOP".equals(nextToken)
                                || "WHILE".equals(nextToken) || "REPEAT".equals(nextToken);
                        if (!innerLayerTail) {
                            if (caseDepth > 0) caseDepth--;
                            else if (depth > 0) depth--;
                        }
                    }
                    word.setLength(0);
                }
                inLine = true;
                i++;
            } else if (c == '/' && next != null && next == '*') {
                // flush
                if (word.length() > 0) {
                    String upper = word.toString().toUpperCase(Locale.ROOT);
                    if (upper.equals("CASE")) {
                        // `END CASE` 的尾词：已由 END 分支按 CASE 终结符消费，不再开新层
                        if (caseEndTailPending) caseEndTailPending = false;
                        else caseDepth++;
                    } else if (upper.equals("BEGIN")) {
                        depth++;
                    } else if (upper.equals("END")) {
                        // 同主状态机：`END IF` / `END LOOP` / `END WHILE` / `END REPEAT` 是内层终结符，
                        // 不减深度（T-SQL 批本身没有这些形态，此处只为与主路径口径一致、避免两处漂移）。
                        // T-PS7-01：与主循环同款单次取词（两处复制点同一提交同步改）
                        String nextToken = nextWordToken(part, i);
                        if (nextToken != null) nextToken = nextToken.toUpperCase(Locale.ROOT);
                        if ("CASE".equals(nextToken)) caseEndTailPending = true;
                        boolean innerLayerTail = "IF".equals(nextToken) || "LOOP".equals(nextToken)
                                || "WHILE".equals(nextToken) || "REPEAT".equals(nextToken);
                        if (!innerLayerTail) {
                            if (caseDepth > 0) caseDepth--;
                            else if (depth > 0) depth--;
                        }
                    }
                    word.setLength(0);
                }
                inBlock = true;
                i++;
            } else if (c == '\'') {
                // flush
                if (word.length() > 0) {
                    String upper = word.toString().toUpperCase(Locale.ROOT);
                    if (upper.equals("CASE")) {
                        // `END CASE` 的尾词：已由 END 分支按 CASE 终结符消费，不再开新层
                        if (caseEndTailPending) caseEndTailPending = false;
                        else caseDepth++;
                    } else if (upper.equals("BEGIN")) {
                        depth++;
                    } else if (upper.equals("END")) {
                        // 同主状态机：`END IF` / `END LOOP` / `END WHILE` / `END REPEAT` 是内层终结符，
                        // 不减深度（T-SQL 批本身没有这些形态，此处只为与主路径口径一致、避免两处漂移）。
                        // T-PS7-01：与主循环同款单次取词（两处复制点同一提交同步改）
                        String nextToken = nextWordToken(part, i);
                        if (nextToken != null) nextToken = nextToken.toUpperCase(Locale.ROOT);
                        if ("CASE".equals(nextToken)) caseEndTailPending = true;
                        boolean innerLayerTail = "IF".equals(nextToken) || "LOOP".equals(nextToken)
                                || "WHILE".equals(nextToken) || "REPEAT".equals(nextToken);
                        if (!innerLayerTail) {
                            if (caseDepth > 0) caseDepth--;
                            else if (depth > 0) depth--;
                        }
                    }
                    word.setLength(0);
                }
                inSingle = true;
            } else if (c == '"') {
                // flush
                if (word.length() > 0) {
                    String upper = word.toString().toUpperCase(Locale.ROOT);
                    if (upper.equals("CASE")) {
                        // `END CASE` 的尾词：已由 END 分支按 CASE 终结符消费，不再开新层
                        if (caseEndTailPending) caseEndTailPending = false;
                        else caseDepth++;
                    } else if (upper.equals("BEGIN")) {
                        depth++;
                    } else if (upper.equals("END")) {
                        // 同主状态机：`END IF` / `END LOOP` / `END WHILE` / `END REPEAT` 是内层终结符，
                        // 不减深度（T-SQL 批本身没有这些形态，此处只为与主路径口径一致、避免两处漂移）。
                        // T-PS7-01：与主循环同款单次取词（两处复制点同一提交同步改）
                        String nextToken = nextWordToken(part, i);
                        if (nextToken != null) nextToken = nextToken.toUpperCase(Locale.ROOT);
                        if ("CASE".equals(nextToken)) caseEndTailPending = true;
                        boolean innerLayerTail = "IF".equals(nextToken) || "LOOP".equals(nextToken)
                                || "WHILE".equals(nextToken) || "REPEAT".equals(nextToken);
                        if (!innerLayerTail) {
                            if (caseDepth > 0) caseDepth--;
                            else if (depth > 0) depth--;
                        }
                    }
                    word.setLength(0);
                }
                inDouble = true;
            } else if (isIdentifierChar(c)) {
                word.append(c);
            } else {
                // flush
                if (word.length() > 0) {
                    String upper = word.toString().toUpperCase(Locale.ROOT);
                    if (upper.equals("CASE")) {
                        // `END CASE` 的尾词：已由 END 分支按 CASE 终结符消费，不再开新层
                        if (caseEndTailPending) caseEndTailPending = false;
                        else caseDepth++;
                    } else if (upper.equals("BEGIN")) {
                        depth++;
                    } else if (upper.equals("END")) {
                        // 同主状态机：`END IF` / `END LOOP` / `END WHILE` / `END REPEAT` 是内层终结符，
                        // 不减深度（T-SQL 批本身没有这些形态，此处只为与主路径口径一致、避免两处漂移）。
                        // T-PS7-01：与主循环同款单次取词（两处复制点同一提交同步改）
                        String nextToken = nextWordToken(part, i);
                        if (nextToken != null) nextToken = nextToken.toUpperCase(Locale.ROOT);
                        if ("CASE".equals(nextToken)) caseEndTailPending = true;
                        boolean innerLayerTail = "IF".equals(nextToken) || "LOOP".equals(nextToken)
                                || "WHILE".equals(nextToken) || "REPEAT".equals(nextToken);
                        if (!innerLayerTail) {
                            if (caseDepth > 0) caseDepth--;
                            else if (depth > 0) depth--;
                        }
                    }
                    word.setLength(0);
                }
            }
            i++;
        }
        // flush（循环尾）
        if (word.length() > 0) {
            String upper = word.toString().toUpperCase(Locale.ROOT);
            if (upper.equals("CASE")) {
                if (caseEndTailPending) caseEndTailPending = false;
                else caseDepth++;
            } else if (upper.equals("BEGIN")) {
                depth++;
            } else if (upper.equals("END")) {
                // T-PS7-01：与主循环同款单次取词（两处复制点同一提交同步改）
                String nextToken = nextWordToken(part, i);
                if (nextToken != null) nextToken = nextToken.toUpperCase(Locale.ROOT);
                if ("CASE".equals(nextToken)) caseEndTailPending = true;
                boolean innerLayerTail = "IF".equals(nextToken) || "LOOP".equals(nextToken)
                        || "WHILE".equals(nextToken) || "REPEAT".equals(nextToken);
                if (!innerLayerTail) {
                    if (caseDepth > 0) caseDepth--;
                    else if (depth > 0) depth--;
                }
            }
        }
        return depth;
    }

    /**
     * 语句首判定（T-SSP-01）：当前缓冲（含本词）的首个有效关键字是否即本词——
     * 前导仅空白与注释才算语句首（跳过规则同 {@link #firstKeywordOf}）。
     */
    private static boolean isStatementHead(@NotNull StringBuilder buffer, int bufferLenAtWordStart,
                                           @NotNull String token) {
        String head = firstKeywordOf(buffer.substring(0, bufferLenAtWordStart) + token);
        return head != null && head.equals(token.toUpperCase(Locale.ROOT));
    }

    /**
     * 前视扫描：{@code from} 之后是否存在词边界形态的目标词（字符串/注释感知，大小写不敏感）。
     * 保守判定——用于决定是否启用 BEGIN/END 块感知（识别不到配对 END 则按顶层切分原样）。
     */
    private static boolean hasWordAhead(@NotNull String sql, int from, @NotNull String target) {
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
                if (word.length() > 0 && word.toString().equalsIgnoreCase(target)) return true;
                word.setLength(0);
            }
            i++;
        }
        return word.length() > 0 && word.toString().equalsIgnoreCase(target);
    }

    private static boolean hasMatchingEndAhead(@NotNull String sql, int from) {
        return hasWordAhead(sql, from, "END");
    }

    /**
     * 单次取词（T-PS7-01）：END 分支的 CASE/IF/LOOP/WHILE/REPEAT 五个候选词一次取词后
     * 各自比对，替代对同一段空白注释的 5 次重复扫描——词法全权委托 {@link #nextWord} 单一核心
     * （F45 合并后同文件仅此一份遍历实现；F23 交付的 nextWord 即本函数的同构去计数形态，
     * 死代码 nextWordIs 一并移除）。本包装只承担计数缝语义：调用一次即 {@link #lookaheadProbeCount}
     * 累加一次（含取词落空返回 null 的调用，与合并前一致）。
     */
    private static String nextWordToken(@NotNull String sql, int from) {
        lookaheadProbeCount++;
        return nextWord(sql, from);
    }

    /**
     * 单趟全文前视（T-PS7-01）：一次扫描同时判定「后方存在 BEGIN 词」与「后方存在 END 词」，
     * 替代 {@code hasWordAhead(…, "BEGIN")} + {@link #hasMatchingEndAhead} 的两次独立全文扫描
     * （DECLARE 声明段武装的合取前提）。词法走查与 {@link #hasWordAhead} 逐位一致
     * （字符串/注释感知、大小写不敏感、词序无关），两布尔取自同一趟遍历，结果等价；
     * 计数缝 {@link #lookaheadProbeCount} 同步累加。
     */
    private static boolean hasBlockBodyAhead(@NotNull String sql, int from) {
        lookaheadProbeCount++;
        boolean beginSeen = false;
        boolean endSeen = false;
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
                if (word.length() > 0) {
                    String token = word.toString();
                    if (token.equalsIgnoreCase("BEGIN")) beginSeen = true;
                    else if (token.equalsIgnoreCase("END")) endSeen = true;
                    word.setLength(0);
                    if (beginSeen && endSeen) return true; // 两词俱在即早退（同一趟扫描内）
                }
            }
            i++;
        }
        if (word.length() > 0) {
            String token = word.toString();
            if (token.equalsIgnoreCase("BEGIN")) beginSeen = true;
            else if (token.equalsIgnoreCase("END")) endSeen = true;
        }
        return beginSeen && endSeen;
    }

    /**
     * T-PS7-01 测试缝：切分过程内前视扫描的调用计数（每次 {@link #splitInternal} 入口清零）。
     * 供测试断言「END 分支每语句前视 ≤ 每 END 一次」——退回旧实现（同段重复扫描 5 次）
     * 计数超阈值即红。仅测试读：生产并发调用计数无契约，纯函数语义不受影响。
     * （Kotlin {@code internal var} → public 静态字段：测试侧 Kotlin 源以属性语法直读，
     * Java 包私有成员对 Kotlin 不可见，参照 UnboundedWriteDetector.parseStatementsForTest 先例。）
     */
    public static int lookaheadProbeCount = 0;
}
