package cn.song.mybatisplus.visualizer.psi

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Java 真实站点的全量短名扫描守卫，不依赖缩进/下一函数/固定六行窗口。
 * 先词法遮蔽注释、字符串、字符和文本块，再平衡括号定位成员函数与 ReadAction 实参范围。
 * 枚举命中必须成功归属方法；委派只接受 EntityAliasSweep 的真实受限、可取消内核。
 * 反向判据直接在真实生产文本的内存副本删修复/加外层锁，不改生产树、不靠合成小样本假绿。
 */
class FullScanCancelGuardTest {
    private data class Method(val name: String, val start: Int, val open: Int, val end: Int) {
        fun body(source: String) = source.substring(open + 1, end)
    }

    @Test
    fun testEveryFullShortNameScanHasCancelCheck() {
        val sources = mainSources()
        val sites = sources.values.sumOf { ALL_NAMES.findAll(mask(it)).count() }
        assertTrue("未命中真实 getAllClassNames，禁止空扫描绿", sites >= 3)
        val offenders = cancelOffenders(sources)
        assertTrue("全量短名扫描须可取消，或委派真实有界扫描器：$offenders", offenders.isEmpty())
    }

    @Test
    fun testLibrarySweepDoesNotInferTableName() {
        val source = finderSource()
        val body = method(source, "runLibrarySweepNow").body(mask(source))
        assertTrue("库扫描必须真实枚举 classpath", ALL_NAMES.containsMatchIn(body))
        assertTrue("库扫描必须使用 @TableName", body.contains("tableNameAnnotation("))
        assertTrue("不得把 classpath 所有类按名字推断成表", annotationOnly(body))
        assertTrue("必须保留剩余预算而不是按整片截断", body.contains("limit - scanned"))
        assertTrue("必须保留分片读动作与片间取消点", slicedSweep(body))
    }

    @Test
    fun testLibrarySweepIsNotWrappedInOuterReadAction() {
        val sources = mainSources()
        val finder = sources.getValue(FINDER)
        val dialog = sources.getValue(DIALOG)
        assertTrue("后台投递真实调用点丢失", method(finder, "requestLibrarySweep").body(mask(finder)).contains("runLibrarySweepNow("))
        assertTrue("按钮真实调用点丢失", method(dialog, "scanLibrariesAndRefresh").body(mask(dialog)).contains("refreshCandidatesFromLibraries("))
        assertTrue("刷新内核必须调用扫库", method(dialog, "refreshCandidatesFromLibraries").body(mask(dialog)).contains("runLibrarySweepNow("))
        val offenders = outerReadOffenders(sources)
        assertTrue("分片扫库及刷新内核不能被外层 ReadAction 包裹：$offenders", offenders.isEmpty())
    }

    /** 每个真实直扫函数删掉取消点必须报红；不只用一个 synthetic sample 自证规则。 */
    @Test
    fun testRemovingRealCancelChecksIsRejected() {
        val sources = mainSources()
        val baseline = cancelOffenders(sources)
        assertTrue("正向基线须先无违规：$baseline", baseline.isEmpty())
        var mutations = 0
        for ((path, source) in sources) {
            val clean = mask(source)
            for (m in methods(clean)) {
                val body = m.body(clean)
                if (!ALL_NAMES.containsMatchIn(body) || !CANCEL.containsMatchIn(body)) continue
                val changed = source.replaceRange(m.open + 1, m.end, CANCEL.replace(source.substring(m.open + 1, m.end), ""))
                assertTrue("未删到真实取消点 $path#${m.name}", changed != source)
                val offenders = cancelOffenders(sources + (path to changed))
                assertTrue("真实站点删修复必须红 $path#${m.name}：$offenders", offenders.any { it.startsWith("$path#${m.name}") })
                mutations++
            }
        }
        assertTrue("至少覆盖项目慢扫和库分片两个真实站点", mutations >= 2)
        val alias = sources.getValue(ALIAS)
        val changedAlias = CANCEL.replace(alias, "")
        assertTrue("委派内核取消点必须存在", changedAlias != alias)
        assertTrue("删掉真实委派内核取消点后不能靠类名白名单放行",
            cancelOffenders(sources + (ALIAS to changedAlias)).any { it.startsWith(ALIAS) })
    }

