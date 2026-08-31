package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * [TableConfigSignature.projectSignature] 纯函数测试（F-44 续 · 补用例大波次）：
 * 配置签名是 WrapperCache / EntityResolver 解析缓存的失效维度，决定文件戳不变时
 * 设置变更后是否重新解析。覆盖：签名非空且 6 段竖线结构、配置变更后签名变化且含新值、相同配置幂等。
 * 仅读 PluginSettings 状态，无生产副作用（tearDown 还原）。
 */
class TableConfigSignatureTest : BasePlatformTestCase() {

    private var savedIgnored: String? = null
    private var savedPrefix: String? = null

    override fun setUp() {
        super.setUp()
        savedIgnored = PluginSettings.getInstance().state.ignoredFields
        savedPrefix = PluginSettings.getInstance().state.tablePrefix
    }

    override fun tearDown() {
        PluginSettings.getInstance().state.ignoredFields = savedIgnored ?: ""
        PluginSettings.getInstance().state.tablePrefix = savedPrefix ?: ""
        super.tearDown()
    }

    fun testProjectSignature_非空且以竖线分隔6段() {
        val sig = TableConfigSignature.projectSignature()
        assertNotNull("签名应恒非 null", sig)
        val parts = sig.split("|")
        assertEquals("应恰好 6 个配置段（推断策略/忽略字段/前缀/后缀/去前缀/去后缀）", 6, parts.size)
    }

    fun testProjectSignature_忽略字段变更后签名变化且含新值() {
        val before = TableConfigSignature.projectSignature()
        PluginSettings.getInstance().state.ignoredFields = "id,created_at"
        val after = TableConfigSignature.projectSignature()
        assertTrue("变更 ignoredFields 应改变签名", before != after)
        assertTrue("签名应包含新忽略字段", after.contains("id,created_at"))
    }

    fun testProjectSignature_相同配置幂等() {
        val a = TableConfigSignature.projectSignature()
        val b = TableConfigSignature.projectSignature()
        assertEquals("相同配置下签名应完全一致（缓存失效判定稳定）", a, b)
    }
}
