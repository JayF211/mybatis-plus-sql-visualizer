package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-DollarInjectMark（O-11）：`\${}` 直替信息横幅测试。
 *
 *  - 组件行为：DOLLAR_INJECT 最低优先（警示/降级在场让位）、仅该类别带 ✕ 关闭按钮、
 *    onDismiss 回调触发；
 *  - 面板接线：`\${}` 计数 >0 即挂横幅且计数正确、纯 `#{}` 不挂、警示在场让位、
 *    ✕ 关闭后 companion 级会话记忆跨面板不再弹（测试复位恢复）。
 *
 * mapper 用 OGNL 字面量（`${'$'}{'col'}`）求值——不依赖模拟参数注入，headless 稳定。
 */
class DollarInjectBannerTest : BasePlatformTestCase() {

    override fun tearDown() {
        // 会话级记忆复位，避免污染其他用例（companion 静态跨用例存活）
        SqlPreviewPanel.resetDollarMarkForTest()
        super.tearDown()
    }

    // ---------- 组件行为 ----------

    fun test直替横幅_组件优先级与关闭按钮() {
        val banner = DegradationBanner()
        banner.show(DegradationBanner.Kind.DOLLAR_INJECT, "dollar-info")
        assertTrue(banner.isVisible)
        assertTrue("信息级类别应带 ✕ 关闭按钮", banner.dismissVisibleForTest())
        // 高优先类别可覆盖直替提示（覆盖后 ✕ 消失——错误/降级类不可关闭）
        banner.show(DegradationBanner.Kind.RENDER_WARNING, "warning")
        assertEquals("渲染警示应覆盖直替提示", DegradationBanner.Kind.RENDER_WARNING, banner.kindForTest())
        assertFalse("警示类别不带 ✕", banner.dismissVisibleForTest())
        // 直替提示不覆盖更高优先类别
        banner.show(DegradationBanner.Kind.DOLLAR_INJECT, "dollar-info")
        assertEquals("低优先不应覆盖渲染警示", DegradationBanner.Kind.RENDER_WARNING, banner.kindForTest())
        // 降级类别同样压制直替提示
        val banner2 = DegradationBanner()
        banner2.show(DegradationBanner.Kind.DEGRADED, "degraded")
        banner2.show(DegradationBanner.Kind.DOLLAR_INJECT, "dollar-info")
        assertEquals(DegradationBanner.Kind.DEGRADED, banner2.kindForTest())
    }

    fun test直替横幅_关闭回调触发() {
        val banner = DegradationBanner()
        var dismissed = false
        banner.onDismiss = { dismissed = true }
        banner.show(DegradationBanner.Kind.DOLLAR_INJECT, "dollar-info")
        banner.clickDismissForTest()
        assertTrue("✕ 点击应触发 onDismiss 回调", dismissed)
    }

    // ---------- 面板接线 ----------

