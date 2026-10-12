package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 扫描支撑基类：仓库根/源码树快照、剥注释与掩码、站点提取与全量命中集合等共享基建
 * （T-M53-II 2026-10-10：成员逐字符搬运；可见性按拆类需要统一放宽为 internal。）
 */
abstract class BannedApiScanSupport {

    // T-TestPerf6：repoRoot/stripComments 挪 companion（三棵源码树快照与 mainSources 剥注释版
    // 都在 companion 构建），调用点签名不变，语义不变
    internal fun repoRoot(): File = Companion.repoRoot()

    /** 剥离 block 注释与行注释（字符串字面量中的 // 会被连带截断——只可能产生漏报不产生误报，可接受）。 */
    internal fun stripComments(source: String): String = Companion.stripComments(source)

    internal fun mainSources(): List<Pair<String, String>> = mainSourcesSnapshot

    /** 全量源码中「禁用片段」命中清单：List<文件相对路径>。 */
    internal fun hits(banned: String): List<String> =
        mainSources().filter { (_, code) -> code.contains(banned) }.map { (path, _) -> path }

    internal fun assertNoHits(banned: String, why: String, fix: String) {
        val found = hits(banned)
        assertTrue(
            "src/main 出现禁用 API `$banned`（$why）。命中 ${found.size} 处：$found。$fix",
            found.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // write-safe 上下文（T-WriteSafeCtx：2026-09-17 真机 Write-unsafe context 事故）
    // ------------------------------------------------------------------

    /** src/main 原文（**不剥注释**——豁免标注要在原文上认；CRLF/缩进原样保留）。T-TestPerf6 起读快照。 */
    internal fun rawMainSources(): List<Pair<String, String>> =
        srcMainKotlinTree
            .filter { (f, _) -> f.extension == "kt" }
            .map { (f, text) -> f.relativeTo(repoRoot()).path.replace('\\', '/') to text }

    /**
     * 配平/提取专用文本：注释与字符串、字符字面量的**内容**替换为等长空白，换行与其余字符原样保留。
     *
     * 为什么不复用 [stripComments]：
     *  - **保行**——提取出的行号要回喂 [leadingCommentBlock]（它读原文找 `// write-safe:` 标注），
     *    行号错位等于豁免认错行；而 [stripComments] 把 block 注释**整段删掉**（连同其中的换行），
     *    多行 block 注释之后的全部行号一律前移（实测：`"a\n/* x\ny */\nb\nc\n"` 里的 `b` 由
     *    第 4 行前移到第 3 行，即该函数**不保行**；断言见自证用例 `TFS06自证_…`）。
     *  - **涂白而非删除**——保持片段内的字符距离，提取出的 body 可直接做令牌匹配
     *    （`setText("x")` 涂白成 `setText(   )`，令牌 `setText(` 照旧命中）。
     *
     * 为什么必须剥（T-FS-06 缺口二）：花括号配平此前跑在**原文**上，注释/字符串里不成对 `{` 会让
     * body 延伸越过 lambda 边界 → **过报**（把 lambda 之外的合法写报成违规）；不成对 `}` 则提前
     * 截断 → **漏报**。字符串/字符字面量是同一类错配源（`"{"` / `'{'` 都不是花括号），一并涂白。
     */
    internal fun maskNonCode(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                // 行注释：涂到行尾（换行留给下一轮原样保留）
                c == '/' && source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                // block 注释（Kotlin 允许嵌套，按深度配对）：涂白，换行保留
                c == '/' && source.startsWith("/*", i) -> {
                    var depth = 0
                    while (i < source.length) {
                        if (source.startsWith("/*", i)) {
                            depth++
                            out.append("  ")
                            i += 2
                        } else if (source.startsWith("*/", i)) {
                            depth--
                            out.append("  ")
                            i += 2
                            if (depth == 0) break
                        } else {
                            out.append(if (source[i] == '\n') '\n' else ' ')
                            i++
                        }
                    }
                }
                c == '"' -> i = maskStringLiteral(source, i, out)
                c == '\'' -> i = maskCharLiteral(source, i, out)
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * 涂白一个字符串字面量（`"…"` / 原始 `"""…"""` / 含 `${…}` 模板的普通字符串）的内容；
     * 返回下一个待处理下标。
     *
     * **模板表达式（T-FS-06 独立复核阻断项修复）**：`"a${b("c")}d"` 这类构造里，`${…}` 内的
     * `"c"` 是**嵌套字符串**。早期实现遇首个未转义 `"` 即结束外层字符串 → in-string 状态翻转，
     * 字符串正文被当代码暴露、模板内的真实代码被涂白，于是后续站点实参区被正文里的 `)` 提前配平到 0
     * （实测 `execute/DataSourceSelector.kt:1009` 实参区被截断到 9414/13999 字符而不再含
     * `ModalityState.any()`、`:2684` 配平返回 −1 整条站点被丢弃 → 两个真实站点脱离两条规则覆盖，
     * 而当前 0 offender 让绿灯看不出收窄；同类构造仓内 47 文件 / 127 处）。
     *
     * 修法：整段字符串（含模板表达式）一律涂白——模板里的代码虽是真代码，但它位于字符串内部、
     * 不参与调用结构，涂白后配平与令牌判定都只看调用本体；`${` 开模板、模板内 `{`/`}` 计数配对、
     * 模板内 `"` 递归按字符串处理，故外层字符串的结束引号总能被正确找到。**严格保长、保行**
     * （换行原样输出），行号因此与原文一致。未闭合的普通字符串仍在行尾收手（不吞后续行）。
     *
     * 残留边界（复核实测登记，仅编译不过的代码可触发）：未闭合的 `"` **且**其中 `${` 也未闭合时，
     * 模板深度 > 0 使换行不再终止字符串 → 会涂白到 EOF，其后站点被吞。正常 Kotlin 不出现此形态，
     * 且不影响「未闭合 `"` 在行尾收手」这一既有行为。
     */
    internal fun maskStringLiteral(source: String, start: Int, out: StringBuilder): Int {
        if (source.startsWith("\"\"\"", start)) {
            out.append("\"\"\"")
            var i = start + 3
            while (i < source.length) {
                if (source.startsWith("\"\"\"", i)) {
                    out.append("\"\"\"")
                    return i + 3
                }
                out.append(if (source[i] == '\n') '\n' else ' ')
                i++
            }
            return i
        }
        out.append('"')
        var i = start + 1
        var templateDepth = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' && i + 1 < source.length -> {
                    out.append("  ")
                    i += 2
                }
                // 未闭合（不该出现）：不吞后续行——仅在外层字符串（非模板表达式内）收手
                c == '\n' && templateDepth == 0 -> return i
                c == '$' && i + 1 < source.length && source[i + 1] == '{' -> {
                    out.append("  ")
                    templateDepth++
                    i += 2
                }
                templateDepth > 0 && c == '{' -> {
                    out.append(' ')
                    templateDepth++
                    i++
                }
                templateDepth > 0 && c == '}' -> {
                    out.append(' ')
                    templateDepth--
                    i++
                }
                // 模板表达式里的嵌套字符串（`"a${b("c")}d"`）——递归涂白，否则外层引号会被误判为结束
                templateDepth > 0 && c == '"' -> i = maskStringLiteral(source, i, out)
                c == '"' -> {
                    out.append('"')
                    return i + 1
                }
                else -> {
                    out.append(if (c == '\n') '\n' else ' ')
                    i++
                }
            }
        }
        return i
    }

