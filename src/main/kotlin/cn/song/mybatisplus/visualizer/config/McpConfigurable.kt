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
import java.awt.GridLayout
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
 *
 * T-McpGateDynamicFilter（O-68）订正与增补：getEnabledToolsets 过滤只覆盖 ReflectionToolsProvider
 * 路径，agent 真正看到的外部会话清单走 getMcpToolsFiltered 过滤层——本页门控经
 * SqlVisualizerToolGateFilterProvider（mcpServer.mcpToolFilterProvider EP，经 McpGateFilterRegistrar
 * 程序化注册，平台 mcpServer 类可达性门控）同步驱动外部路径，两条路径均出自 McpToolGate 单一真相源。
 */
class McpConfigurable : Configurable {

    private var panel: JPanel? = null

    /** 总开关复选框（apply 时写 state.mcpToolsEnabled）。 */
    private var masterCheckBox: JBCheckBox? = null

    /** 工具级复选框（工具名 → 勾选态=启用；apply 时反向写 mcpDisabledTools）。 */
    private val toolCheckBoxes = LinkedHashMap<String, JBCheckBox>()

    /** 全部启用/全部禁用状态切换单按钮（全勾→「全部禁用」点击清空，否则「全部启用」补满）。 */
    private var toggleAllButton: JButton? = null

    /** P1-3：就绪横幅标题行（静态渲染快照，测试断言用）。 */
    private var bannerHeadline: JBLabel? = null

    /** P1-3：就绪横幅门控现状行（静态渲染快照，测试断言用）。 */
    private var bannerGateLine: JBLabel? = null

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

        // P1-3（批次9 波次2）：页内就绪横幅——差异化最强的功能面此前在页内零自我陈述（第十轮
        // 摩擦 #2「MCP 工具可发现性黑洞」，锚点 :62-73 仅 jumpRow、:92-99 复选框循环、:146 才有
        // warning）。形态照抄 R20 落地页零监听器定例：**静态构建时按门控现状渲染，不做动态刷新
        // 监听**（工具数动态取 [McpToolGate.TOOL_NAMES] 的 size，不写死数字——R14 十工具升十一
        // 工具时旧写死文案即过期）。横幅是页内静态提示不是弹窗，不触 BannedApiSourceScanTest
        // 弹窗白名单（弹窗克制红线：文案只指向下方开关，不弹任何对话框）。
        bannerHeadline = JBLabel(
            MpSqlVisualizerBundle.message(
                "settings.mcp.banner.ready",
                McpToolGate.TOOL_NAMES.size.toString(),
            ),
        )
        bannerGateLine = JBLabel(bannerGateHint(state)).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }
        val banner = JPanel(GridLayout(0, 1)).apply {
            border = JBUI.Borders.emptyBottom(10)
            add(bannerHeadline)
            add(bannerGateLine)
        }

        val masterCheckBox = JBCheckBox(
            MpSqlVisualizerBundle.message("settings.mcp.enable.master"),
            state?.mcpToolsEnabled ?: true,
        ).apply {
            toolTipText = MpSqlVisualizerBundle.message("settings.mcp.enable.master.desc")
        }
        this.masterCheckBox = masterCheckBox

        val builder = FormBuilder.createFormBuilder()
            .addComponent(banner)
            .addComponent(jumpRow)
            .addComponent(masterCheckBox)
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

        // 20261008 dev-bak 验收（缺陷 #2）：横幅按 R20 落地页「零监听器」定例构建后不再更新——
        // 勾掉工具 / 关总开关乃至 Apply，横幅在同会话内恒显构建时快照（重开整个设置对话框才刷新）。
        // 验收文档 3.21 预期「横幅切到部分禁用/总开关关闭态」。门控复选框是页内唯一写入方，
        // 对它们挂 ActionListener 即时重算门控行（读复选框现状而非持久化 state，三态口径与
        // [bannerGateHint] 同源；headline 的就绪文案保持静态——工具注册数不随开关变化）。
        val bannerRefresher = java.lang.Runnable { refreshBannerGateLine() }
        masterCheckBox?.addActionListener { bannerRefresher.run() }
        toolCheckBoxes.values.forEach { it.addActionListener { bannerRefresher.run() } }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10)
            add(builder.addComponentFillVertically(JPanel(), 0).panel, BorderLayout.NORTH)
        }
    }

    /** 复选框文案：工具名 + 简述（双语，键按工具名尾段派生）。 */
    private fun checkBoxText(toolName: String): String {
        val descKey = "settings.mcp.tool.desc." + toolName.removePrefix("sqlv_").replace("_", "")
        return toolName + " — " + MpSqlVisualizerBundle.message(descKey)
    }

    /**
     * P1-3：就绪横幅门控现状行（纯函数，三态与 [isToolListedInState] 同一真相源）——
     * 总开关关 > 工具级有禁用 > 全启用。state=null（测试环境服务未注册）按全启用兜底，
     * 与门控默认口径一致。
     */
    private fun bannerGateHint(state: PluginSettings.State?): String = when {
        state?.mcpToolsEnabled == false -> MpSqlVisualizerBundle.message("settings.mcp.banner.masterOff")
        else -> {
            val disabledCount = McpToolGate.TOOL_NAMES.count { !isToolListedInState(state, it) }
            if (disabledCount == 0) {
                MpSqlVisualizerBundle.message("settings.mcp.banner.allEnabled")
            } else {
                MpSqlVisualizerBundle.message("settings.mcp.banner.someDisabled", disabledCount.toString())
            }
        }
    }

    /**
     * 20261008 验收缺陷 #2 修复：横幅门控行按复选框**现状**即时重算（总开关关 > 工具级有禁用 >
     * 全启用，与 [bannerGateHint] 三态同源——数据源从持久化 state 换成页内复选框）。
     */
    private fun refreshBannerGateLine() {
        val gate = bannerGateLine ?: return
        val masterOn = masterCheckBox?.isSelected != false
        val disabledCount = toolCheckBoxes.values.count { !it.isSelected }
        gate.text = when {
            !masterOn -> MpSqlVisualizerBundle.message("settings.mcp.banner.masterOff")
            disabledCount == 0 -> MpSqlVisualizerBundle.message("settings.mcp.banner.allEnabled")
            else -> MpSqlVisualizerBundle.message("settings.mcp.banner.someDisabled", disabledCount.toString())
        }
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

    /** 跳转平台 MCP Server 设置页。弹窗白名单依据（BannedApiSourceScanTest「模态弹窗只允许
     *  白名单内既有场景」）：目标 configurable 类不可用（IDEA < 2025.2 或未装 MCP Server 插件）
     *  时警告属「配置不可用、用户必须知情」场景，与 PluginSettingsPanel 配置异常 warning 同类
     *  ——按钮点了无反应不可接受，需明确告知前置条件。 */
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
        bannerHeadline = null
        bannerGateLine = null
        toolCheckBoxes.clear()
    }

    // ------------------------------------------------------------------
    // 测试访问器（McpConfigurableGateTest；internal 同模块）
    // ------------------------------------------------------------------

    internal fun masterForTest(): JBCheckBox? = masterCheckBox

    internal fun toolCheckBoxForTest(toolName: String): JBCheckBox? = toolCheckBoxes[toolName]

    internal fun toggleAllButtonForTest(): JButton? = toggleAllButton

    /** P1-3：就绪横幅标题行文案（null = 横幅未构建/已释放）。 */
    internal fun bannerHeadlineForTest(): String? = bannerHeadline?.text

    /** P1-3：就绪横幅门控现状行文案。 */
    internal fun bannerGateLineForTest(): String? = bannerGateLine?.text
}
