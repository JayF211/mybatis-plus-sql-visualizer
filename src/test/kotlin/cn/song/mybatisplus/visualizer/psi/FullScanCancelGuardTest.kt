package cn.song.mybatisplus.visualizer.psi

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 全量短名扫描的「有界 + 可取消」守卫（源码扫描，纯逻辑零平台依赖，进 testFast 快测桶）。
 *
 * 为什么要有它：20261008 真机冻结事故（T-GutterDupFreeze）的形态是「`getAllClassNames()` 全量遍历
 * 跑在阻塞式 `ReadAction` 里、读锁全程不放 ⇒ EDT 等读许可被冻住（实测 70 秒、空白窗关不掉）」。
 * 当时的收口是 [cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep]（上限 + 取消检查 + 命中即止），
 * 但**没有任何守卫把这条收口钉住**——20261016 T-JarEmbedded 方案 1 就又在 [EntityClassFinder] 里写了
 * 一处同形态的全量扫描（整条 classpath、无取消检查、单趟 4.5s、把打字阻塞 5.5s），
 * 十类用例全绿却复现了同一个冻结机制。本守卫就是把「守卫看不见它要防的站点」这个缺口补上。
 *
 * 两条断言：
 *  1. **每个** `getAllClassNames()` 遍历所在的成员函数体必须含 `ProgressManager.checkCanceled()`
 *     （或委派给 [cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep] 这类自带取消检查的有界扫描器）；
 *  2. 库扫描 `EntityClassFinder.runLibrarySweepNow` 的函数体**不得**出现 `inferDecoratedTableName`
 *     ——「按类名推断表名」对任何类名都能推出非空表名，会把整条 classpath（含 JDK）的类全建成「表」。
 *
 * 判定面＝`src/main/kotlin` 下全部 `.kt`；先剥注释，再按「恰好 4 空格缩进的成员函数」切函数体
 * （8 空格缩进的嵌套/companion 成员不算边界，避免把函数体切碎成假阳性）。
 */
class FullScanCancelGuardTest {

    @Test
    fun testEveryFullShortNameScanHasCancelCheck() {
        val offenders = mutableListOf<String>()
        for (file in mainKotlinFiles()) {
            val lines = stripComments(file.readText()).lines()
            lines.forEachIndexed { idx, line ->
                if (!line.contains("getAllClassNames(")) return@forEachIndexed
                val start = functionStart(lines, idx) ?: return@forEachIndexed
                val end = nextFunctionStart(lines, start + 1) ?: lines.size
                val body = lines.subList(start, end).joinToString("\n")
                if (!body.contains("checkCanceled(") && !body.contains("EntityAliasSweep")) {
                    offenders += "${file.relativeTo(root()).path}:${start + 1}"
                }
            }
        }
        assertTrue(
            "全量短名扫描必须带取消检查或委派有界扫描器（T-GutterDupFreeze 形态会冻住 EDT）：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun testLibrarySweepDoesNotInferTableName() {
        val file = root().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/psi/EntityClassFinder.kt")
        val lines = stripComments(file.readText()).lines()
        val idx = lines.indexOfFirst { it.contains("fun runLibrarySweepNow") }
        assertTrue("未定位到 runLibrarySweepNow（守卫已失效，需同步更新）", idx >= 0)
        val end = nextFunctionStart(lines, idx + 1) ?: lines.size
        val body = lines.subList(idx, end).joinToString("\n")
        assertTrue(
            "库扫描不得回落到「按类名推断表名」——那会把整条 classpath 的类都建成表（成本爆炸）",
            !body.contains("inferDecoratedTableName"),
        )
    }

    /**
     * 库扫描**不得被外层 `ReadAction` 包住**——它靠分片读动作（片间真正释放读锁）把「打字等读锁」
     * 从整趟（实测 5.5s）压到一片（实测 158ms）；外层再包一层会把读锁变回整趟持有，分片失效。
     */
    @Test
    fun testLibrarySweepIsNotWrappedInOuterReadAction() {
        val offenders = mutableListOf<String>()
        for (file in mainKotlinFiles()) {
            val lines = stripComments(file.readText()).lines()
            lines.forEachIndexed { idx, line ->
                if (!line.contains("ReadAction.compute")) return@forEachIndexed
                val window = lines.subList(idx, minOf(idx + 6, lines.size)).joinToString("\n")
                if (window.contains("runLibrarySweepNow") || window.contains("refreshCandidatesFromLibraries")) {
                    offenders += "${file.relativeTo(root()).path}:${idx + 1}"
                }
            }
        }
        assertTrue(
            "库扫描自带分片读动作，外层再包 ReadAction 会把读锁变回整趟持有（实测写锁等待 5.5s）：$offenders",
            offenders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------

    private fun mainKotlinFiles(): List<File> =
        root().resolve("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun root(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** 从 [idx] 往前找最近的成员函数声明行（恰好 4 空格缩进）；找不到返回 null。 */
    private fun functionStart(lines: List<String>, idx: Int): Int? {
        for (i in idx downTo 0) if (MEMBER_FUNCTION.containsMatchIn(lines[i])) return i
        return null
    }

    /** 从 [from] 往后找下一个成员函数声明行（函数体右界）；找不到返回 null。 */
    private fun nextFunctionStart(lines: List<String>, from: Int): Int? {
        for (i in from until lines.size) if (MEMBER_FUNCTION.containsMatchIn(lines[i])) return i
        return null
    }

    /** 剥离 block 注释与行注释（字符串字面量里的 `//` 会被误剥——只可能漏报不产生误报，可接受）。 */
    private fun stripComments(source: String): String =
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
            .replace(source) { m -> m.value.map { c -> if (c == '\n' || c == '\r') c else ' ' }.joinToString("") }
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    private companion object {
        /** 恰好 4 空格缩进的成员函数声明（8 空格的 companion/嵌套成员不作边界）。 */
        private val MEMBER_FUNCTION =
            Regex("""^ {4}(internal |private |public |protected )?(suspend )?fun [A-Za-z_]""")
    }
}