    /** 涂白字符字面量 `'x'` / `'\n'` 的内容（`'{'` 不是花括号——同类错配源）；非字面量原样放行。 */
    internal fun maskCharLiteral(source: String, start: Int, out: StringBuilder): Int {
        var i = start + 1
        while (i < source.length && source[i] != '\n' && i - start <= 10) {
            if (source[i] == '\\') {
                i += 2
                continue
            }
            if (source[i] == '\'') {
                out.append('\'').append(" ".repeat(i - start - 1)).append('\'')
                return i + 1
            }
            i++
        }
        out.append('\'')
        return start + 1
    }

    /** 从 [open] 处的开括号配平到对应闭括号下标；配不平（延伸到文末）返回 -1。 */
    internal fun matchingDelimiter(code: String, open: Int, openChar: Char, closeChar: Char): Int {
        var depth = 0
        var i = open
        while (i < code.length) {
            val c = code[i]
            if (c == openChar) depth++
            else if (c == closeChar) {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    /** [from] 起跳过空白（含换行）后紧跟 `{` 时取尾随 lambda 体；否则 null。 */
    internal fun trailingLambdaBody(code: String, from: Int): String? {
        var i = from
        while (i < code.length && code[i].isWhitespace()) i++
        if (i >= code.length || code[i] != '{') return null
        val close = matchingDelimiter(code, i, '{', '}')
        return if (close >= 0) code.substring(i, close + 1) else null
    }

    /** 取调用行**向上连续的注释/空行块**（豁免标注的合法位置：紧贴调用行上方）。 */
    internal fun leadingCommentBlock(code: String, callLine: Int): String {
        val lines = code.lines()
        val sb = StringBuilder()
        var i = callLine - 2 // callLine 是 1-based，减 2 = 上一行的 0-based 下标
        while (i >= 0) {
            val t = lines[i].trim()
            if (t.isEmpty() || t.startsWith("//")) {
                sb.insert(0, lines[i] + "\n")
                i--
            } else {
                break
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 项目服务级状态复位（T-StabilityTail T-ST-02d，2026-09-19）
    // ------------------------------------------------------------------

    /**
     * src/test 源码（**原文**，不剥注释——`// project-state:` 豁免标注要在原文上认；掩码由调用方按需生成）。
     * 本守卫自身排除在外（其合成样本与报错文案含被扫片段）。
     */
    internal fun testSources(): List<Pair<String, String>> =
        srcTestKotlinTree
            .filter { (f, _) -> f.extension == "kt" }
            .filter { (f, _) -> f.name != "BannedApiSourceScanTest.kt" }
            .map { (f, text) -> f.relativeTo(repoRoot()).path.replace('\\', '/') to text }

    /**
     * 扫描面保鲜断言：路径解析失败（`src/test/kotlin` 改名 / `user.dir` 异常）时 [testSources] 返回**空列表**，
     * 而规则对空列表恒 0 违规 → 主用例会**静默空绿**（T-ST-02d 独立复核条件项）。空面自证见 `TST02d自证_…`
     * （空列表必须让本断言变红）。
     */
    internal fun assertTestSourcesResolved(sources: List<Pair<String, String>>) {
        assertTrue(
            "扫描面为空——src/test/kotlin 下未解析到任何 .kt（路径改名 / user.dir 异常？），" +
                "空扫描面会让本用例静默空绿：root=${repoRoot().resolve("src/test/kotlin")}",
            sources.isNotEmpty(),
        )
    }

    /** 1-based 行号（掩码文本保行，与原文一致）。 */
    internal fun lineOf(code: String, index: Int): Int = code.take(index).count { it == '\n' } + 1

    internal companion object {
        internal fun repoRoot(): File {
            var dir = File(System.getProperty("user.dir")).absoluteFile
            repeat(6) {
                if (File(dir, "src/main/kotlin").isDirectory) return dir
                dir = dir.parentFile ?: return@repeat
            }
            error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: " + System.getProperty("user.dir"))
        }

        /** 剥离 block 注释与行注释（字符串字面量中的 // 会被连带截断——只可能产生漏报不产生误报，可接受）。 */
        internal fun stripComments(source: String): String =
            source
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .lineSequence()
                .joinToString("\n") { it.substringBefore("//") }

        // T-TestPerf6：三棵源码树快照——同一次测试 JVM 内文件内容恒定，全部扫描点共享一次
        // 遍历+读取（原口径 mainSources 每个断言各付一遍全仓、src/test 三处与 buildSrc 一处内联
        // 各付一遍）；各使用处的过滤/豁免/判读口径在使用点原样保留，快照只消除重复文件系统 IO。
        internal val CHAR_BS: Char = '\\'

        internal fun snapshotTree(rel: String): List<Pair<File, String>> =
            File(repoRoot(), rel).walkTopDown()
                .filter { it.isFile }
                .map { it to it.readText() }
                .toList()

        internal val srcMainKotlinTree: List<Pair<File, String>> by lazy { snapshotTree("src/main/kotlin") }
        internal val srcTestKotlinTree: List<Pair<File, String>> by lazy { snapshotTree("src/test/kotlin") }
        internal val buildSrcKotlinTree: List<Pair<File, String>> by lazy { snapshotTree("buildSrc/src/main/kotlin") }

        internal val mainSourcesSnapshot: List<Pair<String, String>> by lazy {
            srcMainKotlinTree
                .filter { (f, _) -> f.extension == "kt" }
                .map { (f, text) -> f.relativeTo(repoRoot()).path.replace('\\', '/') to stripComments(text) }
        }
    }
}
