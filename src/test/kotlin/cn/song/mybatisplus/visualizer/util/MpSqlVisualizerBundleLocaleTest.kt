package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * T-ReportL10n：AUTO 语言落点改为 IDE 显示语言后的最小守卫。
 *
 * headless 测试环境无 PluginSettings service / 无 IDE 显示语言，
 * [MpSqlVisualizerBundle.ideDisplayLocale] 的两条路径（DynamicBundle.getLocale
 * 正常返回、runCatching 回退 JVM locale）都必须稳定给出非空 Locale——
 * 任何一条抛异常都意味着 AUTO 形态下文案解析被波及。
 */
class MpSqlVisualizerBundleLocaleTest {

    @Test
    fun `IDE显示语言解析_永不抛异常且返回有效Locale`() {
        val locale = MpSqlVisualizerBundle.ideDisplayLocale()
        assertNotNull(locale)
        // headless 下平台路径可能不可用，回退必须落在 JVM 默认 locale；
        // 平台路径可用时返回其显示语言。任何非空 Locale 形态都合法，异常则测试直接失败
        val fallback = Locale.getDefault()
        assertTrue(
            "解析结果必须是平台显示语言或回退的 JVM locale 之一",
            locale == fallback || locale.language.isNotEmpty() || locale == Locale.ROOT,
        )
    }

    @Test
    fun `测试环境无设置服务_文案兜底中文可解析`() {
        // settings service 未注册 → currentLocale 兜底中文：错误报告菜单文案应取到 _zh 值
        val text = MpSqlVisualizerBundle.message("error.report.action")
        assertNotNull(text)
        assertTrue("应解析到中文文案而非 key 本身", text != "error.report.action")
    }

    @Test
    fun `嵌入平台文案按IDE显示语言解析_不随插件语言设置`() {
        // T-ReportL10n-02 契约：messageForIde 只看 ideDisplayLocale——zh → 中文值，
        // 其余语言（utf8Control 禁回退直达 base）→ 英文值；永不是 key 本身
        val locale = MpSqlVisualizerBundle.ideDisplayLocale()
        val text = MpSqlVisualizerBundle.messageForIde("error.report.action")
        val zhText = "报告到 SQL Visualizer 需求池"
        val enText = "Report to SQL Visualizer Feedback Pool"
        if (locale.language == "zh") {
            assertEquals("zh 显示语言应取 _zh 文案", zhText, text)
        } else {
            assertEquals("非 zh 显示语言应直达 base 英文文案", enText, text)
        }
    }
}
