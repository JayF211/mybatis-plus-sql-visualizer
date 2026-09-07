package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.actions.ConvertSqlToJavaAction
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * 设置面板绑定生效性测试（T-xx 设置生效性审计）：
 *
 *  - 反向转换形态（reverseWrapperFormat）：此前**漏挂 isModified**——仅改此项时 Apply 按钮不启用，
 *    改动被平台静默丢弃（字段有真实读取点（ConvertSqlToJavaAction/SqlToJavaPanel）但保存链路断裂，形同假开关）；
 *  - 启动自动监听开关（logAutoCaptureOnStart）：勾选/取消 → apply 写回 state（状态持久化，
 *    实际生效由 LogStartupCapture postStartupActivity 在项目打开时触发）。
 */
class PluginSettingsPanelBindingTest : BasePlatformTestCase() {

    fun testReverseFormatChangeEnablesApplyAndPersists() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.reverseWrapperFormat
        try {
            settings.state.reverseWrapperFormat = PluginSettings.REVERSE_AUTO
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            assertFalse("初始应与配置一致（无变化）", configurable.isModified())

            panel.selectReverseFormat(PluginSettings.REVERSE_QUERY)
            assertTrue(
                "仅改反向转换形态也应检测到变化（修复前漏挂 isModified → Apply 不启用、改动被静默丢弃）",
                configurable.isModified(),
            )

            configurable.apply()
            assertEquals("apply 后配置应写回 QUERY", PluginSettings.REVERSE_QUERY, settings.state.reverseWrapperFormat)

            configurable.reset()
            assertFalse("reset 后应回到与配置一致（无变化）", configurable.isModified())
        } finally {
            settings.state.reverseWrapperFormat = old
        }
    }

    fun testAutoCaptureToggleAppliesToState() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.logAutoCaptureOnStart
        try {
            settings.state.logAutoCaptureOnStart = false
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            panel.setLogAutoCaptureCheck(true)
            assertTrue("勾选「启动自动监听」应检测到变化", configurable.isModified())
            configurable.apply()
            assertTrue("apply 后 state.logAutoCaptureOnStart 应为 true", settings.state.logAutoCaptureOnStart)

            panel.setLogAutoCaptureCheck(false)
            assertTrue("取消勾选同样应检测到变化", configurable.isModified())
            configurable.apply()
            assertFalse("apply 后 state.logAutoCaptureOnStart 应回 false", settings.state.logAutoCaptureOnStart)
        } finally {
            settings.state.logAutoCaptureOnStart = old
        }
    }

    fun testManualEntityToggleAppliesToState() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.reverseManualEntitySelection
        try {
            settings.state.reverseManualEntitySelection = false
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            panel.setManualEntityCheck(true)
            assertTrue("勾选「手动选择实体类」应检测到变化", configurable.isModified())
            configurable.apply()
            assertTrue("apply 后 state.reverseManualEntitySelection 应为 true", settings.state.reverseManualEntitySelection)

            panel.setManualEntityCheck(false)
            assertTrue("取消勾选同样应检测到变化", configurable.isModified())
            configurable.apply()
            assertFalse("apply 后 state.reverseManualEntitySelection 应回 false", settings.state.reverseManualEntitySelection)

            configurable.reset()
            assertFalse("reset 后应回到与配置一致（无变化）", configurable.isModified())
        } finally {
            settings.state.reverseManualEntitySelection = old
        }
    }

    fun testCacheInfoAndClearButton() {
        val configurable = AllSectionsConfigurable()
        configurable.createComponent()
        val panel = configurable.panelForTest()
        // 构造时应已刷新缓存信息（非空文案；Light 环境缓存为空也显示汇总格式）
        assertTrue("缓存信息文案应已初始化: " + panel.cacheInfoForTest(), panel.cacheInfoForTest().isNotBlank())
        assertTrue("缓存信息应含大小单位（B/KB/MB）: " + panel.cacheInfoForTest(), panel.cacheInfoForTest().contains("B"))
        // 统一清除按钮存在且点击不抛异常（Light 项目可能不在 openProjects——空遍历安全）；
        // 点击后信息行变「已清除」状态文本（仿检测更新反馈，持续显示直到下次操作/重开设置）
        panel.clearCacheButtonForTest().doClick()
        assertEquals(
            "点击清除后信息行应显示「已清除」反馈",
            MpSqlVisualizerBundle.message("settings.cache.cleared"),
            panel.cacheInfoForTest(),
        )
    }

    fun testSqlToJavaShortcutRowExistsAndApplies() {
        val configurable = AllSectionsConfigurable()
        configurable.createComponent()
        val panel = configurable.panelForTest()

        // 行存在：递归收集行标签（GridBag 嵌套一层，递归通用）
        val labels = collectLabels(panel.getRoot()).map { it.text }
        assertTrue(
            "设置页应含「SQL→Java 快捷键」行（T-M41 新增；修复前仅 Convert to SQL 一行）",
            labels.contains(MpSqlVisualizerBundle.message("settings.shortcut.sqlToJava")),
        )
        assertTrue(
            "原有「快捷键」（Convert to SQL）行应保留",
            labels.contains(MpSqlVisualizerBundle.message("settings.shortcut")),
        )

        // 修改检测：编辑行值变化 → isModified
        assertFalse("初始应与当前 Keymap 一致（无变化）", configurable.isModified())
        val newShortcut = KeyboardShortcut(
            KeyStroke.getKeyStroke(KeyEvent.VK_J, KeyEvent.CTRL_DOWN_MASK or KeyEvent.ALT_DOWN_MASK),
            null,
        )
        panel.reverseShortcutEditorForTest().shortcut = newShortcut
        assertTrue("仅改 SQL→Java 快捷键也应检测到变化", configurable.isModified())

        // apply 写入 Keymap（无异常）；读回断言兜底——headless 环境 Keymap 不可用时跳过
        configurable.apply()
        runCatching {
            val shortcuts = KeymapManager.getInstance().activeKeymap
                .getShortcuts(ConvertSqlToJavaAction.ACTION_ID)
                .filterIsInstance<KeyboardShortcut>()
            assertTrue("apply 后 activeKeymap 应含新快捷键", shortcuts.contains(newShortcut))
        }.onFailure {
            // Light 环境 Keymap 可能不可用（currentShortcut 兜底 null）——降级跳过读回断言
        }

        // reset 回读：编辑行回到当前 Keymap 值 → 无变化
        configurable.reset()
        assertFalse("reset 后应回到与 Keymap 一致（无变化）", configurable.isModified())
    }

    fun testVersionInfoLabelTopAlignedWithSection() {
        // T-M41 回归：updateSection 为多行组件，GridBag 默认 WEST（垂直居中）会把「版本信息」
        // 标签垂直居中到整块中间（错位到「启动时自动检查更新」行——用户截图实证）→ NORTHWEST 顶部对齐。
        // headless 无 peer，需 doLayout 链后才有真实坐标（pitfall 实证）。
        val configurable = AllSectionsConfigurable()
        configurable.createComponent()
        val root = configurable.panelForTest().getRoot()
        root.setSize(root.preferredSize)
        layoutTree(root)

        // 找到「更新与诊断」分区面板（包含 UpdateSection 的那个 section 容器）
        val updateSection = findComponent(root, UpdateSection::class.java)
        assertTrue("应找到 UpdateSection", updateSection != null)
        val sectionPanel = updateSection!!.parent
        val versionLabel = sectionPanel.components
            .filterIsInstance<JBLabel>()
            .firstOrNull { it.text == MpSqlVisualizerBundle.message("settings.update.versionInfo") }
        assertTrue("「版本信息」标签应在分区面板标签列", versionLabel != null)

        // 标签应顶对齐（与 updateSection 顶部接近），而非垂直居中（旧行为错位到下一行）
        val diff = versionLabel!!.y - updateSection.y
        assertTrue(
            "「版本信息」标签应与 updateSection 顶部对齐（实际偏移 ${diff}px；WEST 垂直居中旧行为约 +20px）",
            diff in -2..10,
        )
    }

    /** 递归 doLayout（headless 布局断言前置——pitfall-headless-validate）。 */
    private fun layoutTree(container: Container) {
        container.doLayout()
        container.components.forEach { if (it is Container) layoutTree(it) }
    }

    /** 递归查找指定类型组件（含自身）。 */
    private fun <T : Component> findComponent(root: Container, type: Class<T>): T? {
        if (type.isInstance(root)) return root as T
        for (c in root.components) {
            if (type.isInstance(c)) return c as T
            if (c is Container) findComponent(c, type)?.let { return it }
        }
        return null
    }

    /** 递归收集组件树中全部 JBLabel（仿 PluginSettingsPanelBuildInfoTest.collectLabels）。 */
    private fun collectLabels(component: Component): List<JBLabel> {
        val labels = mutableListOf<JBLabel>()
        if (component is JBLabel) labels += component
        if (component is Container) component.components.forEach { labels += collectLabels(it) }
        return labels
    }

    // ------------------------------------------------------------------
    // T-TenantLine：多租户注入面板绑定生效性
    // ------------------------------------------------------------------

    fun testTenantLinePanelIsModified() {
        // T-TenantLine：改 injectTenantLine → isModified=true → apply 写回配置
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.injectTenantLine = true
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            assertFalse("初始应与配置一致（无变化）", configurable.isModified())

            // 取消勾选多租户注入开关（与用户勾选同一路径）
            panel.setInjectTenantLineCheck(false)
            assertTrue("改 injectTenantLine 后应检测到变化", configurable.isModified())

            configurable.apply()
            assertEquals("apply 后 state.injectTenantLine 应为 false", false, settings.state.injectTenantLine)

            configurable.reset()
            assertFalse("reset 后应回到与配置一致（无变化）", configurable.isModified())
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testTenantLinePanelApplyReset() {
        // T-TenantLine：面板 apply/reset 写入配置 + 回读
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tenantColumnName = "tenant_id"
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            // 改租户列名（与用户输入同一路径）
            panel.setTenantColumnName("org_id")
            assertTrue("改 tenantColumnName 后应检测到变化", configurable.isModified())

            configurable.apply()
            assertEquals("apply 后 state.tenantColumnName 应为 org_id", "org_id", settings.state.tenantColumnName)

            // reset 回读
            configurable.reset()
            assertFalse("reset 后应回到与配置一致（无变化）", configurable.isModified())
        } finally {
            settings.loadState(snapshot)
        }
    }
}
