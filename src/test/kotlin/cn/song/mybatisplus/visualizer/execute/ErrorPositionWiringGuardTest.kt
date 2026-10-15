package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-ErrorLocateUsability T-ELU-03（O-49）接线守卫：**生产链的每个位置提取调用都必须传被执行文本长度**。
 *
 * 背景（口径修正 3）：越界钳制早已实现，但四处投递点（`DataSourceSelector` 三处 catch +
 * `SqlVisualizerExplainTool` 一处）**全都不传** `sqlMaxLength` → 钳制分支只在单测生效。
 * 这类「实现有、接线漏」的缺口单测照不出来（单测直接调函数、必然传参），故用源码扫描守卫钉住：
 * 生产代码里任何 `ErrorPositionExtractor.buildInfo(...)` / `.extractPosition(...)` 调用，
 * 实参里必须出现长度表达式（`.length`）。
 *
 * 与 [ErrorPositionExtractorTest] 的分工：那边验**算法**（含钳制语义），这边验**接线**
 * （有没有把长度传进来）。二者缺一都会让钳制在生产路径失效。
 *
 * 豁免方式：确有不需要钳制的调用时，把该文件/调用加进 [EXEMPT_FILES] 并写明理由——
 * 不要放宽本规则（放宽等于把「接线漏」重新变成静默缺陷）。
 */
class ErrorPositionWiringGuardTest {

    /** 豁免文件（相对仓库根的路径，正斜杠）：目前为空——生产链四个调用点都应钳制。 */
    private val exemptFiles = emptySet<String>()

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    private val callSite = Regex("""ErrorPositionExtractor\.(buildInfo|extractPosition)\s*\(""")

    /**
     * T-ELU-01 收口（`deliverError`）的调用点。
     *
     * 2026-09-23 真机走查恢复了被合并丢掉的 `deliverError` 后，生产链的**六个**错误投递点
     * （提交链三处 + 控制台历史三处）全部改走它，`buildInfo` 的直接调用在生产代码里只剩
     * `deliverError` 内部一处——扫描面若仍只认「提取调用」，这条守卫就退化成「只看一处」。
     * 故把投递调用一并纳入：**每个投递点都必须把被执行文本长度带进 `deliverError`**。
     */
    private val deliveryCallSite = Regex("""(?<![\w.])deliverError\s*\(""")

    /** 富通道转发点：`executeOnConsole(` 的每个调用都必须带 `onErrorRich`。 */
    private val forwardCallSite = Regex("""(?<![\w.])executeOnConsole\s*\(""")

