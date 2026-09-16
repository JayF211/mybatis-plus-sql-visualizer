package cn.song.mybatisplus.visualizer.feedback;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.diagnostic.Logger;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * 官网需求池 API 客户端（T-ErrReport-01）。
 *
 * <p>契约（sql-visualizer-admin Worker，前端域 Pages Function 同路径代理，见开发计划 §2.2）：
 * <ul>
 *  <li>POST {base}/feedback/upload  multipart 字段 {@code file}（≤5MB，扩展名白名单）→ data.key</li>
 *  <li>POST {base}/feedback/posts   JSON（title 5~200 / description ≥10 / type 1~4 / source='ide'）→ data.postNo</li>
 *  <li>提交限流 5 次/小时/IP，429 的服务端文案直接透出。</li>
 * </ul>
 *
 * <p>HTTP 层同 UpdateApi 约定：<b>单测不测本类</b>——解析/组装为纯函数（internal，单测覆盖），
 * 网络实现由 runIde 沙盒端到端验证；{@code sender} 为测试 seam（fake 不触网）。
 */
public final class FeedbackApi {

    /** Kotlin object 单例等价。 */
    public static final FeedbackApi INSTANCE = new FeedbackApi();

    private FeedbackApi() {
    }

    private static final Logger LOG = Logger.getInstance(FeedbackApi.class);

    public static final String SITE_BASE = "https://sql-visualizer.jvtools.cn";
    public static final String API_BASE = SITE_BASE + "/api/v1";
    public static final String UPLOAD_URL = API_BASE + "/feedback/upload";
    public static final String POSTS_URL = API_BASE + "/feedback/posts";

    /** 提交来源标识：沿用需求池建表 DDL 预留枚举（web / ide / import）。 */
    public static final String SOURCE_IDE = "ide";

    /** 堆栈附件名（txt 在白名单；内容见 {@link ErrorReportComposer#buildStackAttachment}）。 */
    public static final String STACK_ATTACHMENT_NAME = "sql-visualizer-stacktrace.txt";

    private static final Gson GSON = new Gson();

    private static final TypeToken<Map<String, Object>> MAP_TYPE = new TypeToken<Map<String, Object>>() {};

    /** HTTP seam：生产实现 {@link HttpRequestsSender}；测试注入 fake。 */
    public interface HttpSender {
        // 实现（HttpRequests.connect）声明 throws IOException，seam 以 Exception 收宽（fake 可不抛）
        String postJson(String url, String json) throws Exception;

        String postMultipart(String url, String fileName, String mimeType, byte[] content) throws Exception;
    }

    private static volatile HttpSender sender = HttpRequestsSender.INSTANCE;

    public static HttpSender getSender() {
        return sender;
    }

    public static void setSender(HttpSender replacement) {
        sender = replacement;
    }

    /** 需求池帖附件引用（先 upload 拿 key，再随 posts 提交）。 */
    public static final class AttachmentRef {
        private final String key;
        private final String fileName;
        private final String mimeType;
        private final long fileSize;

        public AttachmentRef(String key, String fileName, String mimeType, long fileSize) {
            this.key = key;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.fileSize = fileSize;
        }

        public String getKey() {
            return key;
        }

        public String getFileName() {
            return fileName;
        }

        public String getMimeType() {
            return mimeType;
        }

        public long getFileSize() {
            return fileSize;
        }