    @Test
    fun testWrappingRealSweepCallSitesIsRejected() {
        val sources = mainSources()
        val mutations = listOf(
            Triple(FINDER, "int entries = runLibrarySweepNow();", "int entries = ReadAction.compute(() -> runLibrarySweepNow());"),
            Triple(DIALOG, "result = refreshCandidatesFromLibraries();", "result = ReadAction.<List<TableRow>, RuntimeException>compute(() -> refreshCandidatesFromLibraries());"),
            Triple(DIALOG, "new EntityClassFinder(project).runLibrarySweepNow();", "ReadAction.compute(() -> { return new EntityClassFinder(project).runLibrarySweepNow(); });"),
        )
        for ((path, old, replacement) in mutations) {
            val source = sources.getValue(path)
            assertTrue("真实调用形态变化，需更新反向判据：$path / $old", source.contains(old))
            val offenders = outerReadOffenders(sources + (path to source.replace(old, replacement)))
            assertTrue("真实调用点增加外层锁必须红：$path", offenders.any { it.startsWith(path) })
        }
    }

    @Test
    fun testRealSweepInferenceAndSliceRegressionsAreRejected() {
        val source = finderSource()
        val m = method(source, "runLibrarySweepNow")
        val body = m.body(mask(source))
        val call = "String table = tableNameAnnotation(clazz);"
        assertTrue("真实注解读取站点必须存在", body.contains(call))
        val inferred = body.replace(call, "String table = EntityResolver.inferDecoratedTableName(shortName, project);")
        assertTrue("恢复按类名推断的真实形态必须被识别", !annotationOnly(inferred))
        val whileAt = body.indexOf("while (")
        assertTrue("真实分片循环必须存在", whileAt >= 0)
        val wrapped = body.substring(0, whileAt) + "ReadAction.compute(() -> {" + body.substring(whileAt) + " return null; });"
        assertTrue("整趟外包 ReadAction 必须使分片判据变红", !slicedSweep(wrapped))
    }

    private fun annotationOnly(body: String): Boolean = !body.contains("inferDecoratedTableName")

    private fun cancelOffenders(sources: Map<String, String>): List<String> {
        val aliasSource = mask(sources.getValue(ALIAS))
        val boundedDelegates = methods(aliasSource).filter {
            (it.name == "findAliasCandidates" || it.name == "findAliasOwner") &&
                CANCEL.containsMatchIn(it.body(aliasSource)) &&
                Regex("scanned\\s*>=\\s*limit").containsMatchIn(it.body(aliasSource))
        }.map { it.name }.toSet()
        val offenders = mutableListOf<String>()
        for ((path, source) in sources) {
            val clean = mask(source)
            val members = methods(clean)
            for (call in ALL_NAMES.findAll(clean)) {
                val owner = members.filter { call.range.first in (it.open + 1) until it.end }.minByOrNull { it.end - it.open }
                if (owner == null) {
                    offenders += "$path#UNMAPPED@${call.range.first}"
                    continue
                }
                val body = owner.body(clean)
                val delegated = path == ALIAS && boundedDelegates.any { name ->
                    Regex("\\b${Regex.escape(name)}\\s*\\(").containsMatchIn(body)
                }
                if (!CANCEL.containsMatchIn(body) && !delegated) offenders += "$path#${owner.name}"
            }
        }
        return offenders.distinct()
    }

    private fun outerReadOffenders(sources: Map<String, String>): List<String> = buildList {
        for ((path, source) in sources) {
            val clean = mask(source)
            for (call in READ_ACTION.findAll(clean)) {
                val open = clean.indexOf('(', call.range.first)
                val end = paired(clean, open, '(', ')')
                val args = clean.substring(open + 1, end)
                if (Regex("\\b(runLibrarySweepNow|refreshCandidatesFromLibraries)\\s*\\(").containsMatchIn(args)) {
                    add("$path@${call.range.first}")
                }
            }
        }
    }

    /** checkCanceled 位于分片 while 内且不在任何读动作内；至少两个读动作（枚举 + 片内）。 */
    private fun slicedSweep(body: String): Boolean {
        val loop = Regex("\\bwhile\\s*\\(").find(body) ?: return false
        val conditionEnd = paired(body, body.indexOf('(', loop.range.first), '(', ')')
        val loopOpen = body.indexOf('{', conditionEnd)
        if (loopOpen < 0) return false
        val loopEnd = paired(body, loopOpen, '{', '}')
        val reads = READ_ACTION.findAll(body).map {
            val open = body.indexOf('(', it.range.first)
            it.range.first..paired(body, open, '(', ')')
        }.toList()
        return reads.size >= 2 && reads.none { loop.range.first in it } &&
            CANCEL.findAll(body).any { it.range.first in (loopOpen + 1) until loopEnd && reads.none { r -> it.range.first in r } } &&
            reads.any { it.first in (loopOpen + 1) until loopEnd }
    }

