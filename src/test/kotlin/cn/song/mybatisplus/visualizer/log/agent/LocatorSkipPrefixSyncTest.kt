package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-AgentSourceLocateStale T-03：两侧「框架帧跳过前缀」的跨侧同步守卫（保守选项 —— 保持两份实现 +
 * 差异登记，不抽共享常量；计划 §八 D3 未定案，故不动结构，只把口径钉死）。
 *
 * 背景：agent 侧 `StackLocator` 的 KDoc 曾自称「框架帧过滤规则与 IDE 侧 `CallerSourceLocator` 对齐」，
 * 实测**失实** —— agent 侧另收驱动族 / 连接池 / 数据层插件族二十余项，IDE 侧只收 JDK / 平台 / 本插件 /
 * MyBatis 系。两侧还是**独立两份实现**（agent 禁依赖 IDE 类），且**在不同时机取栈**（IDE 侧拦截器在
 * SQL 生成期、agent 在 JDBC 执行期），故同一次执行的两处位置**可以不同** —— 两侧 KDoc 已改为事实描述。
 *
 * 本测试以源码文本扫描锁住差异（形态照 [AgentProtocolSyncTest] 的跨侧扫描）：
 *  1. 两侧共同前缀集合须互为子集且含锚点（防「两边都提取为空」的空绿）；
 *  2. 差异集合须与下方登记的清单**完全一致** —— 任一侧新增/删除前缀而未同步登记即转红；
 *  3. `docs/instrumentation/SqlSourceInterceptor.kt`（发给用户的示例副本）的跳过规则须与插件侧同步。
 */
class LocatorSkipPrefixSyncTest {

