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
 *
 * <p>T-FeedbackBatch（2026-09-14 用户定案「不能被轻易限流」）：平台错误列表可多选，用户常一次报一堆——
 * 服务端限流是 5 次/小时/IP，逐条提交必然 429。策略：多条合并成<b>一条</b>帖子（描述出清单、附件收全文，
 * 见 {@link #buildBatchTitle}/{@link #buildBatchDescription}/{@link #buildStackAttachment}），把 N 次请求压成 1 次。
 */
public final class ErrorReportComposer {

    /** Kotlin object 单例等价。 */
    public static final ErrorReportComposer INSTANCE = new ErrorReportComposer();

    private ErrorReportComposer() {
    }

    public static final int TITLE_MAX = 200;
    /** 下限供对话框客户端校验复用（T-FeedbackDlg-01，单一事实源）。 */
    public static final int TITLE_MIN = 5;
    /** 下限供对话框客户端校验复用（T-FeedbackDlg-01，单一事实源）。 */
    public static final int DESCRIPTION_MIN = 10;

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
        return buildStackAttachment(
                List.of(stackTraceText != null ? stackTraceText : "(无堆栈)"),
                ideVersion, pluginVersion, fingerprint, occurredAt);
    }

    /**
     * 多事件堆栈附件（T-FeedbackBatch）：平台在对话框里可多选错误，勾选几条就带几条——
     * 每条独立分节（{@code #N 异常类: 消息} 标题 + 全文），空/空白条目剔除；
     * 全部为空时退化为单节 {@code (无堆栈)}。总量仍截断到 60_000 字符。
     */
    public static String buildStackAttachment(List<String> stackTraces, @Nullable String ideVersion,
                                              @Nullable String pluginVersion, @Nullable String fingerprint,
                                              String occurredAt) {
        StringBuilder out = new StringBuilder();
        // T-FeedbackErr 真机第三轮：UTF-8 BOM 让附件自描述编码——R2 元数据里的 MIME 不带 charset，
        // 消费方若忽略响应头（浏览器按本地编码猜、编辑器按系统默认）会把中文堆栈显示成乱码。
        // 真机乱码根因在服务端响应头（已同步补 charset=utf-8），BOM 是文本文件的标准自描述兜底
        out.append('\uFEFF');
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
        int nonBlankCount = 0;
        for (String stack : stackTraces) {
            if (stack != null && !stack.isBlank()) nonBlankCount++;
        }
        out.append("异常条数: ").append(Math.max(nonBlankCount, 1)).append('\n');
        out.append("========================================\n");
        out.append('\n');
        List<String> meaningful = new ArrayList<>();
        for (String stack : stackTraces) {
            if (stack != null && !stack.isBlank()) meaningful.add(stack);
        }
        if (meaningful.isEmpty()) {
            out.append("(无堆栈)");
            return truncateAttachment(out.toString());
        }
        for (int index = 0; index < meaningful.size(); index++) {
            String stack = meaningful.get(index);
            if (index > 0) out.append('\n').append('\n');
            out.append("----- #").append(index + 1).append(" -----\n");
            // T-PerfSweep2-03：append 前逐条截断——此前全文 append 后才 take(60_000)，
            // 组装瞬时分配与所选堆栈总量成正比（20 条 × 大堆栈病态场景在 EDT 瞬时数十 MB
            // 字符操作，弹窗打开有可感卡顿窗）。单条路径保持 ATTACHMENT_MAX_CHARS（存量
            // 行为 byte 级兼容）；批量按条数均分上限（半幅均分，留足分节头与尾注余量）——
            // 瞬时上界 ~30KB 且 20 节级批次不再被终裁整节丢弃（固定 8k/条 × 20 = 160k 仍会
            // 触发 take 丢节，均分方案同时修复内存面与丢节面）；末尾 take 保留为最后防线。
            int limit = meaningful.size() == 1
                    ? ATTACHMENT_MAX_CHARS
                    : Math.max(ATTACHMENT_MAX_CHARS / (meaningful.size() * 2), PER_STACK_MIN_CHARS);
            if (stack.length() > limit) {
                out.append(stack, 0, limit).append("（已截断，原 ").append(stack.length()).append(" 字符）");
            } else {
                out.append(stack);
            }
            if (!stack.endsWith("\n")) out.append('\n');
        }
        return truncateAttachment(out.toString());
    }

    /** 单条附件字符上限（原 60_000 字面量收编为常量，单/批两路径共用最后防线）。 */
    private static final int ATTACHMENT_MAX_CHARS = 60_000;

    /** T-PerfSweep2-03：批量路径每条堆栈下限——条数极多时每节仍保 1k（≈15 帧堆栈）诊断上下文。 */
    private static final int PER_STACK_MIN_CHARS = 1_000;

    private static String truncateAttachment(String text) {
        return text.length() > ATTACHMENT_MAX_CHARS ? text.substring(0, ATTACHMENT_MAX_CHARS) : text;
    }

    // ---------- 多事件批量报告（T-FeedbackBatch） ----------

    /** 批量报告条目：一条异常事件 → 一节内容。 */
    public static final class BatchEntry {
        private final String title;
        private final String message;
        private final String stackTraceText;

        public BatchEntry(String title, @Nullable String message, @Nullable String stackTraceText) {
            this.title = title;
            this.message = message;
            this.stackTraceText = stackTraceText;
        }

        public String getTitle() {
            return title;
        }

        @Nullable
        public String getMessage() {
            return message;
        }

        @Nullable
        public String getStackTraceText() {
            return stackTraceText;
        }

        public BatchEntry copy(String title, String message, String stackTraceText) {
            return new BatchEntry(title, message, stackTraceText);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BatchEntry)) {
                return false;
            }
            BatchEntry that = (BatchEntry) o;
            return Objects.equals(title, that.title)
                    && Objects.equals(message, that.message)
                    && Objects.equals(stackTraceText, that.stackTraceText);
        }

        @Override
        public int hashCode() {
            return Objects.hash(title, message, stackTraceText);
        }

        @Override
        public String toString() {
            return "BatchEntry(title=" + title + ", message=" + message + ", stackTraceText=" + stackTraceText + ")";
        }
    }

    /** 批量合并阈值：≤ 此条数合成一条帖子（服务端 5 次/小时/IP，逐条报必撞 429）。 */
    public static final int BATCH_MERGE_MAX = 5;

    /** 单条合并帖子的异常数上限（超出截断并注明）。 */
    public static final int BATCH_MAX_ITEMS = 20;

    /**
     * 合并帖标题（纯函数，≤200）：首条异常摘要 + {@code 等 N 条异常}，让管理员一眼看出是批次报告。
     */
    public static String buildBatchTitle(List<BatchEntry> entries) {
        BatchEntry first = entries.isEmpty() ? null : entries.get(0);
        String head = buildTitle(first != null ? first.getTitle() : null,
                first != null ? first.getMessage() : null);
        if (entries.size() <= 1) {
            return head;
        }
        String suffix = "（等 " + entries.size() + " 条异常）";
        int room = TITLE_MAX - suffix.length();
        String truncated = head.length() > room ? head.substring(0, room) : head;
        return truncated.length() < TITLE_MIN ? truncated + suffix + " (SQL Visualizer)" : truncated + suffix;
    }

    /**
     * 合并帖描述（纯函数）：总条数说明 + 每条 {@code #N [异常类] 消息} 清单（超出 {@link #BATCH_MAX_ITEMS} 截断并注明），
     * 保证 ≥ {@link #DESCRIPTION_MIN}（服务端 400 拒绝线）。
     */
    public static String buildBatchDescription(List<BatchEntry> entries, @Nullable String userNotes) {
        int total = entries.size();
        int shownCount = Math.min(total, BATCH_MAX_ITEMS);
        StringBuilder out = new StringBuilder();
        out.append("（来自 IDE 的错误批次报告，共 ").append(total).append(" 条）\n");
        for (int index = 0; index < shownCount; index++) {
            BatchEntry entry = entries.get(index);
            String raw = entry.getTitle();
            int dot = raw.lastIndexOf('.');
            String type = raw.substring(dot + 1);
            if (type.isBlank()) {
                type = "PluginError";
            }
            String firstLine = "";
            if (entry.getMessage() != null) {
                for (String line : entry.getMessage().split("\\R")) {
                    if (!line.isBlank()) {
                        firstLine = line.trim();
                        break;
                    }
                }
            }
            out.append('#').append(index + 1).append(' ').append(type);
            if (!firstLine.isBlank()) {
                out.append(": ").append(firstLine);
            }
            out.append('\n');
        }
        if (total > shownCount) {
            out.append("（其余 ").append(total - shownCount).append(" 条见附件）\n");
        }
        String notes = userNotes != null ? userNotes.trim() : null;
        if (notes != null && !notes.isBlank()) {
            out.append('\n').append(notes).append('\n');
        }
        String text = out.toString().trim();
        return text.length() < DESCRIPTION_MIN ? text + "\n(from IDE error report)" : text;
    }

    /**
     * 是否走合并帖（纯函数）：条数 &gt; 1 且 ≤ {@link #BATCH_MERGE_MAX} 时合并成一条帖子，
     * 把 N 次请求压成 1 次——限流桶是「5 次/小时/IP」，逐条提交等于把用户的额度一次烧光。
     * 条数更多时另走「先传附件、再补发」两步（见 PendingReportScheduler 错峰队列）。
     */
    public static boolean shouldMerge(int entryCount) {
        return entryCount >= 2 && entryCount <= BATCH_MERGE_MAX;
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
