package cn.song.mybatisplus.visualizer.feedback

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
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
 *
 * T-FeedbackErr（2026-09-14 真机：报告失败只见「Server returned HTTP response code: 400」）：
 *  - 服务端 4xx/5xx 的**响应体**才是可读原因（`{"code":400,"message":"标题长度需在 5-200 字符之间"}`），
 *    而 HttpRequests 在错误码下只抛通道级消息——[describeHttpFailure] 给出「服务端文案优先」的
 *    统一呈现，[HttpRequestsSender] 负责从 errorStream 把正文读回来（[postJsonWithBody]）；
 *  - 附件上传失败不再连坐整帖：降级为「不带附件提交」并在返回里带上提示（[SubmissionOutcome]），
 *    用户填的标题/描述不会因为一个 txt 上传失败而白填。
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

    /**
     * 提交结果 + 附件降级提示（T-FeedbackErr）：帖子成功但附件没上传上时
     * [warning] 非空——「报告发出去了，但堆栈没带上」，用户能自己决定要不要补。
     */
    data class SubmissionOutcome(val result: SubmitResult, val warning: String? = null)

    /** HTTP seam：生产实现 [HttpRequestsSender]；测试注入 fake。 */
    interface HttpSender {
        fun postJson(url: String, json: String): String
        fun postMultipart(url: String, fileName: String, mimeType: String, content: ByteArray): String
    }

    /**
     * 通道错误 → 用户可读原因（纯函数，T-FeedbackErr）：服务端响应体里的 `message` 永远优先
     * （那是人写的、能指向具体字段），拿不到正文才回退 HTTP 通道消息；连通道消息都没有时给兜底句。
     * 4xx/5xx 在 [HttpRequestsSender] 里以 [HttpFailure] 抛出（正文已读回），非 HTTP 异常走 cause 链。
     */
    internal fun describeHttpFailure(error: Throwable?): String? {
        if (error == null) return null
        val serverMessage = (error as? HttpFailure)?.body?.let(::serverMessageOf)
        if (!serverMessage.isNullOrBlank()) return serverMessage
        return error.cause?.message?.takeIf { it.isNotBlank() }
            ?: error.message?.takeIf { it.isNotBlank() }
            ?: return null
    }

    /** 任意响应正文里取服务端 `message`（纯函数）：非法 JSON / 非对象 / 缺字段一律 null。 */
    internal fun serverMessageOf(body: String?): String? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching {
            val map: Map<String, Any?> = gson.fromJson(text, object : TypeToken<Map<String, Any?>>() {}.type)
            map["message"]?.toString()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    @Volatile
    internal var sender: HttpSender = HttpRequestsSender

    /**
     * 提交链路：附件上传（如有）→ 帖子创建。
     *
     * T-FeedbackErr 降级：附件上传失败**不再直接失败整帖**——丢掉附件继续提交正文，
     * 结果里附 [SubmissionOutcome.warning] 说明堆栈没带上（真机现场：唯一一次 ATTACH 后
     * 报 400，帖子根本没建；用户填的内容连同堆栈一起丢了）。正文提交失败仍是 [SubmitResult.Failure]。
     */
    fun submit(submission: Submission): SubmissionOutcome {
        var attachmentWarning: String? = null
        val uploaded = submission.stackAttachment?.let { stack ->
            runCatching {
                parseUploadResponse(sender.postMultipart(UPLOAD_URL, stack.fileName, "text/plain", stack.content))
            }.getOrElse { Result.failure(it) }.getOrElse { failure ->
                attachmentWarning = describeHttpFailure(failure) ?: "附件上传失败"
                null
            }
        }
        val request = submission.request.copy(attachments = listOfNotNull(uploaded))
        val result = runCatching {
            parsePostResponse(sender.postJson(POSTS_URL, gson.toJson(request)))
        }.getOrElse { failure ->
            SubmitResult.Failure(describeHttpFailure(failure) ?: "提交失败")
        }
        return SubmissionOutcome(result, attachmentWarning)
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
 *
 * T-FeedbackErr：两条链路都改用 [postJsonWithBody] 手写（不再走 `HttpRequests.post` /
 * `throwStatusCodeException`）。原因是平台默认异常只带通道消息（`Server returned HTTP
 * response code: 400 for URL: …`），而服务端在 4xx 响应体里写明了具体原因；要拿到它必须
 * 自己在错误码分支上把 errorStream 读完，再把「状态码 + 正文」包成 [HttpFailure] 抛出去。
 * 请求方式是 `RequestBuilder.tuner` 里设 RequestMethod（与 multipart 同一手法，javap 已在
 * 生产路径验证过；`RequestBuilder.post(String)` 是 2023.3+ 的新名，243 基线不依赖它）。
 */
internal object HttpRequestsSender : FeedbackApi.HttpSender {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /** 读回的响应：正文参数按 UTF-8 解释（服务端 JSON 恒 UTF-8）。 */
    private data class Response(val status: Int, val body: String)

    override fun postJson(url: String, json: String): String = postJsonWithBody(url, json)

    override fun postMultipart(url: String, fileName: String, mimeType: String, content: ByteArray): String {
        val boundary = "mpsv-feed-${UUID.randomUUID()}"
        val body = FeedbackApi.buildMultipartBody(fileName, mimeType, content, boundary)
        return send(url, "multipart/form-data; boundary=$boundary", body)
    }

    private fun postJsonWithBody(url: String, json: String): String =
        send(url, "application/json; charset=UTF-8", json.toByteArray(Charsets.UTF_8))

    private fun send(url: String, contentType: String, body: ByteArray): String {
        val response = HttpRequests.request(url)
            .connectTimeout(CONNECT_TIMEOUT_MS)
            .readTimeout(READ_TIMEOUT_MS)
            .productNameAsUserAgent()
            .tuner { connection ->
                val http = connection as HttpURLConnection
                http.requestMethod = "POST"
                http.doOutput = true
                http.setRequestProperty("Content-Type", contentType)
            }
            .connect { request ->
                request.write(body)
                val http = request.connection as HttpURLConnection
                val status = http.responseCode
                Response(status, readBody(http, status))
            }
        // 4xx/5xx：把服务端正文一并带出去（用户看到的应是「标题长度需在 5-200 字符之间」，
        // 而不是通道级的 Server returned HTTP response code: 400）——浪费一次外层包装只为可读性
        if (response.status !in 200..299) throw HttpFailure(response.status, response.body)
        return response.body
    }

    /** 错误码分支读 errorStream——平台 `readString()` 只认成功流，读错流会抛 NPE。 */
    private fun readBody(connection: HttpURLConnection, status: Int): String = runCatching {
        val stream = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?: return@runCatching ""
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrDefault("")
}

/**
 * HTTP 非 2xx（T-FeedbackErr）：仅用于把服务端响应正文带回上层——
 * [FeedbackApi.describeHttpFailure] 优先取正文里的 `message`，取不到才回退 [message]
 * （保留 `Server returned HTTP response code: 400 for URL: …` 这种通道信息，供排查）。
 */
internal class HttpFailure(status: Int, val body: String) :
    IOException("Server returned HTTP response code: $status for URL (body: ${body.take(200)})")
