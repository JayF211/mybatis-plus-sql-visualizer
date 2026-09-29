package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F20 配对写源码扫描守卫（T-PerfSweep16 F44，仿 BannedApiSourceScanTest 范式）。
 *
 * 背景：SqlLogEntryBuffer 的结构键快查（`@Volatile var hasStructureKeys` 门控位，F20 修复）
 * 依赖一条**手工不变量**——SqlLogManager 里每处 `buffer.suppressKeys = …` 生产写点都必须配对
 * 刷写 `hasStructureKeys`（提交时 3 处：规则变更监听 / init 初始同步 / dispose 清零兜底）。
 * 漏配对编译照过、既有测试难红——结构口径键会被静默降级为「不查 structureKey」，结构类抑制
 * 规则静默失效。本守卫把该不变量从「人记」变为「机查」。
 *
 * 规则：SqlLogManager.kt（剥注释后）每处 `suppressKeys =` 赋值行，其**同行或紧随 1~3 行内**
 * 必须出现 `hasStructureKeys =` 赋值（生产 3 处均为紧随 1 行形态）。
 *
 * 扫描范围限 src/main 的 SqlLogManager.kt 单文件——测试夹具里的 suppressKeys 置位
 * （4d51056 先例：测试侧与生产同步置位门控位）不在扫描范围。
 *
 * 防空绿双保险：
 *  1. 合成样本自证——「suppressKeys 赋值无配对」必报红、「配对样本」必放行、窗口边界（第 3 行
 *     放行 / 第 5 行报红）钉死；
 *  2. 扫描面保鲜——SqlLogManager.kt 缺失 / 读失败 / 一处赋值都没找到 = 红（防改名搬移后恒 0 空绿）。
 */
class SuppressKeysPairingGuardTest {

    /** 配对窗口：赋值行自身 + 紧随行数（任务口径「同行或紧随 1~3 行内」）。 */
    private val pairingWindowLines = 3

    /** 赋值判别：`token =`（排除 `==` 比较；标识符边界排除 `suppressedKeys` 等前缀粘连）。 */
    private fun isAssignmentTo(token: String, line: String): Boolean =
        Regex("""(?<![A-Za-z0-9_])$token\s*=(?!=)""").containsMatchIn(line)

    /**
     * 扫描单文件源码（剥注释后逐行），返回未配对 `suppressKeys =` 赋值的行号清单（1-based）。
     * 独立成函数供合成样本自证（规则「看不见真实形态」的空绿只能靠合成样本钉住——
     * BannedApiSourceScanTest 同款理由）。
     */
    private fun unpairedSuppressKeyLines(source: String): List<Int> {
        val lines = stripComments(source).lines()
        val offenders = mutableListOf<Int>()
        lines.forEachIndexed { i, line ->
            if (!isAssignmentTo("suppressKeys", line)) return@forEachIndexed
            val window = lines.subList(i, minOf(i + pairingWindowLines + 1, lines.size))
            if (window.none { isAssignmentTo("hasStructureKeys", it) }) {
                offenders.add(i + 1)
            }
        }
        return offenders
    }

    /** 扫描面保鲜计数：剥注释后 `suppressKeys =` 赋值行数（生产当前 3 处）。 */
    private fun countSuppressKeyAssignments(source: String): Int =
        stripComments(source).lines().count { isAssignmentTo("suppressKeys", it) }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** 剥离 block 注释与行注释（BannedApiSourceScanTest 同款；注释里记载的 suppressKeys 不算写点）。 */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    private fun readSqlLogManagerSource(): String {
        val file = repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogManager.kt")
        assertTrue(
            "扫描对象缺失：$file 不存在——SqlLogManager 被改名/搬移后本守卫即失去对象，" +
                "更新扫描路径而非删除守卫",
            file.isFile,
        )
        return runCatching { file.readText() }.getOrElse {
            throw AssertionError("SqlLogManager.kt 读取失败（IO/编码）：${it.message}", it)
        }
    }

    // ------------------------------------------------------------------
    // 生产扫描面
    // ------------------------------------------------------------------

