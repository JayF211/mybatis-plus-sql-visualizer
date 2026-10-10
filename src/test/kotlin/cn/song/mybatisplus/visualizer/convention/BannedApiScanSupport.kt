package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// BannedApiSourceScanTest 的扫描支撑件（T-M53-II 2026-10-10：由原文件**逐字符搬出**：
// 字段表、嵌套类型、扫描辅助函数，以及原 Companion 成员（展开为顶层声明、去掉 private；
// 原指向 companion 的两个薄委托 repoRoot/stripComments 删除以免同名重复）。
// 同包顶层声明，故守卫类无需 import，调用点一字未改。

fun mainSources(): List<Pair<String, String>> = mainSourcesSnapshot

/** 全量源码中「禁用片段」命中清单：List<文件相对路径>。 */
fun hits(banned: String): List<String> =
    mainSources().filter { (_, code) -> code.contains(banned) }.map { (path, _) -> path }

fun assertNoHits(banned: String, why: String, fix: String) {
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
fun rawMainSources(): List<Pair<String, String>> =
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
fun maskNonCode(source: String): String {
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
fun maskStringLiteral(source: String, start: Int, out: StringBuilder): Int {
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
fun maskCharLiteral(source: String, start: Int, out: StringBuilder): Int {
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

/**
 * `invokeLater` 调用站点：**括号实参区**与**尾随 lambda 体**一次取全（T-FS-06 缺口一的修法）。
 *
 * 提取跑在 [maskNonCode] 后的文本上（配平必须避开注释/字符串里的假括号），行号因涂白保行
 * 而与原文一致，可直接回喂 [leadingCommentBlock]。
 */
data class InvokeLaterSite(
    /** 调用起始行号（1-based，与 [rawMainSources] 的原文行号对齐）。 */
    val line: Int,
    /** 括号实参区（含括号）；省略实参列表的尾随 lambda 形态为 null。 */
    val args: String?,
    /** 尾随 lambda 体（含花括号）；无尾随 lambda 为 null。 */
    val lambdaBody: String?,
) {
    /**
     * 写体扫描面 = 实参区 + 尾随 lambda 体。
     *
     * `invokeLater { … }` 的写体在**花括号**里、
     * `invokeLater({ … }, ModalityState.any())` 的写体在**实参区**里——只看任一侧都会漏
     * （T-FS-06 缺口一即后者曾被默认模态规则漏掉：该规则原先要求 `invokeLater` 后紧跟 `{`，
     * 而此形态紧跟的是 `(`）。
     */
    val body: String = (args ?: "") + (lambdaBody ?: "")
}

/**
 * 扫出全部 `invokeLater` 调用站点，覆盖四种写法：
 *  ① `invokeLater({ … }, ModalityState.any())`——写体在实参区；
 *  ② `invokeLater({ … }, ModalityState.defaultModalityState())`——实参区含显式默认模态
 *     （T-FS-06 缺口一：旧默认模态规则要求 `invokeLater` 后紧跟 `{`，此形态整片漏网）；
 *  ③ `invokeLater { … }`——省略实参的尾随 lambda（隐式 `defaultModalityState()`）；
 *  ④ 链式前缀 `ApplicationManager.getApplication().invokeLater(…)`——`invokeLater(` 是子串匹配，
 *     前缀写法不影响；负向后顾 `(?<![A-Za-z0-9_])` 保证不误吃 `invokeLaterWriteSafe` /
 *     `invokeLaterOnWriteThread`（正确形态）。
 *
 * 已知边界（全部是**漏报**方向，不误报；T-FS-06 独立复核逐条实测订正）：`invokeLater` 与 `(` 之间插空白、
 * 1 参 `invokeLater(runnable)`（写体在别处的 Runnable 定义里，本扫描面不可判定）、
 * **`invokeLater({ … })`（括号内 lambda 且完全不写模态实参）**——默认模态规则按「省略实参列表
 * 或实参区含 `defaultModalityState()`」判定，此形态两者都不满足故漏报（仓内 0 处）。收编需把判据
 * 改成「实参区无 any()/nonModal() 模态令牌即视为默认模态」，会连带改变对自定义模态的判定
 * （`stateForComponent` 等），本轮只登记边界不扩判据。
 * ~~`)` 与 `{` 之间夹注释~~ **不是边界**：注释已被 [maskNonCode] 涂白、[trailingLambdaBody] 跳空白
 * 后仍能命中（复核实测 `app.invokeLater(ModalityState.nonModal()) /* c */ { … }` 命中）——旧表述过保守。
 */
fun invokeLaterSites(code: String): List<InvokeLaterSite> {
    val out = ArrayList<InvokeLaterSite>()
    // 形态 ①②④：带实参列表；其后可跟尾随 lambda
    var idx = code.indexOf("invokeLater(")
    while (idx >= 0) {
        val open = idx + "invokeLater".length
        val close = matchingDelimiter(code, open, '(', ')')
        if (close >= 0) {
            out.add(
                InvokeLaterSite(
                    line = code.take(idx).count { it == '\n' } + 1,
                    args = code.substring(open, close + 1),
                    lambdaBody = trailingLambdaBody(code, close + 1),
                ),
            )
        }
        idx = code.indexOf("invokeLater(", idx + 1)
    }
    // 形态 ③：省略实参列表的尾随 lambda
    for (m in Regex("""(?<![A-Za-z0-9_])invokeLater\s*\{""").findAll(code)) {
        val open = code.indexOf('{', m.range.first)
        val close = matchingDelimiter(code, open, '{', '}')
        if (close >= 0) {
            out.add(
                InvokeLaterSite(
                    line = code.take(open).count { it == '\n' } + 1,
                    args = null,
                    lambdaBody = code.substring(open, close + 1),
                ),
            )
        }
    }
    return out
}

/** 从 [open] 处的开括号配平到对应闭括号下标；配不平（延伸到文末）返回 -1。 */
fun matchingDelimiter(code: String, open: Int, openChar: Char, closeChar: Char): Int {
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
fun trailingLambdaBody(code: String, from: Int): String? {
    var i = from
    while (i < code.length && code[i].isWhitespace()) i++
    if (i >= code.length || code[i] != '{') return null
    val close = matchingDelimiter(code, i, '{', '}')
    return if (close >= 0) code.substring(i, close + 1) else null
}

/** 闸门令牌：body 内出现即视为写体已过 [cn.song.mybatisplus.visualizer.execute.DocWriteContext] 闸门。 */
val writeGateTokens = listOf("DocWriteContext.runWriteSafe", "DocWriteContext.invokeLaterWriteSafe")

/**
 * 通用扫描：对满足 [isTarget] 的 `invokeLater` 站点判定「回调里做文档写」，
 * 返回 `List<"路径:行号 → 命中令牌">`。
 *
 * 豁免三选一（两条规则同口径）：
 *  ① 调用行上方连续注释块含 `write-safe:` 理由标注；
 *  ② 写体已过闸门（[writeGateTokens]）；
 *  ③ 无写入令牌（本族规则只拦写文档）。
 *
 * 令牌判定只看代码——body 取自 [maskNonCode] 后的文本，注释与字符串里的 `setText` 字样不算写。
 * 单独成函数（而非内联进 @Test）是为了让规则本身可自证：合成源码样本能直接喂进来，
 * 否则「规则看不见某形态」这种空绿只能靠人工改线上代码才验得出来。
 */
fun invokeLaterWriteOffenders(
    sources: List<Pair<String, String>>,
    isTarget: (InvokeLaterSite) -> Boolean,
): List<String> {
    val offenders = ArrayList<String>()
    for ((path, raw) in sources) {
        for (site in invokeLaterSites(maskNonCode(raw))) {
            if (!isTarget(site)) continue
            if (leadingCommentBlock(raw, site.line).contains("write-safe:")) continue
            if (writeGateTokens.any { site.body.contains(it) }) continue
            val hit = writeTokens.filter { site.body.contains(it) }
            if (hit.isNotEmpty()) offenders.add("$path:${site.line} → ${hit.joinToString("/")}")
        }
    }
    return offenders
}

/** `ModalityState.any()`（恒 write-unsafe）规则的站点判定。 */
fun anyModalityWriteOffenders(sources: List<Pair<String, String>>): List<String> =
    invokeLaterWriteOffenders(sources) { it.args?.contains("ModalityState.any()") == true }

/**
 * `update` 包「派发平台卸载/装载禁用 any 模态」规则的命中判定（T-DynUpdateWriteSafe）。
 *
 * 与 [anyModalityWriteOffenders] 的分工：那条按**写令牌**拦「回调体里写文档」，本规则**不看写令牌**
 * ——写发生在被调用的平台卸载内部（`DynamicPlugins.unloadPlugin` → `inModalContext(sentinel
 * "Unloading of <plugin>")` → 平台自身 commit/落盘/dispose），词法上永远看不见（2026-09-22 真机
 * 日志实证：模态串 `[设置对话框, Unloading of SQL Visualizer]`）。故按「包 + 令牌」封口：该包是
 * 仓内唯一向平台动态卸载/装载交棒的地方，**任何形态**的 `ModalityState.any()` 都不该出现
 * （直接实参、赋给变量再传、决策函数返回——三种形态都实测过）。
 *
 * 判定跑在 [maskNonCode] 后的文本上（注释与字符串字面量里的字样不算命中，逐行保行故行号可直接报）；
 * 豁免沿用「该行向上连续注释块含 `write-safe:`」口径（本项 T-01 的只读跳板即此形态：只读当前模态，
 * 无模型改动）。
 */
fun anyModalityInUpdatePackageOffenders(sources: List<Pair<String, String>>): List<String> {
    val offenders = ArrayList<String>()
    for ((path, raw) in sources) {
        if (!path.contains("/update/")) continue
        maskNonCode(raw).lines().forEachIndexed { index, line ->
            if (!line.contains("ModalityState.any()")) return@forEachIndexed
            if (leadingCommentBlock(raw, index + 1).contains("write-safe:")) return@forEachIndexed
            offenders.add("$path:${index + 1}")
        }
    }
    return offenders
}


/**
 * 默认模态规则——`defaultModalityState()` 语义的两种等价写法：
 *  ① 省略实参的尾随 lambda `invokeLater { … }`（[InvokeLaterSite.args] == null）；
 *  ② 显式实参 `invokeLater({ … }, ModalityState.defaultModalityState())`（T-FS-06 补口，此前漏网）。
 * `nonModal()`（恒 write-safe）与 `any()`（另由 [anyModalityWriteOffenders] 管）都不在此列。
 */
fun defaultModalityWriteOffenders(sources: List<Pair<String, String>>): List<String> =
    invokeLaterWriteOffenders(sources) {
        it.args == null || it.args.contains("ModalityState.defaultModalityState()")
    }

/** 取调用行**向上连续的注释/空行块**（豁免标注的合法位置：紧贴调用行上方）。 */
fun leadingCommentBlock(code: String, callLine: Int): String {
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

/**
 * 文档写入令牌：命中即认为该回调在做文档写（`any()` 规则与尾随 lambda 规则共用同一份名单）。
 *
 * 名单里既有平台 API（`setText(` / `commitDocument(` / `WriteAction.`），也有本仓库自己的
 * 写入收口（`writeVfTextViaDocument(` / `dispatchDocWriteOnEdt(` / `setResultText(` …）——
 * 后者是「一次调用背后就是一次文档写」的语义单位，只看平台 API 会漏掉整条自建写链。
 */
val writeTokens = listOf(
    "setText(", "insertString(", "replaceString(", "runWriteCommandAction", "commitDocument(",
    "saveDocument(", "setBinaryContent", "writeVfTextViaDocument(", "docWriteOnce(",
    "dispatchDocWriteOnEdt(", "writeConsoleOutput(", "refreshConsoleModel(",
    "prepareAfterEditorResolved(", "attachConsole(", "prewarmExecTempFile(",
    "prewarmPreviewTempFile(", "syncExecVfContentIfNeeded(", "renderSql(", "showSql(",
    "recoverConsole(", "setResultText(", "createEditor(", "render(", "WriteAction.",
)

// ------------------------------------------------------------------
// fatal 透传（T-FatalPassthrough，2026-09-19）：catch (…: Throwable) 必须判定或登记豁免
// ------------------------------------------------------------------

/**
 * `catch (…: Throwable)` 块：调用行号（1-based，与原文对齐）+ 块体（取自 [maskNonCode] 后的
 * 文本——注释/字符串里的 `isFatal` 字样不算判定）。
 */
data class ThrowableCatchSite(val line: Int, val body: String)

/**
 * 扫出全部 `catch (…: Throwable)` 块，含**嵌套**块（内层块体独立成站点：外层判了 fatal
 * 不代表内层判了）。边界（均为漏报方向，不误报）：
 *  - `catch (Throwable t)`（Java 写法，只出现在注入 agent 的 **Java 模板字符串**里，
 *    `SourceInterceptorTemplates.kt:99`）——被 [maskNonCode] 涂白，不是 Kotlin 站点；
 *  - 多 catch 共体（Kotlin 无 multi-catch 语法）不存在；`catch (e: Exception)` 不是本规则对象。
 */
fun throwableCatchSites(code: String): List<ThrowableCatchSite> {
    val out = ArrayList<ThrowableCatchSite>()
    for (m in Regex("""catch\s*\([^)]*:\s*Throwable\s*\)""").findAll(code)) {
        val open = code.indexOf('{', m.range.last)
        if (open < 0) continue
        val close = matchingDelimiter(code, open, '{', '}')
        out.add(
            ThrowableCatchSite(
                line = code.take(m.range.first).count { it == '\n' } + 1,
                body = if (close >= 0) code.substring(open, close + 1) else code.substring(open),
            ),
        )
    }
    return out
}

/** 豁免记录：[sites] = 提交时该文件的站点数（漂移即红——新增/删除捕获点必须重新登记），[reason] 必填。 */
data class CatchExemption(val sites: Int, val reason: String)

/** fatal 判定令牌（形态 A）：块体内出现即视为已判。 */
val fatalCheckToken = "FatalErrors.isFatal("

/**
 * 已落地 fatal 透传的文件（T-FP-02 子集）——**不得**出现在 [throwableCatchExemptions]，
 * 且每处 `catch (…: Throwable)` 都必须带判定。
 */
val landedFatalPassthroughFiles = setOf(
    "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.kt",
    "src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt",
    "src/main/kotlin/cn/song/mybatisplus/visualizer/log/RunConfigurationProcessListener.kt",
    // T-FP-02b（独立复核条件项）：原豁免理由「可选能力探测失败返回 null（预览主链降级）」把**预览
    // 主链**写成能力探测——属借豁免放走真该收口的站点。已收口（判 fatal + 非 fatal 补 WARN 留痕），
    // 故从豁免表移入本表。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.kt",
    // T-RecentExecMenu（20261007，本轮修复并行会话新增站点时的顺带收口）：`log/SqlToJavaPanel.kt`
    // 原登记 1 处（后台候选收集失败返回空），并行会话 T-MapResultVo v2 又加了一处同形 PSI 读降级
    // （重算调用类 MPJ 能力/方法集）→ 登记数与实际 2 处不符、守卫转红。按本工程方向**整段收口**
    // 而非改登记数：两处均补 `FatalErrors.isFatal(t) throw t`（非 fatal 仍降级，语义不变），
    // 文件随之从豁免表移入本表——此后任一站点退回无判定即红（自动保鲜，无需维护站点数）。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlToJavaPanel.kt",
)

/**
 * 未收口 `catch (…: Throwable)` 的**豁免登记**（T-FatalPassthrough T-FP-03 / 计划 §八 清单）。
 *
 * 为什么是「文件 + 站点数」而不是「文件:行」：行号在**别的高频改动文件**（`log/`、`actions/`、
 * `ui/`）里随任何编辑漂移，逐行白名单会让并行会话的合法改动把守卫打成红的（计划 §六 风险表
 * 「守卫误报」）。站点数同样能钉住回潮——豁免文件里新增一个无判定的 `catch (…: Throwable)`
 * 即红，须补判定或重新登记理由。行号清单见本段末（提交时实测，仅供人工核对）。
 *
 * 豁免口径：捕获面覆盖 fatal 但**失败语义就是降级**（可选能力探测 / 后台候选收集 /
 * 图形渲染回落 / fail-closed 安全闸门）——不是「懒得改」。
 *
 * T-FP-02b 补一条边界（独立复核条件项教训）：**主链降级不算「可选能力探测」**——
 * `ui/SqlPreviewService.kt` 的预览转换失败返回 null 曾被写成「能力探测失败返回 null（预览主链
 * 降级）」放行，实际是用户点击后的唯一主路径（失败只弹「未找到可转换的 Wrapper」提示、零日志），
 * 已收口。登记理由须如实描述失败面属于哪一类，借「探测」名义放走主链会红在复核。
 */
val throwableCatchExemptions: Map<String, CatchExemption> = mapOf(
    // 12 处：平台可选能力探测 + 反射候选遍历（2026.2 ContentManagerListener 接口形态不匹配、
    // filter 接口逐个试、方法/组件查找）——失败即回落轮询兜底 / 返回 null / 视为存活。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogConsoleBridge.kt" to
        CatchExemption(12, "控制台桥的平台可选能力探测与反射候选遍历：失败即降级（轮询兜底/null/视为存活）"),
    // 7 处：后台收集候选（链头/注解/XML tag）失败返回空列表 → EDT 提示「未找到候选」。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt" to
        CatchExemption(7, "后台候选收集失败返回空列表，用户侧看到「未找到候选」提示（非长驻链路）"),
    // 5 处：JOIN 图渲染/后台富化失败回落「无图 / 未富化模型」。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanel.kt" to
        CatchExemption(5, "JOIN 图渲染/富化失败回落无图或未富化模型（已 warn 留痕 + 显式摘转圈）"),
    // 3 处：原生 Database 插件图表可选能力探测。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/diagram/nativefw/db/NativeDatabaseDiagramSupport.kt" to
        CatchExemption(3, "原生 Database 图表可选能力探测，失败回落自建 A 路径（零劣化）"),
    // 3 处：原生 JOIN 图可选能力探测 + 关编辑器失败不阻断。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/diagram/nativefw/NativeJoinDiagramSupport.kt" to
        CatchExemption(3, "原生 JOIN 图可选能力探测，失败回落内置渲染器（零劣化）"),
    // 2 处：本地帮助 HTML 打开失败回落在线文档；版本号读取失败返回空串。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt" to
        CatchExemption(2, "帮助 HTML 打开失败回落在线文档；版本号读取失败返回空串"),
    // 1 处：OGNL 求值器对用户表达式收敛为 Err。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/xml/Ognl.kt" to
        CatchExemption(1, "OGNL 求值器须把用户表达式的任何失败收敛为 Err（同 McpSelectGuard fail-closed 族）"),
    // 1 处：DasModel 遍历失败 bail。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/DatabaseSchemaResolver.kt" to
        CatchExemption(1, "平台 DasModel 形态差异探测：遍历失败 bail + warn（解析降级）"),
    // 1 处：渲染失败回「无图」语义。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/diagram/DiagramPanel.kt" to
        CatchExemption(1, "渲染失败回「无图」语义（清画布 + currentModel，防每次缩放重复抛）"),
    // 1 处：补全异常不影响输入。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlInputEditor.kt" to
        CatchExemption(1, "补全弹窗异常不得影响输入（失败下次输入重试）"),
    // 1 处：SQL 解析失败回落 trimEnd + 分号。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/psource/JdbcTemplateSqlPreviewService.kt" to
        CatchExemption(1, "SQL 解析失败回落「trimEnd + 分号」纯文本形态"),
    // 1 处：安全闸门 fail-closed——调用点注释即依据。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSelectGuard.kt" to
        CatchExemption(1, "安全闸门 fail-closed：解析器对恶意构造输入可能抛 Error，必须接住并拒绝"),
    // 1 处：反射探测返回 false。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogManager.kt" to
        CatchExemption(1, "反射能力探测失败返回 false"),
    // 1 处：后台候选收集失败返回空。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/GenerateCrudSqlFromEntityAction.kt" to
        CatchExemption(1, "后台候选收集失败返回空列表（动作提示无候选）"),
    // 1 处：同上。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/GenerateCrudSqlFromDbTableAction.kt" to
        CatchExemption(1, "后台候选收集失败返回空列表（动作提示无候选）"),
    // 1 处：同上。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertSqlToJavaAction.kt" to
        CatchExemption(1, "后台候选收集失败返回空列表（动作提示无候选）"),
)

/**
 * fatal 透传违规点：`路径:行号`（无判定且未豁免）。单独成函数以便合成样本自证
 * （规则「看不见某形态」的空绿只能靠合成样本钉住，见 `TFP03自证_…`）。
 */
/**
 * 未收口点的**行号清单**（T-FatalPassthrough 实施时实测；守卫不校验行号，仅作人工核对与
 * 计划 §八 登记的出处）。共 **42 处 / 16 文件**（T-FP-02b 起 `ui/SqlPreviewService.kt:2100`
 * 已收口移入已落地表；20261007 `log/SqlToJavaPanel.kt` 两站点收口后同此）：
 *
 * ```
 * log/SqlLogConsoleBridge.kt                                     12 处：151 167 208 410 561 570 595 642 656 664 678 695
 * actions/ConvertToSqlAction.kt                                   7 处：119 339 383 431 485 554 593
 * ui/SqlPreviewPanel.kt                                           5 处：1498 1517 1528 1547 2566
 * ui/diagram/nativefw/db/NativeDatabaseDiagramSupport.kt          3 处：102 122 224
 * ui/diagram/nativefw/NativeJoinDiagramSupport.kt                 3 处：52 115 126
 * log/SqlLogPanel.kt                                              2 处：1139 1155
 * xml/Ognl.kt                                                     1 处：163
 * ui/highlight/DatabaseSchemaResolver.kt                          1 处：352
 * ui/diagram/DiagramPanel.kt                                      1 处：197
 * ui/SqlInputEditor.kt                                            1 处：160
 * psource/JdbcTemplateSqlPreviewService.kt                        1 处：105
 * mcp/McpSelectGuard.kt                                           1 处：69
 * log/SqlLogManager.kt                                            1 处：275
 * actions/GenerateCrudSqlFromEntityAction.kt                      1 处：61
 * actions/GenerateCrudSqlFromDbTableAction.kt                     1 处：50
 * actions/ConvertSqlToJavaAction.kt                               1 处：93
 * ```
 *
 * 已落地（**33 处 / 5 文件**）：`execute/DataSourceSelector.kt` 13、`log/agent/AgentManager.kt` 10、
 * `log/RunConfigurationProcessListener.kt` 7、`ui/SqlPreviewService.kt` 1（T-FP-02b 收口）、
 * `log/SqlToJavaPanel.kt` 2（20261007 收口；该文件此前**登记 1 处、实际 2 处**，故上表登记口径
 * 的 42 与已落地 33 之和为 75，比历史「合计 74」多 1——差额即那次漏登）——
 * 合计 75 处（`grep -rn "catch (.*: Throwable)"` 口径）。
 * 另有 `log/SourceInterceptorTemplates.kt:99` 的 `catch (Throwable t)` 位于**注入 agent 的 Java
 * 源码模板字符串**里，不是 Kotlin 站点（[maskNonCode] 涂白，`TFP03自证_…` 已钉住该边界）。
 *
 * **同族抵消面（runCatching 接住已透传的 fatal）**：本守卫只扫 `catch (…: Throwable)` 块，看不见
 * 「块内 `throw` 被同一函数的外层 `runCatching {}.onFailure/getOrElse` 接住」的抵消——T-FP-02b
 * （独立复核阻断 1/2）已把**写集内**的抵消点逐个放行：`execute/DataSourceSelector.kt`
 * `writeConsoleOutput` 外层（`:796`，包住 `:763`/`:779` 两处捕获块）、`prewarmExecTempFile`
 * 缓存命中刷新（`:3813`）、`prewarmExecTempFileInner` 写盘（`:3874`）、`log/agent/AgentManager.kt`
 * `resolvePid` 外层（`:877`），以及预热链唯一调用方 `ui/SqlPreviewService.kt` 的
 * `runCatching { prewarmExecTempFile(...) }`（`:2121`）。写集外仍存在的同类抵消面（如
 * `executeOnConsole` 的外层 `runCatching`，其内层抛点实际在 `executeOnPooledThread`/`invokeLater`
 * lambda 内、运行时不经该 runCatching）见计划 §九。
 */
fun fatalPassthroughOffenders(sources: List<Pair<String, String>>): List<String> {
    val offenders = ArrayList<String>()
    for ((path, raw) in sources) {
        if (throwableCatchExemptions.containsKey(path)) continue
        for (site in throwableCatchSites(maskNonCode(raw))) {
            if (!site.body.contains(fatalCheckToken)) offenders.add("$path:${site.line}")
        }
    }
    return offenders
}

// ------------------------------------------------------------------
// PSI 跨线程捕获（T-PsiCaptureProtocol T-PCP-04，2026-09-19）
// ------------------------------------------------------------------

/** 池化线程站点：`executeOnPooledThread` 的**括号实参区 + 尾随 lambda 体**（提取口径同 [invokeLaterSites]）。 */
data class PooledThreadSite(val line: Int, val args: String?, val lambdaBody: String?) {
    /** 扫描面 = 实参区 + 尾随 lambda 体（写体/读体可能在任一侧，只看一侧会漏）。 */
    val body: String = (args ?: "") + (lambdaBody ?: "")
}

/** 扫出全部 `executeOnPooledThread` 调用站点；提取跑在 [maskNonCode] 后（配平须避开注释/字符串里的假括号）。 */
fun pooledThreadSites(code: String): List<PooledThreadSite> {
    val out = ArrayList<PooledThreadSite>()
    var idx = code.indexOf("executeOnPooledThread")
    while (idx >= 0) {
        var i = idx + "executeOnPooledThread".length
        while (i < code.length && code[i].isWhitespace()) i++
        var args: String? = null
        if (i < code.length && code[i] == '(') {
            val close = matchingDelimiter(code, i, '(', ')')
            if (close >= 0) {
                args = code.substring(i, close + 1)
                i = close + 1
            }
        }
        out.add(
            PooledThreadSite(
                line = code.take(idx).count { it == '\n' } + 1,
                args = args,
                lambdaBody = trailingLambdaBody(code, i),
            ),
        )
        idx = code.indexOf("executeOnPooledThread", idx + 1)
    }
    return out
}

/**
 * 跨线程 PSI 读令牌：池化块内出现其一，即认为该块在**后台读捕获来的 PSI 元素**
 * （元素 → 所属文件 / 文档 / 区间）。
 */
val psiReadTokens = listOf("containingFile", "viewProvider.document", ".textRange", ".textLength")

/**
 * 两段式校验令牌：池化块内出现其一即视为已走 T-PCP-01 快照校验。
 *
 *  - `PsiSnapshot`——T-PCP-01 工具本体（`PsiSnapshot.capture` / `PsiSnapshot.Validity.Valid` 判定）；
 *  - `validatedRecentAnchor(` / `validatedMethodRows(`——两个 gutter 站点的**校验收口助手**
 *    （`ui/gutter` 包内两个 provider 文件，内部先 `validate()` 再读；新增收口助手须登记进本清单，
 *    或直接在池化块内用 `PsiSnapshot.Validity` 判定）。
 */
val psiValidationTokens = listOf("PsiSnapshot", "validatedRecentAnchor(", "validatedMethodRows(")

/** 豁免记录：[sites] = 提交时该文件的**命中站点数**（漂移即红），[reason] 必填。 */
data class PsiCaptureExemption(val sites: Int, val reason: String)

/**
 * 池化线程内读 PSI 的**豁免登记**（T-PCP-04 落地时实测，仅一处）。
 *
 * 口径：本规则只认「元素级捕获 + 后台读」这一形态（T-PCP-01 的适用面）。豁免条目是
 * **文件级捕获 + 块内重取 PSI**——`PsiFile` 捕获后于块内 `findChildrenOfType` 重新取标签再读行号，
 * 失效面由 `ReadAction` + 平台 reparse 语义承担，不在本轮锚点（计划 §一甲类只列两个 gutter provider）。
 * 将来收编进 `PsiSnapshot` 时须删条目并重新登记站点数。
 */
val psiCaptureExemptions: Map<String, PsiCaptureExemption> = mapOf(
    // 4 处：XML 选区候选 / 全文件语句候选（T-M29.6、T-Perf-14）——池化块内从捕获的 PsiFile 重取 XmlTag 再算行号。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt" to
        PsiCaptureExemption(4, "文件级捕获 + 块内重取 PSI（XmlTag 候选/行号），非元素级快照；计划未含本文件"),
)

/**
 * 池化线程内读 PSI 未走校验的违规点：`路径:行号 → 命中令牌`。
 *
 * 豁免三选一：① 池化块内出现 [psiValidationTokens]（已走两段式校验）；② 调用行上方连续注释块含
 * `psi-capture: <理由>` 标注；③ 文件在 [psiCaptureExemptions] 内。
 *
 * **边界（如实登记，两个方向都有）**：
 *  - **漏报**：PSI 读被挪进池化块**调用的另一个函数**（块内看不见 `containingFile`）即脱离扫描面——
 *    本规则只看池化块本体，与既有的 `invokeLater` 两条规则同口径；`validate()` 自身也可能被
 *    封装进别的助手（此时须把助手登记进 [psiValidationTokens]）。
 *  - **过报**：块内对**块内新解析**的对象读 `textRange`（非捕获元素）也会命中——当前仓内 0 例，
 *    遇到时应把该读挪出块外或加 `psi-capture:` 标注说明其非捕获元素。
 *  - 覆盖的执行原语只有 `executeOnPooledThread`（仓内唯一在用的池化原语，42 处）；
 *    `runAsync`/`supplyAsync`/`ReadAction.nonBlocking` 不在扫描面（仓内 0 处，见计划 §一「非问题」）。
 */
fun psiCaptureOffenders(
    sources: List<Pair<String, String>>,
    skipExemptions: Boolean = true,
): List<String> {
    val offenders = ArrayList<String>()
    for ((path, raw) in sources) {
        if (skipExemptions && psiCaptureExemptions.containsKey(path)) continue
        for (site in pooledThreadSites(maskNonCode(raw))) {
            val read = psiReadTokens.filter { site.body.contains(it) }
            if (read.isEmpty()) continue
            if (psiValidationTokens.any { site.body.contains(it) }) continue
            if (leadingCommentBlock(raw, site.line).contains("psi-capture:")) continue
            offenders.add("$path:${site.line} → ${read.joinToString("/")}")
        }
    }
    return offenders
}

// ------------------------------------------------------------------
// 跳转族读权限约定（T-PsiCaptureProtocol T-PCP-03，2026-09-19）
// ------------------------------------------------------------------

/** 跳转族调用令牌：读平台模型（ModuleManager / LocalFileSystem），调用方须持读权限。 */
val jumpFamilyCallTokens = listOf(
    "SourcePathResolver.resolveVirtualFile(",
    "EntryJumpResolver.resolve(",
    "EntryJumpResolver.resolveCandidates(",
)

/**
 * 跳转族调用点的**读权限白名单**（EDT 直读；新增调用点须显式包 `ReadAction`，否则守卫红）。
 *
 * 本族读 `ModuleManager`/`LocalFileSystem` 平台模型（**不碰 PSI/Document**），故不适用 T-PCP-01
 * 的元素快照——要的是「调用方持读权限」这条约定：EDT 直读合法，后台调用必须自包 `ReadAction`
 * （`log/CallerMethodRangeResolver.kt` 即后者先例，文件内含 `ReadAction` 故自动通过）。
 */
val jumpFamilyReadAllowedFiles = setOf(
    // 族本体：内部转发，读权限由调用方保证（现网两个调用方均在 EDT）
    "src/main/kotlin/cn/song/mybatisplus/visualizer/log/EntryJumpResolver.kt",
    // EDT 右键跳转（平台菜单回调在 EDT）
    "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt",
    "src/main/kotlin/cn/song/mybatisplus/visualizer/log/HistorySqlLogPanel.kt",
    // T-RecentExecMenu O-119：跳转原语共享件——三个调用方（实时页右键、历史页右键、弹框右键菜单）
    // 全是平台菜单/按钮回调（EDT）；本文件只做转接与导航，不自开线程。
    "src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogEntryJumpOps.kt",
)

/** 跳转族调用点缺读权限的违规文件（无 `ReadAction` 且不在白名单）。 */
fun edtReadContractOffenders(sources: List<Pair<String, String>>): List<String> =
    sources
        .filter { (_, code) -> jumpFamilyCallTokens.any { code.contains(it) } }
        .filter { (_, code) -> !code.contains("ReadAction") }
        .map { (path, _) -> path }
        .filter { it !in jumpFamilyReadAllowedFiles }

// ------------------------------------------------------------------
// 项目服务级状态复位（T-StabilityTail T-ST-02d，2026-09-19）
// ------------------------------------------------------------------

/**
 * src/test 源码（**原文**，不剥注释——`// project-state:` 豁免标注要在原文上认；掩码由调用方按需生成）。
 * 本守卫自身排除在外（其合成样本与报错文案含被扫片段）。
 */
fun testSources(): List<Pair<String, String>> =
    srcTestKotlinTree
        .filter { (f, _) -> f.extension == "kt" }
        .filter { (f, _) -> f.name != "BannedApiSourceScanTest.kt" }
        .map { (f, text) -> f.relativeTo(repoRoot()).path.replace('\\', '/') to text }

/**
 * 扫描面保鲜断言：路径解析失败（`src/test/kotlin` 改名 / `user.dir` 异常）时 [testSources] 返回**空列表**，
 * 而规则对空列表恒 0 违规 → 主用例会**静默空绿**（T-ST-02d 独立复核条件项）。空面自证见 `TST02d自证_…`
 * （空列表必须让本断言变红）。
 */
fun assertTestSourcesResolved(sources: List<Pair<String, String>>) {
    assertTrue(
        "扫描面为空——src/test/kotlin 下未解析到任何 .kt（路径改名 / user.dir 异常？），" +
            "空扫描面会让本用例静默空绿：root=${repoRoot().resolve("src/test/kotlin")}",
        sources.isNotEmpty(),
    )
}

/**
 * 初始化入口站点：`setUp` / `@Before` 的**函数体**（含花括号）。
 *
 * [body] 与 [bodyStart] 取自 [maskNonCode] 后的文本（**保行**）：体在掩码文本中的起始下标 + 体内容，
 * 命中下标回推行号即 `code.take(bodyStart + offset)` 的换行数——行号与原文一致，可直接回喂
 * [leadingCommentBlock] 认 `// project-state:` 标注。
 */
data class InitEntrySite(val name: String, val line: Int, val body: String, val bodyStart: Int)

/**
 * 扫出初始化入口站点：①`override fun setUp()`（平台测试基类形态，仓内主流）；
 * ②`@Before` / `@BeforeEach` 标注的函数（纯 JUnit4 类形态，与 `@Before` 注解相距 300 字符内才算）。
 *
 * 定位 `(参数)` 与 `{体}` 都跑在 [maskNonCode] 后的文本上（注释/字符串里的括号不参与配平，
 * 与 [invokeLaterSites] / [pooledThreadSites] 同口径）。
 */
fun initEntrySites(code: String): List<InitEntrySite> =
    entrySites(code, "setUp", Regex("""(?<![A-Za-z0-9_])fun\s+setUp\s*\("""), Regex("""@(?:Before|BeforeEach)\b"""))

/**
 * 收尾入口站点：`override fun tearDown()` / `@After` / `@AfterEach`（T-AgentResiduals R2 的
 * 「收尾窗口不得取服务」规则用）。提取口径与 [initEntrySites] 同源（同一份 [entrySites]），
 * 只换函数名与注解。
 */
fun teardownEntrySites(code: String): List<InitEntrySite> =
    entrySites(code, "tearDown", Regex("""(?<![A-Za-z0-9_])fun\s+tearDown\s*\("""), Regex("""@(?:After|AfterEach)\b"""))

fun entrySites(
    code: String,
    funName: String,
    funRegex: Regex,
    annotationRegex: Regex,
): List<InitEntrySite> {
    val out = ArrayList<InitEntrySite>()
    for (m in funRegex.findAll(code)) {
        val body = functionBody(code, m.range.last) ?: continue
        out.add(InitEntrySite(funName, lineOf(code, body.first), code.substring(body.first, body.last + 1), body.first))
    }
    for (m in annotationRegex.findAll(code)) {
        val funMatch = Regex("""(?<![A-Za-z0-9_])fun\s+([A-Za-z_][A-Za-z0-9_]*)""").find(code, m.range.last + 1)
            ?: continue
        if (funMatch.range.first - m.range.last > 300) continue // 注解与函数相隔太远，不是同一个入口
        val body = functionBody(code, funMatch.range.last + 1) ?: continue
        out.add(
            InitEntrySite(
                funMatch.groupValues[1],
                lineOf(code, body.first),
                code.substring(body.first, body.last + 1),
                body.first,
            ),
        )
    }
    return out
}

/** 1-based 行号（掩码文本保行，与原文一致）。 */
fun lineOf(code: String, index: Int): Int = code.take(index).count { it == '\n' } + 1

/**
 * 从 [from] 起定位 `(参数)` + `{体}`，返回体下标区间；[maxGap] 内找不到或配不平返回 null。
 *
 * 表达式体函数（`@Before fun setUp() = ErrorFingerprint.reset()`，仓内 1 处）**不取体**：
 * 其 `)` 与下一个 `{` 之间隔着 `= …`，若不拦就会把**下一个函数的花括号**当成它的体
 * （扫错区域＝把别人的写记到这个入口名下）。
 */
fun functionBody(code: String, from: Int, maxGap: Int = 400): IntRange? {
    var i = from
    while (i < code.length && i - from <= maxGap && code[i] != '(' && code[i] != '{') i++
    if (i >= code.length || code[i] != '(') return null
    val closeParen = matchingDelimiter(code, i, '(', ')')
    if (closeParen < 0) return null
    var j = closeParen + 1
    while (j < code.length && j - closeParen <= maxGap && code[j] != '{') j++
    if (j >= code.length || code[j] != '{') return null
    val gap = code.substring(closeParen + 1, j)
    if (gap.contains('=') || gap.contains("fun ")) return null
    val close = matchingDelimiter(code, j, '{', '}')
    return if (close >= 0) j..close else null
}

/**
 * 服务查找调用令牌：平台直取 `project.getService(`，以及仓内诊断 wrapper
 * `AlreadyDisposedDiagnostics.projectService(`（T-ST-02c 起仓内测试的**主流**取服务形态——wrapper 的形参是
 * `(project, serviceClass, trigger)`、返回 `T`，与 `getService` 同形承接）。
 *
 * 为什么两个都要认（T-ST-02d 独立复核**阻断 1**）：本轮已修 5 个泄漏类里 `getService(` 命中数为 **0**
 * （`SqlConsoleBridgeFilterProviderTest` / `EpBridgeSinglePathTest` / `AgentListenSyncTest` /
 * `McpHistoryIngestTest` 全走 `projectService(...)`，`SectionConfigurableAutoCaptureLinkageTest` 的 1 处在
 * KDoc 里）——只认 `getService(` 时规则对这些类**整类不可见**，把真泄漏类的 `restore` 行删掉仍绿
 * （复核实证）。两个令牌不互相包含（`projectService` 里没有 `getService` 子串），故各自独立搜索。
 */
val serviceLookupTokens = listOf("getService(", "projectService(")

/**
 * 服务对象绑定名：初始化入口里**直接承接**服务查找返回值的标识符，两种形态都认——
 *  ① `manager = project.getService(SqlLogManager::class.java)` → `manager`；
 *  ② `manager = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.manager")`
 *     → `manager`（wrapper 形态，仓内主流；wrapper 的 trigger 实参在掩码文本里已涂白）。
 *
 * 只认「赋值号与查找调用之间除接收者外没有别的调用」的形态——
 * `snapshot = SqlLogManagerProjectState.capture(project.getService(…))` 承接的是**包装结果**（快照）
 * 而非服务本体，不登记（快照名另由 [snapshotBindings] 登记，用于认 R2 单参 restore）；换行形态的 wrapper
 * （`state = SqlLogManagerProjectState.capture(` 换行后才是 `projectService(…)`）承接行只有空白，
 * 正则天然不匹配——这也是**有意**的（承接的是快照，服务本体无绑定名，靠 [snapshotBindings] 的
 * 「capture 实参含服务查找调用」分支收编）。
 * 直取形态（`project.getService(…).field = x` / `projectService(…).field = x`）另由固定锚点覆盖。
 *
 * 边界（漏报方向）：多行赋值（`val x =` 换行后才是查找调用）不识别；`import` 对象成员后裸调
 * `projectService(` 仍命中（子串匹配，与 import 无关）。
 */
fun serviceBoundNames(body: String): List<String> {
    val names = ArrayList<String>()
    for (token in serviceLookupTokens) {
        var idx = body.indexOf(token)
        while (idx >= 0) {
            val stmtStart = body.lastIndexOfAny(charArrayOf('\n', ';'), idx - 1) + 1
            val head = body.substring(stmtStart, idx)
            val m = Regex("""(?:^|[^A-Za-z0-9_.])([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]*)?=\s*([A-Za-z0-9_.\s]*)$""")
                .find(head)
            if (m != null && m.groupValues[2].trim().endsWith(".")) names.add(m.groupValues[1])
            idx = body.indexOf(token, idx + 1)
        }
    }
    return names.distinct()
}

/**
 * 服务写操作令牌（会改共享实例状态的方法名，正则片段）：`clear()` / `setXxx()` / `startXxx()` /
 * `addXxx()` …。
 *
 * 名单按仓内既有泄漏点的**形态**取：`entries.dedupeEnabled = false` 是赋值形态（另由成员赋值规则管），
 * `setCapturingForTest(false)` / `startCapture()` / `entries.clear()` 是调用形态。名单是**过报方向**
 * 的可调项：业务方法名若与 `addXxx/removeXxx/registerXxx/markXxx` 撞名会被命中，此时加
 * `// project-state: <理由>` 标注放行（见 [projectServiceStateOffenders]）。
 */
val serviceMutatorNamePattern =
    "clear|clearAll|clearForTest|reset|resetForTest|restore|loadState|" +
        "set[A-Z][A-Za-z0-9_]*|start[A-Z][A-Za-z0-9_]*|stop[A-Z][A-Za-z0-9_]*|" +
        "enable[A-Z][A-Za-z0-9_]*|disable[A-Z][A-Za-z0-9_]*|toggle[A-Z][A-Za-z0-9_]*|" +
        "add[A-Z][A-Za-z0-9_]*|remove[A-Z][A-Za-z0-9_]*|put[A-Z][A-Za-z0-9_]*|" +
        "mark[A-Z][A-Za-z0-9_]*|record[A-Z][A-Za-z0-9_]*|register[A-Z][A-Za-z0-9_]*|" +
        "unregister[A-Z][A-Za-z0-9_]*|attach[A-Z][A-Za-z0-9_]*|detach[A-Z][A-Za-z0-9_]*"

/**
 * 服务写操作命中：`List<命中下标（相对 [body]） → 命中片段>`。
 *
 * [anchor] 是「服务表达式」的正则：绑定名形态带 `(?<![A-Za-z0-9_.])` 前置（防 `xmanager.` 误配），
 * 或直取形态 `getService(…).`。两种写形态：①成员赋值（`manager.entries.dedupeEnabled = false`，
 * 以 `(?!=)` 排除 `==`）；②变更类方法调用（`manager.setCapturingForTest(true)` / `manager.entries.clear()`）。
 * 链式成员（`manager.entries.dedupeEnabled`）由中间链片段吞掉——泄漏点常写在 buffer 字段上。
 */
fun serviceWriteHits(body: String, anchor: String): List<Pair<Int, String>> {
    val chain = """(?:[A-Za-z_][A-Za-z0-9_]*\s*\.\s*)*"""
    val out = ArrayList<Pair<Int, String>>()
    for (m in Regex("""$anchor$chain[A-Za-z_][A-Za-z0-9_]*\s*(?:=|\+=|-=)(?!=)""").findAll(body)) {
        out.add(m.range.first to m.value.replace(Regex("\\s+"), " "))
    }
    for (m in Regex("""$anchor$chain(?:$serviceMutatorNamePattern)\s*\(""").findAll(body)) {
        out.add(m.range.first to m.value.replace(Regex("\\s+"), " "))
    }
    return out.distinct()
}

/**
 * 服务**自带**复位口的方法名（**只在与承接名配对时才算复位**，见 [hasBoundServiceReset]）。
 *
 * 名单按「服务对象自身的复位入口」取：`clearForTest` / `resetForTest` / `clearAll` / `loadState`
 * （仓内先例：`RollbackSnapshotStore.clearForTest`、`PluginSettings.loadState`）。名单是过报方向的
 * 可调项——某服务的业务方法若与这些名字撞名且被当成复位口，会**漏报**该类的写，发现时收窄本名单。
 */
val projectStateResetMethodPattern = "clearForTest|clearAll|resetForTest|loadState"

/**
 * 存复原 wrapper（本轮唯一的**全量**复位口）：`SqlLogManagerProjectState.restore(<快照名>)`。
 *
 * **T-AgentResiduals R2 起为单参形态**：`restore(snapshot)`——快照自持服务引用，`tearDown` 不再
 * `project.getService(…)`（那正是 `temporarily-disposed` 窗口发起失败创建、把服务永久毒化、
 * 同片后续取用点成片变红的高发形态，T-ST-02b2 已证）。旧的 `restore(<承接名>, snapshot)` 双参形态
 * **已随 API 删除**，本判据不再认它（认一个已不存在的形态等于给自己留一条永不触发的绿灯）。
 *
 * 刻意**不含** `SqlLogManagerProjectState.capture(`——只有 capture 没有 restore 正是「存了快照忘了还原」
 * 的半成品（capture 只登记快照名，见 [snapshotBindings]；豁免仍必须由 restore 触发）；
 * 也**不含** `SqlLogManager.restoreCaptureStateForTest(`——它只回滚
 * `capturing` / `userArmed` / `waitingForConsole` 三个字段，而 [SqlLogManagerProjectState.capture] 的快照是
 * **16 个字段**（buffer 的 `dedupeEnabled` / `paused` / `filterKeywords` / `maxEntries` / 条目内容 /
 * 轮询态…全不在内），**3/16 的部分复位不足以豁免**对共享实例的写（T-ST-02d 独立复核条件项）。
 * 排除的代价为零：仓内无测试类单独依赖它（唯一直调在 [SqlLogManagerProjectState] 的 restore 体内，
 * 该文件无初始化入口故不在判定面）。只做部分复位而不走 wrapper 全量复位的类会红——补 wrapper
 * 或加 `// project-state: <理由>` 标注。
 * 也**不含** `entries.clear()`——修复前的 `SqlConsoleBridgeFilterProviderTest` tearDown 里正有
 * `clear()`，而泄漏的是 `dedupeEnabled`：清条目不等于复位状态。
 */
val projectStateRestoreWrapper = "SqlLogManagerProjectState.restore"

/** 存快照入口（只用于**绑定快照名**，本身不算复位证据——见 [projectStateRestoreWrapper]）。 */
val projectStateCaptureCall = "SqlLogManagerProjectState.capture"

/**
 * 初始化入口里的「快照名 → capture 实参文本」绑定（R2 单参 restore 的判据依据）。
 *
 * 形态：`<快照名> = SqlLogManagerProjectState.capture(<实参>)`（`val` / `private val` / `var` 均可，
 * 实参可跨行）。实参文本用 [matchingDelimiter] 配平取全，供调用方判定「这个快照是从**共享服务**
 * 捕获的」（实参含承接名或服务查找调用），而不是从别的对象 / 自建实例捕获的。
 *
 * 边界（漏报方向）：赋值目标与 capture 调用之间夹别的东西（如 `a = b = capture(…)`）不识别；
 * 快照名与承接名同名（`manager = capture(manager)`）时两者都登记，判定仍正确。
 */
fun snapshotBindings(body: String): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    var idx = body.indexOf(projectStateCaptureCall)
    while (idx >= 0) {
        val open = idx + projectStateCaptureCall.length
        val close = if (open < body.length && body[open] == '(') matchingDelimiter(body, open, '(', ')') else -1
        if (close >= 0) {
            val stmtStart = body.lastIndexOfAny(charArrayOf('\n', ';'), idx - 1) + 1
            val head = body.substring(stmtStart, idx)
            // 赋值目标紧贴 capture 调用（`val state = ` / `serviceState = ` 两种写法都认）
            val m = Regex("""(?:^|[^A-Za-z0-9_.])([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]*)?=\s*$""").find(head)
            if (m != null) out.add(m.groupValues[1] to body.substring(open, close + 1))
        }
        idx = body.indexOf(projectStateCaptureCall, idx + 1)
    }
    return out
}

/**
 * 复位证据判定（T-ST-02d 独立复核**阻断 2** 的修法 + T-AgentResiduals R2 形态变更）：
 * **绑定到承接服务对象的那个标识符**（或其快照），而非文件级 `contains`。任一形态成立即视为有复位：
 *  ① `<承接名>.<复位口>(…)`——对该服务对象自身的复位调用（[projectStateResetMethodPattern]）；
 *  ② `SqlLogManagerProjectState.restore(<快照名>)`——R2 单参存复原 wrapper，其中
 *     `<快照名>` 须在初始化入口里由 `SqlLogManagerProjectState.capture(<共享服务表达式>)` 绑定
 *     （[snapshotBindings]；实参含承接名或服务查找调用才算「共享服务」）。
 *
 * 为什么必须绑定：旧口径是「文件里出现任一复位令牌即豁免」，于是 `PluginSettings.loadState(snapshot)` /
 * `JoinFkRegistry.clearForTest()` / `PluginCacheManager.clearAll(project)` / `FirstCaptureGuide.resetForTest()`
 * 这类**另一个对象**的复位会把真泄漏静默洗成绿（复核实测四个样本全绿）。绑定后这些调用不再豁免；
 * 同时**不丢覆盖面**——服务自身确实有该复位口时仍认（如 `RollbackSnapshotStore.clearForTest`，
 * 仓内真实形态），且规则对任意服务生效，不限于 `SqlLogManager`。
 *
 * R2 把绑定从 restore 点**前移到 capture 点**（restore 不再带承接名）：`restore(<未绑定名>)` /
 * `restore(<从别处捕获的快照名>)` 都不算证据——自证样本 `TST02d自证_…` 两条负例钉住这一点。
 *
 * 残留边界（漏报方向，如实登记）：按**名**匹配不按实例——同文件两个顶层类用同名标识符时，
 * A 类的复位会豁免 B 类的写（实测：仓内含 >1 顶层声明的测试文件 6 个，但**每个文件里带初始化入口
 * 的顶层类都只有 1 个**（0 个文件存在两个入口类），故当前无实际影响）；
 * 承接名作为**实参**传给别的对象的复位口（`SomeRegistry.clearForTest(manager)`）也按「该对象参与复位」
 * 收编为复位。两种形态都要求承接名在**初始化入口**里被登记过（见 [serviceBoundNames] 的边界）。
 */
fun hasBoundServiceReset(
    code: String,
    boundNames: Collection<String>,
    snapshots: List<Pair<String, String>>,
): Boolean {
    for (name in boundNames) {
        val id = Regex.escape(name)
        if (Regex("""(?<![A-Za-z0-9_.])$id\s*\.\s*(?:$projectStateResetMethodPattern)\s*\(""").containsMatchIn(code)) {
            return true
        }
    }
    for ((snapshotName, captureArg) in snapshots) {
        // 快照必须是从**共享服务**捕获的：实参里出现承接名，或直接是服务查找调用
        val fromSharedService = boundNames.any { captureArg.contains(it) } ||
            serviceLookupTokens.any { captureArg.contains(it) }
        if (!fromSharedService) continue
        if (Regex(
                """(?<![A-Za-z0-9_.])${Regex.escape(projectStateRestoreWrapper)}\s*\(\s*""" +
                    """${Regex.escape(snapshotName)}\s*[,)]""",
            ).containsMatchIn(code)
        ) {
            return true
        }
    }
    return false
}

/**
 * 初始化入口里改**项目服务级**共享状态却无复位证据的违规点：`路径:行号 → 命中片段`。
 *
 * **判据（T-ST-02d）**：某测试类的 `setUp`/`@Before` 体内出现对服务查找
 * （`project.getService(…)` 或 `AlreadyDisposedDiagnostics.projectService(…)`）返回对象的写操作
 * （成员赋值或变更类方法调用），而**类内没有对该承接名的复位调用**（见 [hasBoundServiceReset]）→ 红。
 * 单独成函数以便合成样本自证（规则「看不见某形态」的空绿只能靠合成样本钉住，见 `TST02d自证_…`）。
 *
 * **豁免**：写入行上方连续注释块含 `// project-state: <理由>`（与 `write-safe:` / `psi-capture:` 同族逃生舱）。
 *
 * **覆盖面**：规则对**任意**服务生效（`getService(SqlPreviewService::class.java)` 同样在扫描面），
 * 不只 `SqlLogManager`——本轮审计与报错文案以 SqlLogManager 为例，只因 5 个已修泄漏类都落在它上面。
 *
 * **为什么值得一条守卫**：light 测试项目在同一分片 JVM 内**跨测试类复用同一个 `ProjectImpl`**，
 * 故 `project.getService(SqlLogManager::class.java)` 是**全片共享**单例；本轮人工审计复位了 5 个泄漏类
 * （`testutil/SqlLogManagerProjectState.kt` 存复原 + `SqlLogManager.restoreCaptureStateForTest` 缝），
 * 但人工判定会随代码演进失效——新增测试类即重排分片邻居，泄漏是否发作取决于邻居，红总是
 * 「本类隔离跑绿、分片跑红」。
 *
 * **已知边界**（如实登记，两个方向都有）：
 *  - **漏报｜扫描面只含初始化入口**：写落在测试方法体或 helper 函数里不在扫描面（历史泄漏中
 *    `McpHistoryIngestTest` 的 `recordMcpExecution` 属此形态，靠该类的 capture/restore 兜住）。
 *    收编到「类内任意写」的代价：复位令牌是**类级**判定，靠 `try/finally` 重赋值复位的合法类
 *    会被误判——该形态仓内已有（`AgentListenSyncTest.testisListenActive_跟随监听状态` 的
 *    `finally { logManager.setCapturingForTest(before) }`），只是该类另有 capture/restore 才不红。
 *    故本轮不收编，见 `TST02d自证_…` 的边界样本。
 *  - **漏报｜绑定形态**：多行赋值（`val x =` 换行后才是 `getService(…)` / `projectService(…)`）不识别；
 *    helper 返回（`private fun store() = project.getService(…)`，`RollbackSnapshotStoreTest` /
 *    `RollbackSqlPanelTest` 即此形态）不绑定——两者都靠类内已有的复位口兜住。
 *    换行形态的 wrapper（`state = SqlLogManagerProjectState.capture(` 换行后才是 `projectService(…)`）
 *    不登记为**服务承接名**是有意的（承接的是快照而非服务本体），该形态由 [snapshotBindings] 收编：
 *    capture 实参含服务查找调用即视为「快照来自共享服务」，`restore(<该快照名>)` 算复位
 *    （仓内 3 类正是这一形态，R2 前靠双参 restore 认，R2 后靠快照绑定认）。
 *  - **漏报｜复位判定按名匹配**：复位须落在承接名上（[hasBoundServiceReset]），但按**名**不按实例——
 *    同文件两个顶层类用同名标识符时，A 类的复位会豁免 B 类的写（实测：仓内含 >1 顶层声明的测试文件
 *    6 个，但每个文件里带初始化入口的顶层类都只有 1 个，故当前无实际影响）；承接名作为实参传给
 *    别的对象的复位口（`SomeRegistry.clearForTest(manager)`）也算复位。复位在某分支而泄漏在另一分支的
 *    混合形态同样不在覆盖内（类级判定，换取低误报）。
 *  - **漏报｜部分复位不算证据**：`SqlLogManager.restoreCaptureStateForTest(…)` 只回滚 3/15 字段，
 *    已在 [projectStateRestoreWrapper] 处排除——只做部分复位而不走 wrapper 全量复位的类会红。
 *  - **不判（非误报）**：自建实例（`SqlLogManager(project)` 不进容器）无服务查找承接 → 不绑定；
 *    只读访问（`entries.snapshot()` / `entries.size()` / `manager.capturing`）不命中两种写形态。
 *  - **过报方向**：业务方法名与 [serviceMutatorNamePattern] 撞名且类内确无复位 → 红，此时加
 *    `// project-state: <理由>` 标注。**精度证据只能来自合成样本**（`TST02d自证_…`）——
 *    真实源码上的「0 处」是**空绿**，不构成精度证据，不得拿来论证低误报率。
 */
fun projectServiceStateOffenders(sources: List<Pair<String, String>>): List<String> {
    val offenders = ArrayList<String>()
    for ((path, raw) in sources) {
        val code = maskNonCode(raw)
        val writes = ArrayList<Pair<Int, String>>()
        val boundNames = LinkedHashSet<String>()
        val snapshots = ArrayList<Pair<String, String>>()
        for (site in initEntrySites(code)) {
            val names = serviceBoundNames(site.body)
            boundNames += names
            snapshots += snapshotBindings(site.body)
            val anchors = names.map { """(?<![A-Za-z0-9_.])""" + Regex.escape(it) + """\s*\.\s*""" } +
                listOf(
                    """(?<![A-Za-z0-9_])getService\s*\([^()]*\)\s*\.\s*""",
                    """(?<![A-Za-z0-9_])projectService\s*\([^()]*\)\s*\.\s*""",
                )
            for (anchor in anchors) {
                for ((offset, hit) in serviceWriteHits(site.body, anchor)) {
                    writes.add((site.bodyStart + offset) to hit)
                }
            }
        }
        if (writes.isEmpty()) continue
        if (hasBoundServiceReset(code, boundNames, snapshots)) continue
        for ((offset, hit) in writes) {
            val line = lineOf(code, offset)
            if (leadingCommentBlock(raw, line).contains("project-state:")) continue
            offenders.add("$path:$line → $hit")
        }
    }
    return offenders
}

/**
 * 收尾入口（`tearDown` / `@After` / `@AfterEach`）里**不得发起项目服务查找**：
 * `路径:行号 → 命中片段`。
 *
 * **为什么单列一条（T-AgentResiduals R2，2026-09-22）**：`@After` / `tearDown` 正是「项目
 * temporarily-disposed」窗口的**高发时段**；此刻才首次创建某个订阅 `project.messageBus` 的服务，
 * 平台 `LazyInstanceHolder` 会把 `CannotInitialize` 缓存下来、**该服务在该 ProjectImpl 上永久重抛**
 * （T-ST-02b2 已证：一次失败创建 → 同片后续所有取用点成片变红，`testShards` shard3 flake 的
 * 最后一个来源正是 `SqlLogManagerProjectState.restore` 的**旧双参形态**在 tearDown 里再取一次服务）。
 *
 * 修法固定为「**收尾窗口不触碰服务容器**」：需要在 tearDown 里复原的共享服务，在 setUp 里连**引用**
 * 一起存进快照（`SqlLogManagerProjectState.capture` 现自持 manager 引用、`restore(snapshot)` 单参），
 * 于是 tearDown 全程零 `getService`。
 *
 * **豁免**：查找行上方连续注释块含 `// project-state: <理由>`（与上一条规则同族逃生舱；
 * 例如确需在收尾里探测另一个服务的状态，且该服务在本类 setUp 中已创建——请在标注里写明）。
 *
 * 单独成函数以便合成样本自证（规则「看不见某形态」= 空绿，只能靠合成样本钉住，
 * 见 `R2自证_收尾窗口取服务必红`）。
 */
fun teardownServiceLookupOffenders(sources: List<Pair<String, String>>): List<String> {
    val offenders = ArrayList<String>()
    for ((path, raw) in sources) {
        val code = maskNonCode(raw)
        for (site in teardownEntrySites(code)) {
            for (token in serviceLookupTokens) {
                var idx = site.body.indexOf(token)
                while (idx >= 0) {
                    val line = lineOf(code, site.bodyStart + idx)
                    if (!leadingCommentBlock(raw, line).contains("project-state:")) {
                        offenders.add("$path:$line → ${site.name} 里的 $token")
                    }
                    idx = site.body.indexOf(token, idx + 1)
                }
            }
        }
    }
    return offenders
}

fun repoRoot(): File {
    var dir = File(System.getProperty("user.dir")).absoluteFile
    repeat(6) {
        if (File(dir, "src/main/kotlin").isDirectory) return dir
        dir = dir.parentFile ?: return@repeat
    }
    error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: " + System.getProperty("user.dir"))
}

/** 剥离 block 注释与行注释（字符串字面量中的 // 会被连带截断——只可能产生漏报不产生误报，可接受）。 */
fun stripComments(source: String): String =
    source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lineSequence()
        .joinToString("\n") { it.substringBefore("//") }

// T-TestPerf6：三棵源码树快照——同一次测试 JVM 内文件内容恒定，全部扫描点共享一次
// 遍历+读取（原口径 mainSources 每个断言各付一遍全仓、src/test 三处与 buildSrc 一处内联
// 各付一遍）；各使用处的过滤/豁免/判读口径在使用点原样保留，快照只消除重复文件系统 IO。
val CHAR_BS: Char = '\\'

fun snapshotTree(rel: String): List<Pair<File, String>> =
    File(repoRoot(), rel).walkTopDown()
        .filter { it.isFile }
        .map { it to it.readText() }
        .toList()

val srcMainKotlinTree: List<Pair<File, String>> by lazy { snapshotTree("src/main/kotlin") }
val srcTestKotlinTree: List<Pair<File, String>> by lazy { snapshotTree("src/test/kotlin") }
val buildSrcKotlinTree: List<Pair<File, String>> by lazy { snapshotTree("buildSrc/src/main/kotlin") }

val mainSourcesSnapshot: List<Pair<String, String>> by lazy {
    srcMainKotlinTree
        .filter { (f, _) -> f.extension == "kt" }
        .map { (f, text) -> f.relativeTo(repoRoot()).path.replace('\\', '/') to stripComments(text) }
}
