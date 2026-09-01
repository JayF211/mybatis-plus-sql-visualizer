package cn.song.mybatisplus.visualizer.share.format;

import cn.song.mybatisplus.visualizer.ui.highlight.SqlHighlightColors;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlLexer;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlTokenTypes;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * HTML 报告格式化器（F-8 Phase B）。
 *
 * <p>生成带语法高亮的完整 HTML 报告文件，包含：
 * <ul>
 *   <li>SQL 代码块（复用 {@link SqlLexer} 分词 + {@link SqlHighlightColors} 配色）</li>
 *   <li>参数对照表</li>
 *   <li>元信息（表名 / 方言 / 时间）</li>
 * </ul>
 *
 * <p>输出为独立 HTML 文件，浏览器打开查看。
 */
public final class HtmlReportFormatter {

    /** 单例（兼容 Kotlin {@code object} 语义）。 */
    @NotNull
    public static final HtmlReportFormatter INSTANCE = new HtmlReportFormatter();

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private HtmlReportFormatter() {
    }

    /**
     * 生成完整 HTML 报告。
     *
     * @param sql         要展示的 SQL
     * @param sources     参数来源列表
     * @param paramValues 参数值映射
     * @param tableName   表名（可 null）
     * @param dialect     方言名称（可 null）
     * @return HTML 全文
     */
    @NotNull
    public String format(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            @Nullable String tableName,
            @Nullable String dialect) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String highlighted = highlightSql(sql);
        String paramsTable = buildParamsTable(sources, paramValues);

        StringBuilder sb = new StringBuilder(2048);
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">\n");
        sb.append("<title>SQL Report</title>\n");
        sb.append("<style>\n");
        sb.append("body{font-family:Consolas,Monaco,'Courier New',monospace;margin:20px;background:#282c34;color:#abb2bf}\n");
        sb.append("h1{color:#61afef;font-size:18px;margin-bottom:8px}\n");
        sb.append(".meta{color:#7f848e;font-size:12px;margin-bottom:16px}\n");
        sb.append(".meta span{margin-right:16px}\n");
        sb.append("pre{background:#1e1e1e;border-radius:6px;padding:16px;overflow-x:auto}\n");
        sb.append("code{font-size:13px;line-height:1.5}\n");
        sb.append(".params{margin-top:16px}\n");
        sb.append(".params h2{color:#61afef;font-size:14px}\n");
        sb.append(".params table{border-collapse:collapse;width:100%;font-size:12px}\n");
        sb.append(".params th,.params td{border:1px solid #3e4451;padding:6px 12px;text-align:left}\n");
        sb.append(".params th{background:#21252b;color:#e5c07b}\n");
        sb.append(".kw{color:#c678dd;font-weight:bold}\n");
        sb.append(".tbl{color:#d55fde}\n");
        sb.append(".ident{color:#61afef}\n");
        sb.append(".str{color:#98c379}\n");
        sb.append(".num{color:#d19a66}\n");
        sb.append(".cmt{color:#7f848e;font-style:italic}\n");
        sb.append(".op{color:#abb2bf}\n");
        sb.append(".paren{color:#abb2bf}\n");
        sb.append("</style></head><body>\n");
        sb.append("<h1>SQL Report</h1>\n");
        sb.append("<div class=\"meta\">\n");
        if (tableName != null) {
            sb.append("<span>Table: <b>").append(tableName).append("</b></span>\n");
        }
        if (dialect != null) {
            sb.append("<span>Dialect: <b>").append(dialect).append("</b></span>\n");
        }
        sb.append("<span>Generated: <b>").append(timestamp).append("</b></span>\n");
        sb.append("</div>\n");
        sb.append("<pre><code>").append(highlighted).append("</code></pre>\n");
        sb.append(paramsTable);
        sb.append("</body></html>");
        return sb.toString();
    }

    /** 使用 {@link SqlLexer} 分词 + CSS 类生成高亮 HTML。 */
    @NotNull
    private String highlightSql(@NotNull String sql) {
        SqlLexer lexer = new SqlLexer();
        lexer.start(sql, 0, sql.length());
        StringBuilder sb = new StringBuilder(sql.length() + 256);
        int offset = 0;
        while (lexer.getTokenType() != null) {
            // 处理 token 前的普通文本（转义 HTML 特殊字符）
            if (lexer.getTokenStart() > offset) {
                sb.append(escapeHtml(sql.substring(offset, lexer.getTokenStart())));
            }
            String tokenText = sql.substring(lexer.getTokenStart(), lexer.getTokenEnd());
            String cssClass = cssClassFor(lexer.getTokenType());
            if (cssClass != null) {
                sb.append("<span class=\"").append(cssClass).append("\">");
                sb.append(escapeHtml(tokenText));
                sb.append("</span>");
            } else {
                sb.append(escapeHtml(tokenText));
            }
            offset = lexer.getTokenEnd();
            lexer.advance();
        }
        // 尾部普通文本
        if (offset < sql.length()) {
            sb.append(escapeHtml(sql.substring(offset)));
        }
        return sb.toString();
    }

    /** token 类型 → CSS 类名。 */
    @Nullable
    private String cssClassFor(@Nullable IElementType tokenType) {
        if (tokenType == SqlTokenTypes.KEYWORD) {
            return "kw";
        } else if (tokenType == SqlTokenTypes.TABLE) {
            return "tbl";
        } else if (tokenType == SqlTokenTypes.IDENTIFIER) {
            return "ident";
        } else if (tokenType == SqlTokenTypes.STRING) {
            return "str";
        } else if (tokenType == SqlTokenTypes.NUMBER) {
            return "num";
        } else if (tokenType == SqlTokenTypes.COMMENT) {
            return "cmt";
        } else if (tokenType == SqlTokenTypes.OPERATOR) {
            return "op";
        } else if (tokenType == SqlTokenTypes.PAREN) {
            return "paren";
        }
        return null;
    }

    /** HTML 特殊字符转义。 */
    @NotNull
    private String escapeHtml(@NotNull String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '&':
                    sb.append("&amp;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#39;");
                    break;
                default:
                    sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 生成参数对照表 HTML。 */
    @NotNull
    private String buildParamsTable(@NotNull List<String> sources, @NotNull Map<String, String> paramValues) {
        if (sources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("<div class=\"params\"><h2>Parameters</h2>\n");
        sb.append("<table><tr><th>Source</th><th>Value</th></tr>\n");
        for (String src : sources) {
            String v = paramValues.getOrDefault(src, "?");
            sb.append("<tr><td><code>").append(src).append("</code></td><td><code>")
                    .append(escapeHtml(v)).append("</code></td></tr>\n");
        }
        sb.append("</table></div>\n");
        return sb.toString();
    }
}