    @Test
    fun `生产链位置提取调用都传了被执行文本长度`() {
        val root = repoRoot()
        val offenders = mutableListOf<String>()
        var checked = 0
        var deliveries = 0
        var forwards = 0
        File(root, "src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val rel = file.relativeTo(root).path.replace('\\', '/')
                if (rel in exemptFiles) return@forEach
                val text = file.readText()
                for (m in callSite.findAll(text)) {
                    // 注释里的**提及**不是调用点（判定内联而非独立 Boolean 函数：后者会被
                    // GuardSelfTestCoverageMetaGuardTest 要求逐条登记）
                    val cs1 = text.lastIndexOf('\n', m.range.first - 1).let { if (it < 0) 0 else it + 1 }
                    val pre1 = text.substring(cs1, m.range.first)
                    if (pre1.contains("//") || pre1.trimStart().startsWith("*") || pre1.trimStart().startsWith("/*")) continue
                    val args = readArguments(text, m.range.last + 1) ?: run {
                        offenders += "$rel:${lineOf(text, m.range.first)} 调用实参无法配对（形态变更，请更新守卫）"
                        continue
                    }
                    checked++
                    // 允许两种形态：① 直接给长度表达式（`.length`）；② `deliverError` 内部的**转发**——
                    // 它把收到的 `sqlMaxLength` 原样交给 `buildInfo`，实际取值由投递点保证，而投递点
                    // 由下面的 deliveryCallSite 扫描（≥6 处、必须含 `.length`）逐个钉住。
                    if (!args.contains(".length") && !args.contains("sqlMaxLength")) {
                        offenders += "$rel:${lineOf(text, m.range.first)} ${m.groupValues[1]} 未传被执行文本长度" +
                            "（实参：${args.trim().replace('\n', ' ').take(120)}）"
                    }
                }
                for (m in deliveryCallSite.findAll(text)) {
                    val cs2 = text.lastIndexOf('\n', m.range.first - 1).let { if (it < 0) 0 else it + 1 }
                    val pre2 = text.substring(cs2, m.range.first)
                    if (pre2.contains("//") || pre2.trimStart().startsWith("*") || pre2.trimStart().startsWith("/*")) continue
                    // 定义处 `internal fun deliverError(` 不是调用点
                    if (text.substring(0, m.range.first).trimEnd().endsWith("fun")) continue
                    val args = readArguments(text, m.range.last + 1) ?: run {
                        offenders += "$rel:${lineOf(text, m.range.first)} deliverError 实参无法配对（形态变更，请更新守卫）"
                        continue
                    }
                    deliveries++
                    if (!args.contains(".length")) {
                        offenders += "$rel:${lineOf(text, m.range.first)} deliverError 未传被执行文本长度" +
                            "（实参：${args.trim().replace('\n', ' ').take(120)}）"
                    }
                }
                // 富通道的**转发点**：每个 `executeOnConsole(` 调用都必须把 `onErrorRich` 带下去。
                // 2026-09-23 真机走查实证：`prepareInConsole` 的 `executeAfterPrepare` 分支漏传它
                // （默认 null）⇒ 该路径上 `deliverError` 只能回退旧通道、定位按钮永不出现，
                // 而所有单测与既有守卫全绿（「实现有、接线漏」）。
                for (m in forwardCallSite.findAll(text)) {
                    val cs3 = text.lastIndexOf('\n', m.range.first - 1).let { if (it < 0) 0 else it + 1 }
                    val pre3 = text.substring(cs3, m.range.first)
                    if (pre3.contains("//") || pre3.trimStart().startsWith("*") || pre3.trimStart().startsWith("/*")) continue
                    if (text.substring(0, m.range.first).trimEnd().endsWith("fun")) continue
                    val args = readArguments(text, m.range.last + 1) ?: run {
                        offenders += "$rel:${lineOf(text, m.range.first)} executeOnConsole 实参无法配对（形态变更，请更新守卫）"
                        continue
                    }
                    forwards++
                    if (!args.contains("onErrorRich")) {
                        offenders += "$rel:${lineOf(text, m.range.first)} executeOnConsole 未转发 onErrorRich" +
                            "——富通道在此断链，定位按钮永不出现"
                    }
                }
            }
        assertTrue("守卫未扫到任何位置提取调用点——扫描规则已失效（空绿）：$checked", checked >= 1)
        assertTrue(
            "守卫未扫到任何 executeOnConsole 转发点——扫描规则已失效（空绿）：$forwards（期望 ≥3）",
            forwards >= 3,
        )
        assertTrue(
            "守卫未扫到任何错误投递收口调用点——扫描规则已失效（空绿）：$deliveries（期望 ≥6：提交链三处 + 控制台历史三处）",
            deliveries >= 6,
        )
        assertTrue(
            "生产链位置提取必须传被执行文本长度（越界钳制的生效前提），违规点：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `三个 UI 入口都消费富错误且日志保留旧通道兜底`() {
        val main = File(repoRoot(), "src/main/kotlin/cn/song/mybatisplus/visualizer")
        val preview = File(main, "ui/SqlResultPanel.kt").readText()
        val template = File(main, "actions/SelectedTableSqlTemplateActions.kt").readText()
        val log = File(main, "log/LogToSqlPanel.kt").readText()
        assertTrue(preview.contains("showError(cn.song.mybatisplus.visualizer.execute.ExecutionErrorFormatter.format(info))"))
        assertTrue(template.contains("onErrorRich = { info -> notifySqlTemplateError(project, info, locate) }"))
        assertTrue(template.contains("ExecutionErrorFormatter.format(info)"))
        assertTrue(log.contains("onErrorRich = { info -> showExecutionErrorRich(info) }"))
        assertTrue(log.contains("onError = { errorMsg -> showExecutionError(errorMsg) }"))
        assertTrue(log.contains("executionErrorPanel.showErrorRich(info, null)"))
        val results = File(main, "execute/DataSourceSelectorResults.kt").readText()
        assertTrue(results.contains("rich(ErrorPositionExtractor.buildInfo(cause, message, sqlMaxLength))"))
        assertTrue(results.contains("onError?.invoke(message)"))
        assertTrue(results.contains("refreshResultsComponent(ui, LOG, forceSwapIn = true, generation = generation, onError = onError, onErrorRich = onErrorRich"))
    }

    /** 取调用实参文本（从 `(` 之后开始做括号配对，跳过字符串字面量）。 */
    private fun readArguments(text: String, start: Int): String? {
        var depth = 0
        var i = start
        var inString: Char? = null
        while (i < text.length) {
            val c = text[i]
            when {
                inString != null -> if (c == inString) inString = null
                c == '"' || c == '\'' -> inString = c
                c == '(' -> depth++
                c == ')' -> {
                    if (depth == 0) return text.substring(start, i)
                    depth--
                }
            }
            i++
        }
        return null
    }

    private fun lineOf(text: String, offset: Int): Int = text.take(offset).count { it == '\n' } + 1
}
