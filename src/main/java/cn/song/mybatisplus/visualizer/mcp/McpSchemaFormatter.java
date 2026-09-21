// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSchemaFormatter.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * T-McpToolset schema 上下文限幅与紧凑格式化（纯逻辑层，无 IDE 类型）。
 *
 * max_tables 口径参照 DBX（默认 8、上限 20，调研报告 §5.1）；输出面向 LLM 的紧凑
 * 表+列上下文（含注释），超限追加截断提示（DBX「截断时提示让用户 @table 指定」同语义）。
 */
public final class McpSchemaFormatter {

    private McpSchemaFormatter() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final McpSchemaFormatter INSTANCE = new McpSchemaFormatter();

    /** 默认返回表数（DBX 口径）。 */
    public static final int DEFAULT_MAX_TABLES = 8;

    /** 硬上限（DBX 口径）。 */
    public static final int HARD_MAX_TABLES = 20;

    /** 单元格字符预算（DBX MCP agent_tools.rs 的 200 字符列预算口径）。 */
    public static final int MAX_CELL_CHARS = 200;

    /** tables 入参允许的最多条目（超限直接拒——防一次调用拖全库遍历）。 */
    public static final int MAX_TABLE_NAMES = 50;

    /** 格式化输入的一张表。 */
    public record TableEntry(
        /** 表名。 */
        String name,
        /** 表注释（无则 null）。 */
        String comment,
        /** 列清单（紧凑上下文逐列一行）。 */
        List<ColumnLine> columns
    ) {
    }

    /** 格式化输入的一列（{@code notNull}=true 时追加 NOT NULL 标记）。 */
    public record ColumnLine(
        /** 列名。 */
        String name,
        /** 类型描述（如 bigint / varchar(64)；无则 null）。 */
        String typeSpec,
        /** 列注释（无则 null）。 */
        String comment,
        /** NOT NULL 标记（未知为 null，输出零差异）。 */
        Boolean notNull
    ) {
        /** Kotlin 默认参数等价重载：notNull 缺省 null。 */
        public ColumnLine(String name, String typeSpec, String comment) {
            this(name, typeSpec, comment, null);
        }
    }

