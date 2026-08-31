package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComboBox

/**
 * 设置面板「布局 / 兼容性 / 交互」UI 测试（F-44-6 续 · 用户要求「整个插件 UI 用例」）：
 *
 * 此前仅覆盖绑定/持久化逻辑（PluginSettingsPanelBindingTest 等），真实渲染与布局未测——用户明确点名
 * 「跨 IDE 布局 / section 显隐」是薄弱点。本测试模拟真实 Settings 打开：
 *  - 面板构建即出根组件、关键控件（方言/语言/复选框/清除缓存按钮）均可见且有正首选尺寸
 *    （headless 下 preferredSize 由真实字体度量计算，能证明控件在真实 IDE 中会渲染、不塌缩/消失）；
 *  - section 过滤：仅启用 SQL 日志段 → 其余段（含方言/语言/反向形态等下拉）全部不构建、不占布局；
 *  - 真实交互：勾选「启动自动监听」→ isModified 翻转（Apply 按钮可点击）；切换语言 → 写穿 + 重建不崩、
 *    控件仍存活（验证 M19 写穿式语言切换在真实 Settings 链路下的稳定性）。
 *
 * 注：headless 环境无 realized peer，`doLayout()` 会把 GridBag 子控件尺寸算成 0，故布局断言改用
 * `preferredSize`（与具体布局无关、由字体/文本决定），更稳且同样能暴露「控件不渲染」类缺陷。
 */
class PluginSettingsPanelUiTest : BasePlatformTestCase() {

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    /** 递归收集容器内所有组件（含嵌套）。 */
    private fun allComponents(c: Component): List<Component> {
        val list = mutableListOf(c)
        if (c is Container) for (child in c.components) list += allComponents(child)
        return list
    }

    /** 真实控件类型（排除根容器本身），用于渲染/布局断言。 */
    private val REAL_TYPES = listOf(JComboBox::class, JBCheckBox::class, JBLabel::class, JButton::class, JBTextField::class)

    fun test面板构建_关键控件可见且有正首选尺寸() {
        val settings = PluginSettings.getInstance()
        val panel = PluginSettingsPanel(settings)
        try {
            waitForEdt()
            val root = panel.getRoot()
            assertNotNull("根面板应非空", root)

            val realControls = allComponents(root)
                .filter { it !== root && it.isVisible && REAL_TYPES.any { t -> t.isInstance(it) } }
            assertTrue("应存在可见的真实控件（下拉/复选/标签/按钮/文本）", realControls.isNotEmpty())

            // 所有可见真实控件的首选尺寸 > 0（headless 由字体度量计算）：证明在真实 IDE 中会渲染、
            // 不会因布局塌缩而「消失」（用户点名的跨 IDE 布局错乱信号）。
            // 例外：空文本 JBLabel 是故意的占位/包裹行（addRow(p, g, "", checkBox)），首选宽 0 属正常。
            val zeroPreferred = realControls.filter {
                (it.preferredSize.width <= 0 || it.preferredSize.height <= 0) &&
                    !(it is JBLabel && it.text.isBlank())
            }
            assertEquals(
                "所有含文本/内容的可见真实控件应有正首选尺寸（无渲染塌缩/消失）: ${zeroPreferred.map { it::class.simpleName }}",
                0,
                zeroPreferred.size,
            )

            // 关键控件经 ForTest 缝可触达
            assertNotNull("清除缓存按钮应存在", panel.clearCacheButtonForTest())
            assertNotNull("反向转换快捷键编辑行应存在", panel.reverseShortcutEditorForTest())
        } finally {
            // 无显式 dispose，仅释放引用
        }
    }

    fun testSection可见性_仅SQL日志段隐藏其他段控件() {
        val settings = PluginSettings.getInstance()
        // 全量：6 个 section（部分含子分组，故分隔线数 >= 6）
        val full = PluginSettingsPanel(settings)
        // 仅 SQL 日志段：其余 section 不应被构建
        val sqlLogOnly = PluginSettingsPanel(settings, setOf(PluginSettingsPanel.SECTION_SQL_LOG))
        try {
            waitForEdt()
            val fullCombos = allComponents(full.getRoot()).count { it is JComboBox<*> }
            val sqlLogCombos = allComponents(sqlLogOnly.getRoot()).count { it is JComboBox<*> }

            assertTrue("全量面板应含多个下拉（方言/语言/反向形态/表名推断等）", fullCombos >= 3)
            assertEquals("仅 SQL 日志段不应含任何下拉控件（其余 section 被隐藏）", 0, sqlLogCombos)

            val fullSeparators = allComponents(full.getRoot()).count { it is TitledSeparator }
            val sqlLogSeparators = allComponents(sqlLogOnly.getRoot()).count { it is TitledSeparator }
            assertTrue("全量面板分组数应多于仅 SQL 日志段（section 显隐生效）", fullSeparators > sqlLogSeparators)
        } finally {
            // no-op
        }
    }

    fun test勾选启动自动监听_isModified翻转() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.logAutoCaptureOnStart
        try {
            settings.state.logAutoCaptureOnStart = false
            val panel = PluginSettingsPanel(settings)
            waitForEdt()
            assertFalse("初始应与配置一致（未勾选 → 无变化）", panel.isModified())

            panel.setLogAutoCaptureCheck(true)
            assertTrue("勾选「启动自动监听」应检测到变化（Apply 可点击）", panel.isModified())

            panel.setLogAutoCaptureCheck(false)
            assertFalse("取消勾选应回到与配置一致", panel.isModified())
        } finally {
            settings.state.logAutoCaptureOnStart = old
        }
    }

    fun test切换语言触发写穿与重建_控件仍存活() {
        val settings = PluginSettings.getInstance()
        val oldLang = settings.state.language
        try {
            settings.state.language = "zh"
            val panel = PluginSettingsPanel(settings)
            waitForEdt()
            assertFalse("初始语言与配置一致，无修改", panel.isModified())

            // 切换语言：写穿式持久化（立即写入 state）+ 置 languagePending（Apply 按钮启用）
            // + invokeLater 重建表单。M19.1 设计：语言变更不靠 OK→apply，选中即生效，但仍需 Apply 清 pending。
            panel.selectLanguage("en")
            waitForEdt() // 泵 EDT，使 invokeLater 重建执行
            assertTrue("切换语言应写穿并置 languagePending → isModified 为 true（Apply 可点击）", panel.isModified())
            assertEquals("语言应写穿持久化到配置 state", "en", settings.state.language)

            // 重建后仍能取到根面板与关键控件（不崩、不丢失）
            val rootAfter = panel.getRoot()
            assertNotNull("重建后根面板应仍可取", rootAfter)
            val combosAfter = allComponents(rootAfter).count { it is JComboBox<*> }
            assertTrue("重建后下拉控件应仍存在", combosAfter >= 1)
        } finally {
            // 复位语言配置（pending 标记随面板实例 GC，不影响其他测试）
            settings.state.language = oldLang
        }
    }
}
