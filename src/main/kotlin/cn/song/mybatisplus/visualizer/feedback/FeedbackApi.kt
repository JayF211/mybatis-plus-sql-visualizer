package cn.song.mybatisplus.visualizer.feedback

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * 官网需求池 API 客户端（T-ErrReport-01）。
 *
 * 契约（sql-visualizer-admin Worker，前端域 Pages Function 同路径代理，见开发计划 §2.2）：
 *  - POST {base}/feedback/upload  multipart 字段 `file`（≤5MB，扩展名白名单）→ data.key
 *  - POST {base}/feedback/posts   JSON（title 5~200 / description ≥10 / type 1~4 / source='ide'）→ data.postNo
 *  - 提交限流 5 次/小时/IP，429 的服务端文案直接透出。
 *
 * HTTP 层同 UpdateApi 约定：**单测不测本类**——解析/组装为纯函数（internal，单测覆盖），
 * 网络实现由 runIde 沙盒端到端验证；[sender] 为测试 seam（fake 不触网）。
 */
object FeedbackApi {

    private val LOG = Logger.getInstance(FeedbackApi::class.java)

    const val SITE_BASE = "https://sql-visualizer.jvtools.cn"
    const val API_BASE = "$SITE_BASE/api/v1"
    const val UPLOAD_URL = "$API_BASE/feedback/upload"
    const val POSTS_URL = "$API_BASE/feedback/posts"

    /** 提交来源标识：沿用需求池建表 DDL 预留枚举（web / ide / import）。 */
    const val SOURCE_IDE = "ide"

    /** 堆栈附件名（txt 在白名单；内容见 [ErrorReportComposer.buildStackAttachment]）。 */
    const val STACK_ATTACHMENT_NAME = "sql-visualizer-stacktrace.txt"

    private val gson = Gson()

    /** 需求池帖附件引用（先 upload 拿 key，再随 posts 提交）。 */
    data class AttachmentRef(
        val key: String,
        @SerializedName("fileName") val fileName: String,
        @SerializedName("mimeType") val mimeType: String,
        @SerializedName("fileSize") val fileSize: Long,
    )

    /** POST /feedback/posts 请求体（字段名与服务端 JSON 契约一致，null 字段 Gson 默认省略）。 */
    data class PostRequest(
        val title: String,
        val description: String,
        val type: Int,
        val category: String,
        val submitterName: String?,
        val submitterEmail: String?,
        val isAnonymous: Boolean,
        val source: String,
        val ideVersion: String?,
        val pluginVersion: String?,
        val attachments: List<AttachmentRef>,
    )

    /** 堆栈附件（上传前形态）。 */
    class StackAttachment(val fileName: String, val content: ByteArray)

    /** 一次提交 = 请求体 + 可选堆栈附件（附件先上传换 key）。 */
    data class Submission(val request: PostRequest, val stackAttachment: StackAttachment?)

    sealed class SubmitResult {
        data class Success(val postNo: String) : SubmitResult()
        data class Failure(val message: String) : SubmitResult()
    }

    /** HTTP seam：生产实现 [HttpRequestsSender]；测试注入 fake。 */
    interface HttpSender {
        fun postJson(url: String, json: String): String
        fun postMultipart(url: String, fileName: String, mimeType: String, content: ByteArray): String
    }

    @Volatile
    internal var sender: HttpSender = HttpRequestsSender

    /**
     * 提交链路：附件上传（如有）→ 帖子创建。网络/服务端错误统一 [SubmitResult.Failure]
     *（服务端 message 透出，HTTP 异常取 message），不抛出——调用方直接渲染。
     */
    fun submit(submission: Submission): SubmitResult {
        val withAttachments = runCatching {
            val uploaded = submission.stackAttachment?.let { stack ->
                val body = sender.postMultipart(
                    UPLOAD_URL, stack.fileName, "text/plain", stack.content,
                )
                parseUploadResponse(body).getOrElse { failure ->
                    return SubmitResult.Failure(failure.message ?: "附件上传失败")
                }
            }
            submission.request.copy(attachments = listOfNotNull(uploaded))
        }.getOrElse { return SubmitResult.Failure(it.message ?: "附件上传失败") }

        return runCatching {
            parsePostResponse(sender.postJson(POSTS_URL, gson.toJson(withAttachments)))
        }.getOrElse { SubmitResult.Failure(it.message ?: "提交失败") }
    }

