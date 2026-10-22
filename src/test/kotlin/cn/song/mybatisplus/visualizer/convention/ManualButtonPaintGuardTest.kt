package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按钮视觉样式的**形态级**守卫（UI-043 立项，2026-10-21）。
 *
 * ## 陷阱形态
 *
 * 手工给 `JButton` / `AbstractButton` 涂底色（`isOpaque = true` + `background = …`）来「做一个蓝色
 * 主按钮」。功能完全正常——点击、回调、剪贴板全对——但平台（Darcula / 新 UI）的按钮边框是**内缩
 * 4px 的圆角**（`DarculaButtonPainter.getOffset()` 字节码直接返回常量 4），而 `isOpaque = true` 让
 * `paintComponent` 按**整个矩形**填底色，底色必然溢出到圆角之外，形成一圈方角蓝边。
 *
 * 正确形态是交给 LAF：打 `JButton.styleDefault`（= `DarculaButtonUI.DEFAULT_STYLE_KEY`）client
 * property，让 `DarculaButtonPainter.paintBorder` 走原生 default 样式（平台自身的 ButtonPanel、
 * OnboardingDialogButtons 都这么打标）。
 *
 * ## 为什么必须源码级把关
 *
 * headless 测试拿不到像素，能断言的只有「用了哪个 API 表达样式」。UI-043 当时的用例全部只断言
 * 「点下去发生什么」，与「绘制来源」正交，因此缺陷存在时依然全绿。
 *
 * ## 判据
 *
 * 违规 = 某处对 `JButton`/`AbstractButton` 类型的接收者施加 `apply {}` / `also {}` 块，块内出现
 * `isOpaque = true` 或 `background = <非平台命名色>`。
 *
 * 两条豁免（是判据本身，不是白名单）：
 * 1. **命名色**（`JBColor.namedColor(...)` / `UIUtil.get*` / `JBUI.CurrentTheme.*`）：颜色来源是平台键，
 *    换主题会自动跟随——问题只在「自己铺满矩形」这一动作与自选色值，不在用平台色。
 *    但 `isOpaque = true` **仍然违规**：铺满矩形这个动作本身就是溢出根因，与颜色来源无关。
 * 2. **列表/表格渲染器**（`LogEntryRendering` 一类）：它们实现 `ListCellRenderer` /
 *    `TableCellRenderer`，按平台渲染器约定自行填底色是标准做法（平台自己在 `DefaultListCellRenderer`
 *    里也这么干），且不参与按钮 LAF 的圆角绘制。
 */
class ManualButtonPaintGuardTest {

