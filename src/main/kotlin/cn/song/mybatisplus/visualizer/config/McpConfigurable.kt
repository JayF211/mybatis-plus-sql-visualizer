package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * T-ShortcutSettings：MCP 工具独立子设置页。
 *
 * 三只读工具（mpsv_render_sql / mpsv_schema_context / mpsv_explain）注册进平台 MCP Server
 * （mcp-optional.xml `mcpServer.mcpToolset` EP），由平台 MCP Server 设置页统一管理启用/禁用。
 * 本页面提供跳转入口与工具状态说明——不重复实现平台工具过滤逻辑（单一真相源）。
 *
 * 平台 Configurable 类名 = com.intellij.mcpserver.settings.McpServerSettingsConfigurable
 * （262 mcpserver.jar 常量池实证；包名 mcpserver 全小写）。
 */
class McpConfigurable : Configurable {

    private var panel: JPanel? = null

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

        // 三工具说明清单（只读展示，启用/禁用走平台 MCP Server 设置）
        val toolsInfo = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(JBLabel(MpSqlVisualizerBundle.message("settings.mcp.tools.heading")).apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            })
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10)
            add(FormBuilder.createFormBuilder()
                .addComponent(jumpRow)
                .addComponent(toolsInfo)
                .addComponentFillVertically(JPanel(), 0)
                .panel, BorderLayout.NORTH)
        }
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

    override fun isModified(): Boolean = false

    override fun apply() { /* 纯跳转页，无本地状态 */ }

    override fun disposeUIResources() {
        panel = null
    }
}
