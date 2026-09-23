package cn.song.mybatisplus.visualizer.mcp

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-McpRenderDialectFix：sqlv_render_sql snippet 模式读锁回归测试。
 *
 * 真机实测（2026-09-23，IU 2026.2.3 MCP 通道 + demo 项目）：[McpSourceLocator.renderXmlSnippet]
 * 未包 ReadAction 时，text 链在传 project 下走跨 ns include（T-IC-02）/静态常量（T-M25.2）/
 * mapper 参数类型（M29.5）三处 PSI 读，平台协程线程硬抛
 * "Read access is allowed from inside read-action only"。
 *
 * 判别力设计：调用放在**独立 worker 线程**而非测试主线程——夹具若给测试线程授了读权限，
 * 主线程直调会「摘掉修复也绿」；worker 线程与平台协程线程同样无读权限，未包 ReadAction 必抛，
 * 修复后 ReadAction.compute 在任意线程均可取锁放行。
 */
class McpRenderSnippetReadActionTest : BasePlatformTestCase() {

    fun testSnippetRenderUnderReadActionFromWorkerThread() {
        val snippet = """
            <mapper namespace="cn.song.mybatisplusdemo.mapper.UserMapper">
                <select id="q" resultType="map">select * from user where id = #{id} and status = #{status}</select>
            </mapper>
        """.trimIndent()

        var outcome: McpSourceLocator.Outcome? = null
        var error: Throwable? = null
        val worker = Thread {
            try {
                outcome = McpSourceLocator.renderXmlSnippet(project, snippet)
            } catch (t: Throwable) {
                error = t
            }
        }
        worker.start()
        worker.join(60_000)
        assertFalse("worker thread did not finish in time", worker.isAlive)
        error?.let { throw it }

        val result = outcome!!
        assertNull("expected clean render, got error: ${result.error}", result.error)
        assertTrue("rendered SQL missing table name: ${result.sql}", result.sql.orEmpty().contains("user", ignoreCase = true))
        assertEquals("mapper XML (snippet)", result.sourceType)
    }
}
