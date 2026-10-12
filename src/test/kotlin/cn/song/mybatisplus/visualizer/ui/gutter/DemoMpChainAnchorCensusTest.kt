package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiKeyword
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * MP wrapper「链锚点」自查（T-MpChain-Selfcheck，只读定位用）。
 *
 * 来龙去脉：沙盒实测「链锚点拿不到、方法声明行锚点拿得到」——本类把两件事分开测：
 *  1. **真实 demo 文件普查**：直接加载 demo 的 `UserServiceImpl.java`（**不需要类路径解析**，
 *     行标记快速路径是纯语法判定），逐 provider 逐行统计 line marker，回答「链锚点在哪几行、
 *     方法声明行的图标到底是谁画的」；
 *  2. **光标位置 → 预览产出**：用与 demo 同形的最小夹具（局部变量链 + 链内 caret + 消费调用
 *     caret）断言三处 caret 都能出预览 SQL（`preview(list(w))` 形态，对齐 A24 第 108 行实测）。
 *
 * 环境口径：真实 demo 文件在 Light 测试环境无 MP jar，**预览**不可用（实体解析要 resolve），
 * 故本类只对真实文件做**行标记普查**；预览断言放同形夹具（对齐 GutterPreviewConsistencyTest 惯例）。
 */
class DemoMpChainAnchorCensusTest : BasePlatformTestCase() {

    private val demoRoot = File(
        "..",
        "mybatis-plus-sql-visualizer-demo/mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo",
    )

    /** 全量行标记 provider（plugin.xml 注册清单的同构镜像）。 */
    private val providers: List<LineMarkerProvider> = listOf(
        SqlGutterLineMarkerProvider(),
        StringSqlMethodGutterLineMarkerProvider(),
        XmlSqlGutterLineMarkerProvider(),
        PagesXmlGutterLineMarkerProvider(),
        HbmNamedQueryGutterLineMarkerProvider(),
        AnnotationSqlGutterLineMarkerProvider(),
        NamedQueryGutterLineMarkerProvider(),
        JpaGutterLineMarkerProvider(),
        JpqlGutterLineMarkerProvider(),
        CriteriaQueryGutterLineMarkerProvider(),
        HqlGutterLineMarkerProvider(),
        CustomDaoHqlGutterLineMarkerProvider(),
        JdbcTemplateSqlGutterLineMarkerProvider(),
        XmlInlineSqlGutterLineMarkerProvider(),
    )

    private fun loadRealDemoFile(relative: String) {
        val f = File(demoRoot, relative)
        assertTrue("demo 文件应存在: $f", f.exists())
        myFixture.configureByText(f.name, f.readText())
    }

    /** 真实 demo 文件的「链锚点在哪几行 / 方法行图标是谁画的」普查。 */
    fun testRealUserServiceImpl_行标记普查与链锚点行() {
        loadRealDemoFile("service/impl/UserServiceImpl.java")
        val doc = myFixture.file.viewProvider.document!!
        val leaves = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java).toList() +
            PsiTreeUtil.collectElementsOfType(myFixture.file, PsiKeyword::class.java).toList()

        // 行 → provider 名（marker 所在行按 anchor 元素 textRange 计算，与 IDE 渲染同口径）
        val census = sortedMapOf<Int, MutableList<String>>()
        for (p in providers) {
            for (leaf in leaves) {
                val marker = runCatching { p.getLineMarkerInfo(leaf) }.getOrNull() ?: continue
                val anchorEl = marker.element ?: leaf
                val line = doc.getLineNumber(anchorEl.textRange.startOffset) + 1
                census.getOrPut(line) { mutableListOf() }.add(p.javaClass.simpleName)
            }
        }
        val chainLines = census.filterValues { names -> names.any { it == "SqlGutterLineMarkerProvider" } }.keys
        println("[CENSUS] 链锚点行(SqlGutterLineMarkerProvider)=$chainLines")
        println("[CENSUS] 全量行→provider=" + census.entries.joinToString(" | ") { "${it.key}:${it.value.joinToString(",")}" })

        // 判据 1：wrapper 变量声明行（demo 36/49/104）必须有链锚点
        assertTrue(
            "queryWrapper/updateWrapper 声明行(36/49/104)应有链锚点，实际=$chainLines",
            chainLines.containsAll(listOf(36, 49, 104)),
        )