    /** 详情页深链（官网已有 postNo 路由约定）。 */
    fun detailUrl(postNo: String): String = "$SITE_BASE/about/feedback?postNo=$postNo"

    /** posts 响应解析（纯函数）：code=0 取 data.postNo，否则 message 透出；非法 JSON 归类失败。 */
    internal fun parsePostResponse(body: String): SubmitResult = runCatching {
        val map: Map<String, Any?> = gson.fromJson(body, object : TypeToken<Map<String, Any?>>() {}.type)
        val code = (map["code"] as? Number)?.toInt()
        if (code == 0) {
            val postNo = ((map["data"] as? Map<*, *>)?.get("postNo"))?.toString().takeUnless { it.isNullOrBlank() }
            if (postNo != null) SubmitResult.Success(postNo)
            else SubmitResult.Failure("服务端响应缺少 postNo")
        } else {
            SubmitResult.Failure(map["message"]?.toString() ?: "提交失败（code=$code）")
        }
    }.getOrElse { SubmitResult.Failure("响应解析失败：${it.message}") }

    /** upload 响应解析（纯函数）：code=0 才取 data 附件引用；错误分支必须透出服务端 message（data 缺席是常态）。 */
    internal fun parseUploadResponse(body: String): Result<AttachmentRef> = runCatching {
        val map: Map<String, Any?> = gson.fromJson(body, object : TypeToken<Map<String, Any?>>() {}.type)
        val code = (map["code"] as? Number)?.toInt()
        val data = map["data"] as? Map<String, Any?>
        if (code == 0 && data != null) {
            val key = data["key"]?.toString().takeUnless { it.isNullOrBlank() } ?: error("响应缺少附件 key")
            AttachmentRef(
                key = key,
                fileName = data["fileName"]?.toString() ?: STACK_ATTACHMENT_NAME,
                mimeType = data["mimeType"]?.toString() ?: "text/plain",
                fileSize = (data["fileSize"] as? Number)?.toLong() ?: 0L,
            )
        } else {
            error(map["message"]?.toString() ?: "附件上传失败（code=$code）")
        }
    }

    /**
     * multipart/form-data 单文件字节流（纯函数）：单字段名固定 `file`（服务端 parseBody 约定），
     * 头部 UTF-8，正尾边界各一空行、`--boundary--` 收尾。
     */
    internal fun buildMultipartBody(
        fileName: String,
        mimeType: String,
        content: ByteArray,
        boundary: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val head = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: $mimeType\r\n" +
            "\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(content)
        out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }
}

/**
 * 生产 HTTP 实现（HttpRequests，尊重 IDE 代理设置）。
 * - JSON：`HttpRequests.post(url, body)` 二参即请求体（243 平台字节码反编译实证）；
 * - multipart：request + tuner 设 POST 方法与 Content-Type（tuner 在连接建立前回调），
 *   body 字节经 `Request.write(byte[])` 写出——write/readString 均 javap 核实存在。
 */
internal object HttpRequestsSender : FeedbackApi.HttpSender {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    override fun postJson(url: String, json: String): String =
        HttpRequests.post(url, json)
            .connectTimeout(CONNECT_TIMEOUT_MS)
            .readTimeout(READ_TIMEOUT_MS)
            .productNameAsUserAgent()
            .tuner { connection ->
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            .throwStatusCodeException(true)
            .connect { it.readString() }

    override fun postMultipart(url: String, fileName: String, mimeType: String, content: ByteArray): String {
        val boundary = "mpsv-feed-${UUID.randomUUID()}"
        val body = FeedbackApi.buildMultipartBody(fileName, mimeType, content, boundary)
        return HttpRequests.request(url)
            .connectTimeout(CONNECT_TIMEOUT_MS)
            .readTimeout(READ_TIMEOUT_MS)
            .productNameAsUserAgent()
            .tuner { connection ->
                (connection as java.net.HttpURLConnection).requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            .throwStatusCodeException(true)
            .connect { request ->
                request.write(body)
                request.readString()
            }
    }
}
