package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.mcp.McpToolGate
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * T-ShortcutSettings：MCP 工具独立子设置页；T-McpToolset2 / T-MTS2-00 增补门控区——
 * 总开关（插件 MCP 功能启停）+ 工具级启用/禁用复选框（全部启用/全部禁用状态切换单按钮，
 * T-SelToggle 同款范式）。
 *
 * 工具注册进平台 MCP Server（mcp-optional.xml `mcpServer.mcpToolset` EP，一工具一 toolset 类），
 * 门控经 McpToolGate 读本页写入的设置（总开关 off / 工具被禁用 → 对应 toolset `isEnabled()=false`，
 * 平台 getEnabledToolsets 每次列表/调用重新求值——262 mcpserver.jar 字节码实证，切换即时生效）。
 * 平台侧工具过滤（McpServerSettingsConfigurable）仍是另一独立维度，本页只管插件自有工具；
 * 「点击前往」跳转入口保留（平台级 disallow list 与本页互补，各自单一真相源）。
 */
class McpConfigurable : Configurable {

    private var panel: JPanel? = null

    /** 总开关复选框（apply 时写 state.mcpToolsEnabled）。 */
    private var masterCheckBox: JBCheckBox? = null

    /** 工具级复选框（工具名 → 勾选态=启用；apply 时反向写 mcpDisabledTools）。 */
    private val toolCheckBoxes = LinkedHashMap<String, JBCheckBox>()

    /** 全部启用/全部禁用状态切换单按钮（全勾→「全部禁用」点击清空，否则「全部启用」补满）。 */
    private var toggleAllButton: JButton? = null

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("settings.mcp.title")

    override fun createComponent(): JComponent {
        if (panel == null) {
            panel = buildPanel()
        }
        return panel!!
    }

    private fun buildPanel(): JPanel {
        val openButton = JButton(MpSqlVisualizerBundle.message("settings.mcp.open")).apply {
            toolTipText = MpSqlVisualizerBundle.message("settings.mcp.desc")
            addActionListener { openMcpServerSettings() }
        }

        val jumpRow = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                MpSqlVisualizerBundle.message("settings.mcp.label"),
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(openButton)
                },
            )
            .addComponentToRightColumn(
                JBLabel(MpSqlVisualizerBundle.message("settings.mcp.desc")).apply {
                    foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                },
                1,
            )
            .panel

        // T-MTS2-00：门控区——总开关 + 工具级复选框（测试环境服务未注册 → state=null 默认全启用）
        val state = runCatching { PluginSettings.getInstance().state }.getOrNull()
        masterCheckBox = JBCheckBox(
            MpSqlVisualizerBundle.message("settings.mcp.enable.master"),
            state?.mcpToolsEnabled ?: true,
        ).apply {
            toolTipText = MpSqlVisualizerBundle.message("settings.mcp.enable.master.desc")
        }

        val builder = FormBuilder.createFormBuilder()
            .addComponent(jumpRow)
            .addComponent(masterCheckBox!!)
        McpToolGate.TOOL_NAMES.forEach { toolName ->
            // 复选框只表达「工具级未禁用」，与总开关正交（否则 master off 时复选框全不勾，
            // apply 会把全部工具误写进禁用清单）
            val checkBox = JBCheckBox(checkBoxText(toolName), isToolListedInState(state, toolName))
            toolCheckBoxes[toolName] = checkBox
            builder.addComponent(checkBox, 20)
        }
        toggleAllButton = JButton().apply {
            addActionListener { toggleAll() }
        }
        builder.addComponent(
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(toggleAllButton!!)
            },
            10,
        )
        syncToggleAllLabel()

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10)
            add(builder.addComponentFillVertically(JPanel(), 0).panel, BorderLayout.NORTH)
        }
    }

    /** 复选框文案：工具名 + 简述（双语，键按工具名尾段派生）。 */
    private fun checkBoxText(toolName: String): String {
        val descKey = "settings.mcp.tool.desc." + toolName.removePrefix("mpsv_").replace("_", "")
        return toolName + " — " + MpSqlVisualizerBundle.message(descKey)
    }

    /** 工具级禁用判定（仅禁用清单，不含总开关——总开关是独立维度）。 */
    private fun isToolListedInState(state: PluginSettings.State?, toolName: String): Boolean {
        if (state == null) return true
        return state.mcpDisabledTools.none { it.equals(toolName, ignoreCase = true) }
    }

    /** T-SelToggle 同款：全勾显示「全部禁用」点击清空，否则显示「全部启用」点击补满。 */
    private fun toggleAll() {
        val target = toolCheckBoxes.values.any { !it.isSelected }
        toolCheckBoxes.values.forEach { it.isSelected = target }
        syncToggleAllLabel()
    }

    private fun syncToggleAllLabel() {
        val allSelected = toolCheckBoxes.values.all { it.isSelected }
        toggleAllButton?.text = MpSqlVisualizerBundle.message(
            if (allSelected) "settings.mcp.tools.none" else "settings.mcp.tools.all",
        )
    }

    private fun openMcpServerSettings() {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val configurableClass = runCatching {
            Class.forName("com.intellij.mcpserver.settings.McpServerSettingsConfigurable")
                as? Class<out com.intellij.openapi.options.Configurable>
        }.getOrNull()
        if (configurableClass == null) {
            Messages.showWarningDialog(
                project,
                MpSqlVisualizerBundle.message("settings.mcp.unavailable"),
                MpSqlVisualizerBundle.message("settings.mcp.title"),
            )
            return
        }
        runCatching {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, configurableClass)
        }
    }

    override fun isModified(): Boolean {
        val state = runCatching { PluginSettings.getInstance().state }.getOrNull() ?: return false
        val masterDirty = (masterCheckBox?.isSelected ?: state.mcpToolsEnabled) != state.mcpToolsEnabled
        val toolsDirty = McpToolGate.TOOL_NAMES.any { toolName ->
            (toolCheckBoxes[toolName]?.isSelected ?: isToolListedInState(state, toolName)) != isToolListedInState(state, toolName)
        }
        return masterDirty || toolsDirty
    }

    override fun apply() {
        val state = PluginSettings.getInstance().state
        state.mcpToolsEnabled = masterCheckBox?.isSelected ?: true
        state.mcpDisabledTools = McpToolGate.TOOL_NAMES
            .filter { toolCheckBoxes[it]?.isSelected == false }
            .toMutableList()
    }

    override fun reset() {
        val state = runCatching { PluginSettings.getInstance().state }.getOrNull() ?: return
        masterCheckBox?.isSelected = state.mcpToolsEnabled
        McpToolGate.TOOL_NAMES.forEach { toolName ->
            toolCheckBoxes[toolName]?.isSelected = isToolListedInState(state, toolName)
        }
        syncToggleAllLabel()
    }

    override fun disposeUIResources() {
        panel = null
        masterCheckBox = null
        toggleAllButton = null
        toolCheckBoxes.clear()
    }

    // ------------------------------------------------------------------
    // 测试访问器（McpConfigurableGateTest；internal 同模块）
    // ------------------------------------------------------------------

    internal fun masterForTest(): JBCheckBox? = masterCheckBox

    internal fun toolCheckBoxForTest(toolName: String): JBCheckBox? = toolCheckBoxes[toolName]

    internal fun toggleAllButtonForTest(): JButton? = toggleAllButton
}
