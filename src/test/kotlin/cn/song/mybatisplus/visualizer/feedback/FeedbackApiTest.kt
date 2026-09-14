package cn.song.mybatisplus.visualizer.feedback

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-ErrReport-01：需求池 API 客户端单测——JSON 契约 / multipart 字节结构 / 响应解析 /
 * fake sender 全链路。网络实现（HttpRequestsSender）按 UpdateApi 惯例不在单测覆盖。
 *
 * T-FeedbackErr：补「失败原因呈现」（describeHttpFailure/serverMessageOf）与「附件降级」
 * （不再连坐整帖）两组契约。
 */
class FeedbackApiTest {

    private val gson = Gson()

    @After
    fun tearDown() {
        FeedbackApi.sender = HttpRequestsSender
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

    private fun request(attachments: List<FeedbackApi.AttachmentRef> = emptyList()) = FeedbackApi.PostRequest(
        title = "RuntimeException: boom",
        description = "boom\n\nfingerprint: abcd1234",
        type = 2,
        category = "sql-log",
        submitterName = null,
        submitterEmail = "a@b.c",
        isAnonymous = true,
        source = FeedbackApi.SOURCE_IDE,
        ideVersion = "IntelliJ IDEA 2026.2.1",
        pluginVersion = "2026.9.8",
        attachments = attachments,
    )

    // ---------- 提交链路（fake sender） ----------

    @Test
    fun `无附件提交_发送JSON契约字段`() {
        val sender = RecordingSender()
        FeedbackApi.sender = sender
        val outcome = FeedbackApi.submit(FeedbackApi.Submission(request(), stackAttachment = null))
        val result = outcome.result
        assertTrue(result is FeedbackApi.SubmitResult.Success)
        assertEquals("FR-0042", (result as FeedbackApi.SubmitResult.Success).postNo)
        assertNull("无附件时不应有降级提示", outcome.warning)
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
        val outcome = FeedbackApi.submit(FeedbackApi.Submission(request(), stackAttachment = stack))
        assertTrue(outcome.result is FeedbackApi.SubmitResult.Success)
        assertNull(outcome.warning)
        assertEquals(1, sender.uploadCalls.size)
        assertEquals(FeedbackApi.UPLOAD_URL, sender.uploadCalls[0].first)
        assertEquals("stack.txt", sender.uploadCalls[0].second)
        assertEquals("hello stack", String(sender.uploadCalls[0].third))

        val sent: Map<String, Any?> = gson.fromJson(sender.jsonCalls[0].second, object : TypeToken<Map<String, Any?>>() {}.type)
        val attachments = sent["attachments"] as? List<Map<String, Any?>>
        assertEquals(listOf("feed/x.txt"), attachments?.map { it["key"] })
        assertEquals(9L, (attachments?.firstOrNull()?.get("fileSize") as Number).toLong())
    }

    /**
     * T-FeedbackErr 降级契约：附件被服务端拒绝（415）时不再连坐整帖——
     * 帖子照发（无附件）、结果成功、warning 说明原因，用户填的标题描述不丢。
     */
    @Test
    fun `附件被拒_降级为无附件发帖并带warning`() {
        val sender = RecordingSender(
            uploadResponse = { _, _, _ -> """{"code":415,"message":"不支持的附件类型 .exe"}""" },
        )
        FeedbackApi.sender = sender
        val outcome = FeedbackApi.submit(
            FeedbackApi.Submission(request(), FeedbackApi.StackAttachment("s.txt", byteArrayOf(1))),
        )
        assertTrue("RES=<${outcome.result}>", outcome.result is FeedbackApi.SubmitResult.Success)
        assertEquals("不支持的附件类型 .exe", outcome.warning)
        assertEquals(1, sender.jsonCalls.size)
        val sent: Map<String, Any?> = gson.fromJson(sender.jsonCalls[0].second, object : TypeToken<Map<String, Any?>>() {}.type)
        assertEquals("降级后请求不带附件", true, (sent["attachments"] as? List<*>)?.isEmpty())
    }

    /** 附件上传抛通道异常（HttpFailure）时同样降级，warning 取服务端文案。 */
    @Test
    fun `附件上传抛HttpFailure_降级且warning取服务端文案`() {
        val sender = RecordingSender(uploadResponse = { _, _, _ ->
            throw HttpFailure(413, """{"code":413,"message":"附件超过 5MB，请压缩后上传"}""")
        })
        FeedbackApi.sender = sender
        val outcome = FeedbackApi.submit(
            FeedbackApi.Submission(request(), FeedbackApi.StackAttachment("s.txt", byteArrayOf(1))),
        )
        assertTrue(outcome.result is FeedbackApi.SubmitResult.Success)
        assertEquals("附件超过 5MB，请压缩后上传", outcome.warning)
    }

    /** T-FeedbackErr 主线：4xx 正文里的服务端文案必须压过通道级 message。 */
    @Test
    fun `服务端4xx正文优先于通道消息`() {
        val sender = RecordingSender(jsonResponse = { _, _ ->
            throw HttpFailure(400, """{"code":400,"message":"标题长度需在 5-200 字符之间"}""")
        })
        FeedbackApi.sender = sender
        val outcome = FeedbackApi.submit(FeedbackApi.Submission(request(), null))
        val failure = outcome.result as FeedbackApi.SubmitResult.Failure
        assertEquals("标题长度需在 5-200 字符之间", failure.message)
    }

    @Test
    fun `服务端业务错误透出message`() {
        val sender = RecordingSender(jsonResponse = { _, _ -> """{"code":400,"message":"标题长度需在 5-200 字符之间"}""" })
        FeedbackApi.sender = sender
        val outcome = FeedbackApi.submit(FeedbackApi.Submission(request(), null))
        val result = outcome.result
        assertTrue(result is FeedbackApi.SubmitResult.Failure)
        assertEquals("标题长度需在 5-200 字符之间", (result as FeedbackApi.SubmitResult.Failure).message)
    }

    @Test
    fun `网络异常折入Failure不抛出`() {
        FeedbackApi.sender = object : FeedbackApi.HttpSender {
            override fun postJson(url: String, json: String): String = throw java.io.IOException("connection refused")
            override fun postMultipart(url: String, fileName: String, mimeType: String, content: ByteArray): String = ""
        }
        val outcome = FeedbackApi.submit(FeedbackApi.Submission(request(), null))
        val result = outcome.result
        assertTrue(result is FeedbackApi.SubmitResult.Failure)
        assertTrue((result as FeedbackApi.SubmitResult.Failure).message.contains("connection refused"))
    }

    // ---------- 失败原因呈现（纯函数，T-FeedbackErr） ----------

    @Test
    fun `失败原因_取服务端正文message`() {
        val error = HttpFailure(400, """{"code":400,"message":"描述至少 10 个字符"}""")
        assertEquals("描述至少 10 个字符", FeedbackApi.describeHttpFailure(error))
    }

    @Test
    fun `失败原因_JSON无message时回退通道消息`() {
        // 服务端 400 但正文是 JSON 对象且无 message（非常规）→ 保留通道原文供排查
        val error = HttpFailure(400, """{"code":400}""")
        val message = FeedbackApi.describeHttpFailure(error)
        assertTrue("MSG=<$message>", message?.contains("Server returned HTTP response code: 400") == true)
    }

    @Test
    fun `失败原因_非HTTP异常走cause链`() {
        val wrapper = RuntimeException("outer", java.io.IOException("connection reset"))
        assertEquals("connection reset", FeedbackApi.describeHttpFailure(wrapper))
        assertEquals(null, FeedbackApi.describeHttpFailure(null))
    }

    @Test
    fun `服务端message提取_非法JSON与缺字段返回null`() {
        assertEquals(null, FeedbackApi.serverMessageOf(null))
        assertEquals(null, FeedbackApi.serverMessageOf("   "))
        assertEquals(null, FeedbackApi.serverMessageOf("not json"))
        assertEquals(null, FeedbackApi.serverMessageOf("""{"code":400}"""))
        assertEquals("boom", FeedbackApi.serverMessageOf("""{"code":400,"message":"boom"}"""))
    }

    // ---------- 非 JSON 响应归类（纯函数，T-FeedbackErr 真机第二轮） ----------

    @Test
    fun `非JSON归类_HTML正文给可读线索`() {
        val hint = FeedbackApi.nonJsonHint("<html><body>blocked by proxy</body></html>")
        assertTrue("HINT=<$hint>", hint!!.contains("非 JSON"))
        assertTrue(hint.contains("blocked by proxy"))
    }

    @Test
    fun `非JSON归类_空正文与纯文本与JSON对象`() {
        assertTrue(FeedbackApi.nonJsonHint(null)!!.contains("空响应"))
        assertTrue(FeedbackApi.nonJsonHint("   ")!!.contains("空响应"))
        val plain = FeedbackApi.nonJsonHint("Internal Server Error")
        assertTrue("PLAIN=<$plain>", plain!!.contains("非 JSON") && plain.contains("Internal Server Error"))
        // 正常 JSON 对象正文 → 无提示
        assertEquals(null, FeedbackApi.nonJsonHint("""{"code":0,"data":{}}"""))
    }

    @Test
    fun `posts解析_HTML正文给归类而非Gson原文`() {
        val result = FeedbackApi.parsePostResponse("<html>challenge page</html>")
        val message = (result as FeedbackApi.SubmitResult.Failure).message
        assertTrue("MSG=<$message>", message.contains("非 JSON"))
        assertTrue("不应透出 Gson 英文原文", !message.contains("BEGIN_OBJECT"))
    }

    @Test
    fun `upload解析_HTML正文给归类`() {
        val bad = FeedbackApi.parseUploadResponse("<html>blocked</html>")
        assertTrue(bad.isFailure)
        assertTrue("MSG=<${bad.exceptionOrNull()?.message}>", bad.exceptionOrNull()?.message?.contains("非 JSON") == true)
    }

    @Test
    fun `通道失败_HTML正文带HTTP状态归类`() {
        val message = FeedbackApi.describeHttpFailure(HttpFailure(403, "<html>error 1010</html>"))
        assertTrue("MSG=<$message>", message!!.contains("非 JSON") && message.contains("HTTP 403"))
        assertTrue(!message.contains("BEGIN_OBJECT"))
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
        val ok = FeedbackApi.parseUploadResponse(
            """{"code":0,"data":{"key":"feed/2026-09-08/k.txt","fileName":"a.txt","mimeType":"text/plain","fileSize":12}}""",
        )
        assertEquals("feed/2026-09-08/k.txt", ok.getOrNull()?.key)
        assertEquals(12L, ok.getOrNull()?.fileSize)

        val bad = FeedbackApi.parseUploadResponse("""{"code":413,"message":"附件超过 5MB，请压缩后上传"}""")
        assertTrue("isFailure=<${bad.isFailure}>", bad.isFailure)
        assertTrue("MSG=<${bad.exceptionOrNull()?.message}>", bad.exceptionOrNull()?.message?.contains("5MB") == true)
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
