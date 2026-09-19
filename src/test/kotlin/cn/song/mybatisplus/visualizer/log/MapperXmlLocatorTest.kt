package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * [MapperXmlLocator] 平台集成测试（T-M55-01 跳转双目标）：
 *
 * 在 LightPlatform 虚拟项目中创建真实 mapper XML（走 VirtualFile + PsiManager 全链路，
 * 仿 HbmXmlEntityResolverPlatformTest 先例），验证：
 *  - 命中：namespace + 语句 id 匹配 → 定位到语句标签起始行（0-based）；
 *  - namespace 不匹配 → null（PSI 校验防线）；
 *  - 无 XML / 语句 id 不存在 → null（回退 Java 跳转，不劣化）；
 *  - 多模块同名 mapper（同 namespace 同 id）→ 取第一个，不抛不阻；
 *  - statementId 形态守卫：无点 / 空串 → null。
 */
class MapperXmlLocatorTest : BasePlatformTestCase() {

    private val mapperXml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.demo.mapper.UserMapper">
    <select id="selectById" resultType="map">SELECT * FROM t_user WHERE id = #{id}</select>
    <insert id="insertUser">INSERT INTO t_user(name) VALUES(#{name})</insert>
</mapper>
"""

    private fun addMapperXml(path: String, content: String) {
        myFixture.addFileToProject(path, content)
    }

    /** 期望行号：语句 id 首次出现所在行（0-based），与 fixture 文本逐行核对。 */
    private fun expectedLineOf(content: String, marker: String): Int =
        content.lineSequence().indexOfFirst { it.contains(marker) }

    fun test命中_定位到语句标签行() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", mapperXml)
        val loc = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        assertNotNull("namespace+id 匹配应命中", loc)
        assertEquals("UserMapper.xml", loc!!.virtualFile.name)
        assertEquals(
            "行号 = 语句标签起始行（0-based）",
            expectedLineOf(mapperXml, "selectById"),
            loc.line,
        )
    }

    fun test命中_insert语句() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", mapperXml)
        val loc = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.insertUser")
        assertNotNull(loc)
        assertEquals(expectedLineOf(mapperXml, "insertUser"), loc!!.line)
    }

    fun testNamespace不匹配_返回null() {
        addMapperXml(
            "src/main/resources/mapper/OtherMapper.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.demo.mapper.OrderMapper">
                <select id="selectById">SELECT 1</select>
            </mapper>
            """.trimIndent(),
        )
        // 同 id 但 namespace 不同：不得跨 namespace 误命中
        assertNull("namespace 不匹配不应命中", MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById"))
    }

    fun test语句id不存在_返回null() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", mapperXml)
        assertNull(
            "同 namespace 但 id 不存在应返回 null",
            MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.deleteById"),
        )
    }

    fun test无XML_返回null() {
        assertNull("项目内无 mapper XML 应返回 null", MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById"))
    }

    fun test非Mapper根元素的XML不参与() {
        addMapperXml(
            "src/main/resources/misc/notes.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
            <configuration>
                <select id="selectById">SELECT 1</select>
            </configuration>
            """.trimIndent(),
        )
        assertNull("根元素非 <mapper> 不应命中", MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById"))
    }

    fun test多模块同名_取第一个不抛() {
        addMapperXml("module-a/src/main/resources/mapper/UserMapper.xml", mapperXml)
        addMapperXml("module-b/src/main/resources/mapper/UserMapper.xml", mapperXml)
        val loc = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        assertNotNull("多模块同名应取第一个命中", loc)
        assertEquals("UserMapper.xml", loc!!.virtualFile.name)
    }

    fun testStatementId形态守卫() {
        assertNull("无点号（非 namespace.id 形态）应返回 null", MapperXmlLocator.splitStatementId("selectById"))
        assertNull("空串应返回 null", MapperXmlLocator.splitStatementId(""))
        assertNull("以点结尾应返回 null", MapperXmlLocator.splitStatementId("com.demo.UserMapper."))
        assertNull("以点开头应返回 null", MapperXmlLocator.splitStatementId(".selectById"))
        assertEquals(
            "namespace.id 按最后一个点拆分",
            "com.demo.mapper.UserMapper" to "selectById",
            MapperXmlLocator.splitStatementId("com.demo.mapper.UserMapper.selectById"),
        )
    }
}
