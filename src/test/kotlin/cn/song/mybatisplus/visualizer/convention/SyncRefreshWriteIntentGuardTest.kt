package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BGT 同步 VFS 刷新不得持有写意向锁；EDT 冷路径的包装必须保留。 */
class SyncRefreshWriteIntentGuardTest : BannedApiScanSupport() {
    internal data class WilRefreshOffender(val path: String, val line: Int)

    internal fun syncRefreshInsideWiraOffenders(sources: List<Pair<String, String>>): List<WilRefreshOffender> {
        val out = ArrayList<WilRefreshOffender>()
        for ((path, source) in sources) {
            val masked = maskNonCode(source)
            val allowed = if (path.replace('\\', '/').endsWith(WHITELIST_FILE)) {
                functionBodyRange(masked, "createTempPsiFile")
            } else null
            var idx = masked.indexOf(WIRA_TOKEN)
            while (idx >= 0) {
                val open = wiraComputeBlockStart(masked, idx)
                if (open != null) {
                    val close = matchingDelimiter(masked, open, '(', ')')
                    if (close > open && masked.substring(open, close + 1).contains(SYNC_REFRESH_TOKEN)
                        && (allowed == null || idx !in allowed)) {
                        out.add(WilRefreshOffender(path, lineOf(masked, idx)))
                    }
                }
                idx = masked.indexOf(WIRA_TOKEN, idx + WIRA_TOKEN.length)
            }
        }
        return out
    }

    /** Java 显式类型参数在方法名前；括号区间同时覆盖块 lambda、表达式 lambda 和 Computable 强转。 */
    internal fun wiraComputeBlockStart(masked: String, tokenStart: Int): Int? {
        var i = tokenStart + WIRA_TOKEN.length
        while (i < masked.length && masked[i].isWhitespace()) i++
        if (i >= masked.length || masked[i] != '.') return null
        i++
        while (i < masked.length && masked[i].isWhitespace()) i++
        if (i < masked.length && masked[i] == '<') {
            val end = matchingDelimiter(masked, i, '<', '>')
            if (end < 0) return null
            i = end + 1
            while (i < masked.length && masked[i].isWhitespace()) i++
        }
        if (!masked.regionMatches(i, "compute", 0, 7)) return null
        i += 7
        while (i < masked.length && masked[i].isWhitespace()) i++
        return if (i < masked.length && masked[i] == '(') i else null
    }

    /** 只接受带 Java 可见性修饰符的方法声明，不能让普通调用后的块扩大白名单范围。 */
    internal fun functionBodyRange(masked: String, functionName: String): IntRange? {
        val declaration = Regex(
            "(?m)^\\h*(?:public|protected|private)\\h+(?:(?:static|final|synchronized)\\h+)*" +
                "[\\w.$<>?,\\[\\] ]+\\h+" + Regex.escape(functionName) + "\\s*\\("
        ).find(masked) ?: return null
        val paramsOpen = declaration.range.last
        val paramsClose = matchingDelimiter(masked, paramsOpen, '(', ')')
        if (paramsClose < 0) return null
        val bodyOpen = masked.indexOf('{', paramsClose + 1)
        if (bodyOpen < 0) return null
        val terminator = masked.indexOf(';', paramsClose + 1)
        if (terminator in (paramsClose + 1) until bodyOpen) return null
        val bodyClose = matchingDelimiter(masked, bodyOpen, '{', '}')
        return if (bodyClose > bodyOpen) bodyOpen..bodyClose else null
    }

    private fun productionSource(): Pair<String, String> =
        mainSources().single { it.first.replace('\\', '/').endsWith(WHITELIST_FILE) }

    @Test
    fun 规则一_BGT预热链不得再现WIRA包装() {
        val source = maskNonCode(productionSource().second)
        val body = functionBodyRange(source, "prewarmExecTempFileInner")
        assertNotNull("Java 预热方法必须被定位，不能空扫描", body)
        assertTrue(WIRA_TOKEN !in source.substring(body!!.first, body.last + 1))
        assertTrue(SYNC_REFRESH_TOKEN in source.substring(body.first, body.last + 1))
    }

    @Test
    fun 规则二_EDT冷路径WIRA包装不得被清理() {
        val source = maskNonCode(productionSource().second)
        val body = functionBodyRange(source, "createTempPsiFile")
        assertNotNull("Java 冷路径必须被定位", body)
        val text = source.substring(body!!.first, body.last + 1)
        assertTrue(WIRA_TOKEN in text)
        assertTrue(SYNC_REFRESH_TOKEN in text)
    }