    private fun repoRoot(): java.io.File {
        var dir = java.io.File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (java.io.File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    private fun mainSources(): List<Pair<String, String>> =
        repoRoot().resolve("src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to it.readText() }
            .toList()

    /** 剥离注释与字符串字面量（注释里的历史事故形态与字面量里的 `=` 都会误报）。 */
    private fun maskNonCode(source: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(source) { m ->
            m.value.replace(Regex("[^\n]"), " ")
        }
        val noLine = noBlock.split('\n').joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
        return noLine
            .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
            .replace(Regex("\"(?:\\\\.|[^\"\\\\\n])*\""), "\"\"")
    }

    /** 命中：文件 + 行号 + 形态 + 该块首行。 */
    internal data class Hit(val file: String, val line: Int, val form: String, val excerpt: String)

    internal data class ScanResult(
        val hits: List<Hit>,
        /** 扫到的按钮 apply 块总数（扫描面保鲜用）。 */
        val buttonBlocks: Int,
        /** 走过豁免的站点（灵敏度自证用）。 */
        val exempted: List<String>,
    )

    private fun scan(sources: List<Pair<String, String>>): ScanResult {
        val hits = mutableListOf<Hit>()
        val exempted = mutableListOf<String>()
        var blocks = 0
        sources.forEach { (file, raw) ->
            val masked = maskNonCode(raw)
            val lines = masked.split('\n')

            fun collectBlock(from: Int): String {
                var depth = 0
                var started = false
                val body = StringBuilder()
                var j = from
                while (j < lines.size) {
                    val l = lines[j]
                    body.append(l).append('\n')
                    l.forEach { c ->
                        if (c == '{') { depth++; started = true }
                        if (c == '}') depth--
                    }
                    if (started && depth <= 0) break
                    j++
                }
                return body.toString()
            }

            fun judge(lineNo: Int, text: String, excerpt: String) {
                val where = "$file:$lineNo"
                if (OPAQUE_TRUE.containsMatchIn(text)) {
                    hits += Hit(file, lineNo, "isOpaque = true", excerpt)
                    return
                }
                if (BACKGROUND_ASSIGN.containsMatchIn(text)) {
                    if (NAMED_COLOR.containsMatchIn(text)) {
                        exempted += where
                        return
                    }
                    if (RENDERER_CONTEXT.containsMatchIn(masked) || isRendererFile(file)) {
                        exempted += where
                        return
                    }
                    hits += Hit(file, lineNo, "background = …", excerpt)
                }
            }

            // 面一：`JButton(...).apply { … }`（构造处直接设样式）
            lines.forEachIndexed { i, line ->
                if (!BUTTON_CTOR.containsMatchIn(line)) return@forEachIndexed
                if (!APPLY_BLOCK.containsMatchIn(line)) return@forEachIndexed
                blocks++
                judge(i + 1, collectBlock(i), line.trim())
            }

            // 面二：接收者声明为按钮类型的**函数体**
            // （`fun applyPrimaryButtonStyle(button: JButton) { … }`）。
            // UI-043 的真缺陷长在这里——只扫构造处 apply 块会整段漏掉（反向验证实测守卫不红）。
            lines.forEachIndexed { i, line ->
                val fm = BUTTON_FUN_DEF.find(line) ?: return@forEachIndexed
                if (fm.range.first != 0) return@forEachIndexed
                blocks++
                judge(i + 1, collectBlock(i), line.trim())
            }
        }
        return ScanResult(hits, blocks, exempted)
    }

    /** 列表/表格渲染器文件：按平台约定自行填底色是标准做法。 */
    private fun isRendererFile(file: String): Boolean =
        file.endsWith("LogEntryRendering.kt") ||
            file.contains("/render/") ||
            file.contains("ListCellRenderer") ||
            file.contains("TableCellRenderer")

    @Test
    fun test_按钮不得手工铺底色() {
        val result = scan(mainSources())
        assertTrue("应至少扫到若干按钮 apply 块（扫描面失效即假绿）：${result.buttonBlocks}", result.buttonBlocks >= 3)
        assertTrue(
            "按钮不得手工铺底色（isOpaque=true 或自选色 background）——会在 4px 内缩圆角外溢出成方角边。\n" +
                "改用 applyPrimaryButtonStyle / primaryButton（打 JButton.styleDefault 交给 LAF）。\n" +
                result.hits.joinToString("\n") { "${it.file}:${it.line} [${it.form}] ${it.excerpt}" },
            result.hits.isEmpty(),
        )
    }

    /**
     * 扫描器灵敏度与豁免自证（合成源码）：真陷阱必红、命名色与渲染器必走豁免。
     *
     * 存在理由：真实全仓当前零违规，若扫描面哪天被改瞎（正则写坏、块范围算错），主用例仍会「绿」。
     */
    @Test
    fun test_扫描器灵敏度与豁免自证() {
        val defectOpaque = """
            class SyntheticOpaque {
                fun build() = JButton("x").apply {
                    isOpaque = true
                    background = java.awt.Color(0x35, 0x74, 0xF0)
                }
            }
        """.trimIndent()
        val defectBg = """
            class SyntheticBg {
                fun build() = JButton("y").apply {
                    background = java.awt.Color.RED
                }
            }
        """.trimIndent()
        val namedColor = """
            class SyntheticNamed {
                fun build() = JButton("z").apply {
                    background = com.intellij.ui.JBColor.namedColor("Button.default.startBackground", java.awt.Color.BLUE)
                }
            }
        """.trimIndent()
        val renderer = """
            class SyntheticRenderer : javax.swing.JButton(), javax.swing.ListCellRenderer<Any> {
                fun style() = JButton("r").apply {
                    background = java.awt.Color.DARK_GRAY
                }
            }
        """.trimIndent()

        val result = scan(listOf(
            "src/main/kotlin/SyntheticOpaque.kt" to defectOpaque,
            "src/main/kotlin/SyntheticBg.kt" to defectBg,
            "src/main/kotlin/SyntheticNamed.kt" to namedColor,
            "src/main/kotlin/LogEntryRendering.kt" to renderer,
        ))
        val forms = result.hits.map { it.form }
        assertTrue("isOpaque=true 形态必须被判违规：$forms", forms.any { it.contains("isOpaque") })
        assertTrue("自选色 background 形态必须被判违规：$forms", forms.any { it.contains("background") })
        assertTrue("命名色形态必须走豁免而非违规：${result.exempted}", result.exempted.any { it.contains("SyntheticNamed") })
        assertTrue("渲染器文件形态必须走豁免而非违规：${result.exempted}", result.exempted.any { it.contains("LogEntryRendering") })
    }

    private companion object {
        /** 按钮构造点（`JButton(` / `AbstractButton` 子类构造）。 */
        val BUTTON_CTOR = Regex("\\b(JButton|JToggleButton|JCheckBox|JRadioButton)\\s*\\(")

        /** 紧随其后的 apply/also 块（同块内才有「这块是给按钮设样式」的语义）。 */
        val APPLY_BLOCK = Regex("\\.(apply|also)\\s*\\{")

        /**
         * 顶格函数定义，其**接收者或首个参数**声明为按钮类型：
         * `fun applyPrimaryButtonStyle(button: JButton) {` / `fun JButton.foo() {`。
         * UI-043 的真缺陷就在这种函数体里（构造处 apply 块之外）。
         */
        val BUTTON_FUN_DEF = Regex(
            "^(?:(?:private|internal|protected|public|open|override|suspend|inline)\\s+)*" +
                "fun\\s+(?:[\\w.]+\\.)?\\w+\\s*\\([^)]*\\b(JButton|AbstractButton|JToggleButton)\\b[^)]*\\)"
        )

        val OPAQUE_TRUE = Regex("\\bisOpaque\\s*=\\s*true\\b")

        /** `background = …`（排除 `==`）。 */
        val BACKGROUND_ASSIGN = Regex("\\bbackground\\s*=(?!=)")

        /** 平台命名色：颜色来源是平台键，换主题自动跟随。 */
        val NAMED_COLOR = Regex("namedColor|UIUtil\\.get|JBUI\\.CurrentTheme|JBColor\\.(?!\\()")

        /** 渲染器上下文（合成样本用；真实文件走 [isRendererFile]）。 */
        val RENDERER_CONTEXT = Regex("ListCellRenderer|TableCellRenderer|TreeCellRenderer")
    }
}
