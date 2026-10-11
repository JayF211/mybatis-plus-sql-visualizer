package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

/**
 * R5：抽样 LIMIT 配置化测试（模板函数消费 PluginSettings.state.sampleLimit；
 * BasePlatformTestCase 提供 Application 供 getInstance）。
 *
 * 修复前 LIMIT 硬编码 10（配置点 grep 为零，第四轮报告 R5 缺口）；本用例先红
 * （配置 25 时模板仍产出 LIMIT 10）、修复后绿（LIMIT 25）。钳制 1..10000。
 */
class SampleSelectedTableActionLimitTest : BasePlatformTestCase() {

    private fun templateOf(selected: String): String {
        // template 构造参数为 lambda 属性——经子类实例化直接驱动（与生产同一函数对象）
        val action = SampleSelectedTableAction()
        val field = SelectedTableSqlTemplateAction::class.java.getDeclaredField("template")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val template = field.get(action) as (String) -> String
        return template(selected)
    }

    private fun withLimit(limit: Int, block: () -> Unit) {
        val old = PluginSettings.getInstance().state.sampleLimit
        try {
            PluginSettings.getInstance().state.sampleLimit = limit
            block()
        } finally {
            PluginSettings.getInstance().state.sampleLimit = old
        }
    }

    fun `test R5_默认LIMIT10`() {
        withLimit(10) { assertEquals("SELECT * FROM t_user LIMIT 10", templateOf("t_user")) }
    }

    fun `test R5_配置生效_默认档改25即产出LIMIT25`() {
        withLimit(25) { assertEquals("SELECT * FROM t_user LIMIT 25", templateOf("t_user")) }
    }

    fun `test R5_范围钳制_0负数与超界均收敛`() {
        withLimit(0) { assertEquals("SELECT * FROM t LIMIT 1", templateOf("t")) }
        withLimit(-5) { assertEquals("SELECT * FROM t LIMIT 1", templateOf("t")) }
        withLimit(99999) { assertEquals("SELECT * FROM t LIMIT 10000", templateOf("t")) }
    }
}