    /** 从 user.dir 逐级上溯定位仓库根（与 [AgentProtocolSyncTest.repoRoot] 同款）。 */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根: ${System.getProperty("user.dir")}")
    }

    private fun readSource(relative: String): String {
        val f = File(repoRoot(), relative)
        if (!f.isFile) throw AssertionError("源文件未找到: $relative（本守卫按源码文本扫描，文件缺失即失去锚点）")
        return f.readText()
    }

    private fun agentSource(): String =
        readSource("sub-projects/sql-visualizer-agent/src/main/java/cn/song/mybatisplus/sqlvis/agent/util/StackLocator.java")

    private fun ideSource(): String =
        readSource("src/main/kotlin/cn/song/mybatisplus/visualizer/log/CallerSourceLocator.kt")

    private fun templateSource(): String = readSource("docs/instrumentation/SqlSourceInterceptor.kt")

    // ---------- 断言 ----------

    @Test
    fun 两侧共同前缀互为子集_差异集合与登记清单完全一致() {
        val agent = agentSkipPrefixes(agentSource())
        val ide = startsWithLiterals(functionBody(ideSource(), "isFrameworkFrame"))

        // 提取有效性自检（防空绿）：共同集合须达到量级并含锚点
        val shared = ide.intersect(agent)
        assertTrue("共同前缀集合过小（疑似提取失效）: $shared", shared.size >= 10)
        assertFalse("提取不得把 defaultLoadedFromJar 的 .jar!/.jar/ 当成跳过前缀（实测踩过）", ".jar!" in agent)
        assertFalse("提取不得把 defaultLoadedFromJar 的 .jar!/.jar/ 当成跳过前缀", ".jar/" in agent)
        for (anchor in listOf("java.", "javax.", "org.apache.ibatis.", "com.baomidou.", "com.github.yulichang.")) {
            assertTrue("两侧共同前缀应含 $anchor（实为 $shared）", anchor in shared)
        }
        // 方向一：IDE 侧集合 = 共同集合 ∪ IDE 独有（互为子集的口径）
        assertEquals("IDE 侧独有前缀与登记不符（新增/删除须同步本清单）", IDE_ONLY, ide - agent)
        // 方向二：agent 侧集合 = 共同集合 ∪ agent 独有
        assertEquals("agent 侧独有前缀与登记不符（新增/删除须同步本清单）", AGENT_ONLY, agent - ide)
    }

    @Test
    fun 示例模板跳过规则与插件侧同步() {
        val ide = startsWithLiterals(functionBody(ideSource(), "isFrameworkFrame"))
        val template = startsWithLiterals(functionBody(templateSource(), "isFrameworkFrame"))

        assertEquals("模板出现插件侧没有的前缀（模板已跑偏）", emptySet<String>(), template - ide)
        assertEquals(
            "插件侧新增前缀未同步到示例模板（用户拷贝后位置会算错）",
            TEMPLATE_ABSENT_FROM_PLUGIN_SIDE,
            ide - template,
        )
        assertTrue("模板应含 MPJ 前缀（T-02）", "com.github.yulichang." in template)
    }

    @Test
    fun agent侧跳过表覆盖MPJ全族() {
        val agent = agentSkipPrefixes(agentSource())
        assertTrue("agent 侧应含 MPJ 全族前缀（T-02）", "com.github.yulichang." in agent)
        // 同族先例仍在（PageHelper 那次「漏收即被误选」的实证不可回退）
        assertTrue("同族先例 com.github.pagehelper. 不得丢失", "com.github.pagehelper." in agent)
        assertTrue("同族先例 com.github.benmanes. 不得丢失", "com.github.benmanes." in agent)
    }

    // ---------- 源码提取 ----------

    /** 取 Kotlin 函数声明（到下一个函数声明为止）的源码片段。 */
    private fun functionBody(text: String, name: String): String {
        val at = text.indexOf("fun $name(")
        if (at < 0) throw AssertionError("未找到函数声明 fun $name( —— 跳过规则搬家后须同步本守卫")
        val tail = text.substring(at + 1)
        val next = NEXT_FUN.find(tail)
        return if (next == null) tail else tail.substring(0, next.range.first)
    }

    /** 片段内所有 `startsWith("…")` 字面量（Kotlin 源码转义还原）。 */
    private fun startsWithLiterals(snippet: String): Set<String> =
        STARTS_WITH.findAll(snippet).map { unescape(it.groupValues[1]) }.toSet()

    /** agent 侧：`FRAME_SKIP_PREFIXES` 数组字面量 + `isFrameworkFrame` 方法体里的 `contains("…")`（`$Proxy`）。 */
    private fun agentSkipPrefixes(java: String): Set<String> {
        val start = java.indexOf("FRAME_SKIP_PREFIXES = {")
        if (start < 0) throw AssertionError("agent 侧未找到 FRAME_SKIP_PREFIXES 数组")
        val end = java.indexOf("};", start)
        if (end < 0) throw AssertionError("agent 侧 FRAME_SKIP_PREFIXES 数组未闭合")
        // 注释行剔除：注释里可能出现被讨论的类名，不得计入前缀集合
        val body = java.substring(start, end).lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        val out = LinkedHashSet<String>()
        STRING_LITERAL.findAll(body).forEach { out.add(unescape(it.groupValues[1])) }
        // `contains("…")` 只在 isFrameworkFrame 方法体内取——同文件 defaultLoadedFromJar 里也有
        // contains(".jar!") / contains(".jar/")，全文件扫会把它们误当跳过前缀（实测踩过）
        CONTAINS_LITERAL.findAll(javaMethodBody(java, "isFrameworkFrame"))
            .forEach { out.add(unescape(it.groupValues[1])) }
        return out
    }

    /** 取 Java 方法体（自声明起至下一个 4 空格缩进的收尾 `}` 为止）。 */
    private fun javaMethodBody(java: String, name: String): String {
        val at = java.indexOf("$name(String")
        if (at < 0) throw AssertionError("未找到 Java 方法声明 $name(String")
        val tail = java.substring(at)
        val end = JAVA_METHOD_END.find(tail)
        return if (end == null) tail else tail.substring(0, end.range.first)
    }

    private fun unescape(raw: String): String = raw.replace("\\\$", "$").replace("\\\\", "\\")

    companion object {
        private val NEXT_FUN = Regex("""\n\s*(?:private\s+|public\s+|internal\s+)?fun\s""")
        private val JAVA_METHOD_END = Regex("\\n    \\}")
        private val STARTS_WITH = Regex("startsWith\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)")
        private val STRING_LITERAL = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        private val CONTAINS_LITERAL = Regex("contains\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)")

        /**
         * IDE 侧独有前缀（登记清单，勿凭印象增删）。仅一项 = 本插件自身包前缀；
         * agent 侧对应项是它自己的包前缀 `cn.song.mybatisplus.sqlvis.`（字符串不同故各记一条，
         * 语义等价 = 各自跳过自身模块帧）。
         */
        private val IDE_ONLY = setOf("cn.song.mybatisplus.visualizer.")

        /**
         * agent 侧独有前缀（登记清单）：驱动族 / 连接池 / 数据层插件族 —— 这些帧在 agent 的取栈点
         * （JDBC 执行期，含 MyBatis 插件链与自研汇聚门面）上真实出现，而 IDE 侧取栈点（SQL 生成期）
         * 不经它们。**新增/删除任一项都必须同步本清单**，否则本守卫转红（差异必须被登记，不能静默漂移）。
         */
        private val AGENT_ONLY = setOf(
            "com.sun.", "jdk.internal.", "cn.song.mybatisplus.sqlvis.",
            "com.mysql.", "org.mariadb.", "org.postgresql.", "org.h2.", "org.hsqldb.",
            "org.sqlite.", "com.microsoft.sqlserver.", "oracle.jdbc.", "dm.jdbc.",
            "com.gbasedbt.", "com.kingbase8.",
            "com.alibaba.druid.", "com.mchange.", "org.apache.commons.dbcp2.", "org.apache.tomcat.",
            "org.aspectj.", "net.sf.cglib.", "org.objectweb.asm.", "org.jboss.",
            "com.github.pagehelper.", "org.mybatis.", "net.sf.jsqlparser.",
            "com.alibaba.", "org.apache.", "com.google.", "com.fasterxml.",
            "io.netty.", "io.lettuce.", "redis.clients.", "io.micrometer.", "io.seata.",
            "org.slf4j.", "ch.qos.", "org.ehcache.", "net.sf.ehcache.", "com.github.benmanes.",
            "org.quartz.", "com.netflix.", "org.elasticsearch.", "com.mongodb.",
        )

        /**
         * 示例模板相对插件侧**刻意缺失**的前缀：本插件自身包 —— 模板拷进用户工程后包名不同，
         * 它用 `cn == javaClass.name` 的自身帧跳过替代（见模板内注释）。
         */
        private val TEMPLATE_ABSENT_FROM_PLUGIN_SIDE = setOf("cn.song.mybatisplus.visualizer.")
    }
}