    /** max_tables 限幅：null/非正 → 默认 8；超过硬上限 → 20；其余原样。 */
    public static int clampMaxTables(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_MAX_TABLES;
        }
        if (requested > HARD_MAX_TABLES) {
            return HARD_MAX_TABLES;
        }
        return requested;
    }

    /**
     * 表名清单校验与去重（保序，大小写不敏感去重）。
     *
     * @return 通过的表名；{@code names} 为空或超 {@link #MAX_TABLE_NAMES} 条目时返回 null（调用方报 INVALID_ARGUMENT）。
     */
    public static List<String> sanitizeTableNames(List<String> names) {
        if (names.isEmpty() || names.size() > MAX_TABLE_NAMES) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String raw : names) {
            String name = raw.trim();
            if (name.isEmpty() || !seen.add(name.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            out.add(name);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 紧凑表列上下文（含注释）。
     *
     * 形态（逐表一段）：
     * <pre>{@code
     * user (用户表)
     *   id bigint NOT NULL -- 主键
     *   name varchar(64) -- 用户名
     * }</pre>
     * {@code requestedCount} 与实际返回数不一致（白名单超出 max_tables 截断）时在尾部追加截断提示。
     */
    public static String format(List<TableEntry> tables, int maxTables, int requestedCount) {
        StringBuilder out = new StringBuilder();
        List<TableEntry> shown = tables.subList(0, Math.min(maxTables, tables.size()));
        for (TableEntry table : shown) {
            out.append(table.name());
            if (table.comment() != null && !table.comment().isBlank()) {
                out.append(" (").append(cell(table.comment())).append(')');
            }
            out.append('\n');
            for (ColumnLine column : table.columns()) {
                out.append("  ").append(column.name());
                if (column.typeSpec() != null && !column.typeSpec().isBlank()) {
                    out.append(' ').append(cell(column.typeSpec()));
                }
                if (Boolean.TRUE.equals(column.notNull())) {
                    out.append(" NOT NULL");
                }
                if (column.comment() != null && !column.comment().isBlank()) {
                    out.append(" -- ").append(cell(column.comment()));
                }
                out.append('\n');
            }
        }
        if (requestedCount > shown.size()) {
            out.append("\n[TRUNCATED] showing ").append(shown.size()).append(" of ").append(requestedCount)
                .append(" requested tables ")
                .append("(maxTables=").append(maxTables)
                .append("); re-run with a smaller whitelist or a higher maxTables (limit ")
                .append(HARD_MAX_TABLES)
                .append(").");
        }
        return McpExplainFormatter.trimEnd(out.toString());
    }

    // ------------------------------------------------------------------
    // P3 / T-McpArgLooseDecode：入参形态与取值校验
    //
    // 载体类型（McpStringArg/McpIntArg/McpArgKind）定义在 mcp 包的同名独立文件（master 侧为
    // SqlVisualizerSchemaContextTool.kt 同文件顶层声明，Java 一文件一 public 顶层类型故拆件）：
    // 它们带 kotlinx.serialization 依赖，而本类保持「零第三方类型」的纯逻辑层约定
    // （见 McpToolErrors 文件头），此处只做形态与取值判定，仍是纯函数、可直测。
    // ------------------------------------------------------------------

    /**
     * {@code tables} 入参 → 合规表名清单（保序、大小写不敏感去重）。
     *
     * <p><b>形态判定先于取值判定</b>：平台参数绑定层对「JSON 数组/对象 → String」不容错（见载体 KDoc 的
     * {@code Index -1 out of bounds} 根因），载体把形态带到这一层，非字符串形态与空清单/超限同归
     * {@link McpToolErrors#INVALID_ARGUMENT}——类型写错的 agent 拿到的是参数校验错误，不是内部异常。</p>
     *
     * @return 通过的表名；形态不符或取值不符时 null（调用方用 {@link #tableNamesError} 报错）。
     */
    public static List<String> parseTableNames(McpStringArg arg) {
        if (arg.getKind() != McpArgKind.STRING) {
            return null;
        }
        List<String> names = new ArrayList<>();
        // master: arg.text.split(',', ';', '\n')——Kotlin 多分隔符 split 等价 Java 字符类切分
        for (String part : arg.getText().split("[;,\n]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return sanitizeTableNames(names);
    }

    /** {@link #parseTableNames} 的配套错误文案（形态不符 / 取值不符分别措辞，均为 INVALID_ARGUMENT）。 */
    public static String tableNamesError(McpStringArg arg) {
        if (arg.getKind() != McpArgKind.STRING) {
            return typeMismatch("tables", "a comma-separated string of 1.." + MAX_TABLE_NAMES + " table names", arg);
        }
        return McpToolErrors.code(
            McpToolErrors.INVALID_ARGUMENT,
            "tables must contain 1.." + MAX_TABLE_NAMES + " table names (comma-separated)."
        );
    }

    /**
     * {@code dataSource} 入参 → 数据源别名（空串是合法取值 = 未指定；形态不符 → null）。
     *
     * <p>形态判定同样为 P3 口径：{@code dataSource: ["mp_demo@localhost"]} 曾与 {@code tables} 同栈崩
     * （同一次实测）。</p>
     */
    public static String parseDataSource(McpStringArg arg) {
        return arg.getKind() == McpArgKind.STRING ? arg.getText() : null;
    }

    /** {@link #parseDataSource} 的配套错误文案。 */
    public static String dataSourceError(McpStringArg arg) {
        return typeMismatch("dataSource", "a data source display name string", arg);
    }

    /**
     * {@code maxTables} 入参 → 请求上限（随后由 {@link #clampMaxTables} 钳制）。
     *
     * <p>数值字符串（{@code "8"}）照旧接受：平台绑定对「字符串 → Int」本就有 kotlinx 宽松转换，
     * 改前可用，不因本次收口而拒（属取值合法、仅形态宽松）。形态不符或非整数文本 → null。</p>
     */
    public static Integer parseMaxTables(McpIntArg arg) {
        if (arg.getKind() != McpArgKind.NUMBER && arg.getKind() != McpArgKind.STRING) {
            return null;
        }
        try {
            return Integer.valueOf(arg.getText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@link #parseMaxTables} 的配套错误文案（形态不符 / 非整数文本分别措辞）。 */
    public static String maxTablesError(McpIntArg arg) {
        if (arg.getKind() == McpArgKind.NUMBER || arg.getKind() == McpArgKind.STRING) {
            return McpToolErrors.code(
                McpToolErrors.INVALID_ARGUMENT,
                "maxTables must be an integer (got '" + arg.getText() + "')."
            );
        }
        return typeMismatch("maxTables", "an integer", arg);
    }

    /** 形态不符的统一文案：点明期望形态与实际 JSON 形态（agent 据此自我纠正）。 */
    private static String typeMismatch(String name, String expected, McpArg arg) {
        return McpToolErrors.code(
            McpToolErrors.INVALID_ARGUMENT,
            name + " must be " + expected + " (got JSON " + arg.getKind().getLabel() + ")."
        );
    }

    private static String cell(String value) {
        return value.length() > MAX_CELL_CHARS ? value.substring(0, MAX_CELL_CHARS) + "…" : value;
    }
}
