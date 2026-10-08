package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-GutterDedup：直接加载**真实 demo 工程**的 HQL/DAO 文件，验证"每处调用恰一个预览图标、无重复、
 * 关键 HQL 调用被识别、`execute(callback)` 非字符串实参判 0"。
 *
 * 目标文件（用户指定的 demo 工程 `jpa-hibernate-demo`）：
 *  - `HibernateTemplateDemoServiceImpl`：`commonDao.findByQuery / findByQueryUniqueResult / execute`
 *  - `CommonDaoHibernateImpl`：`getHibernateTemplate().find / findByNamedParam / findByValueBean / execute(callback)` + `session.createQuery`
 *
 * 计数口径：方法调用名 leaf（父级为 [PsiReferenceExpression]）上 [HqlGutterLineMarkerProvider] /
 * [CustomDaoHqlGutterLineMarkerProvider] / [JdbcTemplateSqlGutterLineMarkerProvider] 三者产生 LineMarkerInfo 的个数。
 */
class DemoRecognitionTest : BasePlatformTestCase() {

    private val providers = listOf(
        HqlGutterLineMarkerProvider(),
        CustomDaoHqlGutterLineMarkerProvider(),
        JdbcTemplateSqlGutterLineMarkerProvider(),
    )

    private val previewableMethods = setOf(
        "findByQuery", "findByQueryUniqueResult", "execute", "find",
        "findByNamedParam", "findByValueBean", "createQuery", "createNativeQuery",
    )

    private val demoRoot = File("..", "mybatis-plus-sql-visualizer-demo/jpa-hibernate-demo/src/main/java/cn/song/jpademo")

    /** T-GutterDupFreeze：MP demo 工程根（`mybatis-plus-demo`）。 */
    private val mpDemoRoot = File("..", "mybatis-plus-sql-visualizer-demo/mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo")

    /**
     * T-GutterDupFreeze：本文件的双查 provider 集——链根锚点（[SqlGutterLineMarkerProvider] 场景 B/C）
     * 与假 HQL 锚点（[CustomDaoHqlGutterLineMarkerProvider]）。刻意不含方法定义类 provider，
     * 断言聚焦「同一条链几个锚点」。
     */
    private val mpChainProviders = listOf(
        SqlGutterLineMarkerProvider(),
        CustomDaoHqlGutterLineMarkerProvider(),
    )

    private fun callLeavesIn(): List<PsiIdentifier> =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .filter { it.parent is PsiReferenceExpression && it.text in previewableMethods }

    private fun iconCount(leaf: PsiIdentifier): Int = providers.count { it.getLineMarkerInfo(leaf) != null }

    private fun loadDemoFile(relative: String): File {
        val f = File(demoRoot, relative)
        assertTrue("demo 文件应存在: $f", f.exists())
        myFixture.configureByText(f.name, f.readText())
        return f
    }

    private fun loadMpDemoFile(relative: String): File {
        val f = File(mpDemoRoot, relative)
        assertTrue("demo 文件应存在: $f", f.exists())
        myFixture.configureByText(f.name, f.readText())
        return f
    }

    fun testDemo_Service_commonDao_calls_singleIcon() {
        loadDemoFile("service/impl/HibernateTemplateDemoServiceImpl.java")
        val leaves = callLeavesIn()
        assertTrue("demo 服务应识别到若干预览候选调用点", leaves.isNotEmpty())
        // 1) 无重复：任一调用点至多一个图标
        for (leaf in leaves) {
            assertTrue("调用 ${leaf.text} 不得重复图标（count=${iconCount(leaf)}）", iconCount(leaf) <= 1)
        }
        // 2) 关键 HQL 调用均被识别（恰 1）
        for (name in setOf("findByQuery", "findByQueryUniqueResult", "execute")) {
            val sites = leaves.filter { it.text == name }
            assertTrue("demo 服务应含 $name 调用", sites.isNotEmpty())
            for (leaf in sites) {
                assertEquals("commonDao.$name 应恰 1 图标（不再双图标）", 1, iconCount(leaf))
            }
        }
    }

    fun testDemo_DaoImpl_hibernateTemplate_calls_recognized() {
        loadDemoFile("dao/impl/CommonDaoHibernateImpl.java")
        val leaves = callLeavesIn()
        assertTrue("demo DAO 实现应识别到若干预览候选调用点", leaves.isNotEmpty())
        // 1) 无重复
        for (leaf in leaves) {
            assertTrue("调用 ${leaf.text} 不得重复图标（count=${iconCount(leaf)}）", iconCount(leaf) <= 1)
        }
        // 2) HibernateTemplate HQL 字符串方法被识别（find/findByNamedParam/findByValueBean 各恰 1）
        for (name in setOf("find", "findByNamedParam", "findByValueBean")) {
            val sites = leaves.filter { it.text == name }
            assertTrue("demo DAO 实现应含 $name 调用", sites.isNotEmpty())
            for (leaf in sites) {
                assertEquals("getHibernateTemplate().$name 应恰 1 图标（补齐缺失）", 1, iconCount(leaf))
            }
        }
        // 3) execute(callback) 非字符串实参 → 0 图标（不再被 Jdbc/CustomDaoHql 误标）
        val executeSites = leaves.filter { it.text == "execute" }
        assertTrue("demo DAO 实现应含 execute(callback) 调用", executeSites.isNotEmpty())
        for (leaf in executeSites) {
            assertEquals("getHibernateTemplate().execute(callback) 应 0 图标（非字符串实参）", 0, iconCount(leaf))
        }
        // 4) session.createQuery 各恰 1
        for (leaf in leaves.filter { it.text == "createQuery" }) {
            assertEquals("session.createQuery 应恰 1 图标（Hql）", 1, iconCount(leaf))
        }
    }

