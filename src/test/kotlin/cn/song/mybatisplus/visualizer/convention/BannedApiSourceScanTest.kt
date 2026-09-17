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

    // ------------------------------------------------------------------
    // 第二轮守卫（T-TestGuard2，2026-09-17）：0914 后新增坑档的结构断言
    // ------------------------------------------------------------------

    @Test
    fun `模态弹窗只允许白名单内既有场景`() {
        // 弹窗克制红线（docs/开发计划/20260906-0180）+ T-UiNative「模态轻提示改平台通知」定案：
        // 轻提示/进度/结果反馈一律走平台 Notification（无打断、可聚合），模态 Messages/JOptionPane
        // 只保留「用户必须做选择或知情确认」的场景。白名单 = 现存合法场景快照（T-TestGuard2 实测 3 文件），
        // 新增模态弹窗须在此显式加白并在调用点注释场景依据，否则守卫红——拦截「顺手弹个窗」回潮。
        // SourceCaptureGuide：跳转引导三按钮弹框（用户决策点）+ 引导结果 info/warning；
        // SqlPreviewPanel：执行前 YesNo 确认；PluginSettingsPanel：配置异常 warning。
        val whitelist = setOf(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SourceCaptureGuide.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt",
        )
        val found = mainSources()
            .filter { (_, code) ->
                code.contains("Messages.show") || Regex("JOptionPane\\.show\\w+Dialog").containsMatchIn(code)
            }
            .map { (path, _) -> path }
        val extra = found - whitelist
        assertTrue(
            "src/main 出现白名单外的模态弹窗（$extra）。弹窗克制红线：轻提示/进度/结果走平台 Notification，" +
                "确属「用户必须选择/知情确认」场景才可加白本守卫并在调用点注释依据",
            extra.isEmpty(),
        )
    }

    @Test
    fun `buildSrc脚本禁用fileContents的asText读取`() {
        // T-BS-01 实录（pitfall-filecontents-charset）：providers.fileContents().asText 按 JVM
        // 平台默认字符集解码，本机 GBK daemon 把 UTF-8 的 change-notes 读成乱码（集合→闆嗗悎）。
        // 正确形态 = asBytes + toString(Charsets.UTF_8) 显式解码（mpv-release.gradle.kts 先例）。
        val bad = repoRoot().resolve("buildSrc/src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kts" }
            .filter { stripComments(it.readText()).contains(".asText") }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') }
            .toList()
        assertTrue(
            "buildSrc 脚本出现 fileContents().asText（按 daemon 平台字符集解码，GBK 机器读 UTF-8 文件乱码）：$bad。" +
                "改 asBytes().toString(Charsets.UTF_8) 显式解码",
            bad.isEmpty(),
        )
    }

    @Test
    fun `网络出口收口锚点_FeedbackApi须含errorStream与ContentEncoding处理`() {
        // HttpRequests 两坑（全局 pitfall-http-error-body-swallowed）：① readString 只读成功流，
        // 4xx 正文被丢，须自读 errorStream；② myGzip 默认 true 自动协商 gzip，手写读取须按
        // Content-Encoding 解压。两者收口在 FeedbackApi 的 HttpRequestsSender（postJsonWithBody +
        // readBody）。此守卫防收口被拆散/退化——任何一侧锚点消失即红。
        val feedback = stripComments(
            repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/feedback/FeedbackApi.kt").readText(),
        )
        assertTrue(
            "FeedbackApi.kt 丢失 errorStream 读取（4xx 正文将被 readString 丢弃，用户只看到裸状态码）——" +
                "网络出口收口被拆散，恢复 HttpRequestsSender 的错误正文读取",
            feedback.contains("errorStream"),
        )
        assertTrue(
            "FeedbackApi.kt 丢失 gzip 解压处理（myGzip 默认协商 gzip，手写读取不解压得二进制乱码）——" +
                "恢复 readBody 的 contentEncoding 判别 + GZIPInputStream 解压分支",
            feedback.contains("contentEncoding") && feedback.contains("GZIPInputStream"),
        )
        // UpdateApi 走平台 readString（成功流+自动解压，GET manifest 场景定案合法）——
        // 禁手写原始响应流，防止有人绕开收口自造读取。
        val update = stripComments(
            repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/update/UpdateApi.kt").readText(),
        )
        val raw = listOf("getInputStream(", "openStream(").filter { update.contains(it) }
        assertTrue(
            "UpdateApi.kt 出现手写原始响应流读取（$raw）——绕开平台 readString 会同时踩 4xx 正文丢失与 gzip 不解压两坑。" +
                "清单/下载统一走平台 readString/readBytes",
            raw.isEmpty(),
        )
    }

    @Test
    fun `剪贴板写入只允许收口与白名单直用`() {
        // T-UiNative P0：剪贴板统一走 util.UiTextClipboard（进平台复制栈，Ctrl+Shift+V 可回贴），
        // 此前直写 Toolkit.systemClipboard 与 CopyPasteManager 双轨。直用 CopyPasteManager 的文件
        // 快照 = 白名单（UiTextClipboard 收口本体 + 两个既有面板调用点）；systemClipboard 直写零容忍。
        val whitelist = setOf(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/util/UiTextClipboard.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/components/CopyExecutableSqlAction.kt",
        )
        val direct = mainSources()
            .filter { (_, code) -> code.contains("CopyPasteManager") }
            .map { (path, _) -> path }
        val extra = direct - whitelist
        assertTrue(
            "src/main 出现白名单外的 CopyPasteManager 直用（$extra）——剪贴板写入统一走 util.UiTextClipboard.copy，" +
                "确属特殊场景（如编辑器复制栈交互）才可加白本守卫并注释依据",
            extra.isEmpty(),
        )
        assertNoHits(
            "getSystemClipboard",
            "直写系统剪贴板绕开平台复制栈（与 CopyPasteManager 双轨，T-UiNative P0 已收口）",
            "改走 util.UiTextClipboard.copy",
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
