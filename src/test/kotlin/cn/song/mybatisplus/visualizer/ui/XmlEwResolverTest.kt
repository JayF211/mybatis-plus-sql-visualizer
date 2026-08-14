package cn.song.mybatisplus.visualizer.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * MP 占位展开端到端测试（T-M26-B，P-立项-03）：mapper XML `${ew.customSqlSegment}` →
 * namespace 关联 Mapper 接口 → ReferencesSearch 找调用 → wrapper 实参展开 → WHERE 片段注入。
 */
class XmlEwResolverTest : BasePlatformTestCase() {

    /** 注入轻量调用查找的预览入口（生产路径在 XmlPreviewService 内部走 ReferencesSearch）。 */
    private fun previewWithFinder(
        xml: String,
        id: String,
        project: com.intellij.openapi.project.Project,
        serviceFile: com.intellij.psi.PsiFile,
    ): cn.song.mybatisplus.visualizer.model.PreviewResult {
        val parsed = cn.song.mybatisplus.visualizer.xml.XmlMapperParser().parse(xml)
        val statement = parsed.statements.first { it.id == id }
        val warnings = mutableListOf<String>()
        val placeholders = cn.song.mybatisplus.visualizer.xml.XmlEwExpander.placeholders(xml)
        val finder: (com.intellij.psi.PsiMethod) -> List<com.intellij.psi.PsiElement> = { method ->
            com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(serviceFile, com.intellij.psi.PsiMethodCallExpression::class.java)
                .filter { it.methodExpression.referenceName == method.name }
        }
        val resolved = XmlEwResolver.resolve(parsed.namespace, id, placeholders, project, warnings, finder)
        println("DEBUG-RESOLVED: " + resolved)
        println("DEBUG-WARNINGS: " + warnings)
        val ewBindings = resolved.mapValues { (_, fragments) -> cn.song.mybatisplus.visualizer.xml.MockObject(fragments) }
        val model = cn.song.mybatisplus.visualizer.xml.XmlPreviewModel.from(statement, parsed.errors, extraBindings = ewBindings)
        return XmlPreviewService.toResult(model, warnings)
    }

    private val mapperXml = """
        <mapper namespace="com.example.UserMapper">
          <select id="selectList" resultType="map">
            SELECT * FROM t_user ${'$'}{ew.customSqlSegment}
          </select>
        </mapper>
    """.trimIndent()

    fun testEwCustomSqlSegmentExpanded() {
        myFixture.addFileToProject(
            "UserMapper.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.core.conditions.Wrapper;

            public class User {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                java.util.List<Object> selectList(Wrapper ew);
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Service.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

            public class Service {
                public void run(UserMapper mapper) {
                    mapper.selectList(new QueryWrapper<com.example.User>().eq("id", 1L).eq("name", "x"));
                }
            }
            """.trimIndent(),
        )
        // Light 测试注入轻量调用查找（生产走 ReferencesSearch，此处直接扫描调用表达式）
        val serviceFile = myFixture.file
        val result = previewWithFinder(mapperXml, "selectList", project, serviceFile)
        assertTrue("应展开 ew.customSqlSegment：${result.sql}", result.sql.contains("WHERE (id = 1"))
        assertTrue("不应有占位求值为空提示：${result.warning}", result.warning?.contains("求值为空") != true)
        assertEquals("SELECT * FROM t_user WHERE (id = 1 AND name = 'x')", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    fun testEwNoCallDegradesWithWarning() {
        // 接口存在但无调用处 → 降级提示（占位保持空）
        myFixture.addFileToProject(
            "UserMapper.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.core.conditions.Wrapper;

            public interface UserMapper {
                java.util.List<Object> selectList(Wrapper ew);
            }
            """.trimIndent(),
        )
        myFixture.configureByText("Unrelated.java", "public class Unrelated { int x = 1; }")
        val result = XmlPreviewService.preview(mapperXml, "selectList", project)
        assertTrue("应提示未找到调用：${result.warning}", result.warning?.contains("未找到") == true)
        assertEquals("SELECT * FROM t_user", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    fun testEwNoNamespaceDegrades() {
        val noNsXml = """
            <mapper>
              <select id="selectList">SELECT * FROM t WHERE ${'$'}{ew.customSqlSegment}</select>
            </mapper>
        """.trimIndent()
        val result = XmlPreviewService.preview(noNsXml, "selectList", project)
        assertTrue("应提示缺 namespace：${result.warning}", result.warning?.contains("namespace") == true)
    }

    fun testEwWithoutProjectKeepsOldBehavior() {
        // 无 project（纯文本）→ 不展开（占位求值为空提示）
        val result = XmlPreviewService.preview(mapperXml, "selectList", null)
        assertTrue("无 project 时应保持占位求值为空提示", result.warning?.contains("求值为空") == true)
    }
}
