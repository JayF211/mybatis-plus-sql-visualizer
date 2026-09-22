package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫自证覆盖率元守卫（T-GuardSelfTestCoverage T-GSC-01，O-50 / 第十轮报告 §7.2 Z-30）。
 *
 * ## 要解决的问题
 *
 * 我方守卫**已有**自证能力（合成源码样本喂进判定函数、`BannedApiSourceScanTest.kt:610` 的设计意图原文），
 * 缺的是**约束力**：新加一条守卫规则而不写自证语料，没有任何测试会红——仓内已三次实证的失败形态
 * （守卫绿灯下失效、空扫描面空绿）正是从这条缝里长出来的。本元守卫把「判定函数必须配自证语料」
 * 从人工仪式变成结构性断言。
 *
 * ## 判据
 *
 * 1. **覆盖率**：`GUARD_CLASSES` 登记的每个判定函数，必须在本类源码的**自证语料**函数体内被引用
 *    （词边界匹配）；否则红并点名，或在登记项写 `exempt` 理由（与既有守卫豁免表同规，理由不得为空）。
 * 2. **登记表不得腐烂**：登记的函数名必须真实存在于该类源码（函数改名/删除后登记表会静默空转）。
 * 3. **豁免不得腐烂**：`exempt` 理由非空，且该函数**仍未**获得自证引用（一旦补上自证语料即红，
 *    提示删除豁免条目——同 `EnumReachabilityGuardTest` 的 `WHITELIST` 反腐烂口径）。
 * 4. **扫描面不得整体脱离**：① 磁盘上文件名含 `Guard` 或以 `SourceScanTest` 结尾的测试类必须登记；
 *    ② 任何测试文件里出现类级（4 空格缩进）判定形状函数（`*Offenders` / `*Sites` / `*Violations` /
 *    `*Hits` / `*Functions`）而其文件未登记 → 红；③ 已登记类里出现未逐条登记的判定形状函数 → 红。
 * 5. **元守卫自身灵敏度自证**：合成源码喂进同一判定实现（见末个用例）。
 *
 * ## 自证语料的识别判据
 *
 * 函数是**测试函数**（紧邻上方有 `@Test`，或函数名以 `test` 开头），且满足其一：
 *  - 函数名含 `自证` / `自检` / `selftest`（大小写不敏感）；或
 *  - 声明行上方**紧邻的注解/注释块**含上述关键词（允许中间夹空行与 `//` 分隔线，遇首个代码行即停）。
 *
 * ## 漏报面（已知，勿当「已覆盖」）
 *
 * 1. **命名兜底面认不全判定函数**：本仓既有判定函数命名发散——`DialogWrapperInitOrderGuardTest:178`
 *    与 `EnumReachabilityGuardTest:206` 都叫 `scan`、`DmlAliasGuardTest:42` 叫 `dmlTargetsHaveNoAlias`、
 *    `DockerComposeLabConsistencyTest:147/:159` 是 `internal fun checkXxx`。故**登记表是主识别面**，
 *    命名形状只在第 4 条里当「新函数兜底提示」用。
 * 2. **文件名闭包只认 `Guard` / `SourceScanTest`**：非此命名且不含判定形状函数的守卫类不会被发现
 *    （实测两例已手工登记：`convention/BundlePlaceholderArityTest.kt`、`sql/verify/DockerComposeLabConsistencyTest.kt`）。
 * 3. **只认类级成员（4 空格缩进）**：嵌套类/局部函数里的同名后缀方法不在扫描面
 *    （实测 `ui/SqlPreviewPanelPrewarmWriteSafeTest.kt:86` 的 `writeUnsafeHits` 在嵌套 `RecordingProcessor` 内）。
 * 4. **无判定内核的守卫无约束力**：本仓 20 个守卫类里只有 4 个暴露可注入合成样本的判定内核，其余
 *    断言直接内联在 `@Test` 里（登记项 `decisionFns` 为空并写明依据）——这类守卫的规则漂移仍只能靠人工。
 * 5. **自证语料只在同文件内匹配**：跨文件引用的自证不被识别。
 * 6. **函数体界定按大括号收尾**：表达式体函数（`fun x() = …`）会延伸到下一个同类缩进 `}`，只可能放宽
 *    不会收紧；本仓自证语料全为块体。
 *
 * ## 过报面（已知，已按 kind 分列登记，勿误判为缺陷）
 *
 * 1. **站点提取器与断言助手会命中命名形状**：`invokeLaterSites`（返回 `Site` 集合，不是违规点）、
 *    `assertNoHits`（断言助手）都被 `*Sites` / `*Hits` 兜底形状抓进来——已用 `FnKind.SITES` /
 *    `FnKind.HELPER` 与 `FnKind.VIOLATIONS` 分列登记，语义不混。
 * 2. **判定函数自身 KDoc 里的「自证」字样不算语料**：`BannedApiSourceScanTest.kt:610` 的 KDoc 明写
 *    「让规则本身可自证」，若按「附近有自证字样」宽泛匹配，会把判定函数自己当语料 → 故自证识别
 *    只认**测试函数**（有 `@Test` 或 `test` 前缀），非测试函数一律不看。
 * 3. **任何测试文件里的 `*Offenders` 类函数都会要求登记**：这是刻意的三振出局式提示，不是精度缺陷；
 *    确非判定内核时登记为 `HELPER` 并写依据即可。
 *
 * 本守卫是**测试基建**，不改产品代码、不改既有守卫逻辑；零平台依赖（源码无平台包引用，
 * 故进 `testFast` 快桶——该桶按「源码文本是否出现平台包名」划桶，故本 KDoc 也不得写出那个包名）。
 */
