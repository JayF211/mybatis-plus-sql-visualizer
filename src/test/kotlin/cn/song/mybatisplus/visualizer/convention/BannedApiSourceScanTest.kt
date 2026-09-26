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
 * 末尾一条规则（T-StabilityTail T-ST-02d，2026-09-19）是**唯一扫 src/test 的源码结构规则**：
 * 测试类在 `setUp`/`@Before` 里改**项目服务级共享实例**却无**对该承接对象的复位调用** → 红
 * （light 测试项目在同一分片 JVM 内跨类复用同一个 `ProjectImpl`，改动会串台到同片后续类）。
 * 取服务形态认两种——平台直取 `project.getService(…)` 与仓内主流的诊断 wrapper
 * `AlreadyDisposedDiagnostics.projectService(…)`（T-ST-02c 起 5 个已修泄漏类全走后者）；
 * 复位判定绑定到承接该服务的标识符，**别的对象**的同名复位口不再豁免（T-ST-02d 独立复核阻断 1/2）。
 *
 * **T-AgentResiduals R2（2026-09-22）形态变更**：`SqlLogManagerProjectState` 改为「快照持有服务引用、
 * `restore(snapshot)` 单参」——旧双参 `restore(承接名, snapshot)` 已不存在（tearDown 里再取一次服务
 * 正是 `temporarily-disposed` 窗口发起失败创建、永久毒化服务容器的高发形态）。本守卫的复位判据同步
 * 由「承接名作首个实参」改为「**快照名**由 `SqlLogManagerProjectState.capture(<共享服务表达式>)` 绑定，
 * 且该快照名被 `restore(…)` 消费」——绑定前移到 capture 点，鉴别力不降：`restore(<未绑定名>)` 仍红
 * （自证样本 `TST02d自证_…` 已按新形态重写并补该负例）。
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
        // McpConfigurable：「前往 MCP Server 设置」目标不可用时 warning（与 PluginSettingsPanel 配置不可用同类）；
        // SqlSuppressManagerDialog（O-69）：清空全部规则前 YesNo 知情确认（破坏性、不可恢复；
        //   对话框本体非模态 MODELESS，仅此一处确认弹窗，20260924 收口门禁守卫命中后按红线加白）。
        val whitelist = setOf(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SourceCaptureGuide.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogToSqlPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/config/McpConfigurable.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlSuppressManagerDialog.kt",
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
    private fun anyModalityInUpdatePackageOffenders(sources: List<Pair<String, String>>): List<String> {
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
    fun `update包派发平台卸载装载禁用any模态`() {
        // T-DynUpdateWriteSafe（2026-09-22 真机：`Write-unsafe context!` current modality=
        // {[设置对话框, APPLICATION_MODAL], [Unloading of SQL Visualizer]}）。平台契约（262 源码实证）：
        // 模态的写安全资格是「进入那一刻 myWritingAllowed 的快照」，表以模态实例为键
        // （LaterInvocator.enterModal:204-207 → TransactionGuardImpl.enteredModality:199-201）；
        // 平台卸载会压入 ad-hoc 模态（DynamicPluginsLegacyImpl:509），any() 排出的是 posted runnable
        // （IdeEventQueue:1106-1122 只把输入/Item/Focus 事件算用户活动）→ 快照恒 false → 卸载窗口内
        // 平台自身的 commit/落盘/dispose 全部记 SEVERE。
        // 本规则不看写令牌（写发生在平台卸载内部，词法上永远看不见），故按「包 + 令牌」封口：
        // update 包任何形态的 any() 都不该出现（直接实参 / 变量中转 / 决策函数返回，三种形态实测都命中）。
        val offenders = anyModalityInUpdatePackageOffenders(rawMainSources())
        assertTrue(
            "update 包出现 ModalityState.any()（命中 ${offenders.size} 处：$offenders）。" +
                "平台动态卸载会在该模态下压入 Unloading of <plugin>，其写安全资格取派发时刻快照，" +
                "any() 下恒 false（卸载窗口内平台自身模型改动全部记 Write-unsafe context!）。" +
                "派发一律走 DynamicInstallSupport.dispatchModalityForUnload()；" +
                "确属只读跳板请在该行上方加 `// write-safe: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `update包any模态守卫自证_合成样本必红`() {
        // T-DynUpdateWriteSafe：新规则的负向自证（对齐 T-TS-04 惯例）——违规必报 / 带标注放行 /
        // 他包不误伤，三态缺一即「规则看不见真实形态」的空绿。
        val violating = listOf(
            "src/main/kotlin/cn/x/update/Bad.kt" to """
                class Bad {
                    fun go() {
                        ApplicationManager.getApplication().invokeAndWait({ unloadPlugin() }, ModalityState.any())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "update 包内 any() 派发（含 invokeAndWait 形态）必须被命中，实际" +
                " ${anyModalityInUpdatePackageOffenders(violating)}",
            1,
            anyModalityInUpdatePackageOffenders(violating).size,
        )

        val annotated = listOf(
            "src/main/kotlin/cn/x/update/Ok.kt" to """
                class Ok {
                    fun go() {
                        // write-safe: UI-only（只读跳板，无模型写）
                        ApplicationManager.getApplication().invokeAndWait({ readModality() }, ModalityState.any())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 write-safe 标注的只读站点应放行（豁免不得变成一律放行）",
            0,
            anyModalityInUpdatePackageOffenders(annotated).size,
        )

        val otherPackage = listOf(
            "src/main/kotlin/cn/x/ui/Else.kt" to """
                class Else {
                    fun go() {
                        ApplicationManager.getApplication().invokeLater({ refreshUi() }, ModalityState.any())
                    }
                }
            """.trimIndent(),
        )
        assertEquals("规则只封 update 包，他包同形态不误伤", 0, anyModalityInUpdatePackageOffenders(otherPackage).size)
    }

    @Test
    fun `尾随lambda写文档守卫自证_合成样本必红`() {        // T-TS-04：新规则的**负向自证**。旧提取器只认 `invokeLater(`，而线上四处真实站点
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

    // ------------------------------------------------------------------
    // fatal 透传（T-FatalPassthrough，2026-09-19）：catch (…: Throwable) 必须判定或登记豁免
    // ------------------------------------------------------------------

    /**
     * `catch (…: Throwable)` 块：调用行号（1-based，与原文对齐）+ 块体（取自 [maskNonCode] 后的
     * 文本——注释/字符串里的 `isFatal` 字样不算判定）。
     */
    private data class ThrowableCatchSite(val line: Int, val body: String)

    /**
     * 扫出全部 `catch (…: Throwable)` 块，含**嵌套**块（内层块体独立成站点：外层判了 fatal
     * 不代表内层判了）。边界（均为漏报方向，不误报）：
     *  - `catch (Throwable t)`（Java 写法，只出现在注入 agent 的 **Java 模板字符串**里，
     *    `SourceInterceptorTemplates.kt:99`）——被 [maskNonCode] 涂白，不是 Kotlin 站点；
     *  - 多 catch 共体（Kotlin 无 multi-catch 语法）不存在；`catch (e: Exception)` 不是本规则对象。
     */
    private fun throwableCatchSites(code: String): List<ThrowableCatchSite> {
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
    private data class CatchExemption(val sites: Int, val reason: String)

    /** fatal 判定令牌（形态 A）：块体内出现即视为已判。 */
    private val fatalCheckToken = "FatalErrors.isFatal("

    /**
     * 已落地 fatal 透传的文件（T-FP-02 子集）——**不得**出现在 [throwableCatchExemptions]，
     * 且每处 `catch (…: Throwable)` 都必须带判定。
     */
    private val landedFatalPassthroughFiles = setOf(
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.kt",
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt",
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/RunConfigurationProcessListener.kt",
        // T-FP-02b（独立复核条件项）：原豁免理由「可选能力探测失败返回 null（预览主链降级）」把**预览
        // 主链**写成能力探测——属借豁免放走真该收口的站点。已收口（判 fatal + 非 fatal 补 WARN 留痕），
        // 故从豁免表移入本表。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.kt",
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
    private val throwableCatchExemptions: Map<String, CatchExemption> = mapOf(
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
        // 1 处：后台候选收集失败返回空。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlToJavaPanel.kt" to
            CatchExemption(1, "后台候选收集失败返回空（面板提示无候选）"),
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
     * 计划 §八 登记的出处）。共 **43 处 / 17 文件**（T-FP-02b 起 `ui/SqlPreviewService.kt:2100`
     * 已收口移入已落地表）：
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
     * log/SqlToJavaPanel.kt                                           1 处：818
     * log/SqlLogManager.kt                                            1 处：275
     * actions/GenerateCrudSqlFromEntityAction.kt                      1 处：61
     * actions/GenerateCrudSqlFromDbTableAction.kt                     1 处：50
     * actions/ConvertSqlToJavaAction.kt                               1 处：93
     * ```
     *
     * 已落地（**31 处 / 4 文件**）：`execute/DataSourceSelector.kt` 13、`log/agent/AgentManager.kt` 10、
     * `log/RunConfigurationProcessListener.kt` 7、`ui/SqlPreviewService.kt` 1（T-FP-02b 收口）——
     * 合计 74 处（`grep -rn "catch (.*: Throwable)"` 口径）。
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
    private fun fatalPassthroughOffenders(sources: List<Pair<String, String>>): List<String> {
        val offenders = ArrayList<String>()
        for ((path, raw) in sources) {
            if (throwableCatchExemptions.containsKey(path)) continue
            for (site in throwableCatchSites(maskNonCode(raw))) {
                if (!site.body.contains(fatalCheckToken)) offenders.add("$path:${site.line}")
            }
        }
        return offenders
    }

    @Test
    fun `catch_Throwable块必须判fatal或登记豁免`() {
        // T-FatalPassthrough T-FP-03：JVM 级致命错误（VirtualMachineError / LinkageError /
        // ThreadDeath）被 catch (…: Throwable) 吞成一条 warn 后，崩溃现场只剩一行日志、流程带病
        // 继续（第八轮报告 §7.2 Z-21）。本规则要求每个 `catch (…: Throwable)` 块**要么**判 fatal
        // （形态 A：块内 `FatalErrors.isFatal(`，见 util/FatalErrors.kt），**要么**登记豁免理由。
        // 未收口的 43 处（17 文件）豁免见 [throwableCatchExemptions]；已落地的 31 处（4 文件）见
        // [landedFatalPassthroughFiles]，其中任何一处退回无判定即红。
        val sources = rawMainSources()
        val offenders = fatalPassthroughOffenders(sources)
        assertTrue(
            "catch (…: Throwable) 块未判 fatal 且未登记豁免，命中 ${offenders.size} 处：$offenders。" +
                "块内加 `if (FatalErrors.isFatal(t)) throw t`（反射链先解包 InvocationTargetException），" +
                "确属「失败即降级」语义的在 [throwableCatchExemptions] 登记文件 + 站点数 + 理由",
            offenders.isEmpty(),
        )

        // 已落地文件：不得被豁免掩盖，且确实有站点、有判定（防「加白名单把落地缺失藏掉」）
        for (path in landedFatalPassthroughFiles) {
            val code = sources.firstOrNull { it.first == path }?.second
            assertTrue("已落地文件不在扫描面内（路径写错？）：$path", code != null)
            assertTrue("已落地文件不得出现在豁免表中：$path", !throwableCatchExemptions.containsKey(path))
            val sites = throwableCatchSites(maskNonCode(code!!))
            assertTrue("已落地文件应含 catch (…: Throwable) 站点：$path", sites.isNotEmpty())
            assertTrue(
                "已落地文件的站点应全部带 fatal 判定：$path",
                sites.all { it.body.contains(fatalCheckToken) },
            )
        }

        // 反射解包锚点（原生执行链）：Method.invoke 把目标异常（含 Error）包进
        // InvocationTargetException（普通异常），只判外层会漏判——须对解包后的 cause 判定。
        val nativeRunChain = maskNonCode(
            sources.first { it.first.endsWith("execute/DataSourceSelector.kt") }.second,
        )
        val unwrapSites = throwableCatchSites(nativeRunChain).filter { it.body.contains("InvocationTargetException") }
        assertTrue("原生执行链的反射捕获块应存在（含 InvocationTargetException 解包）", unwrapSites.isNotEmpty())
        assertTrue(
            "反射捕获块须对解包后的 cause 判 fatal（`FatalErrors.isFatal(cause)`）——" +
                "只判外层 InvocationTargetException 会漏判目标方法抛出的 fatal",
            unwrapSites.all { it.body.contains("FatalErrors.isFatal(cause)") },
        )

        // 豁免表保鲜：路径必须在扫描面内、站点数与登记一致、理由非空
        for ((path, exemption) in throwableCatchExemptions) {
            val code = sources.firstOrNull { it.first == path }?.second
            assertTrue("豁免表条目指向不存在的源文件（改名/删除后未同步）：$path", code != null)
            val actual = throwableCatchSites(maskNonCode(code!!)).size
            assertEquals(
                "豁免文件的 catch (…: Throwable) 站点数与登记不符（新增/删除捕获点须补判定或重新登记理由）：$path",
                exemption.sites,
                actual,
            )
            assertTrue("豁免理由不得为空：$path", exemption.reason.isNotBlank())
        }
    }

    @Test
    fun `TFP03自证_无判定必红_有判定与豁免放行`() {
        // 负向自证（规则「看不见某形态」= 空绿，只能靠合成样本钉住）：三态 + 边界。
        val violating = listOf(
            "src/main/kotlin/x/NoCheck.kt" to """
                class NoCheck {
                    fun go() {
                        try {
                            risky()
                        } catch (t: Throwable) {
                            LOG.warn("swallowed", t)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "无判定的 catch (…: Throwable) 必须命中：${fatalPassthroughOffenders(violating)}",
            1,
            fatalPassthroughOffenders(violating).size,
        )

        val checked = listOf(
            "src/main/kotlin/x/Checked.kt" to """
                class Checked {
                    fun go() {
                        try {
                            risky()
                        } catch (t: Throwable) {
                            if (FatalErrors.isFatal(t)) throw t
                            LOG.warn("degraded", t)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "已判 fatal 的站点不得误报：${fatalPassthroughOffenders(checked)}",
            0,
            fatalPassthroughOffenders(checked).size,
        )

        // 嵌套块：外层判了不代表内层判了（内层独立成站点）
        val nestedInnerUnchecked = listOf(
            "src/main/kotlin/x/Nested.kt" to """
                class Nested {
                    fun go() {
                        try {
                            risky()
                        } catch (t: Throwable) {
                            if (FatalErrors.isFatal(t)) throw t
                            try {
                                alsoRisky()
                            } catch (e: Throwable) {
                                LOG.warn("inner swallowed", e)
                            }
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "嵌套 catch 的内层块须独立判定（外层判定不得替内层豁免）：${fatalPassthroughOffenders(nestedInnerUnchecked)}",
            1,
            fatalPassthroughOffenders(nestedInnerUnchecked).size,
        )

        // 注释里的判定字样不算判定（令牌只看代码）；注释里的花括号不得截断块体
        val tokenOnlyInComment = listOf(
            "src/main/kotlin/x/CommentOnly.kt" to """
                class CommentOnly {
                    fun go() {
                        try {
                            risky()
                        } catch (t: Throwable) {
                            // FatalErrors.isFatal(t) 只是注释，不算判定 } {
                            LOG.warn("swallowed", t)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "注释里的 isFatal 字样不算判定，且注释里的花括号不得截断块体：${fatalPassthroughOffenders(tokenOnlyInComment)}",
            1,
            fatalPassthroughOffenders(tokenOnlyInComment).size,
        )

        // `catch (…: Exception)` 不是本规则对象（捕获面不含 Error）
        val exceptionOnly = listOf(
            "src/main/kotlin/x/ExceptionOnly.kt" to """
                class ExceptionOnly {
                    fun go() {
                        try {
                            risky()
                        } catch (e: Exception) {
                            LOG.warn("degraded", e)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "catch (…: Exception) 捕获面不含 Error，不属本规则：${fatalPassthroughOffenders(exceptionOnly)}",
            0,
            fatalPassthroughOffenders(exceptionOnly).size,
        )

        // Java 模板字符串里的 `catch (Throwable t)`（注入 agent 的源码模板）被涂白，不是 Kotlin 站点
        val javaTemplateInString = listOf(
            "src/main/kotlin/x/JavaTemplate.kt" to """
                object JavaTemplate {
                    val SOURCE = ""${'"'}
                        try { run(); } catch (Throwable t) { log(t); }
                    ""${'"'}
                }
            """.trimIndent(),
        )
        assertEquals(
            "字符串里的 Java catch 写法不得被当站点：${fatalPassthroughOffenders(javaTemplateInString)}",
            0,
            fatalPassthroughOffenders(javaTemplateInString).size,
        )
    }

    // ------------------------------------------------------------------
    // PSI 跨线程捕获（T-PsiCaptureProtocol T-PCP-04，2026-09-19）
    // ------------------------------------------------------------------

    /** 池化线程站点：`executeOnPooledThread` 的**括号实参区 + 尾随 lambda 体**（提取口径同 [invokeLaterSites]）。 */
    private data class PooledThreadSite(val line: Int, val args: String?, val lambdaBody: String?) {
        /** 扫描面 = 实参区 + 尾随 lambda 体（写体/读体可能在任一侧，只看一侧会漏）。 */
        val body: String = (args ?: "") + (lambdaBody ?: "")
    }

    /** 扫出全部 `executeOnPooledThread` 调用站点；提取跑在 [maskNonCode] 后（配平须避开注释/字符串里的假括号）。 */
    private fun pooledThreadSites(code: String): List<PooledThreadSite> {
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
    private val psiReadTokens = listOf("containingFile", "viewProvider.document", ".textRange", ".textLength")

    /**
     * 两段式校验令牌：池化块内出现其一即视为已走 T-PCP-01 快照校验。
     *
     *  - `PsiSnapshot`——T-PCP-01 工具本体（`PsiSnapshot.capture` / `PsiSnapshot.Validity.Valid` 判定）；
     *  - `validatedRecentAnchor(` / `validatedMethodRows(`——两个 gutter 站点的**校验收口助手**
     *    （`ui/gutter` 包内两个 provider 文件，内部先 `validate()` 再读；新增收口助手须登记进本清单，
     *    或直接在池化块内用 `PsiSnapshot.Validity` 判定）。
     */
    private val psiValidationTokens = listOf("PsiSnapshot", "validatedRecentAnchor(", "validatedMethodRows(")

    /** 豁免记录：[sites] = 提交时该文件的**命中站点数**（漂移即红），[reason] 必填。 */
    private data class PsiCaptureExemption(val sites: Int, val reason: String)

    /**
     * 池化线程内读 PSI 的**豁免登记**（T-PCP-04 落地时实测，仅一处）。
     *
     * 口径：本规则只认「元素级捕获 + 后台读」这一形态（T-PCP-01 的适用面）。豁免条目是
     * **文件级捕获 + 块内重取 PSI**——`PsiFile` 捕获后于块内 `findChildrenOfType` 重新取标签再读行号，
     * 失效面由 `ReadAction` + 平台 reparse 语义承担，不在本轮锚点（计划 §一甲类只列两个 gutter provider）。
     * 将来收编进 `PsiSnapshot` 时须删条目并重新登记站点数。
     */
    private val psiCaptureExemptions: Map<String, PsiCaptureExemption> = mapOf(
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
    private fun psiCaptureOffenders(
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

    @Test
    fun `池化线程内读PSI须走两段式快照校验或登记豁免`() {
        // T-PsiCaptureProtocol T-PCP-04：EDT 捕获 PsiElement → `executeOnPooledThread` 内读该元素
        // （containingFile / viewProvider.document / textRange）这条链，捕获与读取之间的写操作会让元素
        // 失效——只包 ReadAction 只解决「读权限」，不解决「元素是否仍有效」。本规则要求这类读取
        // 走 T-PCP-01 的 PsiSnapshot 校验（或 `psi-capture:` 标注 / 豁免登记）。
        // 落地形态：两个 gutter provider 各抽一个校验收口助手（`validatedRecentAnchor` /
        // `validatedMethodRows`，池化块只调它），`actions/ConvertToSqlAction.kt` 4 处登记豁免。
        val sources = rawMainSources()
        val offenders = psiCaptureOffenders(sources)
        assertTrue(
            "池化线程内读捕获的 PSI 却无快照校验，命中 ${offenders.size} 处：$offenders。" +
                "改走 PsiSnapshot.capture（EDT 侧）+ validate()（后台侧），或对确属「文件级捕获 + 块内重取」" +
                "的站点在 [psiCaptureExemptions] 登记文件 + 站点数 + 理由，或加 `// psi-capture: <理由>` 标注",
            offenders.isEmpty(),
        )

        // 豁免表保鲜：路径在扫描面内、命中站点数与登记一致、理由非空
        for ((path, exemption) in psiCaptureExemptions) {
            val source = sources.firstOrNull { it.first == path }
            assertTrue("豁免表条目指向不存在的源文件（改名/删除后未同步）：$path", source != null)
            val actual = psiCaptureOffenders(listOf(source!!), skipExemptions = false).size
            assertEquals(
                "豁免文件的命中站点数与登记不符（新增/删除跨线程 PSI 读须补校验或重新登记理由）：$path",
                exemption.sites,
                actual,
            )
            assertTrue("豁免理由不得为空：$path", exemption.reason.isNotBlank())
        }
    }

    @Test
    fun `TPCP04自证_池化块读PSI无校验必红_三种放行形态`() {
        // 负向自证（规则「看不见某形态」= 空绿，只能靠合成样本钉住）：违规必报 + 校验/标注/豁免三态放行。
        val violating = listOf(
            "src/main/kotlin/x/BgRead.kt" to """
                class BgRead {
                    fun go(elt: PsiElement) {
                        app.executeOnPooledThread {
                            ReadAction.compute<Int, Throwable> { elt.containingFile.textRange.startOffset }
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "池化块内读捕获元素的 PSI 属性必须命中：${psiCaptureOffenders(violating)}",
            1,
            psiCaptureOffenders(violating).size,
        )

        val validated = listOf(
            "src/main/kotlin/x/Validated.kt" to """
                class Validated {
                    fun go(elt: PsiElement, project: Project, snapshot: PsiSnapshot?) {
                        app.executeOnPooledThread {
                            val anchor = ReadAction.compute<Pair<String, Int>?, Throwable> { validatedRecentAnchor(project.basePath, snapshot) }
                            log(anchor.toString() + elt.containingFile.name)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "已走校验收口助手的站点不得误报：${psiCaptureOffenders(validated)}",
            0,
            psiCaptureOffenders(validated).size,
        )

        val inlineSnapshotCheck = listOf(
            "src/main/kotlin/x/InlineCheck.kt" to """
                class InlineCheck {
                    fun go(elt: PsiElement, snapshot: PsiSnapshot?) {
                        app.executeOnPooledThread {
                            val valid = snapshot != null && snapshot.validate() is PsiSnapshot.Validity.Valid
                            if (valid) elt.containingFile.textRange.startOffset else 0
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "块内直接用 PsiSnapshot 判定的站点不得误报：${psiCaptureOffenders(inlineSnapshotCheck)}",
            0,
            psiCaptureOffenders(inlineSnapshotCheck).size,
        )

        val annotated = listOf(
            "src/main/kotlin/x/Annotated.kt" to """
                class Annotated {
                    fun go(elt: PsiElement) {
                        // psi-capture: 该元素在块内重新解析（非 EDT 捕获），失效面由平台 reparse 承担
                        app.executeOnPooledThread {
                            elt.containingFile.textRange.startOffset
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 `// psi-capture: <理由>` 标注的站点应豁免：${psiCaptureOffenders(annotated)}",
            0,
            psiCaptureOffenders(annotated).size,
        )

        // 豁免表生效（用真实豁免路径喂合成样本，证明表被真正查过而不是白名单空转）
        val exemptedPath = "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt"
        val exempted = listOf(
            exemptedPath to """
                class Exempted {
                    fun go(tag: XmlTag) {
                        app.executeOnPooledThread {
                            tag.containingFile.viewProvider.document?.getLineNumber(tag.textRange.startOffset)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "豁免路径的命中站点须被计数（否则豁免表空转、漂移检测失效）：" +
                "${psiCaptureOffenders(exempted, skipExemptions = false)}",
            1,
            psiCaptureOffenders(exempted, skipExemptions = false).size,
        )
        assertEquals(
            "豁免表内的路径不得报违规：${psiCaptureOffenders(exempted)}",
            0,
            psiCaptureOffenders(exempted).size,
        )

        // 池化块内不读 PSI（纯数据/纯 IO）不得命中
        val noPsiRead = listOf(
            "src/main/kotlin/x/NoPsi.kt" to """
                class NoPsi {
                    fun go(path: String) {
                        app.executeOnPooledThread {
                            val text = java.io.File(path).readText()
                            log(text)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "池化块内无 PSI 读不得命中：${psiCaptureOffenders(noPsiRead)}",
            0,
            psiCaptureOffenders(noPsiRead).size,
        )

        // 工具函数自证：池化站点提取覆盖「尾随 lambda」与「括号实参 + 尾随 lambda」两种写法
        val sites = pooledThreadSites(maskNonCode(violating[0].second))
        assertEquals("executeOnPooledThread 尾随 lambda 形态应被提取", 1, sites.size)
        assertEquals("提取行号须与原文对齐（保行掩码）", 3, sites[0].line)
    }

    // ------------------------------------------------------------------
    // 跳转族读权限约定（T-PsiCaptureProtocol T-PCP-03，2026-09-19）
    // ------------------------------------------------------------------

    /** 跳转族调用令牌：读平台模型（ModuleManager / LocalFileSystem），调用方须持读权限。 */
    private val jumpFamilyCallTokens = listOf(
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
    private val jumpFamilyReadAllowedFiles = setOf(
        // 族本体：内部转发，读权限由调用方保证（现网两个调用方均在 EDT）
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/EntryJumpResolver.kt",
        // EDT 右键跳转（平台菜单回调在 EDT）
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt",
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/HistorySqlLogPanel.kt",
    )

    /** 跳转族调用点缺读权限的违规文件（无 `ReadAction` 且不在白名单）。 */
    private fun edtReadContractOffenders(sources: List<Pair<String, String>>): List<String> =
        sources
            .filter { (_, code) -> jumpFamilyCallTokens.any { code.contains(it) } }
            .filter { (_, code) -> !code.contains("ReadAction") }
            .map { (path, _) -> path }
            .filter { it !in jumpFamilyReadAllowedFiles }

    @Test
    fun `跳转族调用点须持读权限_后台调用须包ReadAction`() {
        // T-PCP-03：`EntryJumpResolver`/`SourcePathResolver` 一族的 KDoc 约定（「调用方保证持有读权限」）
        // 原先无工具保障——一旦被后台线程裸调，读 ModuleManager 的违约只在真机偶发。本规则把约定固化为
        // 「文件内出现 ReadAction（自包权限）或在 EDT 调用者白名单内」；配套运行期硬断言见下一段。
        val offenders = edtReadContractOffenders(mainSources())
        assertTrue(
            "跳转族调用点缺读权限（文件内无 ReadAction 且不在 EDT 白名单），命中 ${offenders.size} 处：$offenders。" +
                "后台调用须显式包 ReadAction，EDT 调用点加入 [jumpFamilyReadAllowedFiles] 并注明依据",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `TEDTRC自证_跳转族调用点无ReadAction必红_白名单与自包权限放行`() {
        // 独立复核㉗：`edtReadContractOffenders` 此前**只有主用例直接引用、无合成样本**（被元守卫登记为
        // 「受机器保护的豁免」——缺口因此不可见）。此处关闭该缺口：三态各归其位。
        val violating = listOf(
            "src/main/kotlin/cn/x/BadJump.kt" to """
                class BadJump {
                    fun go() {
                        SourcePathResolver.resolveVirtualFile(project, module)
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "文件内无 ReadAction 的跳转族调用点必须被命中：${edtReadContractOffenders(violating)}",
            listOf("src/main/kotlin/cn/x/BadJump.kt"),
            edtReadContractOffenders(violating),
        )

        val wrapped = listOf(
            "src/main/kotlin/cn/x/WrappedJump.kt" to """
                class WrappedJump {
                    fun go() {
                        ReadAction.compute<Unit, Throwable> {
                            EntryJumpResolver.resolve(project, element)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertTrue(
            "自包 ReadAction 的调用点应放行：${edtReadContractOffenders(wrapped)}",
            edtReadContractOffenders(wrapped).isEmpty(),
        )

        val whitelisted = listOf(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt" to """
                class SqlLogPanel {
                    fun go() {
                        EntryJumpResolver.resolveCandidates(project, element)
                    }
                }
            """.trimIndent(),
        )
        assertTrue(
            "EDT 白名单文件应放行（白名单不得失效，否则主用例会因过报而红）：${edtReadContractOffenders(whitelisted)}",
            edtReadContractOffenders(whitelisted).isEmpty(),
        )
    }

    @Test
    fun `跳转族读权限运行期断言锚点`() {
        // 运行期硬断言锚点（T-PCP-03）：`SourcePathResolver.resolveVirtualFile` 入口必须保留
        // `assertReadAccessAllowed()`——源码级规则只管「调用点有没有 ReadAction 字样」，
        // 断言把违约暴露在实际调用链上（后台裸调即抛，不静默读模型）。
        val code = stripComments(
            repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/log/EntrySourceMatcher.kt").readText(),
        )
        assertTrue(
            "SourcePathResolver.resolveVirtualFile 丢失 assertReadAccessAllowed() 入口断言——" +
                "跳转族的读权限约定退回「只写在 KDoc 里」的靠约定状态（T-PCP-03）",
            code.contains("assertReadAccessAllowed()"),
        )
    }

    // ------------------------------------------------------------------
    // 项目服务级状态复位（T-StabilityTail T-ST-02d，2026-09-19）
    // ------------------------------------------------------------------

    /**
     * src/test 源码（**原文**，不剥注释——`// project-state:` 豁免标注要在原文上认；掩码由调用方按需生成）。
     * 本守卫自身排除在外（其合成样本与报错文案含被扫片段）。
     */
    private fun testSources(): List<Pair<String, String>> =
        repoRoot().resolve("src/test/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "BannedApiSourceScanTest.kt" }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to it.readText() }
            .toList()

    /**
     * 扫描面保鲜断言：路径解析失败（`src/test/kotlin` 改名 / `user.dir` 异常）时 [testSources] 返回**空列表**，
     * 而规则对空列表恒 0 违规 → 主用例会**静默空绿**（T-ST-02d 独立复核条件项）。空面自证见 `TST02d自证_…`
     * （空列表必须让本断言变红）。
     */
    private fun assertTestSourcesResolved(sources: List<Pair<String, String>>) {
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
    private data class InitEntrySite(val name: String, val line: Int, val body: String, val bodyStart: Int)

    /**
     * 扫出初始化入口站点：①`override fun setUp()`（平台测试基类形态，仓内主流）；
     * ②`@Before` / `@BeforeEach` 标注的函数（纯 JUnit4 类形态，与 `@Before` 注解相距 300 字符内才算）。
     *
     * 定位 `(参数)` 与 `{体}` 都跑在 [maskNonCode] 后的文本上（注释/字符串里的括号不参与配平，
     * 与 [invokeLaterSites] / [pooledThreadSites] 同口径）。
     */
    private fun initEntrySites(code: String): List<InitEntrySite> =
        entrySites(code, "setUp", Regex("""(?<![A-Za-z0-9_])fun\s+setUp\s*\("""), Regex("""@(?:Before|BeforeEach)\b"""))

    /**
     * 收尾入口站点：`override fun tearDown()` / `@After` / `@AfterEach`（T-AgentResiduals R2 的
     * 「收尾窗口不得取服务」规则用）。提取口径与 [initEntrySites] 同源（同一份 [entrySites]），
     * 只换函数名与注解。
     */
    private fun teardownEntrySites(code: String): List<InitEntrySite> =
        entrySites(code, "tearDown", Regex("""(?<![A-Za-z0-9_])fun\s+tearDown\s*\("""), Regex("""@(?:After|AfterEach)\b"""))

    private fun entrySites(
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
    private fun lineOf(code: String, index: Int): Int = code.take(index).count { it == '\n' } + 1

    /**
     * 从 [from] 起定位 `(参数)` + `{体}`，返回体下标区间；[maxGap] 内找不到或配不平返回 null。
     *
     * 表达式体函数（`@Before fun setUp() = ErrorFingerprint.reset()`，仓内 1 处）**不取体**：
     * 其 `)` 与下一个 `{` 之间隔着 `= …`，若不拦就会把**下一个函数的花括号**当成它的体
     * （扫错区域＝把别人的写记到这个入口名下）。
     */
    private fun functionBody(code: String, from: Int, maxGap: Int = 400): IntRange? {
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
    private val serviceLookupTokens = listOf("getService(", "projectService(")

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
    private fun serviceBoundNames(body: String): List<String> {
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
    private val serviceMutatorNamePattern =
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
    private fun serviceWriteHits(body: String, anchor: String): List<Pair<Int, String>> {
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
    private val projectStateResetMethodPattern = "clearForTest|clearAll|resetForTest|loadState"

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
    private val projectStateRestoreWrapper = "SqlLogManagerProjectState.restore"

    /** 存快照入口（只用于**绑定快照名**，本身不算复位证据——见 [projectStateRestoreWrapper]）。 */
    private val projectStateCaptureCall = "SqlLogManagerProjectState.capture"

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
    private fun snapshotBindings(body: String): List<Pair<String, String>> {
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
    private fun hasBoundServiceReset(
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
    private fun projectServiceStateOffenders(sources: List<Pair<String, String>>): List<String> {
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

    @Test
    fun `初始化入口改项目服务级状态须复位`() {
        // T-StabilityTail T-ST-02d：把「改共享服务状态须复位」这条人工审计结论固化为源码扫描守卫。
        // 判据与误报边界见 [projectServiceStateOffenders] 的 KDoc。
        val sources = testSources()
        // 路径解析失败 = 空扫描面 = 规则恒 0 违规的**空绿**（自证见 TST02d自证_… 的空面样本）
        assertTestSourcesResolved(sources)
        val offenders = projectServiceStateOffenders(sources)
        assertTrue(
            "测试类在 setUp/@Before 里改了项目服务级共享状态（project.getService(…) 或 " +
                "AlreadyDisposedDiagnostics.projectService(…) 返回对象）却无**对该服务对象的复位调用**，" +
                "命中 ${offenders.size} 处：$offenders。light 测试项目在同一分片 JVM 内跨测试类复用同一个 " +
                "ProjectImpl，这类改动会串台到同片后续测试类（发作与否取决于分片邻居，新增测试类即重排）。" +
                "复位走 testutil.SqlLogManagerProjectState.capture（setUp）+ restore（tearDown）、" +
                "或服务自带的 clearForTest/resetForTest/loadState 等复位口（须调在承接该服务的标识符上）；" +
                "确属幂等安全或非共享实例的写，在写入行上方加 `// project-state: <理由>` 标注",
            offenders.isEmpty(),
        )
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
    private fun teardownServiceLookupOffenders(sources: List<Pair<String, String>>): List<String> {
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

    @Test
    fun `收尾窗口不得取项目服务`() {
        // T-AgentResiduals R2：见 [teardownServiceLookupOffenders] 的 KDoc。
        val sources = testSources()
        assertTestSourcesResolved(sources)
        val offenders = teardownServiceLookupOffenders(sources)
        assertTrue(
            "tearDown/@After 里出现项目服务查找，命中 ${offenders.size} 处：$offenders。" +
                "收尾正是「项目 temporarily-disposed」窗口的高发时段，此刻首次创建订阅 project.messageBus 的" +
                "服务会让平台把 CannotInitialize 缓存下来、该服务在本项目实例上永久重抛（同片后续用例成片变红）。" +
                "需要复原的共享服务请在 setUp 里连**引用**一起存（SqlLogManagerProjectState.capture 自持 manager " +
                "引用，restore(snapshot) 单参），tearDown 全程不碰服务容器；确需收尾探测的加 " +
                "`// project-state: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `R2自证_收尾窗口取服务必红_存快照形态放行`() {
        // 负向自证：违规必报 + 三类合法形态放行 + 豁免标注只豁免被标注的那一行。
        val violating = listOf(
            "src/test/kotlin/x/TearDownLookupSampleTest.kt" to """
                class TearDownLookupSampleTest : BasePlatformTestCase() {
                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(
                            AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "tearDown.restore"),
                            null,
                        )
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        val found = teardownServiceLookupOffenders(violating)
        assertEquals("tearDown 里取项目服务必须命中：$found", 1, found.size)
        assertTrue(
            "命中点须锚在**取服务那一行**（行号靠保行掩码回推）：$found",
            found[0].endsWith("TearDownLookupSampleTest.kt:4 → tearDown 里的 projectService("),
        )

        // 合法形态 ①：R2 后的单参 restore（快照自持引用）——tearDown 零服务查找
        val restored = listOf(
            "src/test/kotlin/x/TearDownSnapshotSampleTest.kt" to """
                class TearDownSnapshotSampleTest : BasePlatformTestCase() {
                    private var state: SqlLogManagerProjectState.Snapshot? = null

                    override fun setUp() {
                        super.setUp()
                        state = SqlLogManagerProjectState.capture(project.getService(SqlLogManager::class.java))
                    }

                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(state)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "R2 形态（快照持有引用、tearDown 只 restore）不得误报：${teardownServiceLookupOffenders(restored)}",
            0,
            teardownServiceLookupOffenders(restored).size,
        )

        // 合法形态 ②：@After 形态同样在扫描面内（且它才是不经平台基类的纯 JUnit 类收尾口）
        val afterForm = listOf(
            "src/test/kotlin/x/AfterSampleTest.kt" to """
                class AfterSampleTest {
                    @After
                    fun cleanup() {
                        JoinFkRegistry.clearForTest()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "@After 里不取服务不得误报：${teardownServiceLookupOffenders(afterForm)}",
            0,
            teardownServiceLookupOffenders(afterForm).size,
        )

        // 扫描面自证：@After 形态确实被提取（否则上面那个 0 是「入口没被识别」的空绿）
        assertEquals(
            "@After 入口应被提取：${teardownEntrySites(maskNonCode(afterForm[0].second))}",
            1,
            teardownEntrySites(maskNonCode(afterForm[0].second)).size,
        )

        // 豁免标注：只豁免被标注的那一行
        val annotated = listOf(
            "src/test/kotlin/x/AnnotatedTearDownSampleTest.kt" to """
                class AnnotatedTearDownSampleTest : BasePlatformTestCase() {
                    override fun tearDown() {
                        // project-state: 本类 setUp 已创建该服务，此处只读探测（不发起创建）
                        val m = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "tearDown.probe")
                        m.setCapturingForTest(false)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 `// project-state: <理由>` 标注的取服务应豁免：${teardownServiceLookupOffenders(annotated)}",
            0,
            teardownServiceLookupOffenders(annotated).size,
        )

        // 空扫描面自证：路径解析失败 = 空绿，主用例的非空断言必须能拦住
        assertEquals("空扫描面下规则恒 0 违规", 0, teardownServiceLookupOffenders(emptyList()).size)
    }

    @Test
    fun `TST02d自证_初始化入口写共享服务不复位必红_边界态放行`() {
        // 负向自证（规则「看不见某形态」= 空绿，只能靠合成样本钉住）：违规必报 + 复位/标注/自建实例/
        // 只读四类放行 + 两条**漏报边界**如实钉住（方法体内写、capture-only）。
        val violating = listOf(
            "src/test/kotlin/x/LeakySampleTest.kt" to """
                class LeakySampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager

                    override fun setUp() {
                        super.setUp()
                        manager = project.getService(SqlLogManager::class.java)
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        val found = projectServiceStateOffenders(violating)
        assertEquals(
            "setUp 里改项目服务级状态且类内无复位调用必须命中：$found",
            1,
            found.size,
        )
        assertTrue(
            "命中点须锚在**写入那一行**（行号靠保行掩码回推，不得落在绑定行或入口行）：$found",
            found[0].endsWith("LeakySampleTest.kt:7 → manager.entries.dedupeEnabled ="),
        )

        // 变更类方法调用形态（既有泄漏点里 `setCapturingForTest` / `entries.clear()` 即此形态）
        val violatingCalls = listOf(
            "src/test/kotlin/x/LeakyCallSampleTest.kt" to """
                class LeakyCallSampleTest : BasePlatformTestCase() {
                    override fun setUp() {
                        super.setUp()
                        val manager = project.getService(SqlLogManager::class.java)
                        manager.setCapturingForTest(true)
                        manager.entries.clear()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "变更类方法调用（setXxx / clear）同样是写共享状态：${projectServiceStateOffenders(violatingCalls)}",
            2,
            projectServiceStateOffenders(violatingCalls).size,
        )

        // 复位调用（testutil 存复原，R2 单参形态：快照自持服务引用）→ 放行
        val restored = listOf(
            "src/test/kotlin/x/RestoredSampleTest.kt" to """
                class RestoredSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager
                    private var state: SqlLogManagerProjectState.Snapshot? = null

                    override fun setUp() {
                        super.setUp()
                        manager = project.getService(SqlLogManager::class.java)
                        state = SqlLogManagerProjectState.capture(manager)
                        manager.entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(state)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "类内有 capture/restore 存复原的写不得误报：${projectServiceStateOffenders(restored)}",
            0,
            projectServiceStateOffenders(restored).size,
        )

        // R2 形态的鉴别力（负例 1）：restore 的快照名**未在初始化入口由 capture 绑定** → 不算复位证据
        val unboundSnapshotRestore = listOf(
            "src/test/kotlin/x/UnboundSnapshotSampleTest.kt" to """
                class UnboundSnapshotSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager

                    override fun setUp() {
                        super.setUp()
                        manager = project.getService(SqlLogManager::class.java)
                        manager.entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(state)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "restore(<未由 capture 绑定的快照名>) 不得算复位证据（否则认令牌就等于一律放行）：" +
                "${projectServiceStateOffenders(unboundSnapshotRestore)}",
            1,
            projectServiceStateOffenders(unboundSnapshotRestore).size,
        )

        // R2 形态的鉴别力（负例 2）：快照是从**别的对象**（自建实例，非容器服务）捕获的 → 不算复位证据
        val snapshotFromOtherObject = listOf(
            "src/test/kotlin/x/SnapshotFromOtherSampleTest.kt" to """
                class SnapshotFromOtherSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager
                    private var state: SqlLogManagerProjectState.Snapshot? = null

                    override fun setUp() {
                        super.setUp()
                        manager = project.getService(SqlLogManager::class.java)
                        state = SqlLogManagerProjectState.capture(SqlLogManager(project))
                        manager.entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(state)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "快照从自建实例（SqlLogManager(project)，不进容器）捕获时不得豁免对共享服务的写：" +
                "${projectServiceStateOffenders(snapshotFromOtherObject)}",
            1,
            projectServiceStateOffenders(snapshotFromOtherObject).size,
        )

        // 自建实例（不进容器）→ 不判为泄漏
        val selfBuilt = listOf(
            "src/test/kotlin/x/SelfBuiltSampleTest.kt" to """
                class SelfBuiltSampleTest : BasePlatformTestCase() {
                    override fun setUp() {
                        super.setUp()
                        val manager = SqlLogManager(project)
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "自建实例（SqlLogManager(project) 不进容器）不是共享状态，不得误报：${projectServiceStateOffenders(selfBuilt)}",
            0,
            projectServiceStateOffenders(selfBuilt).size,
        )

        // 只读访问（快照 / size / 属性读）→ 不判
        val readOnly = listOf(
            "src/test/kotlin/x/ReadOnlySampleTest.kt" to """
                class ReadOnlySampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager
                    private var before = 0

                    override fun setUp() {
                        super.setUp()
                        manager = project.getService(SqlLogManager::class.java)
                        before = manager.entries.snapshot().size
                        assertEquals(0, manager.entries.size())
                        assertFalse(manager.isWaitingForConsole)
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "只读访问（snapshot/size/属性读）不得误判为写：${projectServiceStateOffenders(readOnly)}",
            0,
            projectServiceStateOffenders(readOnly).size,
        )

        // `// project-state: <理由>` 标注 → 放行（豁免不得变成「一律放行」：只豁免被标注的那一行）
        val annotated = listOf(
            "src/test/kotlin/x/AnnotatedSampleTest.kt" to """
                class AnnotatedSampleTest : BasePlatformTestCase() {
                    override fun setUp() {
                        super.setUp()
                        val manager = project.getService(SqlLogManager::class.java)
                        // project-state: 该字段写入幂等（置为默认值），且本类不做状态断言
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 `// project-state: <理由>` 标注的写应豁免：${projectServiceStateOffenders(annotated)}",
            0,
            projectServiceStateOffenders(annotated).size,
        )

        // `@Before` 形态（纯 JUnit4 测试类）也在扫描面内
        val beforeForm = listOf(
            "src/test/kotlin/x/BeforeSampleTest.kt" to """
                class BeforeSampleTest {
                    private lateinit var manager: SqlLogManager

                    @Before
                    fun init() {
                        manager = project.getService(SqlLogManager::class.java)
                        manager.entries.maxEntries = 10
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "@Before 入口的写必须同样命中（初始化入口不只 setUp 一种）：${projectServiceStateOffenders(beforeForm)}",
            1,
            projectServiceStateOffenders(beforeForm).size,
        )

        // ---- 边界如实钉住（漏报方向，KDoc「已知边界」段登记）----
        // ① 测试方法体内的写不在扫描面（收编到「类内任意写」会误判 try/finally 重赋值复位的合法类）
        val methodBodyWrite = listOf(
            "src/test/kotlin/x/MethodBodySampleTest.kt" to """
                class MethodBodySampleTest : BasePlatformTestCase() {
                    fun testX() {
                        val manager = project.getService(SqlLogManager::class.java)
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "边界登记：测试方法体内的写不在扫描面（本规则只覆盖初始化入口）：" +
                "${projectServiceStateOffenders(methodBodyWrite)}",
            0,
            projectServiceStateOffenders(methodBodyWrite).size,
        )
        // ② 只 capture 不 restore 是「半成品」——capture 不算复位证据，必须红
        val captureOnly = listOf(
            "src/test/kotlin/x/CaptureOnlySampleTest.kt" to """
                class CaptureOnlySampleTest : BasePlatformTestCase() {
                    private lateinit var state: SqlLogManagerProjectState.Snapshot

                    override fun setUp() {
                        super.setUp()
                        val manager = project.getService(SqlLogManager::class.java)
                        state = SqlLogManagerProjectState.capture(manager)
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "只 capture 不 restore 不算复位证据（存了快照忘了还原正是半成品形态）：" +
                "${projectServiceStateOffenders(captureOnly)}",
            1,
            projectServiceStateOffenders(captureOnly).size,
        )

        // ---- T-ST-02d 独立复核阻断 1：wrapper 取服务形态必须能绑定 ----
        // 复核实证：只认 `getService(` 时，把真泄漏类的 `restore` 行整行删掉规则仍 offenders=0 绿
        // （仓内 5 个已修泄漏类的 getService( 计数全为 0，全走 AlreadyDisposedDiagnostics.projectService）。
        val wrapperBound = listOf(
            "src/test/kotlin/x/WrapperBoundSampleTest.kt" to """
                class WrapperBoundSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager

                    override fun setUp() {
                        super.setUp()
                        manager = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.manager")
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "wrapper 承接（AlreadyDisposedDiagnostics.projectService）必须与 getService 同等绑定——" +
                "否则删掉真泄漏类的 restore 行也不红（复核阻断 1 复核实证）：" +
                "${projectServiceStateOffenders(wrapperBound)}",
            1,
            projectServiceStateOffenders(wrapperBound).size,
        )
        assertTrue(
            "wrapper 形态的命中点须锚在**写入那一行**（不得落在 wrapper 承接行）：" +
                "${projectServiceStateOffenders(wrapperBound)}",
            projectServiceStateOffenders(wrapperBound)[0]
                .endsWith("WrapperBoundSampleTest.kt:7 → manager.entries.dedupeEnabled ="),
        )

        // wrapper 承接 + wrapper 全量复位（R2 单参形态，快照由 wrapper 取服务表达式捕获）→ 绿
        val wrapperRestored = listOf(
            "src/test/kotlin/x/WrapperRestoredSampleTest.kt" to """
                class WrapperRestoredSampleTest : BasePlatformTestCase() {
                    private var state: SqlLogManagerProjectState.Snapshot? = null

                    override fun setUp() {
                        super.setUp()
                        state = SqlLogManagerProjectState.capture(
                            AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.capture")
                        )
                        AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.write")
                            .entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(state)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "wrapper 取服务（换行形态）+ SqlLogManagerProjectState.restore(<快照名>) 全量复位不得误报：" +
                "${projectServiceStateOffenders(wrapperRestored)}",
            0,
            projectServiceStateOffenders(wrapperRestored).size,
        )

        // ---- T-ST-02d 独立复核阻断 2：**别的对象**的复位调用不得洗掉真泄漏 ----
        // 复核实测（旧口径按文件级 contains 判定）：下面四个复位样本各自都能把真泄漏洗成绿。
        val otherObjectResets = listOf(
            "src/test/kotlin/x/OtherObjectResetSampleTest.kt" to """
                class OtherObjectResetSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager

                    override fun setUp() {
                        super.setUp()
                        manager = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.manager")
                        manager.entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        PluginSettings.loadState(snapshot)
                        JoinFkRegistry.clearForTest()
                        PluginCacheManager.clearAll(project)
                        FirstCaptureGuide.resetForTest()
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "另一个对象的复位调用（PluginSettings.loadState / JoinFkRegistry.clearForTest / " +
                "PluginCacheManager.clearAll / FirstCaptureGuide.resetForTest）不得豁免本类对共享服务的写" +
                "（旧口径下这四个样本把真泄漏洗成绿——复核阻断 2 复核实证）：" +
                "${projectServiceStateOffenders(otherObjectResets)}",
            1,
            projectServiceStateOffenders(otherObjectResets).size,
        )

        // 承接名自身的复位口仍算复位证据（绑定判定不得把覆盖面一起砍掉）——真实形态：
        // RollbackSnapshotStoreTest 的 `project.getService(RollbackSnapshotStore::class.java)` + clearForTest()
        val boundServiceReset = listOf(
            "src/test/kotlin/x/BoundResetSampleTest.kt" to """
                class BoundResetSampleTest : BasePlatformTestCase() {
                    private lateinit var store: RollbackSnapshotStore

                    override fun setUp() {
                        super.setUp()
                        store = project.getService(RollbackSnapshotStore::class.java)
                        store.markRollbackExecuted(entry)
                    }

                    override fun tearDown() {
                        store.clearForTest()
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "承接名自身的复位口（store.clearForTest()）必须仍算复位证据（② 选型不得丢覆盖面）：" +
                "${projectServiceStateOffenders(boundServiceReset)}",
            0,
            projectServiceStateOffenders(boundServiceReset).size,
        )
        // 防「复位口样本空绿」：同一份样本去掉复位行必须红——证明上面那个 0 是「写被识别 + 复位被认」，
        // 而不是「写形态没命中」的空绿（该样本的写形态与前面几个样本不同，无法靠它们互证）。
        val boundResetMissing = listOf(
            boundServiceReset[0].first to boundServiceReset[0].second.replace("store.clearForTest()", ""),
        )
        assertEquals(
            "同一份样本去掉 store.clearForTest() 必须红（否则上一条 0 是空绿）：" +
                "${projectServiceStateOffenders(boundResetMissing)}",
            1,
            projectServiceStateOffenders(boundResetMissing).size,
        )

        // ---- T-ST-02d 独立复核条件项：部分复位（3/15 字段）不算复位证据 ----
        // SqlLogManager.restoreCaptureStateForTest 只回滚 capturing/userArmed/waitingForConsole，
        // 而 SqlLogManagerProjectState.capture 快照 15 个字段（buffer 字段全不在内）——故已从复位令牌排除。
        val partialReset = listOf(
            "src/test/kotlin/x/PartialResetSampleTest.kt" to """
                class PartialResetSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager

                    override fun setUp() {
                        super.setUp()
                        manager = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.manager")
                        manager.entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        manager.restoreCaptureStateForTest(false, false, false)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "部分复位（restoreCaptureStateForTest 只回滚 3/15 字段）不足以豁免对 buffer 字段的写，必须红：" +
                "${projectServiceStateOffenders(partialReset)}",
            1,
            projectServiceStateOffenders(partialReset).size,
        )

        // ---- 空扫描面自证：路径解析失败 = 空绿，主用例的非空断言必须能拦住 ----
        assertEquals(
            "空扫描面下规则恒 0 违规——这正是「路径解析失败 = 静默空绿」的机理",
            0,
            projectServiceStateOffenders(emptyList()).size,
        )
        var emptyScanRed = false
        try {
            assertTestSourcesResolved(emptyList())
        } catch (e: AssertionError) {
            emptyScanRed = true
        }
        assertTrue(
            "扫描面为空时非空断言必须变红（否则 src/test/kotlin 路径解析失败会静默空绿）",
            emptyScanRed,
        )

        // 工具函数自证：初始化入口提取覆盖 setUp / @Before 两种形态，行号与原文对齐（保行掩码）
        val setUpSites = initEntrySites(maskNonCode(violating[0].second))
        assertEquals("setUp 入口应被提取：$setUpSites", 1, setUpSites.size)
        assertEquals("入口行号须与原文对齐（保行掩码）", 4, setUpSites[0].line)
        assertEquals("入口名", "setUp", setUpSites[0].name)
        val beforeSites = initEntrySites(maskNonCode(beforeForm[0].second))
        assertEquals("@Before 入口应被提取：$beforeSites", 1, beforeSites.size)
        assertEquals("入口名取函数名", "init", beforeSites[0].name)
    }
}
