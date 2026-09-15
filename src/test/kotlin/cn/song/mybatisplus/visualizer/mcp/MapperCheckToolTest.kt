package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.config.PluginSettings
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mpsv_mapper_check 工具测试（T-McpToolset2 / T-MTS2-03）：契约层（语句清单/#{} 参数数/
 * ${} 直替数）+ 渲染层 simulate（空参数语境下无 WHERE 的 UPDATE 判 DANGEROUS——
 * 「校验渲染产物」的位差落点）。XmlMapperParser/render 链细节由 xml 包测试覆盖。
 */
class MapperCheckToolTest : com.intellij.testFramework.fixtures.BasePlatformTestCase() {

    private fun check(snippet: String, simulate: Boolean = false) =
        MapperCheckTool().mapperCheck(snippet, simulate)

    override fun tearDown() {
        // 应用级设置跨用例存活——恢复默认
        PluginSettings.getInstance().state.mcpToolsEnabled = true
        super.tearDown()
    }

    private val mapper = """
        <mapper namespace="n">
          <select id="s1" resultType="map">
            SELECT * FROM t_user WHERE id = #{id} ORDER BY ${'$'}{orderCol}
          </select>
          <update id="u1">UPDATE t_user SET a = 1</update>
        </mapper>
    """.trimIndent()

    @Test
    fun test契约层_语句清单参数数与直替数() {
        val output = check(mapper)
        assertTrue(output, output.contains("statements: 2"))
        assertTrue(output, output.contains("s1 [SELECT]"))
        assertTrue(output, output.contains("u1 [UPDATE]"))
        assertTrue(output, output.contains("substitutions: 1"))
    }

    @Test
    fun test渲染层simulate_无WHERE的UPDATE判危险() {
        val output = check(mapper, simulate = true)
        assertTrue(output, output.contains("DANGEROUS"))
        assertTrue(output, output.contains("MISSING_WHERE"))
        assertTrue("空参数语境如实标注", output.contains("empty-parameter context"))
    }

    @Test
    fun test无语句mapper_预期错误() {
        val error = runCatching { check("<mapper namespace=\"n\"></mapper>") }.exceptionOrNull()
        assertTrue(error is com.intellij.mcpserver.McpExpectedError)
        assertTrue((error?.message ?: "").contains("no <select|insert|update|delete>"))
    }

    @Test
    fun test空文本_预期错误() {
        val error = runCatching { MapperCheckTool().mapperCheck("", false) }.exceptionOrNull()
        assertTrue(error is com.intellij.mcpserver.McpExpectedError)
    }
}
