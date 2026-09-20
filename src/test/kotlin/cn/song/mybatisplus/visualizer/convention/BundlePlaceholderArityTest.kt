package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回滚告警「键 ↔ 实参 ↔ 占位符」三向一致性源码扫描守卫（T-RollbackI18n，2026-09-20）。
 *
 * **为什么需要它**：`RollbackWarning` 是**数据驱动**的（`messageKey` + `messageArgs` 两个字段分开
 * 传递），UI 层渲染时才 `MpSqlVisualizerBundle.message(key, *args)`。于是「文案有几个占位符」与
 * 「代码传几个实参」是**两处独立编辑**，编译期与单测都拦不住：
 *  - 实参给少了 → `MessageFormat` 把 `{1}` 原样留在弹窗里（用户看到「上限 {1}」）；
 *  - 键名写错/漏加 → 弹窗显示裸键名（`!rollback.xxx!`）。
 * 两者都是「编译通过、headless 全绿、只有真机弹窗才炸」的形态——而既有单测普遍只断言
 * `warnings.any { it.messageKey == "…" }`（**只钉键名，不看渲染结果**），恰好从缺陷旁边走过去：
 * 2026-09-20 验收期就靠人工读代码发现了 P8（`snapshotRowLimit` 少传一个实参），
 * 随后本扫描又查出同类的 `rollback.snapshot.columnMismatch` **两个 bundle 都没有这个键**
 * （既有单测 `RollbackSqlGeneratorTest:310` 断言键名通过，故从未暴露）。
 *
 * **规则**（只覆盖 `RollbackWarning` 站点，共 30 处）：
 *  1. 字面键必须在中英两个 bundle 里都存在（动态拼接键 `rollback.unable.$kind` 跳过）；
 *  2. 实参个数 **不得少于**占位符个数（给多了无害——`MessageFormat` 忽略多余实参，故不报）；
 *  3. 中英 bundle 的 `rollback.*` 占位符集合一致（参数按位置填充，错位即串文案）。
 *
 * 不覆盖一般 `MpSqlVisualizerBundle.message(key, …)` 调用点：那里实参是直接 varargs（常有循环/条件
 * 拼接），静态数不准、误报率高；告警列表是「键 + 实参列表」的纯数据形态，才适合静态核对。
 *
 * 扫描前剥注释（与 [BannedApiSourceScanTest] 同口径：注释里常留着事故现场的旧写法，不剥会误报）；
 * 字符串字面量里的 `//` 会被连带截断，只可能漏报不会误报。
 */
class BundlePlaceholderArityTest {

    private val enPath = "src/main/resources/messages/MpSqlVisualizerBundle.properties"
    private val zhPath = "src/main/resources/messages/MpSqlVisualizerBundle_zh.properties"

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** 逐字符等长涂白注释（保下标对齐，故可拿 masked 的下标去切原文）。 */
    private fun maskNonCode(text: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(text) { " ".repeat(it.value.length) }
        return Regex("//[^\n]*").replace(noBlock) { " ".repeat(it.value.length) }
    }

    private fun loadBundle(relative: String): Map<String, String> =
        File(repoRoot(), relative).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=') }

    /** 从 [open]（`(` 的下标）起配平括号，返回配对 `)` 的下标；不配平返回 -1。 */
    private fun matchParen(s: String, open: Int): Int {
        var depth = 0
        for (i in open until s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** [inner] 的顶层逗号数（跳过字符串字面量与嵌套括号）。 */
    private fun topLevelCommas(inner: String): Int {
        var depth = 0
        var count = 0
        var inString = false
        for (ch in inner) {
            if (inString) {
                if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) count++
            }
        }
        return count
    }

    private fun placeholders(value: String): Set<Int> =
        Regex("\\{(\\d+)}").findAll(value).map { it.groupValues[1].toInt() }.toSet()

    /** 一处 `RollbackWarning` 站点：[file] 仅用于报错定位，[argCount] 为 `listOf(...)` 的元素个数。 */
    private data class Site(val file: String, val key: String, val argCount: Int)

    private fun warningSites(): List<Site> {
        val out = mutableListOf<Site>()
        File(repoRoot(), "src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val raw = file.readText()
                val masked = maskNonCode(raw)
                Regex("RollbackWarning\\(").findAll(masked).forEach { m ->
                    val open = m.range.last
                    val close = matchParen(masked, open)
                    if (close < 0) return@forEach
                    // masked 与 raw 等长且下标对齐 → 用 masked 的下标切 raw 取字面键
                    val inner = raw.substring(open + 1, close)
                    val key = Regex("\"([^\"]+)\"").find(inner)?.groupValues?.get(1) ?: return@forEach
                    if ('$' in key) return@forEach // 动态拼接键（rollback.unable.$kind）不在静态核对范围
                    val listIdx = inner.indexOf("listOf(")
                    val argCount = if (listIdx < 0) {
                        0
                    } else {
                        val listOpen = listIdx + "listOf".length
                        val listClose = matchParen(inner, listOpen)
                        if (listClose < 0) 0 else topLevelCommas(inner.substring(listOpen + 1, listClose)) + 1
                    }
                    out += Site(file.name, key, argCount)
                }
            }
        return out
    }

    @Test
    fun `扫描自检_站点数非零`() {
        val sites = warningSites()
        assertTrue(
            "扫描器应命中全部 RollbackWarning 站点（命中 ${sites.size} 处，远低于预期 = 扫描器失效，本类其余规则会假绿）",
            sites.size >= 20,
        )
    }

    @Test
    fun `告警键必须存在于中英两个bundle`() {
        val en = loadBundle(enPath)
        val zh = loadBundle(zhPath)
        val missing = warningSites()
            .map { it.key }
            .distinct()
            .filter { it !in en || it !in zh }
            .sorted()
        assertTrue("告警键缺失（真机弹窗会显示裸键名）: $missing", missing.isEmpty())
    }

    @Test
    fun `实参个数不得少于占位符个数`() {
        val en = loadBundle(enPath)
        val bad = warningSites().mapNotNull { site ->
            val value = en[site.key] ?: return@mapNotNull null // 键缺失由上一条规则报
            val need = (placeholders(value).maxOrNull() ?: -1) + 1
            if (site.argCount < need) "${site.file} :: ${site.key} args=${site.argCount} need=$need" else null
        }
        assertTrue("实参不足会把未回填的 {n} 字面量留在弹窗里: $bad", bad.isEmpty())
    }

    @Test
    fun `中英bundle的rollback占位符集合一致`() {
        val en = loadBundle(enPath)
        val zh = loadBundle(zhPath)
        val diff = en.keys
            .filter { it.startsWith("rollback.") && zh.containsKey(it) }
            .filter { placeholders(en.getValue(it)) != placeholders(zh.getValue(it)) }
            .sorted()
        assertTrue("中英占位符不一致（参数按位置填充，错位即串文案）: $diff", diff.isEmpty())
    }
}
