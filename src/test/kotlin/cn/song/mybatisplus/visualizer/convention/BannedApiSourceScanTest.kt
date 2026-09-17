package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * src/main 禁用 API / 平台约定源码扫描守卫（T-TestGuard-03）。
 *
 * 收录的每条规则都对应一个「编译通过、headless 测试全绿、只有真机/特定发行版才炸」的历史坑，
 * 见 docs/memory-archive/pitfalls/ 与全局平台陷阱清单：
 *  - ActionToolbar 刷新：updateActionsAsync/updateImmediately 旧式 API 沙盒中不可靠仍禁用；
 *    T-UiNative T-UN-11（2026-09-16）起「uiTraverser + 反射 getAction + setIcon」hack 退役，
 *    刷新钦定 ActionToolbar.updateActionsImmediately()（真实 IDE 工具栏可见场景可靠），
 *    动作图标/灰置一律由 AnAction.update() 声明——反射强改按钮回归本规则把关；
 *  - JBIconButton / SmallIconButton / InlineIconButton / IconUtil：IPGP 发行版裁剪平台类
 *    （编译实证不可用，SqlToJavaPanel/IconScaler 注释即当年事故现场），须自建替代；
 *  - `BrowserUtil.browse(`：不走沙盒隔离直接 ShellExecute 拉起宿主机浏览器
 *    （pitfall-test-sandbox-browser-leak.md，定时回归任务期间周期性弹 Edge）——
 *    含该调用的文件必须自带 isUnitTestMode 短路（SqlLogPanel 帮助降级为先例），
 *    新调用一律走 util.SafeBrowser；
 *  - `createFileFromText(`：EDT 直调触发 262 read access 硬抛，必须处于 ReadAction
 *    保护链路（同文件含 ReadAction 约定，PsiFileFactory 陷阱）。
 *
 * 扫描前剥离 // 与 block 注释：历史事故现场常以注释形态留在代码里（JBIconButton 用法
 * 记录、updateActionsAsync 备注等），不剥会误报。
 */
class BannedApiSourceScanTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** 剥离 block 注释与行注释（字符串字面量中的 // 会被连带截断——只可能产生漏报不产生误报，可接受）。 */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    private fun mainSources(): List<Pair<String, String>> =
        repoRoot().resolve("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to stripComments(it.readText()) }
            .toList()

    /** 全量源码中「禁用片段」命中清单：List<文件相对路径>。 */
    private fun hits(banned: String): List<String> =
        mainSources().filter { (_, code) -> code.contains(banned) }.map { (path, _) -> path }

    private fun assertNoHits(banned: String, why: String, fix: String) {
        val found = hits(banned)
        assertTrue(
            "src/main 出现禁用 API `$banned`（$why）。命中 ${found.size} 处：$found。$fix",
            found.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 禁用 API（真机/发行版才炸的坑）
    // ------------------------------------------------------------------

    @Test
    fun `禁用ActionToolbar旧式刷新API与反射强改按钮hack`() {
        // T-UiNative T-UN-11（2026-09-16）方向反转：原规则钦定的「uiTraverser+反射 getAction+
        // setIcon/repaint」路线是沙盒 workaround，随反射 hack 退役改为禁用对象——真实 IDE 中
        // 工具栏可见，原生更新机制可靠；刷新统一走 updateActionsImmediately()。
        for (api in listOf("updateActionsAsync(", "updateImmediately(")) {
            assertNoHits(
                api,
                "旧式刷新 API 沙盒中不可靠，真机表现不可复现",
                "刷新走 ActionToolbar.updateActionsImmediately()，图标/灰置由 AnAction.update() 声明",
            )
        }
        val hack = mainSources()
            .filter { (_, code) -> code.contains("\"ActionButton\"") || code.contains("getMethod(\"getAction\")") }
            .map { (path, _) -> path }
        assertTrue(
            "src/main 出现 ActionToolbar 反射强改按钮 hack（T-UN-11 已退役）：$hack。" +
                "刷新改走 toolbar.updateActionsImmediately()，动作外观由 AnAction.update() 声明",
            hack.isEmpty(),
        )
    }

    @Test
    fun `禁用IPGP发行版裁剪平台类`() {
        // 名单出处：SqlToJavaPanel.kt / IconScaler.kt 注释（编译实证）；发现新裁剪类请追加并留注释
        for (banned in listOf("JBIconButton", "SmallIconButton", "InlineIconButton", "com.intellij.util.ui.IconUtil")) {
            assertNoHits(
                banned,
                "IPGP 发行版被裁剪，编译/运行期不可用",
                "自建替代（参考 IconScaler 的纯 Swing 实现）",
            )
        }
    }

    @Test
    fun `BrowserUtil_browse调用点必须自带isUnitTestMode短路`() {
        val bad = mainSources()
            .filter { (_, code) -> code.contains("BrowserUtil.browse(") }
            .filter { (_, code) -> !code.contains("isUnitTestMode") }
            .map { (path, _) -> path }
        assertTrue(
            "含 BrowserUtil.browse( 的文件缺 isUnitTestMode 短路（测试沙盒会弹宿主机浏览器）：" +
                "$bad。新调用一律改走 util.SafeBrowser",
            bad.isEmpty(),
        )
    }

    @Test
    fun `createFileFromText调用点文件必须含ReadAction保护`() {
        val bad = mainSources()
            .filter { (_, code) -> code.contains("createFileFromText(") }
            .filter { (_, code) -> !code.contains("ReadAction") }
            .map { (path, _) -> path }
        assertTrue(
            "含 createFileFromText( 的文件无 ReadAction 保护（EDT 直调 262 read access 硬抛）：$bad",
            bad.isEmpty(),
        )
    }

    @Test
    fun `方言setMapping反射只允许DialectPreMapping单入口`() {
        // T-ExecSlowOps F1：SqlDialectMappings.setMapping 平台硬要求 EDT（handleMappingChange →
        // reparseFiles → runWriteAction → VcsVFSListener.beforeContentsChange 断言），262 真机
        // 后台预热线程反射直调必炸 "Access is allowed from EDT only"。方言映射唯一入口是
        // DialectPreMapping.ensureMapped（BGT 读探测 + invokeLater 派 EDT），新调用点禁止绕开。
        val allowed = "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DialectPreMapping.kt"
        val bad = mainSources()
            .filter { (_, code) -> code.contains("\"setMapping\"") }
            .map { (path, _) -> path }
            .filter { it != allowed }
        assertTrue(
            "setMapping 反射出现在 $allowed 之外（线程语义收口被绕开，BGT 直调 EDT-only API 必抛）：$bad。" +
                "方言映射统一走 DialectPreMapping.ensureMapped",
            bad.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // write-safe 上下文（T-WriteSafeCtx：2026-09-17 真机 Write-unsafe context 事故）
    // ------------------------------------------------------------------

    /** src/main 原文（**不剥注释**——豁免标注要在原文上认；CRLF/缩进原样保留）。 */
    private fun rawMainSources(): List<Pair<String, String>> =
        repoRoot().resolve("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to it.readText() }
            .toList()

    /**
     * 括号配平取出每个 `invokeLater(` 调用的完整文本（含闭括号）与起始行号。
     *
     * 精确匹配 `invokeLater(`，故 `invokeLaterWriteSafe(`/`invokeLaterOnWriteThread(`（正确形态）
     * 天然不在扫描面内。
     */
    private fun invokeLaterCalls(code: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var idx = code.indexOf("invokeLater(")
        while (idx >= 0) {
            var depth = 0
            var j = idx + "invokeLater".length
            while (j < code.length) {
                if (code[j] == '(') depth++
                else if (code[j] == ')') {
                    depth--
                    if (depth == 0) break
                }
                j++
            }
            if (j < code.length) {
                out.add(code.take(idx).count { it == '\n' } + 1 to code.substring(idx, j + 1))
            }
            idx = code.indexOf("invokeLater(", idx + 1)
        }
        return out
    }

    /** 取调用行**向上连续的注释/空行块**（豁免标注的合法位置：紧贴调用行上方）。 */
    private fun leadingCommentBlock(code: String, callLine: Int): String {
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

    @Test
    fun `禁止在any模态回调里做文档写`() {
        // T-WriteSafeCtx（2026-09-17 真机堆栈：Write-unsafe context! current modality=NON_MODAL，
        // 栈底 SqlPreviewPanel.showSql:1711-1712）。平台契约（243 源码实证）：`ModalityState.nonModal()`
        // 恒 write-safe、`any()` 恒 write-unsafe（TransactionGuard.java:73-76 原文）——posted runnable
        // 不算用户活动（IdeEventQueue.kt:1054），`wrapLaterInvocation` 对非 write-safe 模态走 else 分支
        // （TransactionGuardImpl.java:201）不套 runWithWritingAllowed，于是写文档必触发
        // TransactionGuardImpl:141-145 的 LOG.error（生产 SEVERE + 「Plugin to blame」+ FUS 上报，
        // 单测 logger 直接抛）。
        //
        // 修法三选一：① 写入类回调改 ModalityState.nonModal()；② 幂等重算的入口经 DocWriteContext
        // （runWriteSafe / invokeLaterWriteSafe）自愈投递；③ 确属纯 UI/只读，在调用行上方加
        // `// write-safe: <理由>` 标注（守卫按「调用行向上连续的注释块」识别豁免）。
        val writeTokens = listOf(
            "setText(", "insertString(", "replaceString(", "runWriteCommandAction", "commitDocument(",
            "saveDocument(", "setBinaryContent", "writeVfTextViaDocument(", "docWriteOnce(",
            "dispatchDocWriteOnEdt(", "writeConsoleOutput(", "refreshConsoleModel(",
            "prepareAfterEditorResolved(", "attachConsole(", "prewarmExecTempFile(",
            "prewarmPreviewTempFile(", "syncExecVfContentIfNeeded(", "renderSql(", "showSql(",
            "recoverConsole(", "setResultText(", "createEditor(", "render(", "WriteAction.",
        )
        val offenders = ArrayList<String>()
        for ((path, raw) in rawMainSources()) {
            for ((line, body) in invokeLaterCalls(raw)) {
                if (!body.contains("ModalityState.any()")) continue
                if (leadingCommentBlock(raw, line).contains("write-safe:")) continue
                // 令牌判定只看代码（剥掉 body 内的注释，避免「注释里提到 setText」误报）
                val stripped = stripComments(body)
                val hit = writeTokens.filter { stripped.contains(it) }
                if (hit.isNotEmpty()) offenders.add("$path:$line → ${hit.joinToString("/")}")
            }
        }
        assertTrue(
            "在 ModalityState.any()（恒 write-unsafe）排出的回调里做文档写，命中 ${offenders.size} 处：$offenders。" +
                "改 ModalityState.nonModal()、或经 DocWriteContext 投递、或对纯 UI 站点加 `// write-safe: <理由>` 标注",
            offenders.isEmpty(),
        )
    }
}
