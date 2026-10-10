package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * O-64/T-RollbackIdentityParse（D2）接线守卫：「整批直跑」旗标的**每个转发点都必须真的传**。
 *
 * 这类「签名有默认值、调用点静默不传」的缺口单测照不出来（单测直接调函数、必然传参），
 * 沿 [ErrorPositionWiringGuardTest] 同款源码扫描先例（「实现有、接线漏」的既定验法）：
 *  1. `SqlPreviewPanel.executeAdhocSql` 体内的每个 `SqlExecutor.execute(`/`executePrepared(`
 *     调用都必须带 `wholeScriptExec` 实参——回滚弹窗链是 P19 的生产入口；
 *  2. `DataSourceSelector` 里每个 `executeOnConsole(` 调用都必须把旗标传进执行核心
 *     （三条路径：executePrepared / 共享控制台复用 / 全链 prepare 后执行）。
 *
 * 豁免：确有不需要旗标的调用点时，加进 [EXEMPT] 并写明理由——不要放宽本规则。
 */
class IdentityWholeScriptWiringGuardTest {

    /** 豁免调用点（文件名 → 行内容包含片段，均须写明理由）。当前为空。 */
    private val exempt = emptyMap<String, Set<String>>()

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）")
    }

    private fun mainKt(relative: String): String =
        File(repoRoot(), "src/main/kotlin/$relative").readText()

    /** 从 `open` 字符起做括号配对，返回覆盖整个实参列表的片段。 */
    private fun argSpan(text: String, open: Int): String {
        var depth = 0
        var i = open
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        while (i < text.length) {
            val c = text[i]
            val prev = if (i > 0) text[i - 1] else ' '
            when {
                inLineComment -> if (c == '\n') inLineComment = false
                inBlockComment -> if (prev == '*' && c == '/') inBlockComment = false
                inString -> if (c == '"' && prev != '\\') inString = false
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> inLineComment = true
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> inBlockComment = true
                c == '"' -> inString = true
                c == '(' || c == '{' -> depth++
                c == ')' || c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        return text.substring(open)
    }

    /** 函数体内（首个 `{` 起括号配对）的指定调用点名片段清单。 */
    private fun callsWithin(fn: String, fnName: String, callName: String): List<String> {
        val sigStart = fn.indexOf(fnName)
        assertTrue("未找到函数 $fnName", sigStart >= 0)
        val bodyStart = fn.indexOf('{', sigStart)
        val body = argSpanBody(fn, bodyStart)
        val result = mutableListOf<String>()
        var idx = body.indexOf(callName)
        while (idx >= 0) {
            val open = body.indexOf('(', idx)
            result += argSpan(body, open)
            idx = body.indexOf(callName, open)
        }
        return result
    }

    /** 从 `{` 起做花括号配对取函数体（含自身）。 */
    private fun argSpanBody(text: String, open: Int): String {
        var depth = 0
        var i = open
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        while (i < text.length) {
            val c = text[i]
            val prev = if (i > 0) text[i - 1] else ' '
            when {
                inLineComment -> if (c == '\n') inLineComment = false
                inBlockComment -> if (prev == '*' && c == '/') inBlockComment = false
                inString -> if (c == '"' && prev != '\\') inString = false
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> inLineComment = true
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> inBlockComment = true
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        return text.substring(open)
    }

    @Test
    fun `executeAdhocSql体内每个SqlExecutor调用都传wholeScriptExec`() {
        // T-M53-II D2-5：`executeAdhocSql` 随执行链组合式重构搬到同包 SqlPreviewPanelExecution.kt
        // （扩展函数形态），读取面须指向站点真正所在文件，否则 `未找到 executeAdhocSql` 直接红。
        val body = mainKt("cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt")
        val fnStart = body.indexOf("internal fun SqlPreviewPanel.executeAdhocSql(")
        assertTrue("未找到 executeAdhocSql", fnStart >= 0)
        val fn = body.substring(fnStart)
        val offenders = mutableListOf<String>()
        var checked = 0
        for (call in listOf("SqlExecutor.execute(", "SqlExecutor.executePrepared(")) {
            for (span in callsWithin(fn, "fun SqlPreviewPanel.executeAdhocSql(", call)) {
                checked++
                val exempted = exempt["SqlPreviewPanel.kt"]?.any { span.contains(it) } == true
                if (!exempted && !span.contains("wholeScriptExec")) {
                    offenders += span.take(120)
                }
            }
        }
        assertTrue("executeAdhocSql 内应至少有 3 个 SqlExecutor 执行调用（实查 $checked）", checked >= 3)
        assertTrue(
            "以下调用未传 wholeScriptExec（接线漏）: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `DataSourceSelector每个executeOnConsole调用都传wholeScriptExec`() {
        // T-M53-II（2026-10-10）：组合式重构把 `executeOnConsole` 的定义点与调用点分散到三个同包
        // 文件（定义在 *Console.kt；调用点在 *DataSourceSelector.kt / *Console.kt / *Results.kt）。
        // 读取面必须覆盖全集——只读单文件会在某个文件恰好无调用点时静默漏掉转发点（真接线漏照绿）。
        val files = listOf(
            "cn/song/mybatisplus/visualizer/execute/DataSourceSelector.kt",
            "cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsole.kt",
            "cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt",
        )
        val offenders = mutableListOf<String>()
        var checked = 0
        files.forEach { rel ->
            val text = mainKt(rel)
            val name = rel.substringAfterLast('/')
            var idx = text.indexOf("executeOnConsole(")
            while (idx >= 0) {
                val before = text.substring(maxOf(0, idx - 30), idx)
                if (!before.contains("fun ")) { // 定义点（`internal fun DataSourceSelector.executeOnConsole(`）不计
                    val open = text.indexOf('(', idx)
                    val span = argSpan(text, open)
                    checked++
                    val exempted = exempt[name]?.any { span.contains(it) } == true
                    if (!exempted && !span.contains("wholeScriptExec")) {
                        offenders += "$name: " + span.take(120)
                    }
                }
                idx = text.indexOf("executeOnConsole(", idx + 1)
            }
        }
        assertTrue("executeOnConsole 应至少有 3 个调用点（实查 $checked）", checked >= 3)
        assertTrue(
            "以下 executeOnConsole 调用未传 wholeScriptExec（接线漏）: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `executeAdhocSql确实计算了identity识别特征`() {
        val fn = mainKt("cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt")
        val fnStart = fn.indexOf("internal fun SqlPreviewPanel.executeAdhocSql(")
        assertTrue(
            "executeAdhocSql 必须用 IdentityInsertStatements 判定旗标（识别特征=含 SET IDENTITY_INSERT 语句）",
            callsWithin(fn.substring(fnStart), "fun SqlPreviewPanel.executeAdhocSql(", "IdentityInsertStatements.containsStatement(")
                .isNotEmpty(),
        )
    }

    /** 自证语料（守卫灵敏度）：合成源码直接喂进 [callsWithin]，缺旗标必被抓、带旗标不误报。 */
    @Test
    fun 自证_合成源码缺旗标被守卫抓到() {
        val synthetic = """
            internal fun executeAdhocSqlExample() {
                SqlExecutor.execute(
                    proj, sql, null, false,
                    onError = { e -> },
                )
                SqlExecutor.executePrepared(
                    proj,
                    wholeScriptExec = wholeScriptExec,
                )
            }
        """.trimIndent()
        val bad = callsWithin(synthetic, "internal fun executeAdhocSqlExample(", "SqlExecutor.execute(")
        assertTrue("缺旗标调用必须被提取", bad.size == 1)
        assertTrue("缺旗标调用必须判违例", !bad[0].contains("wholeScriptExec"))
        val good = callsWithin(synthetic, "internal fun executeAdhocSqlExample(", "SqlExecutor.executePrepared(")
        assertTrue("带旗标调用必须被提取", good.size == 1)
        assertTrue("带旗标调用不得误报", good[0].contains("wholeScriptExec"))
    }
}
