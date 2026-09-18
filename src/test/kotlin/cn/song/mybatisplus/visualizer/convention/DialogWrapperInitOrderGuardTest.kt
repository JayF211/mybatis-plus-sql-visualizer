package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DialogWrapper 子类「createActions 引用后声明属性」初始化顺序守卫（T-CaptureTruth T-CT-07）。
 *
 * 背景（真机实证，非理论风险）：平台的 `DialogWrapper.init()` 会回调 [createActions]，而
 * 243 平台 `com/intellij/openapi/ui/DialogWrapper` 字节码直证 `init()` 方法体内调用 `createActions()`；
 * 子类若把 `init()` 写在 Kotlin `init {}` 块里，则该调用发生在**构造期**。Kotlin 属性初始化按
 * 文本顺序执行——声明在 `init {}` 块之后的属性此刻仍是 null（非空类型不拦，编译期静默通过），
 * 传进动作数组的是 null；平台对 null 动作不抛错（沙盒日志无「确认框异常」为证），而是**静默
 * 不渲染该按钮**。
 *
 * 现场：`EnhancedCaptureConfirmDialog` 的第三动作「关闭增强捕获并不再询问」声明在 `init {}` 之后，
 * 用户真机看不到该按钮，误以为功能没做/做到了别的分支（T-CaptureTruth 立项直接动因）。
 *
 * 本守卫对该类陷阱做**全仓扫描**（不限单个类）：凡 src/main 中 `DialogWrapper(` 子类，
 * 其 `createActions()` 体引用的属性都不得声明在 `init()` 调用之后。
 * 运行期测试无法覆盖本陷阱——平台不保存 createActions 的返回数组（243 字节码核实：无 Action[]
 * 字段，数组直接进 createSouthPanel），构造后反射读到的永远是「已初始化」的字段值，只能源码级把关。
 */
class DialogWrapperInitOrderGuardTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** 剥离 // 行注释与 /* */ 块注释（历史事故常以注释形态留存，不剥会误报）。 */
    private fun stripComments(text: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(text) { m ->
            m.value.replace(Regex("[^\n]"), " ")
        }
        return noBlock.split('\n').joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
    }

    private fun dialogSources(): List<Pair<String, List<String>>> =
        repoRoot().resolve("src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to stripComments(it.readText()).split('\n') }
            .filter { (_, lines) -> lines.any { it.contains("DialogWrapper(") } }
            .toList()

    /** 类级属性声明（4 空格缩进）→ 属性名 to 行号。 */
    private fun propertyDeclLines(lines: List<String>): Map<String, Int> {
        val decls = mutableMapOf<String, Int>()
        lines.forEachIndexed { i, line ->
            val m = Regex("^ {4}(?:private |internal |protected |open |override )*(?:val|var) (\\w+)").find(line)
            if (m != null) decls.putIfAbsent(m.groupValues[1], i)
        }
        return decls
    }

    /** 平台 `init()` 调用行号（`init {}` 块本身不算）。 */
    private fun platformInitCallLine(lines: List<String>): Int? =
        lines.indexOfFirst { Regex("^\\s*init\\(\\)\\s*$").matches(it) }.takeIf { it >= 0 }

    /** `createActions` 覆写体文本（表达式体读到括号配平；块体读到函数收尾）。 */
    private fun createActionsBody(lines: List<String>): Pair<Int, String>? {
        val start = lines.indexOfFirst { it.contains("fun createActions") }
        if (start < 0) return null
        val head = lines[start]
        if (head.contains("= arrayOf(") || head.contains("= listOf(") || head.contains("=")) {
            val sb = StringBuilder()
            var depth = 0
            var i = start
            while (i < lines.size) {
                sb.append(lines[i]).append('\n')
                depth += lines[i].count { it == '(' } - lines[i].count { it == ')' }
                if (i > start && depth <= 0) break
                i++
            }
            return start to sb.toString()
        }
        val sb = StringBuilder()
        var i = start
        while (i < lines.size) {
            sb.append(lines[i]).append('\n')
            if (i > start && Regex("^ {4}\\}").containsMatchIn(lines[i])) break
            i++
        }
        return start to sb.toString()
    }

    @Test
    fun test_createActions不得引用后声明属性() {
        val violations = mutableListOf<String>()
        var scanned = 0
        dialogSources().forEach { (path, lines) ->
            val initLine = platformInitCallLine(lines) ?: return@forEach
            val body = createActionsBody(lines) ?: return@forEach
            scanned++
            val decls = propertyDeclLines(lines)
            decls.forEach { (name, declLine) ->
                if (declLine > initLine && Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(body.second)) {
                    violations += "$path: createActions 引用属性 $name（声明@${declLine + 1}）" +
                        "晚于平台 init() 调用（@${initLine + 1}）——构造期该属性为 null，按钮静默不渲染"
                }
            }
        }
        assertTrue("应至少扫描到一个 DialogWrapper 子类（扫描面失效即为假绿）", scanned >= 1)
        assertTrue("存在 Kotlin 初始化顺序陷阱：\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun test_增强捕获确认框第三动作已就位且早于init块() {
        val (_, lines) = dialogSources().firstOrNull { it.first.endsWith("EnhancedCaptureConfirmDialog.kt") }
            ?: error("未找到 EnhancedCaptureConfirmDialog.kt")
        val initLine = platformInitCallLine(lines) ?: error("未找到平台 init() 调用")
        val declLine = lines.indexOfFirst { it.contains("val disableCaptureAction") }
        assertTrue("disableCaptureAction 应存在（第三动作「关闭增强捕获并不再询问」）", declLine >= 0)
        assertTrue(
            "disableCaptureAction 必须声明在 init() 调用之前（当前 声明@${declLine + 1} vs init()@${initLine + 1}）",
            declLine < initLine,
        )
        val body = createActionsBody(lines) ?: error("未找到 createActions")
        assertTrue(
            "createActions 应包含 disableCaptureAction（三动作：同意/关闭增强/取消）",
            body.second.contains("disableCaptureAction"),
        )
    }
}