class GuardSelfTestCoverageMetaGuardTest {

    // ------------------------------------------------------------------
    // 仓库定位与源码读取（与同包既有守卫同形态）
    // ------------------------------------------------------------------

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    private fun testRoot(): File = repoRoot().resolve("src/test/kotlin")

    /** 登记表路径是 `cn/song/mybatisplus/visualizer/` 之后的包相对路径（如 `convention/XxxTest.kt`）。 */
    private fun sourceOf(entryPath: String): String = File(testRoot(), TEST_PKG + entryPath).readText()

    /** 全量测试源码（包相对路径 → 文本）；登记表与命名兜底面共用。 */
    private fun allTestSources(): List<Pair<String, String>> =
        testRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                val full = file.relativeTo(testRoot()).path.replace('\\', '/')
                val rel = if (full.startsWith(TEST_PKG)) full.removePrefix(TEST_PKG) else full
                rel to file.readText()
            }
            .toList()

    // ------------------------------------------------------------------
    // 判定实现（可注入源码：主用例与灵敏度自证共用同一实现）
    // ------------------------------------------------------------------

    /** 类级成员函数声明（4 空格缩进；`internal`/`override` 等修饰符与反引号函数名都收）。 */
    private fun fnDecls(source: String): List<FnDecl> {
        val lines = source.split('\n')
        val out = mutableListOf<FnDecl>()
        lines.forEachIndexed { idx, line ->
            val m = FUN_DECL.find(line) ?: return@forEachIndexed
            val name = m.groupValues[1].trim('`')
            out += FnDecl(name, idx + 1, isSelfTest(lines, idx, name), bodyOf(lines, idx))
        }
        return out
    }

    /** 自证语料：函数名 + 函数体文本（[fnDecls] 里 `selfTest = true` 的那些）。 */
    private fun selfTestFns(source: String): List<Pair<String, String>> =
        fnDecls(source).filter { it.selfTest }.map { it.name to it.body }

    /** 命名兜底面命中的类级函数：名 + 行号。 */
    private fun decisionShapedDecls(source: String): List<Pair<String, Int>> =
        fnDecls(source).filter { DECISION_SHAPE.containsMatchIn(it.name) }.map { it.name to it.line }

    /** 纯判定：源码里「无自证语料引用且未登记豁免」的判定函数名。 */
    private fun uncoveredNames(source: String, declared: List<DecisionFn>): List<String> {
        val selfTests = selfTestFns(source)
        return declared
            .filter { it.exempt == null }
            .filter { fn -> selfTests.none { (_, body) -> references(body, fn.name) } }
            .map { it.name }
    }

    /** 自证语料里对该函数名的引用（词边界，避免 `foo` 命中 `fooBar`）。 */
    private fun references(body: String, name: String): Boolean =
        Regex("\\b" + Regex.escape(name) + "\\b").containsMatchIn(body)

    /**
     * 是否自证语料：先必须是**测试函数**（紧邻上方有 `@Test`，或函数名以 `test` 开头），再看关键词。
     *
     * 只认测试函数是刻意的——判定函数自身 KDoc 常写「可自证」（`BannedApiSourceScanTest.kt:610`），
     * 不设这道门会把判定函数自己当语料，覆盖率检查整体空转。
     */
    private fun isSelfTest(lines: List<String>, declIdx: Int, name: String): Boolean {
        val above = annotationRun(lines, declIdx)
        val isTest = name.startsWith("test") || above.any { it.startsWith("@Test") }
        if (!isTest) return false
        if (SELF_TEST_MARK.containsMatchIn(name)) return true
        return above.any { SELF_TEST_MARK.containsMatchIn(it) }
    }

    /** 声明行上方紧邻的注解/注释块（遇首个代码行即停；空行与 `//` 分隔线允许夹在中间）。 */
    private fun annotationRun(lines: List<String>, declIdx: Int): List<String> {
        val out = mutableListOf<String>()
        var i = declIdx - 1
        while (i >= 0) {
            val s = lines[i].trim()
            if (s.startsWith("@") || s.startsWith("//") || s.startsWith("*") || s.startsWith("/*") || s.isEmpty()) {
                out += s
                i--
                continue
            }
            break
        }
        return out
    }

    /** 函数体：声明行到首个「缩进不深于声明行且以 `}` 开头」的行（含）；找不到则到文件尾。 */
    private fun bodyOf(lines: List<String>, declIdx: Int): String {
        val declIndent = indentOf(lines[declIdx])
        var i = declIdx + 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("}") && indentOf(line) <= declIndent) {
                return lines.subList(declIdx, i + 1).joinToString("\n")
            }
            i++
        }
        return lines.subList(declIdx, lines.size).joinToString("\n")
    }

    private fun indentOf(line: String): Int = line.length - line.trimStart().length

    /** 文件名闭包：含 `Guard` 或以 `SourceScanTest` 结尾（指南守卫族全清单的机器可读口径）。 */
    private fun isGuardFile(fileName: String): Boolean {
        val base = fileName.removeSuffix(".kt")
        return "Guard" in base || base.endsWith("SourceScanTest")
    }

    // ------------------------------------------------------------------
    // 覆盖率检查
    // ------------------------------------------------------------------

    @Test
    fun `每个守卫判定函数必须有自证语料或登记豁免`() {
        val missing = mutableListOf<String>()
        GUARD_CLASSES.forEach { entry ->
            val source = sourceOf(entry.path)
            uncoveredNames(source, entry.decisionFns).forEach { name ->
                val kind = entry.decisionFns.first { it.name == name }.kind
                missing += "${entry.path} :: $name（$kind）"
            }
        }
        assertTrue(
            "守卫判定函数缺自证语料（合成样本喂不进去 = 「规则看不见某形态」的空绿只能靠人工改线上代码才发现）：\n" +
                missing.joinToString("\n") { "  - $it" } +
                "\n修法二选一：① 补一个自证用例（测试函数名含 `自证`/`自检`/`selftest`，或紧邻注释标注）；" +
                "② 在 GUARD_CLASSES 对应项登记 exempt 理由（理由不得为空）。" +
                "约定原文见 docs/架构设计/03-开发与测试指南.md 守卫章节。",
            missing.isEmpty(),
        )
    }

    @Test
    fun `登记表不得腐烂_登记的函数必须真实存在于该类源码`() {
        val stale = GUARD_CLASSES.flatMap { entry ->
            val names = fnDecls(sourceOf(entry.path)).map { it.name }.toSet()
            entry.decisionFns.filter { it.name !in names }.map { "${entry.path} :: ${it.name}" }
        }
        assertTrue(
            "登记表条目在源码里已不存在（函数改名/删除后本元守卫对该项静默空转）：\n" +
                stale.joinToString("\n") { "  - $it" } + "\n修法：同步改名或删除该条目",
            stale.isEmpty(),
        )
    }

    @Test
    fun `豁免条目不得腐烂_理由非空且仍无自证语料`() {
        val problems = mutableListOf<String>()
        GUARD_CLASSES.forEach { entry ->
            if (entry.note.isBlank()) problems += "${entry.path} 登记说明为空（须写清该类为何无判定内核或为何登记）"
            val selfTests = selfTestFns(sourceOf(entry.path))
            entry.decisionFns.filter { it.exempt != null }.forEach { fn ->
                if (fn.exempt!!.isBlank()) problems += "${entry.path} :: ${fn.name} 豁免理由为空"
                val refs = selfTests.filter { (_, body) -> references(body, fn.name) }.map { it.first }
                if (refs.isNotEmpty()) {
                    problems += "${entry.path} :: ${fn.name} 已获自证语料（${refs.joinToString()}）——请删除该豁免条目"
                }
            }
        }
        assertTrue(
            "豁免表/登记表腐烂（豁免条目必须随语料补齐而删除，同 EnumReachabilityGuardTest 的 WHITELIST 口径）：\n" +
                problems.joinToString("\n") { "  - $it" },
            problems.isEmpty(),
        )
    }

    @Test
    fun `磁盘守卫类必须全部登记_防新守卫类整体脱离扫描面`() {
        val registered = GUARD_CLASSES.map { it.path }.toSet()
        val unregistered = allTestSources()
            .filter { (path, _) -> isGuardFile(path.substringAfterLast('/')) && path !in registered }
            .map { it.first }
            .sorted()
        assertTrue(
            "磁盘上存在未登记的守卫类（整体脱离自证覆盖率扫描面）：$unregistered。" +
                "修法：在 GUARD_CLASSES 登记（无「返回违规点/站点集合」的判定内核时 decisionFns 留空并写明依据）",
            unregistered.isEmpty(),
        )
    }

    @Test
    fun `命名兜底面命中的判定形状函数必须全部登记`() {
        val registered = GUARD_CLASSES.map { it.path }.toSet()
        val unregisteredFiles = allTestSources()
            .filter { (path, source) -> path !in registered && decisionShapedDecls(source).isNotEmpty() }
            .map { (path, source) ->
                "$path -> " + decisionShapedDecls(source).joinToString { "${it.first}:${it.second}" }
            }
        assertTrue(
            "存在未登记却声明了判定形状函数（*Offenders/*Sites/*Violations/*Hits/*Functions）的测试文件：\n" +
                unregisteredFiles.joinToString("\n") { "  - $it" } +
                "\n修法：确是守卫判定内核则登记进 GUARD_CLASSES 并配自证语料；否则登记为 HELPER 类豁免并写明依据",
            unregisteredFiles.isEmpty(),
        )

        val undeclared = GUARD_CLASSES.flatMap { entry ->
            val declared = entry.decisionFns.map { it.name }.toSet()
            decisionShapedDecls(sourceOf(entry.path))
                .filter { (name, _) -> name !in declared }
                .map { (name, line) -> "${entry.path}:$line $name" }
        }
        assertTrue(
            "已登记守卫类里出现未逐条登记的判定形状函数（新判定函数脱离覆盖率约束）：\n" +
                undeclared.joinToString("\n") { "  - $it" },
            undeclared.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 元守卫自身灵敏度自证（合成源码喂进同一判定实现）
    // ------------------------------------------------------------------

    /**
     * 元守卫自身的红绿语料：三种形态喂给 [uncoveredNames] / [decisionShapedDecls] / [selfTestFns]，
     * 断言各自落位——否则本元守卫自己也可能是「扫描面失效仍全绿」的那一个。
     */
    @Test
    fun `元守卫灵敏度自证_未自证的判定函数必红_自证与KDoc边界各归其位`() {
        // ① 未自证：判定函数存在，唯一的 @Test 不是自证语料 → 必须被点名
        val uncovered = """
            class SyntheticUncoveredTest {
                private fun syntheticOffenders(code: String): List<String> = emptyList()

                @Test
                fun `主用例_全仓零违规`() {
                    assertTrue(syntheticOffenders("x").isEmpty())
                }
            }
        """.trimIndent()
        assertTrue(
            "判定形状函数必须被识别：${decisionShapedDecls(uncovered)}",
            decisionShapedDecls(uncovered).any { it.first == "syntheticOffenders" },
        )
        assertTrue(
            "非自证的 @Test 不得算语料（否则覆盖率检查整体空转）：${selfTestFns(uncovered).map { it.first }}",
            selfTestFns(uncovered).isEmpty(),
        )
        assertTrue(
            "缺自证的判定函数必须被点名：${uncoveredNames(uncovered, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS)))}",
            uncoveredNames(uncovered, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS))) ==
                listOf("syntheticOffenders"),
        )

        // ② 已自证：自证用例函数体内引用判定函数 → 放行
        val covered = """
            class SyntheticCoveredTest {
                private fun syntheticOffenders(code: String): List<String> = emptyList()

                /** 合成样本自证：注入违规形态必报。 */
                @Test
                fun `合成样本自证_注入必报`() {
                    assertTrue(syntheticOffenders("bad").isNotEmpty())
                }
            }
        """.trimIndent()
        assertTrue(
            "自证用例（名字含关键词）必须被识别：${selfTestFns(covered).map { it.first }}",
            selfTestFns(covered).any { it.first.contains("自证") },
        )
        assertTrue(
            "被自证用例引用的判定函数不得报缺自证：${uncoveredNames(covered, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS)))}",
            uncoveredNames(covered, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS))).isEmpty(),
        )

        // ③ KDoc 边界：判定函数自身 KDoc 含「自证」字样 + 主用例引用它 → 仍必须报缺自证
        //    （BannedApiSourceScanTest.kt:610 的真实形态：设计说明写「让规则本身可自证」）
        val kdocOnly = """
            class SyntheticKdocOnlyTest {
                /**
                 * 单独成函数（而非内联进 @Test）是为了让规则本身可自证：合成源码样本能直接喂进来。
                 */
                private fun syntheticOffenders(code: String): List<String> = emptyList()

                @Test
                fun `主用例_全仓零违规`() {
                    assertTrue(syntheticOffenders("x").isEmpty())
                }
            }
        """.trimIndent()
        assertTrue(
            "判定函数自身 KDoc 里的「自证」字样不得被当语料（否则 :610 形态会洗绿整个检查）：" +
                "${selfTestFns(kdocOnly).map { it.first }}",
            selfTestFns(kdocOnly).isEmpty(),
        )
        assertTrue(
            "KDoc 含「自证」的判定函数仍必须报缺自证：${uncoveredNames(kdocOnly, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS)))}",
            uncoveredNames(kdocOnly, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS))) ==
                listOf("syntheticOffenders"),
        )

        // ④ 注释标注形态（DmlAliasGuardTest.kt:121 的真实形态：`//` 注释与 @Test 之间夹 `// ----` 与空行）
        val commentMarked = """
            class SyntheticCommentMarkedTest {
                private fun syntheticOffenders(code: String): List<String> = emptyList()

                // ---- 守卫有效性自证：注入违规形态必红（防止守卫自身空转）----
                // ----
                @Test
                fun injectedFormsAreDetected() {
                    assertTrue(syntheticOffenders("bad").isNotEmpty())
                }
            }
        """.trimIndent()
        assertTrue(
            "紧邻 `//` 注释标注的自证用例必须被识别（允许夹 `//` 分隔线与空行）：${selfTestFns(commentMarked).map { it.first }}",
            selfTestFns(commentMarked).any { it.first == "injectedFormsAreDetected" },
        )
        assertTrue(
            "注释标注的自证用例应覆盖其引用的判定函数：${uncoveredNames(commentMarked, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS)))}",
            uncoveredNames(commentMarked, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS))).isEmpty(),
        )

        // ⑤ 中文函数名（无反引号）形态：`test_扫描器灵敏度与豁免自证()` 是仓内真实形态
        //    （DialogWrapperInitOrderGuardTest:363 / EnumReachabilityGuardTest:267）。Java 正则的 `\w`
        //    只认 ASCII，函数名匹配写成 `[A-Za-z_]\w*` 会在中文首字符处断掉、整条声明静默漏识别，
        //    把这两个类的 `scan` 误报成「缺自证」——2026-09-22 首次实跑即栽在此。
        val cjkNamed = """
            class SyntheticCjkNamedTest {
                private fun syntheticOffenders(code: String): List<String> = emptyList()

                @Test
                fun test_扫描器灵敏度与豁免自证() {
                    assertTrue(syntheticOffenders("bad").isNotEmpty())
                }
            }
        """.trimIndent()
        assertTrue(
            "中文函数名的自证用例必须被识别（Java 正则 `\\w` 只认 ASCII，写成 `[A-Za-z_]\\w*` 会整条漏）：" +
                "${selfTestFns(cjkNamed).map { it.first }}",
            selfTestFns(cjkNamed).any { it.first.startsWith("test_扫描器") },
        )
        assertTrue(
            "中文函数名自证用例应覆盖其引用的判定函数：${uncoveredNames(cjkNamed, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS)))}",
            uncoveredNames(cjkNamed, listOf(DecisionFn("syntheticOffenders", FnKind.VIOLATIONS))).isEmpty(),
        )
    }

    private data class FnDecl(val name: String, val line: Int, val selfTest: Boolean, val body: String)

    private enum class FnKind {
        /** 返回违规点集合的判定内核（`List<String>` 形态），本元守卫的主要约束对象。 */
        VIOLATIONS,

        /** 返回站点集合的提取器（`List<XxxSite>` 形态），违规点内核的输入。 */
        SITES,

        /** 命名形状偶然命中的断言助手/夹具，不是判定内核。 */
        HELPER,
    }

    /**
     * 一个判定函数登记项。
     *
     * [exempt] 非空即豁免（必须写理由，且该函数仍须保持「无自证」——补上自证后本元守卫会红并要求删除）。
     */
    private data class DecisionFn(val name: String, val kind: FnKind, val exempt: String? = null)

    /**
     * 一个守卫类登记项。
     *
     * [decisionFns] 为空表示该类没有「返回违规点/站点集合」的判定内核（断言直接内联在 `@Test` 里），
     * 此时 [note] 必须写清依据——这正是基线扫描的产出之一：本仓 20 个守卫类里只有 4 个暴露判定内核。
     */
    private data class GuardEntry(val path: String, val decisionFns: List<DecisionFn>, val note: String)

    private companion object {
        const val TEST_PKG = "cn/song/mybatisplus/visualizer/"

        /**
         * 类级成员函数声明（4 空格缩进；反引号函数名与常见修饰符都收）。
         *
         * 标识符用 `[\p{L}_][\p{L}\p{N}_]*` 而非 `[A-Za-z_]\w*`：Java 正则的 `\w` **只认 ASCII**，
         * 而本仓自证用例大量用「无反引号的 `test_扫描器灵敏度与豁免自证()`」这种**中文函数名**形态——
         * 用 `\w` 会在中文首字符处断掉、`\s*\(` 随即失配，整条声明**静默不被识别**，进而把
         * `DialogWrapperInitOrderGuardTest.scan` / `EnumReachabilityGuardTest.scan` 误报成「缺自证」
         * （2026-09-22 首次实跑实证：本元守卫自身先栽在这条上）。
         */
        val FUN_DECL = Regex(
            "^ {4}(?:(?:private|internal|public|protected|override|suspend|inline|operator|open|tailrec)\\s+)*" +
                "fun\\s+(`[^`]+`|[\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\(",
        )

        /**
         * 判定函数的命名兜底形状（计划约定的 `*Offenders`/`*Sites` 加同性质后缀）。
         *
         * 尾锚用 `\z` 而非 `$`：Kotlin 普通字符串里 `$` 是模板起始符（同 `EnumReachabilityGuardTest`
         * 的 `tailMatches` 教训），写 `"...Functions)$"` 有被词法层吃掉的风险。
         */
        val DECISION_SHAPE = Regex("(Offenders|Sites|Violations|Hits|Functions)\\z")

        /** 自证语料关键词（大小写不敏感）。 */
        val SELF_TEST_MARK = Regex("自证|自检|selftest", RegexOption.IGNORE_CASE)

        /**
         * 守卫类登记表（**主识别面**：命名约定认不全本仓的判定函数，见类头「漏报面」第 1 条）。
         *
         * 基线扫描（2026-09-22，master 树）实测：20 个文件名闭包内的守卫类中，仅 4 个暴露可注入合成
         * 样本的判定内核（`BannedApiSourceScanTest` / `DialogWrapperInitOrderGuardTest` /
         * `EnumReachabilityGuardTest` / `DmlAliasGuardTest`），另两例手工扩展登记
         * （`BundlePlaceholderArityTest` / `DockerComposeLabConsistencyTest`）。
         */
        val GUARD_CLASSES = listOf(
            // ---- 判定内核类（有可注入合成样本的判定函数） ----
            GuardEntry(
                "convention/BannedApiSourceScanTest.kt",
                listOf(
                    DecisionFn(
                        "assertNoHits",
                        FnKind.HELPER,
                        "断言助手（不返回违规点集合），命名偶然命中 *Hits 兜底形状；其行为由各主用例的断言直接覆盖",
                    ),
                    DecisionFn(
                        "invokeLaterSites",
                        FnKind.SITES,
                        "站点提取器，被共享内核 invokeLaterWriteOffenders(:613) 在 :619 调用；自证经转发覆盖",
                    ),
                    DecisionFn(
                        "invokeLaterWriteOffenders",
                        FnKind.VIOLATIONS,
                        "共享内核，被已自证的 anyModalityWriteOffenders(:631)/defaultModalityWriteOffenders(:640) " +
                            "在 :632/:641 调用；自证经转发覆盖（无独立合成样本用例）",
                    ),
                    DecisionFn("anyModalityWriteOffenders", FnKind.VIOLATIONS),
                    DecisionFn("defaultModalityWriteOffenders", FnKind.VIOLATIONS),
                    DecisionFn(
                        "throwableCatchSites",
                        FnKind.SITES,
                        "站点提取器，被 fatalPassthroughOffenders(:1128) 在 :1132 调用；自证经转发覆盖",
                    ),
                    DecisionFn("fatalPassthroughOffenders", FnKind.VIOLATIONS),
                    DecisionFn("pooledThreadSites", FnKind.SITES),
                    DecisionFn("psiCaptureOffenders", FnKind.VIOLATIONS),
                    DecisionFn(
                        "edtReadContractOffenders",
                        FnKind.VIOLATIONS,
                        "基线实测缺口：仅被主用例 `跳转族调用点须持读权限…`(:1614) 在 :1618 引用，无合成样本自证用例" +
                            "（如实登记，非本次修复范围）",
                    ),
                    DecisionFn("initEntrySites", FnKind.SITES),
                    DecisionFn(
                        "serviceWriteHits",
                        FnKind.SITES,
                        "站点提取器，被 projectServiceStateOffenders(:1916) 在 :1931 调用；自证经转发覆盖",
                    ),
                    DecisionFn("projectServiceStateOffenders", FnKind.VIOLATIONS),
                    // ---- 2026-09-22 合并 master 后补登记（master 侧 O-56 批次扩 BannedApiSourceScanTest 时新增） ----
                    DecisionFn("anyModalityInUpdatePackageOffenders", FnKind.VIOLATIONS),
                    DecisionFn(
                        "entrySites",
                        FnKind.SITES,
                        "站点提取器，被 initEntrySites(:1790)/teardownEntrySites(:1798) 转发调用；" +
                            "自证经转发覆盖（无直接合成样本用例）",
                    ),
                    DecisionFn("teardownEntrySites", FnKind.SITES),
                    DecisionFn("teardownServiceLookupOffenders", FnKind.VIOLATIONS),
                ),
                "本仓判定内核最集中的守卫类；五个自证用例 :703/:784/:1197/:1456/:1968 + 空扫描面自证 :2317",
            ),
            GuardEntry(
                "convention/DialogWrapperInitOrderGuardTest.kt",
                listOf(DecisionFn("scan", FnKind.VIOLATIONS)),
                "判定函数名不命中命名形状（叫 scan，:178）——正是「登记表为主识别面」的实证；自证 :363",
            ),
            GuardEntry(
                "convention/EnumReachabilityGuardTest.kt",
                listOf(DecisionFn("scan", FnKind.VIOLATIONS)),
                "判定函数叫 scan（:206）；自证 :267；WHITELIST 反腐烂口径被本元守卫的豁免检查沿用",
            ),
            GuardEntry(
                "generator/DmlAliasGuardTest.kt",
                listOf(DecisionFn("dmlTargetsHaveNoAlias", FnKind.VIOLATIONS)),
                "判定函数名不命中命名形状（:42）；自证用例靠 `//` 行注释标注（:121）+ 函数名不含关键词（:125）",
            ),
            GuardEntry(
                "sql/verify/DockerComposeLabConsistencyTest.kt",
                listOf(
                    DecisionFn("checkImagePinning", FnKind.VIOLATIONS),
                    DecisionFn("checkPorts", FnKind.VIOLATIONS),
                    DecisionFn(
                        "checkRequiredEnv",
                        FnKind.VIOLATIONS,
                        "基线实测缺口：三个漂移自证用例（:93/:105/:123）只覆盖镜像钉版本与端口两检查；" +
                            "本函数无合成样本自证（如实登记）",
                    ),
                    DecisionFn(
                        "checkHealthcheck",
                        FnKind.VIOLATIONS,
                        "基线实测缺口：同上，无合成样本自证（如实登记）",
                    ),
                ),
                "手工扩展登记（文件名不含 Guard / SourceScanTest，文件名闭包发现不到）；判定函数是 internal fun checkXxx",
            ),
            GuardEntry(
                "convention/BundlePlaceholderArityTest.kt",
                listOf(DecisionFn("warningSites", FnKind.SITES)),
                "手工扩展登记（文件名不含 Guard，靠命名兜底面发现）；扫描自检 :129 覆盖 warningSites",
            ),
            // ---- 无判定内核的守卫类（断言直接内联在 @Test；登记以关闭文件名闭包） ----
            GuardEntry(
                "acp/AcpTransportScopeSourceScanTest.kt",
                emptyList(),
                "断言直接内联在 @Test；repoRoot/stripComments/stripStrings/mainSources/stripXmlComments 为夹具",
            ),
            GuardEntry(
                "config/PluginXmlStructureGuardTest.kt",
                emptyList(),
                "断言经 tagsOf(:44)+assertHolds(:47) 助手内联在 @Test；无返回违规点集合的判定内核",
            ),
            GuardEntry(
                "config/SettingsRealityGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；mainSources/nonConfigSources 为夹具",
            ),
            GuardEntry(
                "execute/ExecutionTimeoutGuardTest.kt",
                emptyList(),
                "表驱动纯逻辑断言直接内联在 @Test；无复用判定函数",
            ),
            GuardEntry(
                "execute/SessionIdentityGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；memory(:63) 为夹具",
            ),
            GuardEntry(
                "i18n/BundleParityGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；loadBundle/repoRoot/mainSources/metaInfXml 为夹具",
            ),
            GuardEntry(
                "i18n/DegradedBannerWordingGuardTest.kt",
                emptyList(),
                "扫描自检 :52 直接内联 bannerEntries(:40) 断言，无独立判定函数",
            ),
            GuardEntry(
                "log/ConsoleReplayGuardTest.kt",
                emptyList(),
                "判定内核 decide(:14) 返回重放/流式决策（非违规点集合），不在本元守卫面内；8 个用例为合成状态注入式",
            ),
            GuardEntry(
                "log/LogRouterHotPathRegexGuardTest.kt",
                emptyList(),
                "单用例断言直接内联",
            ),
            GuardEntry(
                "log/ReplayGuardLine0ReadTest.kt",
                emptyList(),
                "断言直接内联在 @Test；attach(:65) 为夹具",
            ),
            GuardEntry(
                "log/SqlLogAutoSaverPruneGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；tempDir/makeFile/namesOf/entryOf 为夹具",
            ),
            GuardEntry(
                "log/SqlLogListFreezeGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；effectiveRenderer/allLists/withSingleLineMode 为夹具",
            ),
            GuardEntry(
                "log/agent/AgentUrlDecoupleGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；source(:38) 为夹具",
            ),
            GuardEntry(
                "mcp/McpScopeSourceScanTest.kt",
                emptyList(),
                "断言直接内联在 @Test；repoRoot/stripComments/stripStrings/stripXmlComments 为夹具",
            ),
            GuardEntry(
                "mcp/McpSelectGuardTest.kt",
                emptyList(),
                "断言经 assertDenied(:17)/assertAllowed(:24) 助手内联在 @Test",
            ),
            GuardEntry(
                "update/UpdateZipGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；withTempZip/manualZip/concatManualZips 为夹具",
            ),
            // ---- 2026-09-22 合并 master 后补登记（master 侧 O-48/O-49 批次新增的守卫类） ----
            GuardEntry(
                "convention/SqlLogManagerLookupGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；repoRoot/stripComments/mainSources 为夹具（T-AgentResiduals R2 配套守卫）",
            ),
            GuardEntry(
                "execute/ErrorPositionWiringGuardTest.kt",
                emptyList(),
                "断言直接内联在 @Test；repoRoot/readArguments/lineOf 为夹具（T-ELU-03 接线守卫）",
            ),
            // ---- 本元守卫自身 ----
            GuardEntry(
                "convention/GuardSelfTestCoverageMetaGuardTest.kt",
                emptyList(),
                "本元守卫自身：判定内核为纯函数（fnDecls/selfTestFns/decisionShapedDecls/uncoveredNames），" +
                    "灵敏度由 `元守卫灵敏度自证_未自证的判定函数必红_自证与KDoc边界各归其位` 用例钉住",
            ),
        )
    }
}
