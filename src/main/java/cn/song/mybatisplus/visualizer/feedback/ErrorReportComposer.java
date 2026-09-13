package cn.song.mybatisplus.visualizer.feedback;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * 报告内容组装（T-ErrReport-04）：全部纯函数、平台无关（单测直接覆盖）。
 *
 * <p>标题/描述满足需求池服务端约束（title 5~200、description ≥10，见开发计划 §2.2 契约表）；
 * 分类映射为粗粒度启发式（按插件包名线索），管理员可在需求池后台改判。
 */
public final class ErrorReportComposer {

    /** Kotlin object 单例等价。 */
    public static final ErrorReportComposer INSTANCE = new ErrorReportComposer();

    private ErrorReportComposer() {
    }

    public static final int TITLE_MAX = 200;
    private static final int TITLE_MIN = 5;
    private static final int DESCRIPTION_MIN = 10;

    /** 需求池反馈类型：1功能需求 / 2Bug / 3体验优化 / 4疑问咨询（sql-visualizer-admin constants）。 */
    public static final int TYPE_BUG = 2;
    public static final int TYPE_QUESTION = 4;

    /** 对话框预填（错误形态；手动形态各字段为空由对话框自行留白）。 */
    public static final class Prefill {
        private final String title;
        private final String description;
        private final String category;

        public Prefill(String title, String description, String category) {
            this.title = title;
            this.description = description;
            this.category = category;
        }

        public String getTitle() {
            return title;
        }

        public String getDescription() {
            return description;
        }

        public String getCategory() {
            return category;
        }

