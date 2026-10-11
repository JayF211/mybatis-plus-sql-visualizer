package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 初始化入口/收尾窗口对项目服务级状态的守卫规则族（内核与其自证用例同文件）
 * （T-M53-II 2026-10-10：成员逐字符搬运；可见性按拆类需要统一放宽为 internal。）
 */
class BannedApiScanServiceStateTest : BannedApiScanSupport() {

    /**
     * 初始化入口站点：`setUp` / `@Before` 的**函数体**（含花括号）。
     *
     * [body] 与 [bodyStart] 取自 [maskNonCode] 后的文本（**保行**）：体在掩码文本中的起始下标 + 体内容，
     * 命中下标回推行号即 `code.take(bodyStart + offset)` 的换行数——行号与原文一致，可直接回喂
     * [leadingCommentBlock] 认 `// project-state:` 标注。
     */
    internal data class InitEntrySite(val name: String, val line: Int, val body: String, val bodyStart: Int)

    /**
     * 扫出初始化入口站点：①`override fun setUp()`（平台测试基类形态，仓内主流）；
     * ②`@Before` / `@BeforeEach` 标注的函数（纯 JUnit4 类形态，与 `@Before` 注解相距 300 字符内才算）。
     *
     * 定位 `(参数)` 与 `{体}` 都跑在 [maskNonCode] 后的文本上（注释/字符串里的括号不参与配平，
     * 与 [invokeLaterSites] / [pooledThreadSites] 同口径）。
     */
    internal fun initEntrySites(code: String): List<InitEntrySite> =
        entrySites(code, "setUp", Regex("""(?<![A-Za-z0-9_])fun\s+setUp\s*\("""), Regex("""@(?:Before|BeforeEach)\b"""))

    /**
     * 收尾入口站点：`override fun tearDown()` / `@After` / `@AfterEach`（T-AgentResiduals R2 的
     * 「收尾窗口不得取服务」规则用）。提取口径与 [initEntrySites] 同源（同一份 [entrySites]），
     * 只换函数名与注解。
     */
    internal fun teardownEntrySites(code: String): List<InitEntrySite> =
        entrySites(code, "tearDown", Regex("""(?<![A-Za-z0-9_])fun\s+tearDown\s*\("""), Regex("""@(?:After|AfterEach)\b"""))

    internal fun entrySites(
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

    /**
     * 从 [from] 起定位 `(参数)` + `{体}`，返回体下标区间；[maxGap] 内找不到或配不平返回 null。
     *
     * 表达式体函数（`@Before fun setUp() = ErrorFingerprint.reset()`，仓内 1 处）**不取体**：
     * 其 `)` 与下一个 `{` 之间隔着 `= …`，若不拦就会把**下一个函数的花括号**当成它的体
     * （扫错区域＝把别人的写记到这个入口名下）。
     */
    internal fun functionBody(code: String, from: Int, maxGap: Int = 400): IntRange? {
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
    internal val serviceLookupTokens = listOf("getService(", "projectService(")

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
    internal fun serviceBoundNames(body: String): List<String> {
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
    internal val serviceMutatorNamePattern =
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
    internal fun serviceWriteHits(body: String, anchor: String): List<Pair<Int, String>> {
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
    internal val projectStateResetMethodPattern = "clearForTest|clearAll|resetForTest|loadState"

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
    internal val projectStateRestoreWrapper = "SqlLogManagerProjectState.restore"

    /** 存快照入口（只用于**绑定快照名**，本身不算复位证据——见 [projectStateRestoreWrapper]）。 */
    internal val projectStateCaptureCall = "SqlLogManagerProjectState.capture"

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
    internal fun snapshotBindings(body: String): List<Pair<String, String>> {
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
    internal fun hasBoundServiceReset(
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
    internal fun projectServiceStateOffenders(sources: List<Pair<String, String>>): List<String> {
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
    internal fun teardownServiceLookupOffenders(sources: List<Pair<String, String>>): List<String> {
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