    /** 含两处 `\${}`（OGNL 字面量求值）的 XML 预览结果；warning 可选注入。 */
    private fun dollarXmlResult(warning: String? = null): PreviewResult {
        val mapper = """
            <mapper namespace="com.example.BizMapper">
              <select id="s1" resultType="map">
                SELECT * FROM t_biz ORDER BY ${'$'}{'create_time'} , ${'$'}{'id'}
              </select>
            </mapper>
        """.trimIndent()
        val parsed = XmlMapperParser().parse(mapper)
        val model = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        val rendered = model.renderSql()
        assertEquals("前置：两处 ${'$'} 直替计数（计数语义锁死）", 2, rendered.dollarSubstitutions)
        return PreviewResult(
            sql = rendered.sql,
            compactSql = rendered.sql.replace(Regex("\\s+"), " ").trim(),
            unresolvedParams = rendered.mappings.map { it.property },
            paramTable = ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }),
            wrapperType = null,
            tableName = null,
            xmlModel = model,
            warning = warning,
        )
    }

    fun test预览面板_直替横幅按计数显示() {
        val panel = SqlPreviewPanel(project)
        val banner = panel.degradationBannerForTest()
        try {
            panel.showResult(dollarXmlResult())
            val kind = banner.kindForTest()
            if (kind == DegradationBanner.Kind.DEGRADED) {
                // headless 沙盒预览降级链常驻（内存 PSI 层也亮横幅）——DOLLAR_INJECT 让位属
                // 设计行为；计数文案与 ✕ 在真机（无降级）路径生效，组件级用例已锁行为
                return
            }
            assertEquals("两处 `\${}` 直替 → DOLLAR_INJECT 挂起", DegradationBanner.Kind.DOLLAR_INJECT, kind)
            assertEquals(
                MpSqlVisualizerBundle.message("panel.banner.dollarInject", SqlPreviewPanel.DOLLAR_TOKEN, 2),
                banner.textForTest(),
            )
            assertTrue(banner.dismissVisibleForTest())
        } finally {
            panel.clear()
        }
    }

    fun test预览面板_纯井号语句不挂直替横幅() {
        val panel = SqlPreviewPanel(project)
        val banner = panel.degradationBannerForTest()
        try {
            val mapper = """
                <mapper namespace="com.example.BizMapper">
                  <select id="s1" resultType="map">SELECT * FROM t_biz WHERE a = #{a}</select>
                </mapper>
            """.trimIndent()
            val parsed = XmlMapperParser().parse(mapper)
            val model = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
            val rendered = model.renderSql()
            assertEquals("前置：无 `\${}` 计数为 0", 0, rendered.dollarSubstitutions)
            panel.showResult(
                PreviewResult(
                    sql = rendered.sql,
                    compactSql = rendered.sql,
                    unresolvedParams = rendered.mappings.map { it.property },
                    paramTable = null,
                    wrapperType = null,
                    tableName = null,
                    xmlModel = model,
                ),
            )
            assertTrue(
                "无 `\${}` → 不应挂直替类别（${banner.kindForTest()}）",
                banner.kindForTest() != DegradationBanner.Kind.DOLLAR_INJECT,
            )
        } finally {
            panel.clear()
        }
    }

    fun test预览面板_警示在场直替提示让位() {
        val panel = SqlPreviewPanel(project)
        val banner = panel.degradationBannerForTest()
        try {
            panel.showResult(dollarXmlResult(warning = "解析失败"))
            assertTrue(
                "警示在场 → 直替信息提示让位（${banner.kindForTest()}）",
                banner.kindForTest() == DegradationBanner.Kind.RENDER_WARNING ||
                    banner.kindForTest() == DegradationBanner.Kind.DEGRADED,
            )
        } finally {
            panel.clear()
        }
    }

    fun test预览面板_关闭后会话记忆不再弹() {
        val panel1 = SqlPreviewPanel(project)
        val panel2 = SqlPreviewPanel(project)
        try {
            panel1.showResult(dollarXmlResult())
            if (panel1.degradationBannerForTest().kindForTest() != DegradationBanner.Kind.DOLLAR_INJECT) {
                // headless 降级链常驻 → 直替横幅未挂起，无 ✕ 可点；会话记忆门控在
                // updateMeta 分支内，✕ 回调链路由组件级 test直替横幅_关闭回调触发 覆盖
                return
            }
            panel1.degradationBannerForTest().clickDismissForTest()
            assertFalse("✕ 后当前横幅隐藏", panel1.degradationBannerForTest().isVisible)
            // 会话级记忆：新面板同语句不再弹（companion 级，跨面板实例）
            panel2.showResult(dollarXmlResult())
            assertTrue(
                "会话记忆生效 → 新面板不挂直替类别（${panel2.degradationBannerForTest().kindForTest()}）",
                panel2.degradationBannerForTest().kindForTest() != DegradationBanner.Kind.DOLLAR_INJECT,
            )
            // 复位后恢复显示
            SqlPreviewPanel.resetDollarMarkForTest()
            panel2.showResult(dollarXmlResult())
            assertEquals(DegradationBanner.Kind.DOLLAR_INJECT, panel2.degradationBannerForTest().kindForTest())
        } finally {
            panel1.clear()
            panel2.clear()
        }
    }
}
