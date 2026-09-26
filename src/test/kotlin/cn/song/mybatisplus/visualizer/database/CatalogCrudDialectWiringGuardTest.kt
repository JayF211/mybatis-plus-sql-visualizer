package cn.song.mybatisplus.visualizer.database

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 目录来源 CRUD 生成的**方言接线守卫**（2026-09-23 真机走查发现并修）。
 *
 * 背景：`DbTableSpecAdapter.toSpec` 原写 `currentDialect()`（**不传 project**），而
 * `DialectRegistry.effective(null)` 在 auto 分支里 `project ?: return byName(manualName)`
 * 直接落到全局设置 → `PluginSettings.DEFAULT_DIALECT = "MySQL"`：**无论表属于哪个库，一律按
 * MySQL 渲染**。真机实证（本机 SQL Server 2022 容器，右键 `dbo.[user]` → 生成 CRUD SQL）：
 * 得到 `INSERT INTO `user` (…)`（反引号），该形态在 SQL Server 上直接
 * `Msg 102, Level 15 … Incorrect syntax near '`'`；且因方言不是 SqlServerDialect，
 * 勾选自增列时也不会过 `renderInsertWrapper`（O-52 的 CRUD 侧判据因此在真机上不成立）。
 *
 * 这类「实现有、接线漏」的缺口单测照不出来——单测直接构造 `CrudTableSpec` 并自己传 dialect，
 * 必然走对分支。故用源码扫描守卫钉住接线本身。
 *
 * **反向验证**：把该行改回 `currentDialect()` → 本用例必红。
 * **守卫边界**：只扫这一个文件里的 `currentDialect(...)` 调用形态，不判断实参语义
 * （传 `null` 字面量也能骗过它）；语义正确性靠真机走查与 `DialectRegistryTest` 覆盖。
 */
class CatalogCrudDialectWiringGuardTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile
        }
        error("未定位到仓库根（src/main/java 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    private val callSite = Regex("""currentDialect\s*\(""")

    @Test
    fun `目录来源适配器解析方言时必须传项目上下文`() {
        val root = repoRoot()
        val file = File(root, "src/main/java/cn/song/mybatisplus/visualizer/database/DbTableSpecAdapter.java")
        assertTrue("守卫目标文件不存在（路径已变，扫描规则失效）：${file.path}", file.isFile)
        val text = file.readText()
        val offenders = mutableListOf<String>()
        var checked = 0
        for (m in callSite.findAll(text)) {
            // 跳过注释里的**提及**（本文件的 KDoc 与行内注释都会写 `currentDialect()` 这个形态，
            // 第一版守卫因此在「已修好」的树上误报——注释不是调用点）。判定内联而非独立 Boolean 函数：
            // 后者会被 GuardSelfTestCoverageMetaGuardTest 要求逐条登记。
            val cs1 = text.lastIndexOf('\n', m.range.first - 1).let { if (it < 0) 0 else it + 1 }
            val pre1 = text.substring(cs1, m.range.first)
            if (pre1.contains("//") || pre1.trimStart().startsWith("*") || pre1.trimStart().startsWith("/*")) continue
            val args = readArguments(text, m.range.last + 1)
            checked++
            if (args == null || args.isBlank()) {
                offenders += "第 ${lineOf(text, m.range.first)} 行：`currentDialect()` 未传项目——" +
                    "会落到全局默认方言（PluginSettings.DEFAULT_DIALECT=MySQL），与表所属库无关"
            }
        }
        assertTrue("守卫未扫到任何 currentDialect 调用点——扫描规则已失效（空绿）：$checked", checked >= 1)
        assertTrue(
            "目录来源 CRUD 必须按表所属项目解析方言，违规点：\n" + offenders.joinToString("\n"),
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
