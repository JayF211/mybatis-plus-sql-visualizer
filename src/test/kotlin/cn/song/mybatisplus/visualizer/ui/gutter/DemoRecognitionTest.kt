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
}