        public AttachmentRef copy(String key, String fileName, String mimeType, long fileSize) {
            return new AttachmentRef(key, fileName, mimeType, fileSize);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AttachmentRef)) {
                return false;
            }
            AttachmentRef that = (AttachmentRef) o;
            return fileSize == that.fileSize
                    && Objects.equals(key, that.key)
                    && Objects.equals(fileName, that.fileName)
                    && Objects.equals(mimeType, that.mimeType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, fileName, mimeType, fileSize);
        }

        @Override
        public String toString() {
            return "AttachmentRef(key=" + key + ", fileName=" + fileName + ", mimeType=" + mimeType
                    + ", fileSize=" + fileSize + ")";
        }
    }

    /** POST /feedback/posts 请求体（字段名与服务端 JSON 契约一致，null 字段 Gson 默认省略）。 */
    public static final class PostRequest {
        private final String title;
        private final String description;
        private final int type;
        private final String category;
        private final String submitterName;
        private final String submitterEmail;
        private final boolean isAnonymous;
        private final String source;
        private final String ideVersion;
        private final String pluginVersion;
        private final List<AttachmentRef> attachments;

        public PostRequest(String title, String description, int type, String category,
                           @Nullable String submitterName, @Nullable String submitterEmail, boolean isAnonymous,
                           String source, @Nullable String ideVersion, @Nullable String pluginVersion,
                           List<AttachmentRef> attachments) {
            this.title = title;
            this.description = description;
            this.type = type;
            this.category = category;
            this.submitterName = submitterName;
            this.submitterEmail = submitterEmail;
            this.isAnonymous = isAnonymous;
            this.source = source;
            this.ideVersion = ideVersion;
            this.pluginVersion = pluginVersion;
            this.attachments = attachments;
        }

        public String getTitle() {
            return title;
        }

        public String getDescription() {
            return description;
        }

        public int getType() {
            return type;
        }

        public String getCategory() {
            return category;
        }

        @Nullable
        public String getSubmitterName() {
            return submitterName;
        }

        @Nullable
        public String getSubmitterEmail() {
            return submitterEmail;
        }

        /** 兼容 Kotlin data class 属性命名（anonymous：JavaBean 语义为 isAnonymous）。 */
        public boolean isAnonymous() {
            return isAnonymous;
        }

        public boolean getIsAnonymous() {
            return isAnonymous;
        }

        public String getSource() {
            return source;
        }

        @Nullable
        public String getIdeVersion() {
            return ideVersion;
        }

        @Nullable
        public String getPluginVersion() {
            return pluginVersion;
        }

        public List<AttachmentRef> getAttachments() {
            return attachments;
        }

        public PostRequest copy(String title, String description, int type, String category,
                                String submitterName, String submitterEmail, boolean isAnonymous, String source,
                                String ideVersion, String pluginVersion, List<AttachmentRef> attachments) {
            return new PostRequest(title, description, type, category, submitterName, submitterEmail, isAnonymous,
                    source, ideVersion, pluginVersion, attachments);
        }

        /** 仅替换附件（Kotlin {@code copy(attachments = ...)} 的等价形态）。 */
        public PostRequest copy(List<AttachmentRef> attachments) {
            return new PostRequest(title, description, type, category, submitterName, submitterEmail, isAnonymous,
                    source, ideVersion, pluginVersion, attachments);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PostRequest)) {
                return false;
            }
            PostRequest that = (PostRequest) o;
            return type == that.type
                    && isAnonymous == that.isAnonymous
                    && Objects.equals(title, that.title)
                    && Objects.equals(description, that.description)
                    && Objects.equals(category, that.category)
                    && Objects.equals(submitterName, that.submitterName)
                    && Objects.equals(submitterEmail, that.submitterEmail)
                    && Objects.equals(source, that.source)
                    && Objects.equals(ideVersion, that.ideVersion)
                    && Objects.equals(pluginVersion, that.pluginVersion)
                    && Objects.equals(attachments, that.attachments);
        }

        @Override
        public int hashCode() {
            return Objects.hash(title, description, type, category, submitterName, submitterEmail, isAnonymous,
                    source, ideVersion, pluginVersion, attachments);
        }

        @Override
        public String toString() {
            return "PostRequest(title=" + title + ", description=" + description + ", type=" + type
                    + ", category=" + category + ", submitterName=" + submitterName + ", submitterEmail="
                    + submitterEmail + ", isAnonymous=" + isAnonymous + ", source=" + source + ", ideVersion="
                    + ideVersion + ", pluginVersion=" + pluginVersion + ", attachments=" + attachments + ")";
        }
    }

    /** 堆栈附件（上传前形态）。 */
    public static final class StackAttachment {
        private final String fileName;
        private final byte[] content;

        public StackAttachment(String fileName, byte[] content) {
            this.fileName = fileName;
            this.content = content;
        }

        public String getFileName() {
            return fileName;
        }

        public byte[] getContent() {
            return content;
        }
    }

    /** 一次提交 = 请求体 + 可选堆栈附件（附件先上传换 key）。 */
    public static final class Submission {
        private final PostRequest request;
        @Nullable private final StackAttachment stackAttachment;

        public Submission(PostRequest request, @Nullable StackAttachment stackAttachment) {
            this.request = request;
            this.stackAttachment = stackAttachment;
        }

        public PostRequest getRequest() {
            return request;
        }

        @Nullable
        public StackAttachment getStackAttachment() {
            return stackAttachment;
        }

        public Submission copy(PostRequest request, StackAttachment stackAttachment) {
            return new Submission(request, stackAttachment);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Submission)) {
                return false;
            }
            Submission that = (Submission) o;
            return Objects.equals(request, that.request)
                    && Objects.equals(stackAttachment, that.stackAttachment);
        }

        @Override
        public int hashCode() {
            return Objects.hash(request, stackAttachment);
        }

        @Override
        public String toString() {
            return "Submission(request=" + request + ", stackAttachment=" + stackAttachment + ")";
        }
    }

    public static abstract class SubmitResult {
        private SubmitResult() {
        }

        public static final class Success extends SubmitResult {
            private final String postNo;

            public Success(String postNo) {
                this.postNo = postNo;
            }

            public String getPostNo() {
                return postNo;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (!(o instanceof Success)) {
                    return false;
                }
                return Objects.equals(postNo, ((Success) o).postNo);
            }

            @Override
            public int hashCode() {
                return Objects.hash(postNo);
            }

            @Override
            public String toString() {
                return "Success(postNo=" + postNo + ")";
            }
        }

        public static final class Failure extends SubmitResult {
            private final String message;

            public Failure(String message) {
                this.message = message;
            }

            public String getMessage() {
                return message;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (!(o instanceof Failure)) {
                    return false;
                }
                return Objects.equals(message, ((Failure) o).message);
            }

            @Override
            public int hashCode() {
                return Objects.hash(message);
            }

            @Override
            public String toString() {
                return "Failure(message=" + message + ")";
            }
        }
    }

    /**
     * 提交结果 + 附件降级提示（T-FeedbackErr）：帖子成功但附件没上传上时
     * {@code warning} 非空——「报告发出去了，但堆栈没带上」，用户能自己决定要不要补。
     */
    public static final class SubmissionOutcome {
        private final SubmitResult result;
        private final String warning;

        public SubmissionOutcome(SubmitResult result, String warning) {
            this.result = result;
            this.warning = warning;
        }

        public SubmissionOutcome(SubmitResult result) {
            this(result, null);
        }

        public SubmitResult getResult() {
            return result;
        }

        public String getWarning() {
            return warning;
        }
    }

    /**
     * 通道错误 → 用户可读原因（纯函数，T-FeedbackErr）：服务端响应体 {@code message} 永远优先
     * （那是人写的、能指向具体字段），非 JSON 正文（代理/CDN 拦截页）给可读归类，拿不到才回退
     * HTTP 通道消息；连通道消息都没有时给兜底句。
     * 4xx/5xx 在 {@link HttpRequestsSender} 里以 {@link HttpFailure} 抛出（正文已读回），非 HTTP 异常走 cause 链。
     */
    static String describeHttpFailure(Throwable error) {
        if (error == null) {
            return null;
        }
        if (error instanceof HttpFailure) {
            HttpFailure failure = (HttpFailure) error;
            String serverMessage = serverMessageOf(failure.getBody());
            if (serverMessage != null) {
                return serverMessage;
            }
            String hint = nonJsonHint(failure.getBody());
            if (hint != null) {
                return hint + "（HTTP " + failure.getStatus() + "）";
            }
        }
        if (error.getCause() != null && error.getCause().getMessage() != null && !error.getCause().getMessage().isBlank()) {
            return error.getCause().getMessage();
        }
        if (error.getMessage() != null && !error.getMessage().isBlank()) {
            return error.getMessage();
        }
        return null;
    }

    /** 任意响应正文里取服务端 {@code message}（纯函数）：非法 JSON / 非对象 / 缺字段一律 null。 */
    static String serverMessageOf(String body) {
        String text = body != null ? body.trim() : "";
        if (text.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> map = GSON.fromJson(text, MAP_TYPE.getType());
            Object message = map != null ? map.get("message") : null;
            if (message != null) {
                String s = String.valueOf(message);
                return s.isBlank() ? null : s;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 非 JSON 正文的可读归类（纯函数，T-FeedbackErr 真机第二轮）：本服务所有接口的 2xx 响应
     * 都是 {@code {...}} 对象；不以 {@code {}@code 开头＝响应被中间层替换——HTML 拦截页（代理/CDN）、网关错误页、
     * SPA 404 页最常见。Gson lenient 模式对这类正文报「Expected BEGIN_OBJECT but was STRING
     * at line 1 column 1」，对用户毫无诊断价值，故截取正文片段给出处线索。
     */
    static String nonJsonHint(String body) {
        String text = body != null ? body.trim() : "";
        if (text.isEmpty()) {
            return "服务端返回空响应（网络或代理异常）";
        }
        if (text.startsWith("{")) {
            return null;
        }
        String snippet = text.length() > 80 ? text.substring(0, 80) : text;
        snippet = snippet.replaceAll("\\s+", " ");
        return "服务端返回了非 JSON 响应（疑似代理/CDN 拦截页或网关错误页）：" + snippet;
    }

    /**
     * 提交链路：附件上传（如有）→ 帖子创建。
     *
     * T-FeedbackErr 降级：附件上传失败<b>不再直接失败整帖</b>——丢掉附件继续提交正文，
     * 结果里附 {@link SubmissionOutcome} 的 warning 说明堆栈没带上（真机现场：唯一一次 ATTACH 后
     * 报 400，帖子根本没建；用户填的内容连同堆栈一起丢了）。正文提交失败仍是 {@link SubmitResult.Failure}。
     */
    public static SubmissionOutcome submit(Submission submission) {
        String attachmentWarning = null;
        AttachmentRef uploaded = null;
        StackAttachment stack = submission.getStackAttachment();
        if (stack != null) {
            try {
                uploaded = parseUploadResponse(sender.postMultipart(
                        UPLOAD_URL, stack.getFileName(), "text/plain", stack.getContent()));
            } catch (Throwable failure) {
                LOG.debug("feedback attachment upload failed", failure);
                attachmentWarning = describeHttpFailure(failure) != null ? describeHttpFailure(failure) : "附件上传失败";
                uploaded = null;
            }
        }
        PostRequest request = submission.getRequest().copy(uploaded != null ? List.of(uploaded) : List.of());
        SubmitResult result;
        try {
            result = parsePostResponse(sender.postJson(POSTS_URL, GSON.toJson(request)));
        } catch (Throwable failure) {
            LOG.debug("feedback post failed", failure);
            result = new SubmitResult.Failure(describeHttpFailure(failure) != null ? describeHttpFailure(failure) : "提交失败");
        }
        return new SubmissionOutcome(result, attachmentWarning);
    }

    /** 详情页深链（官网已有 postNo 路由约定）。 */
    public static String detailUrl(String postNo) {
        return SITE_BASE + "/about/feedback?postNo=" + postNo;
    }

    /** posts 响应解析（纯函数）：code=0 取 data.postNo，否则 message 透出；正文不是 JSON 对象时给可读归类。 */
    static SubmitResult parsePostResponse(String body) {
        try {
            Map<String, Object> map = GSON.fromJson(body, MAP_TYPE.getType());
            Integer code = intOrNull(map.get("code"));
            if (code != null && code == 0) {
                String postNo = stringOrNull(valueOfData(map, "postNo"));
                if (postNo != null && !postNo.isBlank()) {
                    return new SubmitResult.Success(postNo);
                }
                return new SubmitResult.Failure("服务端响应缺少 postNo");
            }
            Object message = map.get("message");
            return new SubmitResult.Failure(message != null ? String.valueOf(message) : "提交失败（code=" + code + "）");
        } catch (Throwable failure) {
            return new SubmitResult.Failure(nonJsonHint(body) != null ? nonJsonHint(body) : "响应解析失败：" + failure.getMessage());
        }
    }

    /**
     * upload 响应解析（纯函数）：code=0 才取 data 附件引用；错误分支必须透出服务端 message
     *（data 缺席是常态）。失败以 {@link IllegalStateException} 抛出（message 即服务端文案），
     * {@link #submit} 捕获后折入 {@link SubmitResult.Failure}——对应 Kotlin 版
     * {@code Result<AttachmentRef>} 的 {@code getOrElse} 早退语义。
     */
    static AttachmentRef parseUploadResponse(String body) throws IllegalStateException {
        Map<String, Object> map;
        try {
            map = GSON.fromJson(body, MAP_TYPE.getType());
        } catch (Throwable failure) {
            throw new IllegalStateException(nonJsonHint(body) != null ? nonJsonHint(body) : "响应解析失败：" + failure.getMessage());
        }
        Integer code = intOrNull(map.get("code"));
        Object dataRaw = map.get("data");
        Map<?, ?> data = dataRaw instanceof Map ? (Map<?, ?>) dataRaw : null;
        if (code != null && code == 0 && data != null) {
            String key = stringOrNull(data.get("key"));
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("响应缺少附件 key");
            }
            return new AttachmentRef(
                    key,
                    stringOrDefault(data.get("fileName"), STACK_ATTACHMENT_NAME),
                    stringOrDefault(data.get("mimeType"), "text/plain"),
                    longOrDefault(data.get("fileSize"), 0L));
        }
        Object message = map.get("message");
        throw new IllegalStateException(message != null ? String.valueOf(message) : "附件上传失败（code=" + code + "）");
    }

    /**
     * multipart/form-data 单文件字节流（纯函数）：单字段名固定 {@code file}（服务端 parseBody 约定），
     * 头部 UTF-8，正尾边界各一空行、{@code --boundary--} 收尾。
     */
    static byte[] buildMultipartBody(String fileName, String mimeType, byte[] content, String boundary) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + mimeType + "\r\n"
                + "\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // 解析辅助
    // ------------------------------------------------------------------

    private static @Nullable Integer intOrNull(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static long longOrDefault(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static @Nullable String stringOrNull(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private static String stringOrDefault(Object value, String fallback) {
        return value != null ? String.valueOf(value) : fallback;
    }

    /** {@code (map["data"] as? Map<*, *>)?.get(key)} 的等价读取。 */
    private static @Nullable Object valueOfData(Map<String, Object> map, String key) {
        Object data = map.get("data");
        if (data instanceof Map) {
            return ((Map<?, ?>) data).get(key);
        }
        return null;
    }
}
