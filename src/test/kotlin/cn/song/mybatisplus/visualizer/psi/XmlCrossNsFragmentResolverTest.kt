package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService
import cn.song.mybatisplus.visualizer.xml.XmlIncludeScan
import cn.song.mybatisplus.visualizer.xml.XmlMapperFragments
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 跨命名空间 include 解析测试（T-IC-02/T-IC-03，仿 MapperXmlLocatorTest 的 LightPlatform 先例：
 * `myFixture.addFileToProject` 创建的真实文件可被 FileTypeIndex + PSI 命中）。
 *
 * 覆盖：跨文件命中展开（含动态标签）、property 穿透、歧义降级（多文件同 namespace 同 id）、
 * namespace 不匹配降级、纯文本链（无 project）沿用改动前降级文案、纯逻辑归并的失败原因文案。
 */
class XmlCrossNsFragmentResolverTest : BasePlatformTestCase() {

    private fun addMapper(path: String, content: String) {
        myFixture.addFileToProject(path, content)
    }

    private fun preview(text: String, withProject: Boolean = true): PreviewResult =
        XmlPreviewService.preview(text, "s1", if (withProject) project else null)

    private val baseMapper = """<?xml version="1.0" encoding="UTF-8"?>
<mapper namespace="com.demo.BaseMapper">
  <sql id="cols">id, name</sql>
  <sql id="aliasCol">${'$'}{alias}.id</sql>
  <sql id="cond"><if test="status != null">AND status = #{status}</if></sql>
</mapper>
"""

    fun test跨文件命中_片段展开且无降级警示() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val result = preview("""
            <mapper namespace="com.demo.OrderMapper">
              <select id="s1">SELECT <include refid="com.demo.BaseMapper.cols"/> FROM t</select>
            </mapper>
        """.trimIndent())
        assertNull("跨命名空间命中不应有警示：${result.warning}", result.warning)
        assertEquals("SELECT id, name FROM t", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    fun test跨文件命中_动态标签片段展开() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val result = preview("""
            <mapper namespace="com.demo.OrderMapper">
              <select id="s1">SELECT * FROM t WHERE 1 = 1 <include refid="com.demo.BaseMapper.cond"/></select>
            </mapper>
        """.trimIndent())
        assertNull("跨命名空间动态片段应正常渲染：${result.warning}", result.warning)
        assertEquals("SELECT * FROM t WHERE 1 = 1 AND status = ?", result.sql.replace(Regex("\\s+"), " ").trim())
        assertEquals(listOf("status"), result.unresolvedParams)
    }

    fun test跨文件命中_property穿透() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val result = preview("""
            <mapper namespace="com.demo.OrderMapper">
              <select id="s1">SELECT <include refid="com.demo.BaseMapper.aliasCol"><property name="alias" value="t1"/></include> FROM t</select>
            </mapper>
        """.trimIndent())
        assertNull("property 应穿透到跨文件片段：${result.warning}", result.warning)
        assertEquals("SELECT t1.id FROM t", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    fun test歧义_同名片段多文件重复声明_降级() {
        // 非法但存在：同 namespace 声明在两个文件（MyBatis 运行时后注册覆盖，本插件歧义降级）
        addMapper(
            "module-a/src/main/resources/mapper/DupMapper.xml",
            """<mapper namespace="com.demo.DupMapper"><sql id="cols">id</sql></mapper>""",
        )
        addMapper(
            "module-b/src/main/resources/mapper/DupMapper.xml",
            """<mapper namespace="com.demo.DupMapper"><sql id="cols">name</sql></mapper>""",
        )
        val result = preview("""
            <mapper namespace="com.demo.OrderMapper">
              <select id="s1">SELECT * FROM t <include refid="com.demo.DupMapper.cols"/></select>
            </mapper>
        """.trimIndent())
        assertTrue("歧义应降级并给出原因：${result.warning}", result.warning.orEmpty().contains("重复声明"))
        assertEquals("SELECT * FROM t", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    fun testnamespace不匹配_降级() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val result = preview("""
            <mapper namespace="com.demo.OrderMapper">
              <select id="s1">SELECT * FROM t <include refid="com.demo.Missing.cols"/></select>
            </mapper>
        """.trimIndent())
        assertTrue(
            "无该 namespace 应降级并给出原因：${result.warning}",
            result.warning.orEmpty().contains("未找到 namespace 为 com.demo.Missing"),
        )
        assertEquals("SELECT * FROM t", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    fun testnamespace匹配但片段不存在_降级() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val result = preview("""
            <mapper namespace="com.demo.OrderMapper">
              <select id="s1">SELECT * FROM t <include refid="com.demo.BaseMapper.nope"/></select>
            </mapper>
        """.trimIndent())
        assertTrue(
            "同 namespace 无该片段应降级并给出原因：${result.warning}",
            result.warning.orEmpty().contains("下无片段 nope"),
        )
        assertEquals("SELECT * FROM t", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    fun test纯文本链_无project_沿用旧降级文案() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val result = preview(
            """
            <mapper namespace="com.demo.OrderMapper">
              <select id="s1">SELECT * FROM t <include refid="com.demo.BaseMapper.cols"/></select>
            </mapper>
            """.trimIndent(),
            withProject = false,
        )
        // 无 project 不做跨命名空间解析：文案与行为与 T-IncludeCrossNs 改动前逐字节一致
        assertTrue(
            "无 project 应沿用旧降级文案：${result.warning}",
            result.warning.orEmpty().contains(
                "Include refid 'com.demo.BaseMapper.cols' not found in this mapper " +
                    "(cross-namespace include is not supported).",
            ),
        )
        assertEquals("SELECT * FROM t", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    fun test同文件裸id_行为不变() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val result = preview("""
            <mapper namespace="com.demo.OrderMapper">
              <sql id="cols">a, b</sql>
              <select id="s1">SELECT <include refid="cols"/> FROM t</select>
            </mapper>
        """.trimIndent())
        assertNull("本文件片段仍走本文件展开：${result.warning}", result.warning)
        assertEquals("SELECT a, b FROM t", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    fun testCollect_歧义与未找到的原因文案() {
        val scan = XmlIncludeScan(
            namespace = "com.demo.OrderMapper",
            localFragmentIds = emptySet(),
            dottedRefIds = setOf("com.demo.DupMapper.cols", "com.demo.Missing.cols"),
        )
        val result = XmlCrossNsFragmentResolver.collect(
            scan,
            listOf(
                XmlMapperFragments("com.demo.DupMapper", mapOf("cols" to "id")),
                XmlMapperFragments("com.demo.DupMapper", mapOf("cols" to "name")),
            ),
        )
        assertTrue("歧义不产出片段", result.fragments.isEmpty())
        assertTrue(result.failures.getValue("com.demo.DupMapper.cols").contains("歧义"))
        assertTrue(result.failures.getValue("com.demo.Missing.cols").contains("未找到 namespace 为 com.demo.Missing"))
    }

    fun testCollect_无候选refid_返回空表() {
        val result = XmlCrossNsFragmentResolver.collect(
            XmlIncludeScan("n", setOf("cols"), emptySet()),
            listOf(XmlMapperFragments("other", mapOf("cols" to "id"))),
        )
        assertTrue(result.fragments.isEmpty())
        assertTrue(result.failures.isEmpty())
    }
}
