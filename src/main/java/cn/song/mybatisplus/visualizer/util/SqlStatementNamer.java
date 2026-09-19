// T-SyncK2JMaster309 自 master util/SqlStatementNamer.kt 移植
package cn.song.mybatisplus.visualizer.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语句注释命名提取（T-StatementNaming，O-19；DBX e561bd15 结果页签命名三规则对齐 +
 * XML 注释方言扩展）。从 SQL 的<b>前导注释区</b>提取语句名，供还原产物组头与 MCP 语句头
 * 附注（`-- SQL #N [名称]`）——「第 3 条语句」不如「分页查询」好认。
 *
 * <p>三规则（DBX queryResultSource.ts 对齐，形态差异见各条）：
 * <ol>
 *  <li>`-- Name: x` 优先（大小写不敏感，前导区内首个命中即定）；</li>
 *  <li>回退最近一条非空 `--` 行注释（前导区内最后出现的一条）；</li>
 *  <li>块注释（斜杠星号形态）与 XML 注释（尖括号叹号双横线形态）<b>剔除</b>——不参与命名
 *      （DBX 块注释剔除 + 本插件 mapper XML 场景的方言扩展）。</li>
 * </ol>
 *
 * <p>边界（防误取，测试锁死）：
 * <ul>
 *  <li>行内尾注释（`SELECT 1 -- 分页`）不是语句名——只认前导区整行注释；</li>
 *  <li>无前导注释返回 null——消费方零差异（组头维持 `-- SQL #N` 逐字节不变，硬门禁）；</li>
 *  <li>名称 ≤ {@link #MAX_NAME_LENGTH} 字符截断（防 MCP 输出与浮层单行撑爆）。</li>
 * </ul>
 */
public final class SqlStatementNamer {

    private SqlStatementNamer() {}

    /** 名称长度上限（截断；DBX 无此约束，本插件 MCP token 预算与 UI 单行形态加的）。 */
    private static final int MAX_NAME_LENGTH = 40;

    /** `-- Name: x` 形态（大小写不敏感；行首可有空白）。 */
    private static final Pattern NAME_PREFIX =
            Pattern.compile("^\\s*--\\s*name\\s*[:：]\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);

    /** `--` 行注释（整行；行首可有空白）。 */
    private static final Pattern LINE_COMMENT = Pattern.compile("^\\s*--");

    /** 分隔线形态（纯横线/等号/星号/下划线——不是名字）。 */
    private static final Pattern SEPARATOR_ONLY = Pattern.compile("^[-=*_\\s]*$");

    /** 块注释起始（斜杠星号形态与 XML 注释形态；前导区剔除依据）。 */
    private static final Pattern BLOCK_START = Pattern.compile("^\\s*(/\\*|<!--)");

    /**
     * 提取语句名：[name:] 前缀优先 → 最近一条非空行注释回退 → 块注释/XML 注释剔除；
     * 无前导注释（或前导区只有块/XML 注释）返回 null。
     */
    @Nullable
    public static String nameOf(@NotNull String sql) {
        String fallback = null;
        for (String line : sql.split("\r\n|\n|\r", -1)) {
            // 命中代码行（非注释非空白）→ 前导区结束，取当前最优
            if (!LINE_COMMENT.matcher(line).find() && !line.isBlank() && !BLOCK_START.matcher(line).find()) {
                return fallback != null ? truncate(fallback) : null;
            }
            if (line.isBlank()) {
                // 空行：前导区内跳过（回退取「最近一条」，空行不断链）
            } else if (BLOCK_START.matcher(line).find()) {
                // 块注释/XML 注释：剔除，不当名不回退
            } else {
                // `--` 行注释：Name: 前缀优先即定；否则记为回退候选（后者覆盖前者=最近一条）
                String content = substringAfter(line, "--").trim();
                if (content.isBlank() || SEPARATOR_ONLY.matcher(content).matches()) {
                    // 纯 `--` 分隔线与横线装饰不当名
                } else {
                    Matcher m = NAME_PREFIX.matcher(line);
                    if (m.find()) {
                        return truncate(m.group(1).trim());
                    }
                    fallback = content;
                }
            }
        }
        return fallback != null ? truncate(fallback) : null;
    }

    /** Kotlin String.substringAfter 等价：不含分隔符时返回原串。 */
    @NotNull
    private static String substringAfter(@NotNull String s, @NotNull String delimiter) {
        int index = s.indexOf(delimiter);
        return index < 0 ? s : s.substring(index + delimiter.length());
    }

    @NotNull
    private static String truncate(@NotNull String name) {
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
    }
}