    @Test
    fun `SqlLogManager生产写点suppressKeys全部配对刷写hasStructureKeys`() {
        val source = readSqlLogManagerSource()
        val sites = countSuppressKeyAssignments(source)
        assertTrue(
            "扫描面保鲜失败：SqlLogManager.kt 中一处 suppressKeys = 赋值都没找到（提交时 3 处）——" +
                "写点形态改名会让本守卫静默架空成恒 0 空绿，须同步更新扫描令牌而非删守卫",
            sites >= 1,
        )
        val offenders = unpairedSuppressKeyLines(source)
        assertTrue(
            "SqlLogManager.kt 存在未配对刷写 hasStructureKeys 的 suppressKeys 赋值（行号 $offenders）。" +
                "F20 手工不变量：每处 buffer.suppressKeys = … 须同行或紧随 $pairingWindowLines 行内配对 " +
                "buffer.hasStructureKeys = …（同步口径用 suppressRuleManager.suppressedKeysContainStructure，" +
                "清零兜底用 false），漏配对会让结构口径键静默退化为「不查 structureKey」",
            offenders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 合成样本自证（防空绿双保险之一）
    // ------------------------------------------------------------------

    @Test
    fun `守卫自证_无配对合成样本必红`() {
        val violating = """
            class Buf {
                fun apply() {
                    buffer.suppressKeys = ruleManager.suppressedKeys
                    notifyUi()
                }
            }
        """.trimIndent()
        val offenders = unpairedSuppressKeyLines(violating)
        assertTrue(
            "「suppressKeys 赋值无配对」合成样本必须被命中（规则看不见即空绿）：$offenders",
            offenders.size == 1,
        )
    }

    @Test
    fun `守卫自证_配对合成样本放行`() {
        val paired = """
            class Buf {
                fun apply() {
                    buffer.suppressKeys = ruleManager.suppressedKeys
                    buffer.hasStructureKeys = ruleManager.suppressedKeysContainStructure
                }
            }
        """.trimIndent()
        assertTrue(
            "紧随 1 行配对的合成样本必须放行：${unpairedSuppressKeyLines(paired)}",
            unpairedSuppressKeyLines(paired).isEmpty(),
        )
    }

    @Test
    fun `守卫自证_窗口边界第3行放行第5行报红`() {
        val pairedAtWindowEdge = """
            class Buf {
                fun apply() {
                    buffer.suppressKeys = ruleManager.suppressedKeys
                    val a = 1
                    val b = 2
                    buffer.hasStructureKeys = true
                }
            }
        """.trimIndent()
        assertTrue(
            "紧随第 3 行（窗口边界）配对应放行：${unpairedSuppressKeyLines(pairedAtWindowEdge)}",
            unpairedSuppressKeyLines(pairedAtWindowEdge).isEmpty(),
        )

        val pairedOutsideWindow = """
            class Buf {
                fun apply() {
                    buffer.suppressKeys = ruleManager.suppressedKeys
                    val a = 1
                    val b = 2
                    val c = 3
                    val d = 4
                    buffer.hasStructureKeys = true
                }
            }
        """.trimIndent()
        assertTrue(
            "紧随第 5 行（窗口外）配对必须报红（窗口放宽即失去护栏意义）：${unpairedSuppressKeyLines(pairedOutsideWindow)}",
            unpairedSuppressKeyLines(pairedOutsideWindow).size == 1,
        )
    }

    @Test
    fun `守卫自证_注释与比较不算赋值`() {
        val commentsAndComparisons = """
            class Buf {
                /** 文档里提到 buffer.suppressKeys = … 与 hasStructureKeys == true 不算写点。 */
                fun apply() {
                    // buffer.suppressKeys = oldHistory 注释掉的旧写法不算写点
                    if (buffer.suppressKeys == ruleManager.suppressedKeys) return
                    val keys = buffer.suppressKeys
                }
            }
        """.trimIndent()
        assertTrue(
            "注释记载与 == 比较不得计为赋值写点（误报会淹没问题）：${unpairedSuppressKeyLines(commentsAndComparisons)}",
            unpairedSuppressKeyLines(commentsAndComparisons).isEmpty(),
        )
    }
}
