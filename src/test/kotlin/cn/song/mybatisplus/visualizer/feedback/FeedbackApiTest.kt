package cn.song.mybatisplus.visualizer.feedback

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-ErrReport-01：需求池 API 客户端单测——JSON 契约 / multipart 字节结构 / 响应解析 /
 * fake sender 全链路。网络实现（HttpRequestsSender）按 UpdateApi 惯例不在单测覆盖。
 */
class FeedbackApiTest {

    private val gson = Gson()

    @After
    fun tearDown() {
        FeedbackApi.sender = HttpRequestsSender.INSTANCE
    }

    /** 记录调用并返回可配置响应的 fake sender（捕获列表供断言）。 */
    private class RecordingSender(
        private val jsonResponse: (String, String) -> String =
            { _, _ -> """{"code":0,"message":"ok","data":{"postNo":"FR-0042"}}""" },
        private val uploadResponse: (String, String, ByteArray) -> String =
            { _, _, _ -> """{"code":0,"data":{"key":"feed/x.txt","fileName":"s.txt","mimeType":"text/plain","fileSize":9}}""" },
    ) : FeedbackApi.HttpSender {
        val jsonCalls = mutableListOf<Pair<String, String>>()
        val uploadCalls = mutableListOf<Triple<String, String, ByteArray>>()

        override fun postJson(url: String, json: String): String {
            jsonCalls += url to json
            return jsonResponse(url, json)
        }

        override fun postMultipart(url: String, fileName: String, mimeType: String, content: ByteArray): String {
            uploadCalls += Triple(url, fileName, content)
            return uploadResponse(url, fileName, content)
        }
    }

    /** k2j 互操作适配：Java 构造器无具名实参，按 PostRequest 声明顺序位置传参。 */
    private fun request(attachments: List<FeedbackApi.AttachmentRef> = emptyList()) = FeedbackApi.PostRequest(
        "RuntimeException: boom",
        "boom\n\nfingerprint: abcd1234",
        2,
        "sql-log",
        null,
        "a@b.c",
        true,
        FeedbackApi.SOURCE_IDE,
        "IntelliJ IDEA 2026.2.1",
        "2026.9.8",
        attachments,
    )

    // ---------- 提交链路（fake sender） ----------

    @Test
    fun `无附件提交_发送JSON契约字段`() {
        val sender = RecordingSender()
        FeedbackApi.sender = sender
        val result = FeedbackApi.submit(FeedbackApi.Submission(request(), null))
        assertTrue(result is FeedbackApi.SubmitResult.Success)
        assertEquals("FR-0042", (result as FeedbackApi.SubmitResult.Success).postNo)
        assertEquals(0, sender.uploadCalls.size)
        assertEquals(1, sender.jsonCalls.size)
        assertEquals(FeedbackApi.POSTS_URL, sender.jsonCalls[0].first)

        val sent: Map<String, Any?> = gson.fromJson(sender.jsonCalls[0].second, object : TypeToken<Map<String, Any?>>() {}.type)
        assertEquals("RuntimeException: boom", sent["title"])
        // Gson Object 目标下数字为 LazilyParsedNumber，统一按 Number 比
        assertEquals(2, (sent["type"] as Number).toInt())
        assertEquals("sql-log", sent["category"])
        assertEquals("ide", sent["source"])
        assertEquals(true, sent["isAnonymous"])
        assertEquals("IntelliJ IDEA 2026.2.1", sent["ideVersion"])
        assertEquals("2026.9.8", sent["pluginVersion"])
        // null 字段序列化省略（Gson 默认）
        assertTrue(!sent.containsKey("submitterName"))
    }

    @Test
    fun `带附件提交_先上传后引用key`() {
        val sender = RecordingSender()
        FeedbackApi.sender = sender
        val stack = FeedbackApi.StackAttachment("stack.txt", "hello stack".toByteArray())
        val result = FeedbackApi.submit(FeedbackApi.Submission(request(), stack))
        assertTrue(result is FeedbackApi.SubmitResult.Success)
        assertEquals(1, sender.uploadCalls.size)
        assertEquals(FeedbackApi.UPLOAD_URL, sender.uploadCalls[0].first)
        assertEquals("stack.txt", sender.uploadCalls[0].second)
        assertEquals("hello stack", String(sender.uploadCalls[0].third))

        val sent: Map<String, Any?> = gson.fromJson(sender.jsonCalls[0].second, object : TypeToken<Map<String, Any?>>() {}.type)
        val attachments = sent["attachments"] as? List<Map<String, Any?>>
        assertEquals(listOf("feed/x.txt"), attachments?.map { it["key"] })
        assertEquals(9L, (attachments?.firstOrNull()?.get("fileSize") as Number).toLong())
    }

