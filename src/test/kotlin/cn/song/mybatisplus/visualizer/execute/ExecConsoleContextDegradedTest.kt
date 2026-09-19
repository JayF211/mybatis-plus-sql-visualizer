package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ExecConsoleContext 降级分支守卫（T-TestGuard2 G2-6，此前 0 测试）。
 *
 * T-ConsoleDbContext 定案「全部入口包 runCatching，任何反射/平台异常退化为不改动、不附加，
 * 绝不影响主链」——本类在**默认 test（database jar 被剥离）**环境恰好只能验证降级面：
 *  - null console：`as?` 对 null 不触发类型解析，两环境行为一致；
 *  - 异类型 console：无 database jar 环境走 runCatching 捕 NoClassDefFoundError 的
 *    「跳过」分支，有 jar 环境走 instanceof 失败分支——**只断言不抛且返回非空/空**，
 *    不对文案细节做环境敏感断言。
 *
 * 真实 JdbcConsole 链路（对齐 switchSchema/回退 setSearchPath）在 testDatabase 通道，
 * 本机驱动管线不通绿跳（绿跳守卫诚实口径）——有效验证靠用户真机复验。
 */
class ExecConsoleContextDegradedTest {

    @Test
    fun `align_null控制台_优雅降级不抛`() {
        val result = ExecConsoleContext.alignToDataSourceDatabase(null)
        // null as? JdbcConsole 恒 null（不触发类解析），两环境同走「非 JdbcConsole」分支
        assertTrue(
            "null console 应走「非 JdbcConsole，跳过对齐」降级分支，实际: $result",
            result.contains("非 JdbcConsole"),
        )
    }

    @Test
    fun `describe_null控制台_返回null不附加诊断`() {
        assertNull("null console 不应产生诊断行（错误提示不得携带空上下文）", ExecConsoleContext.describeContext(null))
    }

    @Test
    fun `align_异类型控制台_不抛且返回非空`() {
        // 环境敏感面：有 database jar → instanceof 失败「非 JdbcConsole」；
        // 无 jar → NoClassDefFoundError 被 runCatching 兜住。共同行为 = 不抛 + 非空描述。
        val result = ExecConsoleContext.alignToDataSourceDatabase(Any())
        assertNotNull("异类型 console 也必须返回诊断描述（绝不抛出影响主链）", result)
    }

    @Test
    fun `describe_异类型控制台_返回null`() {
        assertNull(ExecConsoleContext.describeContext(Any()))
    }

    @Test
    fun `align_null控制台_不误报URL无库标注`() {
        // T-SI-03 ① 的「未能确定默认库」标注只在拿到真实 JdbcConsole、URL 确实无库时出现；
        // 非 JdbcConsole 降级分支不得带该标记——否则日志里两种「取不到库」混为一谈。
        val result = ExecConsoleContext.alignToDataSourceDatabase(null)
        assertFalse(
            "降级分支不得带 URL 无库标注，实际: $result",
            result.contains(ExecConsoleContext.UNDETERMINED_MARKER),
        )
    }
}
