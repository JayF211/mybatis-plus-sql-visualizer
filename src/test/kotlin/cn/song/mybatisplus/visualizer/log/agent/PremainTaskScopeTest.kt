package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-GradleTaskInjectScope：范围闸门判定纯函数表驱动。
 *
 * 覆盖路线 A 全部分支：测试族 / IDE 族 skip、应用任务注入、非 external-system 配置零突变，
 * 以及边界（大小写、`:` 前缀、Maven goal 形态、`clean bootRun` 型多任务组合）。真机 fork JVM
 * 侧的坐实见计划文档 §七 留人工条目——本表钉死的是判定核，fork 行为只是它的传导结果。
 *
 * 反向验证（2026-09-23，读数见计划文档 §八）：摘掉测试族分支 → 测试族行全红；
 * 摘掉 IDE 族分支 → IDE 族行全红；复原后全绿。
 */
class PremainTaskScopeTest {

    private data class Row(
        val isExternal: Boolean,
        val taskNames: List<String>,
        val expect: String,
    )

    private fun outcomeOf(row: Row): String =
        when (val d = PremainTaskScope.decide(row.isExternal, row.taskNames)) {
            is PremainTaskScope.Decision.Inject -> "inject"
            is PremainTaskScope.Decision.Skip -> "skip:${d.reason}"
        }

    @Test
    fun 范围闸门表驱动_任务语义全分支() {
        val rows = listOf(
            // ---- 非 external-system 配置：恒注入（零突变面），任务名根本不参与判定 ----
            Row(false, emptyList(), "inject"),
            Row(false, listOf("test"), "inject"),
            Row(false, listOf("runIde", "check"), "inject"),
            // ---- 测试族 skip ----
            Row(true, listOf("test"), "skip:external-test-task"),
            Row(true, listOf("check"), "skip:external-test-task"),
            Row(true, listOf("verification"), "skip:external-test-task"),
            Row(true, listOf("verify"), "skip:external-test-task"),
            Row(true, listOf("build"), "skip:external-test-task"),
            Row(true, listOf("surefire:test"), "skip:external-test-task"),
            Row(true, listOf("failsafe:integration-test"), "skip:external-test-task"),
            Row(true, listOf("connectedAndroidTest"), "skip:external-test-task"),
            Row(true, listOf(":app:test"), "skip:external-test-task"),
            Row(true, listOf("testDebugUnitTest"), "skip:external-test-task"),
            Row(true, listOf("Test"), "skip:external-test-task"),
            Row(true, listOf("clean", "test"), "skip:external-test-task"),
            // ---- IDE/构建验证族 skip ----
            Row(true, listOf("runIde"), "skip:external-ide-task"),
            Row(true, listOf("runIde2"), "skip:external-ide-task"),
            Row(true, listOf("buildSearchableOptions"), "skip:external-ide-task"),
            Row(true, listOf("prepareSandbox"), "skip:external-ide-task"),
            Row(true, listOf("prepareUiSandbox"), "skip:external-ide-task"),
            Row(true, listOf("verifyPlugin"), "skip:external-ide-task"),
            Row(true, listOf("verifyPluginStructure"), "skip:external-ide-task"),
            Row(true, listOf(":plugins:runIde"), "skip:external-ide-task"),
            // ---- 应用运行任务：注入保留 ----
            Row(true, listOf("bootRun"), "inject"),
            Row(true, listOf("run"), "inject"),
            Row(true, listOf("quarkusDev"), "inject"),
            Row(true, listOf("spring-boot:run"), "inject"),
            Row(true, listOf(":app:bootRun"), "inject"),
            Row(true, listOf("clean", "bootRun"), "inject"),
            Row(true, listOf("assemble"), "inject"),
            Row(true, emptyList(), "inject"),
            // ---- 病态组合：测试族优先于 IDE 族（行为同为 skip，原因码钉死取证口径） ----
            Row(true, listOf("test", "runIde"), "skip:external-test-task"),
        )
        for (row in rows) {
            assertEquals(
                "external=${row.isExternal} tasks=${row.taskNames}",
                row.expect,
                outcomeOf(row),
            )
        }
    }

    @Test
    fun 任务名归一化_空白与空段不参与判定() {
        // 空白/空段归一化后丢弃，不产生任何族命中
        assertEquals(
            PremainTaskScope.Decision.Inject,
            PremainTaskScope.decide(true, listOf(" ", "", "  ", "bootRun")),
        )
        assertEquals(
            PremainTaskScope.Decision.Skip(PremainTaskScope.REASON_TEST_TASK),
            PremainTaskScope.decide(true, listOf(" :module: TEST ")),
        )
    }

    @Test
    fun 原因常量与断言用字面量一致() {
        // 原因串进日志，改动会打断既有排障检索；此处钉死字面量
        assertEquals("external-test-task", PremainTaskScope.REASON_TEST_TASK)
        assertEquals("external-ide-task", PremainTaskScope.REASON_IDE_TASK)
    }

    @Test
    fun 注入单例与既有四闸门决策核无类型纠缠() {
        // Inject 是单例：范围闸门 skip 不携带端口类载荷，注入与否由 AgentManager 四闸门续判
        assertEquals(PremainTaskScope.Decision.Inject, PremainTaskScope.decide(false, listOf("test")))
        assertEquals(PremainTaskScope.Decision.Inject, PremainTaskScope.decide(true, listOf("bootRun")))
    }
}
