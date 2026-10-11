package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sqlv_reverse_sql 工具测试（T-McpToolset2 / T-MTS2-02）：SELECT 反向生成 QueryWrapper、
 * 解析失败带行号、空输入拒绝。生成细节由 SqlToJavaConverter/JavaCodeGenerator 测试覆盖。
 */
class ReverseSqlToolTest {

    private fun reverse(sql: String) = SqlVisualizerReverseSqlTool().reverseSql(sql)

    @Test
    fun `SELECT反向生成QueryWrapper代码`() {
        val output = reverse("SELECT id, name FROM t_user WHERE id = 1")
        assertTrue(output, output.contains("QueryWrapper"))
        assertTrue(output, output.contains("t_user"))
    }

    @Test
    fun `解析失败_带行号的预期错误`() {
        val error = runCatching { reverse("SELEKT x FORM y") }.exceptionOrNull()
        assertTrue("解析失败应抛 SqlvExpectedToolException", error is SqlvExpectedToolException)
        assertTrue(
            "错误消息应带行号定位",
            (error?.message ?: "").contains("cannot parse SQL (line"),
        )
    }

    @Test
    fun `空输入_预期错误`() {
        val error = runCatching { SqlVisualizerReverseSqlTool().reverseSql("  ") }.exceptionOrNull()
        assertTrue(error is SqlvExpectedToolException)
    }

    // ------------------------------------------------------------------
    // T-MapResultVo：maps=true + mapperVariable → Map 响应形态调用行
    // ------------------------------------------------------------------

    @Test
    fun `Map响应_join生成selectJoinMaps调用行`() {
        val sql = "SELECT t.id, t1.name FROM t_user t LEFT JOIN t_addr t1 ON t1.id = t.addr_id WHERE t.id = 1"
        val output = SqlVisualizerReverseSqlTool().reverseSql(sql, true, "deviceInfoMapper")
        assertTrue(output, output.contains("List<Map<String, Object>> list = deviceInfoMapper.selectJoinMaps(wrapper);"))
        assertTrue(output, !output.contains(".class, wrapper)"))
    }

    @Test
    fun `Map响应_单表生成selectMaps_分页同名重载`() {
        val sql = "SELECT id, name FROM t_user WHERE status = 1 LIMIT 10"
        val output = SqlVisualizerReverseSqlTool().reverseSql(sql, true, "userMapper")
        assertTrue(output, output.contains("Page<Map<String, Object>> page = userMapper.selectMaps(new Page<>(1, 10), wrapper);"))
    }

    @Test
    fun `Map响应_缺mapperVariable_回落纯表达式零回归`() {
        val sql = "SELECT id FROM t_user WHERE id = 1"
        val output = SqlVisualizerReverseSqlTool().reverseSql(sql, true)
        assertTrue(output, !output.contains("selectJoinMaps"))
        assertTrue(output, output.contains("QueryWrapper"))
    }
}
