// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpRenderInput.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import java.util.regex.Pattern;

/**
 * T-McpToolset sqlv_render_sql 入参校验与形态分发（纯逻辑层，无 IDE 类型）。
 *
 * 计划 §二：入参「项目相对文件路径 + 行号」或「源码片段 + 触发面类型」。一期片段形态仅支持
 * mapper XML 文本（{@code cn.song.mybatisplus.visualizer.ui.XmlPreviewService} 为 text 形态入口；
 * Java 片段无 PSI 锚点不可定位——渲染链是 PSI 语义的，裁剪写进实施记录）。
 */
public final class McpRenderInput {

    private McpRenderInput() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final McpRenderInput INSTANCE = new McpRenderInput();

    /** 触发面类型（sourceType）允许值：一期只有 mapper XML 片段。 */
    public static final String SOURCE_TYPE_XML_MAPPER = "xml_mapper";

    /** 行号合法范围（1-based；0 = 未提供/取整文件首个语句）。 */
    public static final int MAX_LINE = 1_000_000;

    /** 片段长度上限。 */
    public static final int MAX_SNIPPET_LENGTH = 100_000;

    /** 绝对路径探测（盘符前缀，如 `C:`）。 */
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:");

    /** 校验通过的入参形态。 */
    public record Parsed(
        /** 路径形态时的项目相对文件路径（片段形态为 null）。 */
        String filePath,
        /** 路径形态时的 1-based 行号（未提供或 ≤0 为 null = 首个可预览语句）。 */
        Integer line,
        /** 片段形态时的 mapper XML 文本（路径形态为 null）。 */
        String xmlSnippet,
        /** 片段形态时的触发面类型（恒为 {@link #SOURCE_TYPE_XML_MAPPER}；路径形态为 null）。 */
        String sourceType
    ) {
    }

    /**
     * 入参校验：路径形态（filePath 非空）与片段形态（xmlSnippet+sourceType=xml_mapper）二选一；
     * 两者都给时路径形态优先。行号只允许正整数。
     *
     * @return (校验通过的形态, null) 或 (null, 结构化错误文本)——Kotlin {@code Pair<Parsed?, String?>} 形态。
     */
    public static kotlin.Pair<Parsed, String> parse(String filePath, Integer line, String xmlSnippet, String sourceType) {
        String file = filePath == null ? null : filePath.trim();
        if (file != null && file.isEmpty()) {
            file = null;
        }
        if (file != null) {
            if (line != null && (line < 0 || line > MAX_LINE)) {
                return new kotlin.Pair<>(null, McpToolErrors.code(
                    McpToolErrors.INVALID_ARGUMENT,
                    "line must be within 1.." + MAX_LINE + " (1-based); got " + line + "."
                ));
            }
            Integer positiveLine = (line != null && line > 0) ? line : null;
            return new kotlin.Pair<>(new Parsed(file, positiveLine, null, null), null);
        }
        String snippet = xmlSnippet == null ? null : xmlSnippet.trim();
        if (snippet != null && snippet.isEmpty()) {
            snippet = null;
        }
        if (snippet != null) {
            if (snippet.length() > MAX_SNIPPET_LENGTH) {
                return new kotlin.Pair<>(null, McpToolErrors.code(
                    McpToolErrors.INVALID_ARGUMENT,
                    "xmlSnippet exceeds the maximum length of " + MAX_SNIPPET_LENGTH + " characters."
                ));
            }
            String type = sourceType == null ? null : sourceType.trim();
            if (type != null && type.isEmpty()) {
                type = null;
            }
            if (type != null && !type.equals(SOURCE_TYPE_XML_MAPPER)) {
                return new kotlin.Pair<>(null, McpToolErrors.code(
                    McpToolErrors.INVALID_ARGUMENT,
                    "sourceType '" + type + "' is not supported for snippet rendering; "
                        + "supported: " + SOURCE_TYPE_XML_MAPPER + ". For Java sources pass filePath+line instead."
                ));
            }
            return new kotlin.Pair<>(new Parsed(null, null, snippet, type != null ? type : SOURCE_TYPE_XML_MAPPER), null);
        }
        return new kotlin.Pair<>(null, McpToolErrors.code(
            McpToolErrors.INVALID_ARGUMENT,
            "provide either (filePath, line) of the source location or (xmlSnippet, "
                + "sourceType=" + SOURCE_TYPE_XML_MAPPER + ") for mapper XML text."
        ));
    }

    /** 项目相对路径归一化（统一正斜杠、拒绝绝对路径与 `..` 上跳）。 */
    public static String normalizeFilePath(String raw) {
        String path = raw.trim().replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty() || path.contains("..") || DRIVE_PREFIX.matcher(path).find()) {
            return null;
        }
        return path;
    }
}
