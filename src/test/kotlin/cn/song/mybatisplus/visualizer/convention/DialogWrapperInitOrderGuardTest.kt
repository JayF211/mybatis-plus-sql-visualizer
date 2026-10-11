package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 初始化顺序陷阱守卫（T-CaptureTruth T-CT-07 立项，T-ReachAudit T-RA-05 从 DialogWrapper 专项
 * 推广到**全类**）。
 *
 * ## 陷阱形态
 *
 * Kotlin：类级 `init {}` 块（含其直接调用的同类方法）在**构造期**执行，而属性初始化按文本顺序
 * 执行——声明在 `init {}` 之后的属性此刻还持有 JVM 默认值（对象类型为 null、布尔 false、数值 0），
 * **不是声明初值**。非空类型不拦（编译期静默通过），平台对 null 也常常容忍，于是表现为静默失效。
 *
 * 真机实证（T-CT-07）：243 平台 `com/intellij/openapi/ui/DialogWrapper` 字节码直证 `init()` 方法体
 * 内调用 `createActions()`；`EnhancedCaptureConfirmDialog` 把 `init()` 写在 Kotlin `init {}` 块里，
 * 而第三动作 `disableCaptureAction` 声明在其后 → 传进动作数组的是 null → 平台**静默不渲染该按钮**
 * （沙盒日志无「确认框异常」为证：平台容忍 null 而非抛错），用户真机看不到按钮，误以为功能没做。
 *
 * 运行期测试覆盖不到本陷阱：平台不保存 createActions 的返回数组（243 字节码核实无 Action[] 字段，
 * 数组直接进 createSouthPanel），构造完成后反射读到的永远是「已初始化」的字段值——只能源码级把关。
 *
 * ## k2j 移植注（生产为 Java，扫描根 src/main/java + .java）
 *
 * Java 侧等价形态与判据对应关系（**逐条对齐原判据，不降级**）：
 *  1. Kotlin `init {}` 块 → Java **类级实例初始化块** `{ … }`（Java 的实例初始化器与字段初始化器
 *     同样按**文本顺序**在构造期执行 → 「块内读到声明在其后的字段 = 读到 JVM 默认值」是同一陷阱）。
 *  2. Kotlin 构造器体（移植时 `init {}` 内容多落入构造器体）→ Java **构造器体**，同样计入构造期范围。
 *  3. 「构造期直接调用的同类方法」→ Java 同类**实例**方法（静态方法不读实例字段，按 Java 语义排除）；
 *     嵌套方法体/匿名对象体（`new MouseAdapter() { … }` 覆写体）与 **lambda 体**（Java 无尾随 lambda，
 *     回调体一律在括号内）在构造之后才执行 → 按原判据「构造期范围不含嵌套 fun 体」同口径排除。
 *  4. 形参名遮蔽：Java 构造器/方法形参可同名遮蔽字段（`this.x = x;`）→ 判定前从扫描面剔除形参名。
 *  5. 字段初值判定：Java 字段初始化器**先于构造器体**执行，故「有声明初值 + 读点在构造器体内」不是
 *     陷阱（新增豁免桶 exemptByFieldInitializer，Java 特有语义）；而「有声明初值 + 读点在**文本序在前**
 *     的实例初始化块内」仍是陷阱（走违规）。初值文本须剥掉 Java 行尾 `;`（Kotlin 无分号）。
 *
 * ## 判据（两条，都是判据本身，不是白名单）
 *
 * 违规 = 存在构造期范围（实例初始化块 / 构造器体，含其构造期直接调用的同类方法体）内的引用，指向
 * 「文本声明在其后」（实例初始化块口径）/ 任意（构造器口径）的类级字段，且该引用是**读**（引用点之前
 * 没有对该字段的赋值），且该字段**声明初值与 JVM 默认值不等**（或 Java 侧无声明初值 = 构造期恒 null/0/false）。
 *
 * 1. **初值与 JVM 默认值等值即豁免**（`false` / `0` / `null` / `""` / `Collections.emptyList()` 等）：
 *    构造期读到的值与声明初值逐字相等，无行为差异——`SqlLogPanel.historyPanelRegistered = false`
 *    即此类（防重复注册守卫读到的 false 正是它想要的语义）。
 * 2. **构造期先写后读即豁免**：`modeButton` 在 `toolbar()`（构造器调用）里先赋值再被读，读取点之前
 *    已有赋值 → 不是「读到未初始化值」。真陷阱（T-CT-07）是**只读不写**（createActions 只把字段
 *    传进数组）。
 *
 * 另有两条**不在扫描面内**的形态，同样不是白名单而是判据：
 * - **构造期范围不含嵌套方法体/匿名对象体/lambda 体**——那是匿名对象/回调的方法体，执行时机在
 *   构造**之后**，字段早已初始化；
 * - 实例初始化块口径下声明在该块**之前**的字段（含 `static final` 常量——类初始化语义）。
 *
 * ## 已知保守面（非缺陷，勿误判）
 *
 * 构造器体口径下「字段在构造期范围内**任意位置**被赋值即视为先写后读」——按文本序逐个模拟调用序
 * 已覆盖同类方法体内赋值（构造器 → 方法体按调用点文本序内联），残留保守面仅剩「同一方法体内
 * 赋值点在读点之后」的极端形态（Java 编译器/IDE 惯例不会写出）。
 *
 * 本守卫是**门禁型**；四轴全仓报告（含 when 模式消费、只写不读字段、Topic 配对等本守卫不覆盖的面）
 * 见 `dev-support/audit_reachability.py`。
 */
class DialogWrapperInitOrderGuardTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/java 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
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

    /**
     * 仅用于结构分析（大括号配平）的字符串字面量置空——字面量里的 `{`/`}` 会干扰深度计算，
     * 深度错位即扫描面错位。剥注释后的文本上再做，故不会吃掉代码里的 `"` 用法。
     */
    private fun stripStrings(text: String): String =
        text.replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
            .replace(Regex("\"(?:\\\\.|[^\"\\\\\n])*\""), "\"\"")

    /** 全仓 src/main 源码（剥注释）——k2j 生产树为 src/main/java（.java）。 */
    private fun mainSources(): List<Pair<String, String>> =
        repoRoot().resolve("src/main").walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to stripComments(it.readText()) }
            .toList()

    /** DialogWrapper 子类源码（专项用例用；剥注释）。 */
    private fun dialogSources(): List<Pair<String, List<String>>> =
        mainSources()
            .map { (path, code) -> path to code.split('\n') }
            .filter { (_, lines) -> lines.any { it.contains("extends DialogWrapper") } }

    // ------------------------------------------------------------------
    // 通用扫描器（可注入源码：专项用例与灵敏度自证共用同一实现）
    // ------------------------------------------------------------------

    /** 每行「进入时」的大括号深度（用于界定构造期范围与类级成员层级）。 */
    private fun depths(lines: List<String>): IntArray {
        val out = IntArray(lines.size)
        var d = 0
        lines.forEachIndexed { i, l ->
            out[i] = d
            d += l.count { it == '{' } - l.count { it == '}' }
        }
        return out
    }

    /** 块结束行（不含）。 */
    private fun blockEnd(lines: List<String>, dep: IntArray, start: Int): Int {
        val d = dep[start]
        var i = start + 1
        while (i < lines.size && dep[i] > d) i++
        return i
    }

    /**
     * lambda 体覆盖的行集合 + 每行参与「构造期同步代码」判定的前缀长度。
     *
     * Java 无 Kotlin 尾随 lambda：回调体一律写在括号内（`foo(() -> { … })`）。回调体在构造之后执行，
     * 不算构造期读——整段排除；箭头所在行只分析箭头之前的代码（`modeButton = smallButton(…, () -> {`）。
     * 与 Kotlin 原口径「构造期范围不含嵌套 fun 体」同义（Kotlin 的延迟回调写作 `invokeLater { }`
     * 或嵌套 `object : …`，在 Java 侧分别落为 lambda 与匿名类体）。
     */
    private fun lambdaLines(lines: List<String>, dep: IntArray): Pair<Set<Int>, Map<Int, Int>> {
        val body = mutableSetOf<Int>()
        val prefix = mutableMapOf<Int, Int>()
        lines.forEachIndexed { i, line ->
            var pos = line.indexOf("->")
            while (pos >= 0) {
                prefix[i] = minOf(prefix[i] ?: line.length, pos)
                var j = i
                var k = pos + 2
                while (true) {
                    while (j < lines.size && k >= lines[j].length) {
                        j++
                        k = 0
                    }
                    if (j >= lines.size || !lines[j][k].isWhitespace()) break
                    k++
                }
                if (j < lines.size && k < lines[j].length && lines[j][k] == '{') {
                    blockEnd(lines, dep, j).let { e -> (i + 1 until minOf(e, lines.size)).forEach { body += it } }
                }
                pos = line.indexOf("->", pos + 2)
            }
        }
        return body to prefix
    }

    /**
     * 声明头部（跨行到配平右括号）的形参名——Java 形参可同名遮蔽字段（`this.x = x;`），
     * 判定字段读点前须先把这些标识符剔出扫描面。
     */
    private fun paramNames(lines: List<String>, start: Int): Set<String> {
        val sb = StringBuilder()
        var i = start
        while (i < lines.size) {
            sb.append(lines[i]).append(' ')
            if (sb.count { it == '(' } <= sb.count { it == ')' }) break
            i++
        }
        val text = sb.toString()
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open < 0 || close < open) return emptySet()
        return text.substring(open + 1, close)
            .split(Regex(",(?![^<>]*>)"))
            .mapNotNull { chunk ->
                val ids = Regex("[A-Za-z_]\\w*").findAll(chunk).map { it.value }.toList()
                if (ids.size >= 2) ids.last() else null
            }
            .toSet()
    }

    private data class Hit(
        val file: String,
        val line: Int,
        val name: String,
        val initializer: String,
        val readBy: List<String>,
    )

    private data class ScanResult(
        /** 全部「构造期范围引用到后声明/无初值字段」候选（含被豁免者，供灵敏度自证）。 */
        val candidates: List<String>,
        val exemptByDefaultValue: List<String>,
        val exemptByWrittenFirst: List<String>,
        /** Java 特有豁免：有声明初值 + 读点在构造器体内（字段初始化器先于构造器体执行，非陷阱）。 */
        val exemptByFieldInitializer: List<String>,
        val violations: List<Hit>,
        val initBlocks: Int,
        val constructors: Int,
        val scopesWithBodies: Int,
    )

    private fun scan(sources: List<Pair<String, String>>): ScanResult {
        val candidates = mutableListOf<String>()
        val exemptDefault = mutableListOf<String>()
        val exemptWritten = mutableListOf<String>()
        val exemptFieldInit = mutableListOf<String>()
        val violations = mutableListOf<Hit>()
        var initBlocks = 0
        var constructors = 0
        var scopesWithBodies = 0

        /**
         * T-M53-II（2026-10-10，master 侧同款机制）：同包协作文件方法体索引 —— 组合式重构把
         * `SqlPreviewPanel.toolbar()` 这类构造期被构造器/初始化块调用的方法体搬到了同包协作文件。
         * master 侧为 Kotlin 扩展函数形态（`fun SqlPreviewPanel.toolbar(`，EXT_FUN_DEF 索引），
         * k2j 生产为 Java：方法体落同包门面类的**静态方法**（`static JBPanel toolbar(SqlPreviewPanel p)`），
         * 故本索引键 = (首参类型, 方法名)，与归属类名 + 构造期内被调用名配对使用。
         * 若不跨文件解析，「构造期先写后读」这条豁免路径就对拆分后形态失明（k2j 实测：
         * toolbar 搬出门面后 modeButton 的先写后读豁免随 309 布局失效）。
         */
        val extBodies = mutableMapOf<Pair<String, String>, String>()
        sources.forEach { (_, raw) ->
            val ls = stripStrings(raw).split('\n')
            val dp = depths(ls)
            ls.forEachIndexed { fi, l ->
                val fm = FACADE_STATIC_DEF.find(l) ?: return@forEachIndexed
                extBodies.putIfAbsent(fm.groupValues[1] to fm.groupValues[2], blockEnd(ls, dp, fi).let { ls.subList(fi, it).joinToString("\n") })
            }
        }

        sources.forEach { (file, raw) ->
            val lines = stripStrings(raw).split('\n')
            val dep = depths(lines)
            val (lambdaBody, lambdaPrefix) = lambdaLines(lines, dep)
            // 类体范围（含嵌套类）——扫描按类归属：Kotlin 侧 `init {}` 只在声明它的类内生效，
            // Java 侧同文件多类（含嵌套类）各有自己的字段/方法表，不按类归属会跨类串味（同名方法被
            // 别的类的构造器内联 → 假违规；T-RA-05 移植首跑实证：SqlJoinDiagramProvider 的
            // JoinTableNode.entity 被同文件他类构造器经 getIdentifyingElement 误判）。
            val classRanges = mutableListOf<Pair<Int, Int>>()
            lines.forEachIndexed { i, l ->
                if (CLASS_DEF.containsMatchIn(l)) classRanges += i to blockEnd(lines, dep, i)
            }

            classRanges.forEach { (cstart, cend) ->
                val cdepth = dep[cstart] + 1
                val indent = cdepth * 4
                // 类级字段与类级实例方法（静态方法不读实例字段，按 Java 语义排除）
                val fields = LinkedHashMap<String, Int>()
                val methods = LinkedHashMap<String, Triple<Int, Int, Set<String>>>()
                for (i in cstart until cend) {
                    if (dep[i] != cdepth) continue
                    val l = lines[i]
                    val fm = FUN_DEF.find(l)
                    if (fm != null && fm.range.first == 0 && fm.groupValues[1].length == indent) {
                        if ("static" in fm.groupValues[2]) continue
                        val name = fm.groupValues[4]
                        if (name !in methods) methods[name] = Triple(i, blockEnd(lines, dep, i), paramNames(lines, i))
                        continue
                    }
                    val dm = DECL.find(l)
                    if (dm != null && dm.range.first == 0 && dm.groupValues[1].length == indent) {
                        fields.putIfAbsent(dm.groupValues[3], i)
                    }
                }

                // 构造期范围：类级实例初始化块 `{ … }` + 构造器体
                val scopes = mutableListOf<Triple<Int, String, Set<String>>>()
                for (i in cstart until cend) {
                    if (dep[i] != cdepth) continue
                    val l = lines[i]
                    when {
                        INIT_BLOCK.matches(l) -> scopes += Triple(i, "init", emptySet())
                        CTOR.find(l)?.range?.first == 0 && !CTOR_NOT_CLASS.containsMatchIn(l) ->
                            scopes += Triple(i, "ctor", paramNames(lines, i))
                    }
                }

                // T-M53-II（master 侧同区语义）：归属类名——构造期内被调用、方法体在同包门面类静态
                // 方法的查找键（master 用 ownerClassAt 顶格回溯；k2j 类体扫描已按 classRanges 归属，
                // 类名直接取自声明行）。供下方 CALL 内联段的门面方法体并读使用。
                val ownerClass = CLASS_DEF.find(lines[cstart])?.groupValues?.get(1)
                scopes.forEach { (idx, kind, shadow0) ->
                    if (kind == "init") initBlocks++ else constructors++
                    val end = blockEnd(lines, dep, idx)
                    // 实例初始化块口径只看「文本声明在其后」的字段（文本序陷阱）；构造器口径看全部字段
                    // （Java 构造期读到无初值字段即 JVM 默认值 null/0/false）
                    val scopeFields =
                        if (kind == "init") fields.filterValues { it > end } else LinkedHashMap(fields)
                    if (scopeFields.isEmpty()) return@forEach

                    val written = mutableSetOf<String>()
                    val readBeforeWrite = LinkedHashMap<String, MutableList<String>>()
                    val referenced = mutableSetOf<String>()
                    val visited = mutableSetOf<String>()

                    /** 嵌套方法体/匿名对象体（构造之后才执行的回调）整段跳过——同 Kotlin 原口径。 */
                    fun nestedSkip(a: Int, b: Int): Set<Int> {
                        val skip = mutableSetOf<Int>()
                        for (i in a + 1 until b) {
                            if (FUN_DEF.find(lines[i])?.range?.first == 0) {
                                (i until blockEnd(lines, dep, i)).forEach { skip += it }
                            }
                        }
                        return skip
                    }

                    fun process(a: Int, b: Int, caller: String, depth: Int, shadowed: Set<String>) {
                        if (depth > 3) return
                        val skip = nestedSkip(a, b) + lambdaBody
                        for (i in a until b) {
                            if (i in skip) continue
                            val line = lambdaPrefix[i]?.let { lines[i].substring(0, it) } ?: lines[i]
                            // 先记「本行赋值」（赋值在同行读点之前，保守不误报），再判读点
                            scopeFields.keys.forEach { n -> if (assign(n).containsMatchIn(line)) written += n }
                            scopeFields.keys.forEach { n ->
                                if (n in shadowed) return@forEach
                                if (word(n).containsMatchIn(line)) {
                                    referenced += n
                                    if (n !in written) readBeforeWrite.getOrPut(n) { mutableListOf() }.add(caller)
                                }
                            }
                            CALL.findAll(line).forEach { m ->
                                val name = m.groupValues[1]
                                if (name in JAVA_KEYWORDS || name == caller) return@forEach
                                if (name == "init" && "createActions" in methods) {
                                    // 平台契约：DialogWrapper.init() 内部回调 createActions()（243 字节码直证）
                                    val (s, e, p) = methods.getValue("createActions")
                                    process(s, e, "createActions", depth + 1, p)
                                } else if (name in methods && name !in visited) {
                                    visited += name
                                    val (s, e, p) = methods.getValue(name)
                                    process(s, e, name, depth + 1, p)
                                } else if (name !in visited) {
                                    // T-M53-II：构造期被调用、方法体在同包门面类静态方法（master 侧为
                                    // 同包扩展函数）——并入解析面，否则先写后读豁免对拆分后形态失明。
                                    val fb = ownerClass?.let { oc -> extBodies[oc to name] }
                                    if (fb != null) {
                                        visited += name
                                        processText(fb, name, depth + 1)
                                    }
                                }
                            }
                        }
                    }

                    // T-M53-II：门面静态方法体内联（无行号形态）——字段引用/读写判定与 process 同口径，
                    // 仅作用于文本（门面体内 `p.field` 引用与裸引用同被词边界正则命中）。
                    fun processText(bodyText: String, caller: String, depth: Int) {
                        if (depth > 3) return
                        val bodyLines = bodyText.split('\n')
                        val bDep = depths(bodyLines)
                        val (bLambda, bPrefix) = lambdaLines(bodyLines, bDep)
                        val skip = fun funNestSkip(): Set<Int> {
                            val st = mutableSetOf<Int>()
                            for (i in bodyLines.indices) {
                                if (FUN_DEF.find(bodyLines[i])?.range?.first == 0) {
                                    (i until blockEnd(bodyLines, bDep, i)).forEach { st += it }
                                }
                            }
                            return st
                        }()
                        for (i in bodyLines.indices) {
                            if (i in skip || i in bLambda) continue
                            val line = bPrefix[i]?.let { bodyLines[i].substring(0, it) } ?: bodyLines[i]
                            scopeFields.keys.forEach { n -> if (assign(n).containsMatchIn(line)) written += n }
                            scopeFields.keys.forEach { n ->
                                if (word(n).containsMatchIn(line)) {
                                    referenced += n
                                    if (n !in written) readBeforeWrite.getOrPut(n) { mutableListOf() }.add(caller)
                                }
                            }
                            CALL.findAll(line).forEach { m ->
                                val name2 = m.groupValues[1]
                                if (name2 in JAVA_KEYWORDS || name2 == caller) return@forEach
                                if (name2 in methods && name2 !in visited) {
                                    visited += name2
                                    val (s2, e2, p2) = methods.getValue(name2)
                                    process(s2, e2, name2, depth + 1, p2)
                                } else if (name2 !in visited) {
                                    val fb2 = ownerClass?.let { oc -> extBodies[oc to name2] }
                                    if (fb2 != null) {
                                        visited += name2
                                        processText(fb2, name2, depth + 1)
                                    }
                                }
                            }
                        }
                    }

                    process(idx + 1, end, if (kind == "ctor") "ctor" else "init", 0, shadow0)
                    if (referenced.isEmpty()) return@forEach
                    scopesWithBodies++

                    referenced.sorted().forEach { n ->
                        candidates += n
                        if (n !in readBeforeWrite) {
                            // 构造期范围内只被写或先写后读——读取点之前已有赋值，非未初始化读
                            if (n in written) exemptWritten += n
                            return@forEach
                        }
                        val declLine = scopeFields.getValue(n)
                        val initValue = initializerOf(lines, declLine)
                        when {
                            // 初值与 JVM 默认值等值（或无背后字段）：构造期读到的就是声明初值
                            initValue != null && initValue in JVM_DEFAULTS -> exemptDefault += n
                            // Java 语义：字段初始化器先于构造器体执行 → 构造器体内读到的是声明初值，非陷阱
                            kind == "ctor" && initValue != null -> exemptFieldInit += n
                            else -> violations += Hit(
                                file,
                                declLine + 1,
                                n,
                                initValue ?: "<无初值>",
                                readBeforeWrite[n]!!.distinct().sorted(),
                            )
                        }
                    }
                }
            }
        }
        return ScanResult(
            candidates, exemptDefault, exemptWritten, exemptFieldInit, violations,
            initBlocks, constructors, scopesWithBodies,
        )
    }

    /**
     * 声明初值文本（`=` 后的表达式）；无初值（Java 无 lateinit，即 `private X x;`）返回 null。
     *
     * 初值跨行（`private final JComboBox<String> x = new JComboBox<>(\n  …);`）时取下一行——只影响
     * 报错文案可读性，判据只关心「是否等于 JVM 默认值」。**Java 行尾分号须剥掉**（Kotlin 无分号，
     * 不剥则 `false;` 永远落不进 JVM_DEFAULTS 等值集）。
     */
    private fun initializerOf(lines: List<String>, declLine: Int): String? {
        val decl = lines[declLine]
        val m = Regex("(?<![=!<>+\\-*/])=(?!=)").find(decl) ?: return null
        val sameLine = decl.substring(m.range.last + 1).trim().trimEnd(';').trim()
        if (sameLine.isNotEmpty()) return sameLine
        return lines.getOrNull(declLine + 1)?.trim()?.trimEnd(';')?.trim()?.takeIf { it.isNotEmpty() } ?: ""
    }

    /** 平台 `init()` 调用行号（构造器体内的 `init();` 调用，非类级 `init` 定义）。 */
    private fun platformInitCallLine(lines: List<String>): Int? =
        lines.indexOfFirst { Regex("^\\s*init\\(\\)\\s*;?\\s*$").matches(it) }.takeIf { it >= 0 }

    /** `createActions` 覆写体文本（Java 一律块体：读到 4 空格缩进的函数收尾括号）。 */
    private fun createActionsBody(lines: List<String>): Pair<Int, String>? {
        val start = lines.indexOfFirst { Regex("""\bcreateActions\s*\(""").containsMatchIn(it) }
        if (start < 0) return null
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
    fun test_init块经方法引用的后声明属性_初值与JVM默认值不等即违规() {
        val result = scan(mainSources())
        // 扫描面失效即假绿：构造期范围（实例初始化块 + 构造器体）与「有同类方法体可解析」的范围数
        // 都必须非零。k2j 生产为 Java：Kotlin `init {}` 块落为 Java 实例初始化块（本树 4 处）+ 构造器体（500+）。
        assertTrue(
            "应至少扫描到若干构造期范围（实例初始化块 + 构造器体；扫描面失效即为假绿）：" +
                "${result.initBlocks} + ${result.constructors}",
            result.initBlocks + result.constructors >= 100,
        )
        assertTrue(
            "应至少解析到一个构造期范围调用的同类方法体（扫描面失效即为假绿）：${result.scopesWithBodies}",
            result.scopesWithBodies >= 5,
        )
        // 两条豁免路径必须真实生效过（否则「零违规」可能只是因为扫描面根本没看到那两处形态）
        assertTrue(
            "应至少有一处「初值=JVM 默认值」豁免（SqlLogPanel.historyPanelRegistered = false）：" +
                "${result.exemptByDefaultValue}",
            "historyPanelRegistered" in result.exemptByDefaultValue,
        )
        assertTrue(
            "应至少有一处「构造期先写后读」豁免（SqlPreviewPanel 的 modeButton 在 toolbar() 里赋值）：" +
                "${result.exemptByWrittenFirst}",
            "modeButton" in result.exemptByWrittenFirst,
        )
        assertTrue(
            "存在初始化顺序陷阱（构造期读到 JVM 默认值而非声明初值）：\n" +
                result.violations.joinToString("\n") { v ->
                    "${v.file}: ${v.name}（声明@${v.line}）初值 `${v.initializer}` 与 JVM 默认值不等，" +
                        "却在构造期经 ${v.readBy} 被读——构造期该字段是 null/0/false，" +
                        "调用方（含平台回调）会静默拿到默认值。" +
                        "修法：把声明上移到构造期范围之前（实例初始化块口径）、或让初值与 JVM 默认值等值、" +
                        "或在读点之前先赋值"
                },
            result.violations.isEmpty(),
        )
    }

    @Test
    fun test_增强捕获确认框第三动作已就位且早于init块() {
        val (_, lines) = dialogSources().firstOrNull { it.first.endsWith("EnhancedCaptureConfirmDialog.java") }
            ?: error("未找到 EnhancedCaptureConfirmDialog.java")
        val initLine = platformInitCallLine(lines) ?: error("未找到平台 init() 调用")
        // Java 字段声明形态（Kotlin `private val disableCaptureAction = …` 的等价物）
        val declLine = lines.indexOfFirst { Regex("""\bdisableCaptureAction\s*=""").containsMatchIn(it) }
        assertTrue("disableCaptureAction 应存在（第三动作「关闭增强捕获并不再询问」）", declLine >= 0)
        // Java 侧真实约束：字段必须带**字段初始化器**（构造器体在字段初始化器之后执行，故 init() 回调
        // createActions() 时已就位）；若改成构造器体内 init() 之后赋值，平台拿到 null → 按钮静默消失。
        assertTrue(
            "disableCaptureAction 必须以字段初始化器形态声明（`private final … disableCaptureAction = …`）——" +
                "仅靠构造器体赋值时 init() → createActions() 会拿到 null（T-CT-07 真机形态）",
            declLine >= 0 && lines[declLine].contains("="),
        )
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

    /**
     * T-ConsentTruth T-CN-02：**状态可达性守卫**——确认框枚举的每个常量都必须存在写入点。
     *
     * 现场：T-PS4-01 三态化把 `showAndGet()` 换成 `show()` + 读 outcome，只接上 DISABLE_CAPTURE，
     * `ACCEPTED` 全仓无赋值 → `confirm()` 恒 false → 增强捕获经 UI 完全不可达（用户报障
     * 「点确认但开启标识没变且不生效」）。
     *
     * 守卫数据驱动（遍历枚举 values），因此**将来新增状态而忘了接线，本守卫自动红**——
     * 不必为每个状态各写一条断言。豁免：字段初值本身就是产出点的那一态（REJECTED = X 关闭按拒绝）。
     */
    @Test
    fun test_确认框每个结果态都必须存在写入点() {
        val (_, lines) = dialogSources().firstOrNull { it.first.endsWith("EnhancedCaptureConfirmDialog.java") }
            ?: error("未找到 EnhancedCaptureConfirmDialog.java")
        val src = lines.joinToString("\n")
        // Java 字段形态：`private EnhancedCaptureConsent outcome = EnhancedCaptureConsent.REJECTED;`
        val initializer = Regex("""EnhancedCaptureConsent\s+outcome\s*=\s*EnhancedCaptureConsent\.(\w+)""")
            .find(src)?.groupValues?.get(1)
        assertTrue("应能从字段初值读出默认态（正则失效？）", initializer != null)

        val unreachable = cn.song.mybatisplus.visualizer.config.EnhancedCaptureConsent.values()
            .filter { it.name != initializer }
            .filterNot { src.contains("outcome = EnhancedCaptureConsent.${it.name}") }
            .map { it.name }
        assertTrue(
            "确认框存在不可达结果态（枚举加了状态却忘了接线）：$unreachable——" +
                "默认态（$initializer）之外的每一态都必须有 `outcome = EnhancedCaptureConsent.X` 写入点",
            unreachable.isEmpty(),
        )
    }

    /**
     * 扫描器灵敏度与豁免自证（合成源码）：五种形态喂给同一实现，断言各自落位。
     *
     * 存在理由：真实全仓当前零违规，若扫描面哪天被改瞎（正则写坏、深度算错），主用例仍会「绿」——
     * 这是守卫类测试最典型的假绿形态。本用例用合成源码把判据钉死：真陷阱必红（Kotlin 形态的 Java
     * 等价物 + Java 特有的「无初值字段构造期被读」形态）、两条豁免必生效、延迟回调（匿名类体）必不
     * 进扫描面。
     */
    @Test
    fun test_扫描器灵敏度与豁免自证() {
        // ① 实例初始化块 → 同类方法 → 读「文本声明在其后」的字段（Kotlin init{} 陷阱的 Java 直译）
        val defect = """
            class SyntheticDefect {
                {
                    buildActions();
                }

                private void buildActions() {
                    use(thirdAction);
                }

                private final Object thirdAction = new Object();
            }
        """.trimIndent()
        // ② 初值 = JVM 默认值 → 豁免（读到的值与声明初值逐字相等）
        val defaultValued = """
            class SyntheticDefaultValued {
                {
                    addTab();
                }

                private void addTab() {
                    if (!registered) {
                        checkState();
                    }
                    registered = true;
                }

                private boolean registered = false;
            }
        """.trimIndent()
        // ③ 延迟回调（匿名对象覆写体）引用后声明字段 → 不得进扫描面
        val deferred = """
            class SyntheticDeferred {
                {
                    register(new Runnable() {
                        @Override
                        public void run() {
                            Object x = laterConstant;
                            System.out.println(x);
                        }
                    });
                }

                private void register(Runnable r) {
                    r.hashCode();
                }

                private final Object laterConstant = new Object();
            }
        """.trimIndent()
        // ④ 构造期先写后读 → 豁免（Java 的 lateinit 等价形态：无初值字段先赋值再读）
        val writtenFirst = """
            class SyntheticWrittenFirst {
                {
                    buildToolbar();
                }

                private void buildToolbar() {
                    modeButton = new Object();
                    modeButton.hashCode();
                }

                private Object modeButton;
            }
        """.trimIndent()
        // ⑤ Java 特有陷阱形态（T-CT-07 的 Java 对应物）：无初值动作字段，构造器在 init() **之后**才赋值
        //    → 平台 createActions() 拿到 null，按钮静默消失。
        //    注：平台回调链（`init()` → `createActions()`）只在**构造器体**里认（Java 移植侧 `init()`
        //    一律由构造器调用，EnhancedCaptureConfirmDialog.java:73 实证）；实例初始化块里写 `init()`
        //    会与扫描器「同类同名调用不自内联」的防重入口径相撞，故本形态用构造器承载。
        val nullAction = """
            class SyntheticNullAction extends DialogWrapper {
                private Object disableAction;

                SyntheticNullAction() {
                    init();
                    disableAction = new Object();
                }

                protected Object[] createActions() {
                    return new Object[]{disableAction};
                }
            }
        """.trimIndent()

        val result = scan(listOf(
            "src/main/java/SyntheticDefect.java" to defect,
            "src/main/java/SyntheticDefaultValued.java" to defaultValued,
            "src/main/java/SyntheticDeferred.java" to deferred,
            "src/main/java/SyntheticWrittenFirst.java" to writtenFirst,
            "src/main/java/SyntheticNullAction.java" to nullAction,
        ))
        val names = result.violations.map { it.name }
        assertTrue(
            "T-CT-07 形态（实例初始化块 → 同类方法 → 读「文本声明在其后」的对象字段）必须被判定为违规：$names",
            "thirdAction" in names,
        )
        assertTrue(
            "Java 特有形态（无初值动作字段在 init() 之后才赋值 → 平台回调读到 null）必须被判定为违规：$names",
            "disableAction" in names,
        )
        assertTrue(
            "延迟回调形态（匿名类覆写体引用后声明字段）不得进扫描面：${result.candidates}",
            "laterConstant" !in result.candidates,
        )
        assertTrue(
            "初值=JVM 默认值形态（registered = false）必须走豁免而非违规：${result.exemptByDefaultValue}",
            "registered" in result.exemptByDefaultValue && "registered" !in names,
        )
        assertTrue(
            "构造期先写后读形态（无初值 modeButton 先赋值再读）必须走豁免而非违规：${result.exemptByWrittenFirst}",
            "modeButton" in result.exemptByWrittenFirst && "modeButton" !in names,
        )
    }

    private companion object {
        /** 类级字段声明（Java 形态：`private [static] [final] <Type> name [= init];`；行首匹配）。 */
        val DECL = Regex(
            "^( *)(?:(?:private|public|protected|static|final|volatile|transient|abstract|synchronized|native" +
                "|default|@\\w+(?:\\([^)]*\\))?)\\s+)*([\\w.<>\\[\\],?]+)\\s+(\\w+)\\s*[;=]"
        )

        /** 类级实例初始化块（行首 4 空格 + 独占一行的 `{`；方法体 `{` 落在签名行尾，不会误判）。 */
        val INIT_BLOCK = Regex("^ {4}\\{\\s*$")

        /** 类体声明（`class X` / `record X` / `enum X` / `interface X`，含修饰符与嵌套类）——扫描按类归属。 */
        val CLASS_DEF = Regex("^( *)(?:\\w+\\s+)*\\b(?:class|interface|enum|record)\\s+(\\w+)")

        /** 构造器（4 空格缩进 + 可选修饰符 + 大写开头类名 + `(`）。 */
        val CTOR = Regex("^ {4}(?:(?:public|protected|private)\\s+)?([A-Z]\\w*)\\s*\\(")

        /** 类/记录/接口声明行不得被当构造器（`record MetaPerfSegment(String …)` 形如构造器）。 */
        val CTOR_NOT_CLASS = Regex("\\b(?:class|interface|enum|record)\\s")

        /** 类级实例方法声明（行首匹配；静态方法在扫描时另判）。 */
        val FUN_DEF = Regex(
            "^( *)((?:(?:private|public|protected|static|final|abstract|synchronized|native|default" +
                "|@\\w+(?:\\([^)]*\\))?)\\s+)*)([\\w.<>\\[\\],?]+)\\s+(\\w+)\\s*\\("
        )

        /**
         * T-M53-II（master 侧 EXT_FUN_DEF 的 Java 对应物）：同包门面类**静态方法**定义
         * （`static <Ret> <名>(<OwnerType> p, …)`，首参为宿主面板实例）——组合式重构后，类的成员
         * 方法体可能被搬到同包协作文件的静态门面里（如 `SqlPreviewPanelResultViewKt.toolbar(p)`）。
         * 收 group[1]=方法名、group[2]=首参类型（宿主类名）。
         */
        val FACADE_STATIC_DEF = Regex(
            "^ {4}(?:public\s+)?static\s+[\w.<>\[\],?\s]+\s+(\w+)\s*\(\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:final\s+)?([A-Z]\w*)\s+\w+[,)]"
        )

        val CALL = Regex("(?:[\\w.]+\\s*\\.\\s*)?(\\w+)\\s*\\(")

        /** 与 JVM 默认值等值的字面初值：构造期读到它与读到声明初值无行为差异（Java 形态）。 */
        val JVM_DEFAULTS = setOf(
            "false", "0", "0L", "0l", "0.0", "0f", "0F", "0.0f", "null", "\"\"",
            "Collections.emptyList()", "Collections.emptyMap()", "Collections.emptySet()",
        )

        /**
         * Java 关键字/类型名/常用静态工厂：出现在构造期语句位但不是「同类实例方法调用」。
         *
         * 名单口径 = 「CALL 正则可能抓到的非同类方法名」：字面量关键字（`new`/`this`/`super`…）、
         * 基本类型与常用 JDK 类型名（`String`/`List`/`Objects`…，`new X()` 形态会被 CALL 抓到）、
         * 平台常用静态工厂（`valueOf`/`requireNonNull`…）。宁可多列——漏列只会把 JDK 调用误当同类方法
         * 内联（假读点），多列不产生漏报。
         */
        val JAVA_KEYWORDS = setOf(
            "if", "for", "while", "switch", "return", "catch", "synchronized", "try", "new", "throw",
            "assert", "case", "break", "continue", "instanceof", "this", "super", "null", "true", "false",
            "int", "long", "boolean", "double", "float", "char", "byte", "short", "void", "var",
            "record", "class", "interface", "enum", "String", "Object",
            "Integer", "Long", "Boolean", "Double", "Float", "Character", "Byte", "Short",
            "List", "Map", "Set", "ArrayList", "HashMap", "HashSet", "Optional", "Objects", "Arrays",
            "Collections", "Stream",
            "valueOf", "requireNonNull", "equals", "hashCode", "toString", "addAll", "put", "get",
            "set", "remove", "size", "isEmpty",
        )

        val NAME_CACHE = HashMap<String, Regex>()
        val ASSIGN_CACHE = HashMap<String, Regex>()

        /** 词边界引用（不误吃 `xxxName` / `Namexxx`）。 */
        fun word(name: String): Regex = NAME_CACHE.getOrPut(name) { Regex("\\b" + Regex.escape(name) + "\\b") }

        /** 字段赋值点（排除 `==` / `!=` / `<=` / `+=` 等；`this.x =` 亦计）。 */
        fun assign(name: String): Regex =
            ASSIGN_CACHE.getOrPut(name) { Regex("(?<![\\w])" + Regex.escape(name) + "\\s*=(?!=)") }
    }
}
