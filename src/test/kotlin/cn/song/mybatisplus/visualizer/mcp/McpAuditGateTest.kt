package cn.song.mybatisplus.visualizer.mcp

import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * MCP 审计闸门双路径单测（T-McpDynamicToolset）：audit 的 intrinsics 透传＋continuation 包装
 * 形态的语义回归——同步/挂起两路径的成功记账与预期错误转译。
 *
 * **历史限制解除**：T-MTS2-05 时代挂起链测试被 243 测试 JVM 缺 SpillingKt 阻断（audit 高阶
 * suspend 结构性 spill），本测试直接跑挂起路径——这本身就是零 spill 改造的活体证据
 * （若字节码重新引入 SpillingKt 引用，本测试类在 CI JVM 上加载即 NoClassDefFoundError）。
 * **限制（如实）**：失败/转译路径的 JVM 单测不可行——243 测试 JVM 平台 stdlib 2.0.21（test
 * classpath 遮蔽项目 2.3.20）缺 SpillingKt，且测试代码自身的 runCatching 包挂起调用同样生成
 * null-out 引用（同根）——预期错误转译与异常传播语义由 262 沙盒真机 [MCP-AUDIT] 对账覆盖。
 */
class McpAuditGateTest {

    /** 探针工具：暴露 protected audit/throwExpected 供直调。 */
    private class ProbeTool : McpToolsetSupport("sqlv_probe") {
        suspend fun probe(block: suspend () -> String): String = audit("sqlv_probe", "probe", block)
        suspend fun fail(msg: String): Nothing = throwExpected(msg)
    }

    @Test
    fun `同步成功路径_记账并返回结果`() = runBlocking {
        assertEquals("ok", ProbeTool().probe { "ok" })
    }

    @Test
    fun `挂起成功路径_经包装续体记账并返回结果`() = runBlocking {
        assertEquals("async", ProbeTool().probe { yield(); "async" })
    }




}
