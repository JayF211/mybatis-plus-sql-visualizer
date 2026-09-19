package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
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
 *
 * write-safe 上下文两条规则（T-WriteSafeCtx + T-TS-04 + T-FS-06）：「`ModalityState.any()` 排出的
 * 回调里做文档写」与「默认模态（`defaultModalityState()` 语义）排出的回调里做文档写」——豁免口径一致
 * （调用行上方 `// write-safe: <理由>` 标注，或写体已过 `DocWriteContext` 闸门）。
 * T-FS-06 补两处缺口：①此前默认模态规则要求 `invokeLater` 后**紧跟** `{`、`any()` 规则只扫括号实参区，
 * 于是 `invokeLater({ … }, ModalityState.defaultModalityState())`（显式默认模态 + **括号内** lambda）
 * 两条规则都不覆盖（旧 KDoc 记为「双规则盲区」的 `invokeLater(ModalityState.any()) { … }` 形态
 * **编译不过**——平台 `Application` 只有 `invokeLater(Runnable[, ModalityState[, Condition]])` 一族
 * 重载，不存在 `(ModalityState, Runnable)`，`util-8.jar` javap 实证；故那是一条不可达的「盲区」，
 * 真正可达且被漏掉的是本形态）；现由 [invokeLaterSites] 一次取全「实参区 + 尾随 lambda 体」后各归其规则。②花括号配平此前跑在未剥注释的
 * 原文上：注释/字符串里不成对 `{` 会让 body 越过 lambda 边界（**过报**，把 lambda 外的合法写报成
 * 违规），不成对 `}` 则提前截断（**漏报**）；现跑在 [maskNonCode]（剥注释 + 涂白字符串字面量、
 * **逐行保行**）后的文本上。
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
    fun `测试代码禁直调BrowserUtil_browse统一走SafeBrowser`() {
        // T-BrowserLeak2（2026-09-18）：SafeBrowserTest 直调真实 URL 曾致定时回归每次真开 Edge
        // （守卫「app != null && isUnitTestMode」在无 Application 环境放行）。src/test 同样禁直调，
        // 统一走 util.SafeBrowser——其 fail-safe 守卫在无 Application 环境恒短路。
        val bad = repoRoot().resolve("src/test/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "BannedApiSourceScanTest.kt" } // 本守卫自身的报错文案含该片段，须自豁免
            .filter { stripComments(it.readText()).contains("BrowserUtil.browse(") }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') }
            .toList()
        assertTrue(
            "src/test 出现 BrowserUtil.browse( 直调（$bad）——测试进程触达即弹宿主机浏览器，" +
                "统一改走 util.SafeBrowser",
            bad.isEmpty(),
        )
    }

    @Test
    fun `测试代码禁碰真实系统剪贴板统一走内存假实现`() {
        // T-ClipboardIso（2026-09-18 用户真机反馈）：Windows 剪贴板是全机唯一互斥资源
        // （WClipboard.openClipboard）——用户在跑测试时复制内容、或并行会话/分片 JVM 抢占，
        // 测试里的 systemClipboard 读写即抛 `cannot open system clipboard`（实证：
        // SqlPreviewPanelTest setUp 快照处单类 10 例全红），且测试写入会覆盖用户自己的剪贴板。
        // 故 src/test 禁直连系统剪贴板：一律 installFakeClipboard（testutil）替换平台服务。
        // 唯一豁免 = testutil 假实现本体（不含系统剪贴板访问，仅作类型引用）。
        val bad = repoRoot().resolve("src/test/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "BannedApiSourceScanTest.kt" } // 本守卫自身报错文案含该片段，须自豁免
            .filter { it.name != "FakeClipboard.kt" } // 假实现本体（不含 systemClipboard 访问）
            .filter { it.readText().let { t -> t.contains("systemClipboard") || t.contains("getSystemClipboard") } }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') }
            .toList()
        assertTrue(
            "src/test 出现系统剪贴板直连（$bad）——用户复制/并行会话占用时必抛 cannot open system " +
                "clipboard（整类用例假失败），且会覆盖用户剪贴板；改走 testutil.installFakeClipboard",
            bad.isEmpty(),
        )
    }

    @Test
    fun `SafeBrowser守卫语义_无Application必短路锚点`() {
        // T-BL2-03：守卫语义锚点防回退——isTestEnvironment 必须是「app == null || isUnitTestMode」
        // fail-safe 形态；若有人改回「app != null &&」（白名单放行），无 Application 测试进程会真开浏览器。
        val code = stripComments(
            repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/util/SafeBrowser.kt").readText(),
        )
        assertTrue(
            "SafeBrowser.kt 丢失 fail-safe 守卫形态（app == null || isUnitTestMode）——" +
                "无平台 Application 的测试进程会放行 BrowserUtil 并弹宿主机浏览器（T-BrowserLeak2 实录）",
            code.contains("app == null || app.isUnitTestMode"),
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
        // 只保留「用户必须做选择或知情确认」的场景。白名单 = 现存合法场景快照（T-TestGuard2 实测 3 文件，
        // T-MergeDev2Master 合流后补 2 文件——dev 侧早于本守卫落地的同类别场景），
        // 新增模态弹窗须在此显式加白并在调用点注释场景依据，否则守卫红——拦截「顺手弹个窗」回潮。
        // SourceCaptureGuide：跳转引导三按钮弹框（用户决策点）+ 引导结果 info/warning；
        // SqlPreviewPanel：执行前 YesNo 确认；PluginSettingsPanel：配置异常 warning；
        // LogToSqlPanel：DML 执行前 YesNo 确认（与预览面板同交互，ExecuteConfirmMessages 同款）；
        // McpConfigurable：「前往 MCP Server 设置」目标不可用时 warning（与 PluginSettingsPanel 配置不可用同类）。
        val whitelist = setOf(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SourceCaptureGuide.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogToSqlPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/config/McpConfigurable.kt",
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
    private fun maskNonCode(source: String): String {
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
    private fun maskStringLiteral(source: String, start: Int, out: StringBuilder): Int {
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
    private fun maskCharLiteral(source: String, start: Int, out: StringBuilder): Int {
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
    private data class InvokeLaterSite(
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
    private fun invokeLaterSites(code: String): List<InvokeLaterSite> {
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
    private fun matchingDelimiter(code: String, open: Int, openChar: Char, closeChar: Char): Int {
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
    private fun trailingLambdaBody(code: String, from: Int): String? {
        var i = from
        while (i < code.length && code[i].isWhitespace()) i++
        if (i >= code.length || code[i] != '{') return null
        val close = matchingDelimiter(code, i, '{', '}')
        return if (close >= 0) code.substring(i, close + 1) else null
    }

    /** 闸门令牌：body 内出现即视为写体已过 [cn.song.mybatisplus.visualizer.execute.DocWriteContext] 闸门。 */
    private val writeGateTokens = listOf("DocWriteContext.runWriteSafe", "DocWriteContext.invokeLaterWriteSafe")

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
    private fun invokeLaterWriteOffenders(
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
    private fun anyModalityWriteOffenders(sources: List<Pair<String, String>>): List<String> =
        invokeLaterWriteOffenders(sources) { it.args?.contains("ModalityState.any()") == true }

    /**
     * 默认模态规则——`defaultModalityState()` 语义的两种等价写法：
     *  ① 省略实参的尾随 lambda `invokeLater { … }`（[InvokeLaterSite.args] == null）；
     *  ② 显式实参 `invokeLater({ … }, ModalityState.defaultModalityState())`（T-FS-06 补口，此前漏网）。
     * `nonModal()`（恒 write-safe）与 `any()`（另由 [anyModalityWriteOffenders] 管）都不在此列。
     */
    private fun defaultModalityWriteOffenders(sources: List<Pair<String, String>>): List<String> =
        invokeLaterWriteOffenders(sources) {
            it.args == null || it.args.contains("ModalityState.defaultModalityState()")
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

    /**
     * 文档写入令牌：命中即认为该回调在做文档写（`any()` 规则与尾随 lambda 规则共用同一份名单）。
     *
     * 名单里既有平台 API（`setText(` / `commitDocument(` / `WriteAction.`），也有本仓库自己的
     * 写入收口（`writeVfTextViaDocument(` / `dispatchDocWriteOnEdt(` / `setResultText(` …）——
     * 后者是「一次调用背后就是一次文档写」的语义单位，只看平台 API 会漏掉整条自建写链。
     */
    private val writeTokens = listOf(
        "setText(", "insertString(", "replaceString(", "runWriteCommandAction", "commitDocument(",
        "saveDocument(", "setBinaryContent", "writeVfTextViaDocument(", "docWriteOnce(",
        "dispatchDocWriteOnEdt(", "writeConsoleOutput(", "refreshConsoleModel(",
        "prepareAfterEditorResolved(", "attachConsole(", "prewarmExecTempFile(",
        "prewarmPreviewTempFile(", "syncExecVfContentIfNeeded(", "renderSql(", "showSql(",
        "recoverConsole(", "setResultText(", "createEditor(", "render(", "WriteAction.",
    )

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
        //
        // T-FS-06 缺口一：站点判定改为「实参区含 any()」，写体扫描面 = 实参区 + **尾随 lambda 体**——
        // `invokeLater({ editor.document.setText(…) }, ModalityState.any())` 的写体在实参区。
        val offenders = anyModalityWriteOffenders(rawMainSources())
        assertTrue(
            "在 ModalityState.any()（恒 write-unsafe）排出的回调里做文档写，命中 ${offenders.size} 处：$offenders。" +
                "改 ModalityState.nonModal()、或经 DocWriteContext 投递、或对纯 UI 站点加 `// write-safe: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `尾随lambda写文档守卫自证_合成样本必红`() {
        // T-TS-04：新规则的**负向自证**。旧提取器只认 `invokeLater(`，而线上四处真实站点
        // （SqlInputEditor:142 / SqlPreviewPanel:2486 / SqlToJavaPanel:820,881）全是省略括号、
        // 隐式 defaultModalityState 的尾随 lambda 形态——规则看不见它们等于空绿。
        // 用合成源码钉住三态：违规必报 / 过闸放行 / 带理由标注放行（豁免不得变成「一律放行」）。
        val violating = listOf(
            "src/main/kotlin/x/Bad.kt" to """
                class Bad {
                    fun go() {
                        ApplicationManager.getApplication().invokeLater {
                            editor.document.setText("boom")
                        }
                    }
                }
            """.trimIndent(),
        )
        val found = defaultModalityWriteOffenders(violating)
        assertEquals(
            "尾随 lambda（隐式 defaultModalityState）里写文档必须被新规则命中，实际命中 ${found.size} 处：$found",
            1,
            found.size,
        )

        val gated = listOf(
            "src/main/kotlin/x/Gated.kt" to """
                class Gated {
                    fun go() {
                        app.invokeLater {
                            DocWriteContext.runWriteSafe(project, "reason") { editor.document.setText("ok") }
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "写体已过 DocWriteContext 闸门的站点不得误判（闸门感知豁免）：${defaultModalityWriteOffenders(gated)}",
            0,
            defaultModalityWriteOffenders(gated).size,
        )

        val annotated = listOf(
            "src/main/kotlin/x/Annotated.kt" to """
                class Annotated {
                    fun go() {
                        // write-safe: 用户输入触发的补全前置提交（PSI 同步，不改文档文本）
                        app.invokeLater {
                            PsiDocumentManager.getInstance(project).commitDocument(doc)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 `// write-safe: <理由>` 标注的站点应豁免：${defaultModalityWriteOffenders(annotated)}",
            0,
            defaultModalityWriteOffenders(annotated).size,
        )
    }

    @Test
    fun `禁止在默认模态尾随lambda里做文档写`() {
        // T-TS-04：`invokeLater { … }`（省略模态实参）取 `ModalityState.defaultModalityState()`，
        // 平台侧 `defaultModalityImpl()` 依次取 ProgressManager.currentProgressModality → 线程/协程
        // 上下文 modality → NON_MODAL（DataSourceSelector T-FixWriteSafe 已逐帧实证）：**常态**落
        // NON_MODAL 恒 write-safe，但**在模态进度/带 modality 上下文里**取到该模态——若该模态是
        // 「写不安全进入」的，排队的 runnable 就在该模态事件泵内以 write-unsafe 身份执行，改文档
        // 即 `Write-unsafe context!`。故写入类回调只有两条合规出路：写体过 DocWriteContext 闸门，
        // 或调用行上方带理由的 `// write-safe:` 标注（用户输入活动内创建的拍属后者）。
        //
        // T-FS-06 补口：显式 `invokeLater({ … }, ModalityState.defaultModalityState())` 与省略实参的
        // 尾随 lambda 是**同一语义**（同一份 defaultModalityImpl 取值链），此前只认后者。
        val offenders = defaultModalityWriteOffenders(rawMainSources())
        assertTrue(
            "在默认模态（defaultModalityState）排出的回调里做文档写，命中 ${offenders.size} 处：$offenders。" +
                "写体过 DocWriteContext.runWriteSafe / invokeLaterWriteSafe 闸门，或对纯 UI/用户输入触发的站点" +
                "加 `// write-safe: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `TFS06自证_显式模态实参写法必报且配平不越界`() {
        // ---- 缺口一：显式默认模态 + 括号内 lambda 此前两条规则都不覆盖 ----
        // 旧默认模态规则要求 `invokeLater` 后紧跟 `{`、any() 规则只扫含 any() 的实参区，于是
        // `invokeLater({ … }, ModalityState.defaultModalityState())` 整片漏网（当前仓内 0 处，属预防性
        // 守卫——必须用合成样本把「新规则看得见它」钉住，否则将来落地即静默）。
        // 样本一律用**可编译**的真实写法（平台无 `(ModalityState, Runnable)` 重载，见类 KDoc）。
        val anyArgLambda = listOf(
            "src/main/kotlin/x/ChainedAny.kt" to """
                class ChainedAny {
                    fun go(doc: Document) {
                        ApplicationManager.getApplication().invokeLater({ doc.setText("boom") }, ModalityState.any())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "any() 实参的写文档站点必须被 any() 规则命中（链式前缀 ApplicationManager.getApplication(). " +
                "一并覆盖）：${anyModalityWriteOffenders(anyArgLambda)}",
            1,
            anyModalityWriteOffenders(anyArgLambda).size,
        )
        assertEquals(
            "同一站点归 any() 规则，不得被默认模态规则重复计入：" +
                "${defaultModalityWriteOffenders(anyArgLambda)}",
            0,
            defaultModalityWriteOffenders(anyArgLambda).size,
        )

        val nonModalArg = listOf(
            "src/main/kotlin/x/NonModalArg.kt" to """
                class NonModalArg {
                    fun go(doc: Document) {
                        app.invokeLater({ doc.setText("ok") }, ModalityState.nonModal())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "nonModal() 实参恒 write-safe，两条规则都不得报：" +
                "${anyModalityWriteOffenders(nonModalArg) + defaultModalityWriteOffenders(nonModalArg)}",
            0,
            anyModalityWriteOffenders(nonModalArg).size + defaultModalityWriteOffenders(nonModalArg).size,
        )

        // 显式 defaultModalityState 实参 = 与省略实参的尾随 lambda 同一语义（T-FS-06 顺带补口）
        val explicitDefault = listOf(
            "src/main/kotlin/x/ExplicitDefault.kt" to """
                class ExplicitDefault {
                    fun go(doc: Document) {
                        app.invokeLater({ doc.setText("boom") }, ModalityState.defaultModalityState())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "显式 defaultModalityState 实参 + 尾随 lambda 必须被默认模态规则命中：" +
                "${defaultModalityWriteOffenders(explicitDefault)}",
            1,
            defaultModalityWriteOffenders(explicitDefault).size,
        )

        // ---- 缺口二：配平必须先剥注释/涂白字符串，否则过报（body 越界）或漏报（body 被截断）----
        val unbalancedOpenInComment = listOf(
            "src/main/kotlin/x/UnbalancedOpen.kt" to """
                class UnbalancedOpen {
                    fun go(doc: Document) {
                        app.invokeLater {
                            // 注释里的不成对左花括号 {
                            touchUi()
                        }
                        WriteAction.compute<Unit, Throwable> { doc.setText("outside") }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "lambda 体注释里不成对 `{` 不得让 body 越过 lambda 边界（否则 lambda 外 WriteAction 里的" +
                "合法写被过报——实测原状即红）：${defaultModalityWriteOffenders(unbalancedOpenInComment)}",
            0,
            defaultModalityWriteOffenders(unbalancedOpenInComment).size,
        )

        val unbalancedCloseInComment = listOf(
            "src/main/kotlin/x/UnbalancedClose.kt" to """
                class UnbalancedClose {
                    fun go(doc: Document) {
                        app.invokeLater {
                            // 注释里的不成对右花括号 }
                            doc.setText("boom")
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "注释里不成对 `}` 不得提前截断 body 而漏掉其后的真违规：" +
                "${defaultModalityWriteOffenders(unbalancedCloseInComment)}",
            1,
            defaultModalityWriteOffenders(unbalancedCloseInComment).size,
        )

        val braceInString = listOf(
            "src/main/kotlin/x/BraceInString.kt" to """
                class BraceInString {
                    fun go(doc: Document) {
                        app.invokeLater {
                            val s = "} 字符串里的花括号不算花括号"
                            doc.setText(s)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "字符串字面量里的 `}` 不得提前截断 body（同类错配源，一并涂白）：" +
                "${defaultModalityWriteOffenders(braceInString)}",
            1,
            defaultModalityWriteOffenders(braceInString).size,
        )

        // ---- 字符串模板 ${…} 内嵌字符串（T-FS-06 独立复核阻断项）：掩码器 in-string 状态不得翻转 ----
        // 反例原状：遇首个未转义 `"` 即结束外层字符串 → 正文被当代码、模板内代码被涂白 → 后续站点的
        // 实参区被正文里的 `)` 提前配平到 0（实测 execute/DataSourceSelector.kt:1009 实参区被截断到
        // 9414/13999 字符而不再含 ModalityState.any()、:2684 整条站点被丢弃）。
        //
        // 样本形态（`9f8607b` 复核实测校准）：**模板必须跨行、且其后正文里带一个 `)`** 才能区分修复前后。
        // 单行简单形态（如 `"a${b("c")}d"`）**不能**钉住——掩码输出虽然不同，但误判在同一行内自行
        // 重新同步、括号得失对称，两版判定结果一致（`9f8607b` 提交报文曾误称该形态「修复前必红」，
        // 经复核实测更正）。本形态 pre-fix 实测 any 规则漏报（`[]`）、post-fix 报 1 处。
        val templateNestedString = listOf(
            "src/main/kotlin/x/TemplateNested.kt" to """
                class TemplateNested {
                    fun go(doc: Document) {
                        app.invokeLater({
                            LOG.info("a=${'$'}{
                                f("x")
                            }) b)")
                            doc.setText("z")
                        }, ModalityState.any())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "模板表达式内嵌字符串不得翻转掩码器状态（否则其后带 any() 实参的站点会脱离覆盖）：" +
                "${anyModalityWriteOffenders(templateNestedString)}",
            1,
            anyModalityWriteOffenders(templateNestedString).size,
        )
        assertEquals(
            "该站点归 any() 规则，不得被默认模态规则重复计入：" +
                "${defaultModalityWriteOffenders(templateNestedString)}",
            0,
            defaultModalityWriteOffenders(templateNestedString).size,
        )

        // ---- 工具函数自证：stripComments 不保行 / maskNonCode 保行（行号错位=豁免标注认错行）----
        val multilineBlockComment = "a\n/* x\ny */\nb\nc\n"
        assertEquals(
            "stripComments 把多行 block 注释整段删除（含其中的换行）→ 其后行号前移，不可用于回推行号",
            2,
            stripComments(multilineBlockComment).lines().indexOfFirst { it.trim() == "b" },
        )
        assertEquals(
            "maskNonCode 逐行保行（注释涂白、换行保留）——配平提取的行号才能回喂 leadingCommentBlock",
            3,
            maskNonCode(multilineBlockComment).lines().indexOfFirst { it.trim() == "b" },
        )
        assertEquals(
            "maskNonCode 不增删行（原文 5 行 + 末尾空串 = 6 项）",
            multilineBlockComment.lines().size,
            maskNonCode(multilineBlockComment).lines().size,
        )
    }
}