    private fun methods(clean: String): List<Method> = buildList {
        for (signature in METHOD.findAll(clean)) {
            val openParen = clean.indexOf('(', signature.range.first)
            val closeParen = paired(clean, openParen, '(', ')')
            val suffix = clean.substring(closeParen + 1)
            val bodyStart = Regex("^\\s*(?:throws\\s+[\\w.$,\\s]+)?\\{").find(suffix) ?: continue
            val open = closeParen + 1 + bodyStart.value.lastIndexOf('{')
            add(Method(signature.groupValues[1], signature.range.first, open, paired(clean, open, '{', '}')))
        }
    }

    private fun method(source: String, name: String): Method {
        val hits = methods(mask(source)).filter { it.name == name }
        check(hits.size == 1) { "未唯一定位 Java 真实函数 $name，命中 ${hits.size}" }
        return hits.single()
    }

    private fun paired(source: String, from: Int, left: Char, right: Char): Int {
        check(from >= 0 && source[from] == left) { "括号起点失效 $from" }
        var depth = 0
        for (i in from until source.length) {
            if (source[i] == left) depth++
            if (source[i] == right && --depth == 0) return i
        }
        error("未闭合 $left @ $from")
    }

    /** 保留字符偏移与换行；字符串中的 //、花括号、假 API 名不能干扰扫描。 */
    private fun mask(source: String): String {
        val chars = source.toCharArray()
        var i = 0
        fun blank(from: Int, until: Int) {
            for (j in from until until) if (chars[j] != '\n' && chars[j] != '\r') chars[j] = ' '
        }
        while (i < source.length) {
            val start = i
            when {
                source.startsWith("//", i) -> {
                    i = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                    blank(start, i)
                }
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2)
                    check(end >= 0) { "未闭合块注释" }
                    i = end + 2
                    blank(start, i)
                }
                source.startsWith("\"\"\"", i) -> {
                    i += 3
                    while (i < source.length && !source.startsWith("\"\"\"", i)) {
                        i += if (source[i] == '\\') 2 else 1
                    }
                    i = minOf(source.length, i + 3)
                    blank(start, i)
                }
                source[i] == '"' || source[i] == '\'' -> {
                    val quote = source[i++]
                    while (i < source.length) {
                        if (source[i] == '\\') { i = minOf(source.length, i + 2); continue }
                        if (source[i++] == quote) break
                    }
                    blank(start, i)
                }
                else -> i++
            }
        }
        return String(chars)
    }

    private fun finderSource() = mainSources().getValue(FINDER)
    private fun mainSources(): Map<String, String> {
        val root = root()
        val files = root.resolve("src/main/java").walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        check(files.isNotEmpty()) { "Java 生产树为空，禁止空绿" }
        return files.associate { it.relativeTo(root.resolve("src/main/java")).invariantSeparatorsPath to it.readText() }
    }

    private fun root(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (dir.resolve("src/main/java").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到 src/main/java：${System.getProperty("user.dir")}")
    }

    private companion object {
        const val FINDER = "cn/song/mybatisplus/visualizer/psi/EntityClassFinder.java"
        const val DIALOG = "cn/song/mybatisplus/visualizer/ui/EntitySelectionDialog.java"
        const val ALIAS = "cn/song/mybatisplus/visualizer/psi/jpa/EntityAliasSweep.java"
        val ALL_NAMES = Regex("\\bgetAllClassNames\\s*\\(")
        val CANCEL = Regex("\\bProgressManager\\s*\\.\\s*checkCanceled\\s*\\(\\s*\\)\\s*;")
        val READ_ACTION = Regex("\\bReadAction\\s*\\.\\s*(?:<[^;{}()]+>\\s*)?(?:compute|run)\\s*\\(")
        val METHOD = Regex("(?m)^[ \\t]*(?:(?:public|protected|private|static|final|synchronized|native|strictfp|default)\\s+)+(?:[\\w${'$'}@.<>,?\\[\\]\\s]+\\s+)?([A-Za-z_${'$'}][\\w${'$'}]*)\\s*\\(")
    }
}
