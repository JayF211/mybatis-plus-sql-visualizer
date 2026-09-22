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

    @Test
    fun `生产链位置提取调用都传了被执行文本长度`() {
        val root = repoRoot()
        val offenders = mutableListOf<String>()
        var checked = 0
        File(root, "src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val rel = file.relativeTo(root).path.replace('\\', '/')
                if (rel in exemptFiles) return@forEach
                val text = file.readText()
                for (m in callSite.findAll(text)) {
                    val args = readArguments(text, m.range.last + 1) ?: run {
                        offenders += "$rel:${lineOf(text, m.range.first)} 调用实参无法配对（形态变更，请更新守卫）"
                        continue
                    }
                    checked++
                    if (!args.contains(".length")) {
                        offenders += "$rel:${lineOf(text, m.range.first)} ${m.groupValues[1]} 未传被执行文本长度" +
                            "（实参：${args.trim().replace('\n', ' ').take(120)}）"
                    }
                }
            }
        assertTrue("守卫未扫到任何调用点——扫描规则已失效（空绿）：$checked", checked >= 4)
        assertTrue(
            "生产链位置提取必须传被执行文本长度（越界钳制的生效前提），违规点：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
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