    /**
     * T-GutterDupFreeze（S1+S2，20261008 用户真机）：MP 工厂链复现文件——真实 demo 工程语料
     * `WrapperFactoryCoverageServiceImpl.listByBareFactoryChainExists`。
     *
     * 修复前实测（像素级图标归属核对）：三条 `userMapper.selectList(<wrapper>)` 行各 2 枚本插件图标
     * （假 HQL 锚点 + 批量锚点），全方法体 10 枚；修复后应为 4 枚——四条链各 1 枚，落在链根
     * （`Wrappers.<User>lambdaQuery`，场景 C）与变量声明（`variableWrapper`，场景 B）。
     *
     * 本用例是「一行两枚」与「整文件图标数」两类断言的判别点：把 S1 的字符串承载门控或 S2 的
     * 实参让位摘掉，必有一条变红（见计划 §S1/S2 反向验证）。
     */
    fun testDemo_MpFactoryChain_singleAnchorPerChain() {
        loadMpDemoFile("service/impl/WrapperFactoryCoverageServiceImpl.java")
        val method = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethod::class.java)
            .first { it.name == "listByBareFactoryChainExists" }
        val leaves = PsiTreeUtil.collectElementsOfType(method, PsiIdentifier::class.java)

        fun chainIconCount(leaf: PsiIdentifier): Int =
            mpChainProviders.count { it.getLineMarkerInfo(leaf) != null }

        // 1) 批量/假 HQL 锚点不得落在 mapper 消费调用上（selectList 三条、selectPage 一条）
        for (name in listOf("selectList", "selectPage")) {
            val sites = leaves.filter { it.text == name && it.parent is PsiReferenceExpression }
            assertTrue("复现用例应含 $name 调用", sites.isNotEmpty())
            for (leaf in sites) {
                assertEquals(
                    "$name(<wrapper>) 处应 0 图标（批量锚点让位 + 内容启发不得把链源码当 HQL）",
                    0, chainIconCount(leaf),
                )
            }
        }
        // 2) 链内嵌字符串 SQL 的条件方法（exists/notExists）不被任何 provider 认领
        for (name in listOf("exists", "notExists")) {
            for (leaf in leaves.filter { it.text == name && it.parent is PsiReferenceExpression }) {
                assertEquals("$name 条件方法处应 0 图标", 0, chainIconCount(leaf))
            }
        }
        // 3) 链根图标分布＝[0,1,1,1]：三条内联工厂链各 1 枚；变量声明形态那条
        //    （`variableWrapper = Wrappers.<User>lambdaQuery(...)`）按既有去重规则让位给变量名锚点（0 枚）
        val roots = leaves.filter { it.text == "lambdaQuery" && it.parent is PsiReferenceExpression }
        assertEquals("复现用例应含四条 lambdaQuery 调用（三条内联 + 一条在变量声明初始化式内）", 4, roots.size)
        assertEquals(
            "链根图标分布应为 [0,1,1,1]（含去重让位那条）",
            listOf(0, 1, 1, 1), roots.map { chainIconCount(it) }.sorted(),
        )
        // 4) 变量声明形态（第三条链）锚在变量名上，恰 1
        val declared = leaves.filter { it.text == "variableWrapper" && it.parent is com.intellij.psi.PsiVariable }
        assertEquals("复现用例应含变量声明形态", 1, declared.size)
        assertEquals("变量声明行应恰 1 图标（场景 B）", 1, chainIconCount(declared.first()))
        // 5) 方法体内图标总数 = 4（修复前 10）——多出的 6 枚即重复面
        val iconLeaves = leaves.filter { chainIconCount(it) > 0 }
        assertEquals(
            "listByBareFactoryChainExists 方法体应恰 4 枚预览图标，实际落在：" +
                iconLeaves.map { it.text + "@line" + lineOf(it) },
            4, iconLeaves.size,
        )
    }

    private fun lineOf(leaf: PsiIdentifier): Int = runCatching {
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(leaf.containingFile)
        (doc?.getLineNumber(leaf.textRange.startOffset) ?: -1) + 1
    }.getOrDefault(-1)
}
