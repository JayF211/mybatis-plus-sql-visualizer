package cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * N6：`JoinVirtualFkProvider.getForeignKeys` 两参契约的**源码级**守卫。
 *
 * 为什么必须用源码级而不是行为级：该 provider 的签名与实现体都引用 `com.intellij.database.*`，
 * 而本仓 plain `test` 分片**没有 database jar**（database 链的用例在 `testDatabase` 分片），
 * 所以「provider 见到 `refTable == null` 是否早退」这件事在纯 JVM 里调不动——
 * 而它正是本次故障的那一行（历史形态：`if (refTable == null) return JBIterable.empty()`，
 * 导致 `DbDiagramDataModel.calculateEdges` 建边恒空、原生图只剩节点没有连线）。
 * 纯 JVM 那侧的守卫只能覆盖到 `JoinFkRegistry.selectForCall`（契约语义），覆盖不到「provider 有没有绕过它」。
 *
 * 覆盖边界（诚实标注，勿当全能门禁）：本守卫只认「在 `getForeignKeys` 体内对 `refTable` 做 null 早退」
 * 这一形态。若改写成 `refTable?.let {} ?: return JBIterable.empty()`、`when (refTable) { null -> return … }`
 * 之类，本守卫**会漏**。行为级覆盖在 `testDatabase` 的
 * `HeavyNativeDatabaseDiagramJoinChainTest`（直接以 `refTable = null` 调 provider + 断言引用表同实例）
 * 与真机走查；三者不可互相替代。
 */
class JoinVirtualFkProviderContractTest {

    @Test
    fun providerMustNotEarlyReturnOnNullRefTable() {
        val body = methodBody(providerSource(), "getForeignKeys")
        assertTrue(
            "未能从源码里切出 getForeignKeys 方法体（守卫自身失效，须先修守卫而不是删断言）",
            body.isNotBlank(),
        )
        val nullGuards = Regex("""if\s*\(\s*refTable\s*==\s*null\s*\)""").findAll(body).toList()
        assertTrue(
            "JoinVirtualFkProvider.getForeignKeys 不得对 refTable == null 早退：该入参为 null 表示平台的" +
                "**两参建边链**（ModelRelationManager.getForeignKeys(Project, DasTable) 字节码恒定传 null），" +
                "契约是「不做引用表过滤、返回该表全部外键」；早退会让原生图连线恒空。" +
                "命中 ${nullGuards.size} 处。",
            nullGuards.isEmpty(),
        )
    }

    @Test
    fun providerMustDelegateSelectionToRegistryContract() {
        // 正对照：光「删掉早退」还不够——关系集合必须经 selectForCall 取，
        // 否则另一种退化（分支没了但也没接上两参语义）不会被上面那条抓住。
        val body = methodBody(providerSource(), "getForeignKeys")
        assertTrue("未能切出 getForeignKeys 方法体", body.isNotBlank())
        assertTrue(
            "getForeignKeys 必须经 JoinFkRegistry.selectForCall 取关系集合（N6 两参契约的承载点）",
            body.contains("selectForCall"),
        )
    }

    // ===== 源码定位与掩码（沿用 BannedApiSourceScanTest 的约定）=====

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    private fun providerSource(): String = maskNonCode(
        repoRoot().resolve(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/diagram/nativefw/db/JoinVirtualFkProvider.kt",
        ).readText(),
    )

    /**
     * 涂白注释与字符串字面量（**逐行保行**，便于按缩进/花括号切方法体）。
     *
     * 必须涂：本 provider 的 KDoc 里就写着「不要在此对 refTable == null 早退」，
     * 不涂会把守卫自己的说明文字当成违规命中（假红）。
     */
    private fun maskNonCode(source: String): String {
        val noBlockComments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
            .replace(source) { m -> m.value.map { if (it == '\n') '\n' else ' ' }.joinToString("") }
        val noTripleQuoted = Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL)
            .replace(noBlockComments) { m -> m.value.map { if (it == '\n') '\n' else ' ' }.joinToString("") }
        return noTripleQuoted.lineSequence().joinToString("\n") { line ->
            val withoutLineComment = line.substringBefore("//")
            // 单行字符串字面量涂白（保留引号位置，长度不影响按行切片）
            Regex("\"(\\\\.|[^\"\\\\])*\"").replace(withoutLineComment) { m ->
                "\"" + " ".repeat((m.value.length - 2).coerceAtLeast(0)) + "\""
            }
        }
    }

    /** 切出名为 [name] 的函数体（含签名行），按花括号配对找闭合；找不到返回空串。 */
    private fun methodBody(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        if (start < 0) return ""
        val open = source.indexOf('{', start)
        if (open < 0) return ""
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        return ""
    }
}