    @Test
    fun 规则三_全仓同步refresh不得包WIRA() {
        val sources = mainSources()
        assertTrue("扫描面必须包含 Java 生产文件", sources.any { it.first.endsWith(".java") })
        val offenders = syncRefreshInsideWiraOffenders(sources)
        assertTrue("非 EDT 冷路径中发现持写意向锁的同步刷新：$offenders", offenders.isEmpty())
    }

    @Test
    fun 自证_Java泛型强转和表达式lambda均检出() {
        val calls = listOf(
            "WriteIntentReadAction.compute(() -> { return fs.refreshAndFindFileByIoFile(file); });",
            "WriteIntentReadAction.<VirtualFile>compute(() -> fs.refreshAndFindFileByIoFile(file));",
            "WriteIntentReadAction.compute((Computable<VirtualFile>) () -> fs.refreshAndFindFileByIoFile(file));"
        )
        calls.forEach { call ->
            assertEquals(call, 1, syncRefreshInsideWiraOffenders(listOf("fake/Bgt.java" to call)).size)
        }
    }

    @Test
    fun 自证_只豁免冷路径方法体() {
        val source = """
            public class Example {
                public static VirtualFile createTempPsiFile(Project project, String sql) {
                    return WriteIntentReadAction.compute(() -> fs.refreshAndFindFileByIoFile(file));
                }
                public static void prewarmExecTempFileInner(Project project, String sql) {
                    WriteIntentReadAction.compute(() -> fs.refreshAndFindFileByIoFile(file));
                }
            }
        """.trimIndent()
        val offenders = syncRefreshInsideWiraOffenders(listOf(WHITELIST_FILE to source))
        assertEquals(1, offenders.size)
        assertEquals(6, offenders.single().line)
        assertEquals(2, syncRefreshInsideWiraOffenders(listOf("fake/Other.java" to source)).size)
    }

    @Test
    fun 自证_裸调和注释不报() {
        val source = """
            import com.intellij.openapi.application.WriteIntentReadAction;
            public class Example {
                public void prewarm() {
                    // WriteIntentReadAction.compute(() -> fs.refreshAndFindFileByIoFile(file));
                    fs.refreshAndFindFileByIoFile(file);
                }
            }
        """.trimIndent()
        assertTrue(syncRefreshInsideWiraOffenders(listOf("fake/Bare.java" to source)).isEmpty())
    }

    @Test
    fun 反向_真实预热站点还原WIRA必须变红() {
        val (path, original) = productionSource()
        val body = functionBodyRange(maskNonCode(original), "prewarmExecTempFileInner")!!
        val bodyText = original.substring(body.first, body.last + 1)
        val bare = "vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);"
        assertTrue("反向探针必须命中真实修改点", bodyText.contains(bare))
        val broken = original.replaceRange(body, bodyText.replace(bare,
            "vf = WriteIntentReadAction.compute((com.intellij.openapi.util.Computable<VirtualFile>) () -> " +
                "LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile));"))
        assertTrue(syncRefreshInsideWiraOffenders(listOf(path to original)).isEmpty())
        assertEquals("恢复真实旧形态后必须恰好报一个后台站点", 1,
            syncRefreshInsideWiraOffenders(listOf(path to broken)).size)
    }

    @Test
    fun 预热预算必须取消并清理在途状态() {
        val source = mainSources().single { it.first.replace('\\', '/').endsWith("execute/DataSourceSelector.java") }.second
        val masked = maskNonCode(source)
        val body = functionBodyRange(masked, "prewarmExecTempFile")!!
        val text = masked.substring(body.first, body.last + 1)
        assertTrue(text.contains("prewarmFuture.get(EXEC_PREWARM_BUDGET_MS"))
        assertTrue(text.contains("prewarmFuture.cancel(true)"))
        assertTrue(text.contains("Thread.currentThread().interrupt()"))
        assertTrue(text.contains("FatalErrors.isFatal(cause)"))
        assertTrue(text.contains("finally"))
        assertTrue(text.contains("execPrewarmInFlight.set(false)"))
    }

    private companion object {
        private const val WIRA_TOKEN = "WriteIntentReadAction"
        private const val SYNC_REFRESH_TOKEN = "refreshAndFindFileBy"
        private const val WHITELIST_FILE = "execute/DataSourceSelectorResultsKt.java"
    }
}
