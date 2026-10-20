package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「函数体内现场 Regex」源码扫描守卫（T-PerfSweep18 F59/F60＋T-PerfSweep24 F84）。
 *
 * 性能系列多轮的定型修法：热/常用路径函数体内的 `Regex(...)` 字面构造改为
 * object/class 级属性单例——同一 [Regex] 实例跨调用复用（预编译 Pattern 不重建）。
 * 本守卫从**源码结构**钉住该形态：登记文件内每处 `Regex(` 构造必须位于
 * `val NAME ... = Regex(` 属性初始化行上；函数体内重建即红。
 *
 * 「同一 Pattern 实例」断言的口径说明（诚实分级）：实例同一性由**构造位置唯一**保证——
 * 属性只在 object/class 初始化时求值一次，函数体内不再有构造点即每次调用取同一实例。
 * 黑盒侧无法在不改签名注入的前提下截获函数内部使用的实例，故行为面由各件既有
 * 行为测试承载（ExplainIndexAdvisorTest / PaginationSimTest / SqlPreviewService* 族），
 * 本守卫承担红绿判别（回退提常量改动 → 函数体内 Regex 复现 → 红）。
 *
 * 新文件收录：把相对路径加进 [GUARDED_FILES] 即可（无豁免语法——要豁免就别收）。
 */
class InFunctionRegexSourceScanTest {

    /** 受守卫文件（相对仓根，`/` 分隔）。当前收录＝F59/F60/F84 三件触及的文件。 */
    private val guardedFiles = listOf(
        "src/main/kotlin/cn/song/mybatisplus/visualizer/util/ExplainIndexAdvisor.kt",
        "src/main/kotlin/cn/song/mybatisplus/visualizer/sql/interceptor/PaginationSimulator.kt",
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.kt",
    )

    /**
     * 属性初始化行形态：`[private|internal|public|protected] val NAME [: Regex]? = Regex(`。
     * 可见性修饰符是判别关键——Kotlin 局部变量不可带可见性修饰符（编译错），故带修饰符的
     * `val X = Regex(` 必是属性；裸 `val x = Regex(`（函数体内局部）必不匹配。
     */
    private val hoistedPropertyLine = Regex(
        """^\s*(?:private|internal|public|protected)\s+(?:@JvmStatic\s+)?val\s+\w+\s*(?::\s*Regex\s*\??)?\s*=\s*Regex\(""",
    )

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: " + System.getProperty("user.dir"))
    }

    /** 剥注释（口径同 BannedApiSourceScanTest：字符串里的 // 可能被截断——只可能漏报不误报）。 */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `受守卫文件内无函数体现场Regex构造`() {
        val violations = mutableListOf<String>()
        for (rel in guardedFiles) {
            val file = File(repoRoot(), rel)
            assertTrue("受守卫文件必须存在：$rel", file.isFile)
            val stripped = stripComments(file.readText())
            stripped.lineSequence().forEachIndexed { idx, line ->
                if (line.contains("Regex(") && !hoistedPropertyLine.containsMatchIn(line)) {
                    violations += "$rel:${idx + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            "函数体内现场 Regex 构造回潮（须提 object/class 级属性单例）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /** 自证：守卫的行形态判定器本身能区分「提常量属性行」与「函数体行」。 */
    @Test
    fun `判定器自证_属性行放行_函数体行报红`() {
        assertTrue(hoistedPropertyLine.containsMatchIn("    private val SEQ_SCAN_ON = Regex(\"\"\"Seq Scan on (\\w+)\"\"\")"))
        assertTrue(hoistedPropertyLine.containsMatchIn("    internal val LIMIT_CLAUSE = Regex(\"(?i)\\\\bLIMIT\\\\b\")"))
        assertTrue(hoistedPropertyLine.containsMatchIn("    private val X: Regex = Regex(\"a\")"))
        // 局部 val（无可见性修饰符）＝函数体内形态，必红
        assertEquals(false, hoistedPropertyLine.containsMatchIn("    val m = Regex(\"a\").find(cell)"))
        assertEquals(false, hoistedPropertyLine.containsMatchIn("        val genericPattern = Regex(\"x\")"))
        assertEquals(false, hoistedPropertyLine.containsMatchIn("        compactSql = sql.replace(Regex(\"\\\\s+\"), \" \").trim()"))
    }
}