        public Prefill copy(String title, String description, String category) {
            return new Prefill(title, description, category);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Prefill)) {
                return false;
            }
            Prefill that = (Prefill) o;
            return Objects.equals(title, that.title)
                    && Objects.equals(description, that.description)
                    && Objects.equals(category, that.category);
        }

        @Override
        public int hashCode() {
            return Objects.hash(title, description, category);
        }

        @Override
        public String toString() {
            return "Prefill(title=" + title + ", description=" + description + ", category=" + category + ")";
        }
    }

    /** 从异常信息组装预填（title = 异常类简单名: message 首行，截断到 200）。 */
    public static Prefill prefill(@Nullable String throwableClassName, @Nullable String message,
                                  @Nullable String stackTraceText) {
        return new Prefill(
                buildTitle(throwableClassName, message),
                buildDescription(message, null, null),
                guessCategory(stackTraceText));
    }

    /** 标题规则：{@code 异常类简单名: message 首行}；超 200 截断、不足 5 补后缀兜底。 */
    public static String buildTitle(@Nullable String throwableClassName, @Nullable String message) {
        String simpleName = "PluginError";
        if (throwableClassName != null) {
            int dot = throwableClassName.lastIndexOf('.');
            String candidate = dot >= 0 ? throwableClassName.substring(dot + 1) : throwableClassName;
            if (!candidate.isBlank()) {
                simpleName = candidate;
            }
        }
        String firstLine = "";
        if (message != null) {
            for (String line : message.split("\\R")) {
                if (!line.isBlank()) {
                    firstLine = line.trim();
                    break;
                }
            }
        }
        String raw = firstLine.isBlank() ? simpleName + ": (no message)" : simpleName + ": " + firstLine;
        String truncated = raw.length() > TITLE_MAX ? raw.substring(0, TITLE_MAX) : raw;
        if (truncated.length() < TITLE_MIN) {
            return truncated + " (SQL Visualizer)";
        }
        return truncated;
    }

    /** 描述组装：message 全文 + 用户补充 + 指纹行；不足 10 字符补兜底句（服务端 400 拒绝线）。 */
    public static String buildDescription(@Nullable String message, @Nullable String userNotes,
                                          @Nullable String fingerprint) {
        List<String> parts = new ArrayList<>();
        String msg = message != null ? message.trim() : null;
        if (msg != null && !msg.isBlank()) {
            parts.add(msg);
        }
        String notes = userNotes != null ? userNotes.trim() : null;
        if (notes != null && !notes.isBlank()) {
            parts.add(notes);
        }
        if (fingerprint != null && !fingerprint.isBlank()) {
            parts.add("fingerprint: " + fingerprint);
        }
        String text = String.join("\n\n", parts).trim();
        if (text.length() < DESCRIPTION_MIN) {
            return text + "\n(from IDE error report)";
        }
        return text;
    }

    /** 分类映射：堆栈/消息中的插件包名线索 → 需求池 category slug；兜底 other（特定规则先于 wrapper）。 */
    public static String guessCategory(@Nullable String stackTraceText) {
        if (stackTraceText == null) {
            return CATEGORY_OTHER;
        }
        if (stackTraceText.contains("visualizer.log") || stackTraceText.contains("JdbcTemplateLogParser")) {
            return CATEGORY_SQL_LOG;
        }
        if (stackTraceText.contains("BatchOperationConverter") || stackTraceText.contains("visualizer.batch")) {
            return CATEGORY_BATCH;
        }
        if (stackTraceText.contains("SqlReverseParser") || stackTraceText.contains("ReverseConverter")) {
            return CATEGORY_SQL_TO_JAVA;
        }
        if (stackTraceText.contains("visualizer.completion") || stackTraceText.contains("CompletionContrib")) {
            return CATEGORY_COMPLETION;
        }
        if (stackTraceText.contains("visualizer.xml") || stackTraceText.contains("XmlPreview")) {
            return CATEGORY_XML;
        }
        if (stackTraceText.contains("visualizer.annotation")) {
            return CATEGORY_ANNOTATION;
        }
        if (stackTraceText.contains("visualizer.converter") || stackTraceText.contains("Wrapper")) {
            return CATEGORY_WRAPPER;
        }
        return CATEGORY_OTHER;
    }

    /**
     * 堆栈附件 txt 内容：环境头 + 异常全文（服务端附件白名单允许 .txt，≤5MB——
     * 超长堆栈截断到 60_000 字符，正常堆栈远小于此）。
     */
    public static String buildStackAttachment(@Nullable String stackTraceText, @Nullable String ideVersion,
                                              @Nullable String pluginVersion, @Nullable String fingerprint,
                                              String occurredAt) {
        StringBuilder out = new StringBuilder();
        out.append("SQL Visualizer 错误报告\n");
        out.append("========================================\n");
        out.append("时间: ").append(occurredAt).append('\n');
        out.append("IDE: ").append(ideVersion != null ? ideVersion : "unknown").append('\n');
        out.append("插件版本: ").append(pluginVersion != null ? pluginVersion : "unknown").append('\n');
        out.append("操作系统: ")
                .append(System.getProperty("os.name") != null ? System.getProperty("os.name") : "unknown")
                .append(' ')
                .append(System.getProperty("os.version") != null ? System.getProperty("os.version") : "")
                .append('\n');
        out.append("指纹: ").append(fingerprint != null ? fingerprint : "none").append('\n');
        out.append("========================================\n");
        out.append('\n');
        out.append(stackTraceText != null
                ? stackTraceText.substring(0, Math.min(stackTraceText.length(), 60_000))
                : "(无堆栈)");
        return out.toString();
    }

    /** 需求池分类 slug（与 sql-visualizer-admin Categories 一致）。 */
    public static final String CATEGORY_WRAPPER = "wrapper";
    public static final String CATEGORY_XML = "xml";
    public static final String CATEGORY_ANNOTATION = "annotation";
    public static final String CATEGORY_SQL_TO_JAVA = "sql-to-java";
    public static final String CATEGORY_SQL_LOG = "sql-log";
    public static final String CATEGORY_BATCH = "batch";
    public static final String CATEGORY_COMPLETION = "completion";
    public static final String CATEGORY_OTHER = "other";
}