    @Test
    fun `上传失败短路_不再发帖`() {
        val sender = RecordingSender(
            uploadResponse = { _, _, _ -> """{"code":415,"message":"不支持的附件类型 .exe"}""" },
        )
        FeedbackApi.sender = sender
        val result = FeedbackApi.submit(
            FeedbackApi.Submission(request(), FeedbackApi.StackAttachment("s.txt", byteArrayOf(1))),
        )
        assertTrue("RES=<${result}>", result is FeedbackApi.SubmitResult.Failure)
        assertTrue("MSG=<${(result as FeedbackApi.SubmitResult.Failure).message}>", result.message.contains("415") || result.message.contains("附件"))
        assertEquals(0, sender.jsonCalls.size)
    }

    @Test
    fun `服务端业务错误透出message`() {
        val sender = RecordingSender(jsonResponse = { _, _ -> """{"code":400,"message":"标题长度需在 5-200 字符之间"}""" })
        FeedbackApi.sender = sender
        val result = FeedbackApi.submit(FeedbackApi.Submission(request(), null))
        assertTrue(result is FeedbackApi.SubmitResult.Failure)
        assertEquals("标题长度需在 5-200 字符之间", (result as FeedbackApi.SubmitResult.Failure).message)
    }

    @Test
    fun `网络异常折入Failure不抛出`() {
        FeedbackApi.sender = object : FeedbackApi.HttpSender {
            override fun postJson(url: String, json: String): String = throw java.io.IOException("connection refused")
            override fun postMultipart(url: String, fileName: String, mimeType: String, content: ByteArray): String = ""
        }
        val result = FeedbackApi.submit(FeedbackApi.Submission(request(), null))
        assertTrue(result is FeedbackApi.SubmitResult.Failure)
        assertTrue((result as FeedbackApi.SubmitResult.Failure).message.contains("connection refused"))
    }

    // ---------- 响应解析（纯函数） ----------

    @Test
    fun `posts响应解析成功`() {
        val result = FeedbackApi.parsePostResponse("""{"code":0,"message":"提交成功","data":{"postNo":"FR-0007"}}""")
        assertEquals("FR-0007", (result as FeedbackApi.SubmitResult.Success).postNo)
    }

    @Test
    fun `posts响应非法JSON归类失败`() {
        val result = FeedbackApi.parsePostResponse("<html>502</html>")
        assertTrue(result is FeedbackApi.SubmitResult.Failure)
    }

    @Test
    fun `upload响应解析成功与失败`() {
        // k2j 互操作适配：parseUploadResponse 以 IllegalStateException 表达失败
        // （master Kotlin 版为 Result<AttachmentRef> 的 isFailure/getOrNull 语义）
        val ok = FeedbackApi.parseUploadResponse(
            """{"code":0,"data":{"key":"feed/2026-09-08/k.txt","fileName":"a.txt","mimeType":"text/plain","fileSize":12}}""",
        )
        assertEquals("feed/2026-09-08/k.txt", ok.key)
        assertEquals(12L, ok.fileSize)

        var failureMessage: String? = null
        try {
            FeedbackApi.parseUploadResponse("""{"code":413,"message":"附件超过 5MB，请压缩后上传"}""")
        } catch (expected: IllegalStateException) {
            failureMessage = expected.message
        }
        assertTrue("应抛 IllegalStateException 表达失败", failureMessage != null)
        assertTrue("MSG=<${failureMessage}>", failureMessage?.contains("5MB") == true)
    }

    // ---------- multipart 字节流（纯函数） ----------

    @Test
    fun `multipart结构含字段名_文件名与闭合边界`() {
        val body = String(
            FeedbackApi.buildMultipartBody("stack.txt", "text/plain", "ABC".toByteArray(), "BOUNDARY1"),
            Charsets.UTF_8,
        )
        assertTrue(body.startsWith("--BOUNDARY1\r\n"))
        assertTrue(body.contains("Content-Disposition: form-data; name=\"file\"; filename=\"stack.txt\"\r\n"))
        assertTrue(body.contains("Content-Type: text/plain\r\n"))
        assertTrue(body.contains("\r\n\r\nABC\r\n"))
        assertTrue(body.endsWith("\r\n--BOUNDARY1--\r\n"))
    }

    // ---------- URL ----------

    @Test
    fun `详情页深链格式`() {
        assertEquals(
            "https://sql-visualizer.jvtools.cn/about/feedback?postNo=FR-0042",
            FeedbackApi.detailUrl("FR-0042"),
        )
    }
}