        // 判据 2：链内行（.eq/.ge 逐行）与方法声明行是否出锚点——只报告不断言（暴露真实分布）
        val detectorLines = leaves.filter { WrapperAnchorDetector.anchorElement(it) != null }
            .map { doc.getLineNumber(it.textRange.startOffset) + 1 }
            .distinct().sorted()
        println("[CENSUS] WrapperAnchorDetector 命中行=$detectorLines")
    }

    // ------------------------------------------------------------------
    // 光标位置 → 预览产出（demo 同形最小夹具）
    // ------------------------------------------------------------------

    /** demo 第 36~41 行同形：局部变量链 + 链内 caret + 消费调用 caret。 */
    private fun configureChainFixture() {
        myFixture.configureByText(
            "ChainDemo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class ChainDemo {
                public java.util.List<User> query(Long id) {
                    LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
                    queryWrapper.eq(User::getId, id);
                    return list(queryWrapper);
                }

                java.util.List<User> list(Object w) { return null; }

                static class User { private Long id; }
            }
            """.trimIndent(),
        )
    }

    private fun newService() = SqlPreviewService(project)

    /** caret 在 wrapper 变量声明行（锚点行）→ 预览应出 SQL。 */
    fun test光标在链声明锚点_预览出SQL() {
        configureChainFixture()
        val varId = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .first { it.text == "queryWrapper" }
        assertNotNull("变量声明应为链锚点", WrapperAnchorDetector.anchorElement(varId))
        val result = newService().preview(varId)
        assertNotNull("链声明锚点处预览应非空", result)
        assertTrue("应生成 SELECT: ${result!!.sql}", result.sql.startsWith("SELECT"))
    }

    /** caret 在链内（`queryWrapper.eq(...)` 的 eq 方法名，对齐 demo 第 37 行按 Alt+Shift+Q）→ 预览应出 SQL。 */
    fun test光标在链内eq调用_预览出SQL() {
        configureChainFixture()
        val eqName = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "eq" }
            .methodExpression.referenceNameElement!!
        val result = newService().preview(eqName)
        assertNotNull("链内 eq 调用处预览应非空（光标落点非锚点行也要能触发）", result)
        assertTrue("应生成 SELECT: ${result!!.sql}", result.sql.startsWith("SELECT"))
    }

    /** caret 在消费调用（`return list(queryWrapper);` 的 list，对齐 demo 第 41 行）→ 预览应出 SQL。 */
    fun test光标在消费调用list_预览出SQL() {
        configureChainFixture()
        val listName = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "list" && it.argumentList.expressions.isNotEmpty() }
            .methodExpression.referenceNameElement!!
        val result = newService().preview(listName)
        assertNotNull("消费调用 list(wrapper) 处预览应非空", result)
        assertTrue("应生成 SELECT: ${result!!.sql}", result.sql.startsWith("SELECT"))
    }

    // ------------------------------------------------------------------
    // 差异面测量（沙盒症状归因用，不断言产品行为）
    // ------------------------------------------------------------------

    /**
     * 光标落在**链内行的行首缩进空白**（沙盒按快捷键时最常见的落点）——测量该落点的锚点判定与
     * 预览结果：空白不是链锚点，且不在任何 `PsiReferenceExpression`/`PsiMethodCallExpression`
     * 祖先链上（[cn.song.mybatisplus.visualizer.psi.WrapperLocator.locateWithTable] 五个分支全落空）。
     */
    fun test光标在链内行首空白_锚点与预览边界测量() {
        configureChainFixture()
        val doc = myFixture.file.viewProvider.document!!
        val eqCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "eq" }
        val lineStart = doc.getLineStartOffset(doc.getLineNumber(eqCall.textRange.startOffset))
        val leaf = myFixture.file.findElementAt(lineStart)!!
        val preview = newService().preview(leaf)
        println(
            "[BOUNDARY] lineStartLeaf=${leaf.javaClass.simpleName} text='${leaf.text}' " +
                "anchor=${WrapperAnchorDetector.anchorElement(leaf)} " +
                "preview=${preview?.sql?.take(30) ?: "null"}",
        )
        assertNull("行首缩进空白不是链锚点（链锚点在变量声明行）", WrapperAnchorDetector.anchorElement(leaf))
    }

    /**
     * `WrapperCache` 负结果粘性测量：定位返回 null（如启动热身期索引未就绪 / 光标落点无链）时，
     * **null 会被按 (文件, 锚点偏移, 开关, 配置签名) + 文件戳缓存**，同戳再取直接返回缓存的 null、
     * 不再重算——只有文件戳变化（编辑）才会重算。
     */
    fun test负结果缓存粘性_文件戳变化才失效() {
        configureChainFixture()
        val file = myFixture.file
        val cache = cn.song.mybatisplus.visualizer.psi.WrapperCache()
        var calls = 0
        val first = cache.getOrCompute(file, 0, true, "sig") { calls++; null }
        assertNull(first)
        val second = cache.getOrCompute(file, 0, true, "sig") { calls++; "OK" }
        assertNull("同戳再取应命中缓存的负结果", second)
        assertEquals("负结果命中缓存时不再重算", 1, calls)
        // 文件戳变化 → 缓存失效重算
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            file.viewProvider.document!!.insertString(0, "\n")
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val third = cache.getOrCompute(file, 0, true, "sig") { calls++; "OK" }
        assertEquals("文件戳变化后应重算", 2, calls)
        assertEquals("OK", third)
    }
}
