package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.PluginSettingsConfigurable
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlHighlightColors
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import java.text.SimpleDateFormat
import java.util.Date
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListModel

/** SQL 末尾分号结束符（显示层统一追加；已以 `;` 结尾或空串不重复；T-M42 提为顶层，openPreview/右键复制/列表复制共用）。 */
internal fun withTerminator(sql: String): String =
    if (sql.isEmpty() || sql.endsWith(";")) sql else sql + ";"

/**
 * 日志条目列表（T-M42）：覆写 JBList.itemToText——内置 Ctrl+C 复制默认返回 cellRenderer
 * 渲染文本（HTML 源码，用户实测缺陷），覆写后复制输出纯 SQL（多选逐条换行拼接、
 * CopyPasteManager 写入、Ctrl+C 快捷键注册均由 JBList 既有链路复用）。
 * open：internal 类默认 final，测试子类经公开包装访问 protected itemToText。
 */
internal open class SqlEntryList(model: ListModel<LogSqlEntry>) : JBList<LogSqlEntry>(model) {
    override fun itemToText(index: Int, element: LogSqlEntry): String = withTerminator(element.restoredSql)
}

/** 工具窗口面板（标签页 + 工具栏；headless 测试不布局，交互断言走条目模型层）。 */
class SqlLogPanel(
    private val project: Project,
    private val manager: SqlLogManager,
) : JPanel(BorderLayout()), Disposable {

    private val listModel = DefaultListModel<LogSqlEntry>()
    private val list = SqlEntryList(listModel)
    private val tabs = JBTabbedPane()
    /** T-M38：日志转 SQL 转换 tab（粘贴日志 → 批量还原可执行 SQL；原「SQL 预览」被动展示改造）。 */
    private val logToSqlPanel = LogToSqlPanel(project)
    /** T-M36.4：SQL → Java 转换 tab（粘贴 SQL → 格式化/补全 → 转换 → 可编辑 Java 高亮结果区）。 */
    private val sqlToJavaPanel = SqlToJavaPanel(project)
    private val dedupeCheckBox = JCheckBox(MpSqlVisualizerBundle.message("log.panel.dedupe")).apply {
        isSelected = manager.entries.dedupeEnabled
        addActionListener { manager.entries.dedupeEnabled = isSelected }
    }

    /**
     * 工具栏动作组（T-M39：internal 供测试断言帮助入口存在性；
     * update 由工具栏生命周期驱动，headless 测试手动触发；须在 init 前声明）。
     */
    internal val toolbarGroup = DefaultActionGroup(
        CaptureAction(),
        ClearAction(),
        SettingsAction(),
        HelpAction(),
    )

    init {
        // 标签 1：SQL 日志（工具栏 + 条目列表）
        val logPanel = JPanel(BorderLayout())
        logPanel.add(buildToolbar(), BorderLayout.NORTH) // JPanel（工具栏 + 去重勾选开关）
        logPanel.add(buildListPane(), BorderLayout.CENTER)

        // 标签 2：日志转 SQL（粘贴日志 → 批量还原可执行 SQL）
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.logs"), logPanel)
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.logToSql"), logToSqlPanel)
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.sqlToJava"), sqlToJavaPanel)
        add(tabs, BorderLayout.CENTER)

        // T-M36.6：tab 重选时刷新形态下拉（设置页修改后切回即同步；不触发重生成）
        tabs.addChangeListener {
            if (tabs.selectedComponent == sqlToJavaPanel) {
                sqlToJavaPanel.refreshFormatFromSettings()
            }
        }

        manager.addUiListener { refreshList() }
        refreshList()

        // capturing 变化 → 刷新工具栏按钮图标/文案（调用 ActionToolbar.updateActionsAsync()；
        // T-M51-06：updateActionsImmediately() 已弃用，改用 updateActionsAsync()（接口公开方法，
        // 三平台 243/253/262 皆存——javap 实证）
        manager.addCapturingListener {
            LOG.info("SqlLogPanel.capturingListener: 触发")
            ApplicationManager.getApplication().invokeLater {
                _toolbarRef?.let { toolbar ->
                    runCatching {
                        toolbar.updateActionsAsync()
                        LOG.info("SqlLogPanel.capturingListener: updateActionsAsync 调用成功")
                    }.onFailure {
                        LOG.warn("SqlLogPanel.capturingListener: updateActionsAsync 失败 - ${it.message}")
                    }
                }
            }
        }
    }

    // ---------- 工具栏（IDEA 原生 ActionToolbar：扁平图标按钮 + tooltip） ----------

    private fun buildToolbar(): JPanel {
        // T-M31.16：暂停按钮移除（捕捉开关已覆盖——用户方案 A）
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID, toolbarGroup, true)
            .apply { targetComponent = this@SqlLogPanel }
        _toolbarRef = toolbar // 保存引用供 capturing 监听刷新

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        bar.add(toolbar.component)
        bar.add(dedupeCheckBox) // 去重勾选开关（T-M31.5）
        return bar
    }

    /** 工具栏引用（capturing 变化时刷新按钮状态）。 */
    private var _toolbarRef: ActionToolbar? = null

    private inner class CaptureAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = if (manager.capturing) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
            e.presentation.text = MpSqlVisualizerBundle.message(
                if (manager.capturing) "log.panel.stop" else "log.panel.capture",
            )
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (manager.capturing) {
                manager.stopCapture()
            } else {
                manager.startCapture()
            }
        }
    }

    private inner class ClearAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.GC
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.clear")
        }

        override fun actionPerformed(e: AnActionEvent) {
            manager.entries.clear()
        }
    }

    private inner class SettingsAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.General.Settings
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.settings")
        }

        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginSettingsConfigurable::class.java)
        }
    }

    /** T-M39：帮助入口（? 按钮，位于设置按钮旁）→ 帮助弹窗（核心功能/使用方法/默认快捷键）。
     *  图标用 AllIcons.Actions.Help（纯灰 #6E6E6E 问号，与设置/清空按钮同色系；
     *  ContextHelp 为灰蓝圆圈 #9AA7B0，与旁边按钮色相不一致——用户反馈）。 */
    private inner class HelpAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Help
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.help")
        }

        override fun actionPerformed(e: AnActionEvent) {
            HelpDialog(project).show()
        }
    }

    // ---------- 条目列表 ----------

    private fun buildListPane(): JBScrollPane {
        list.emptyText.text = MpSqlVisualizerBundle.message("log.panel.empty")
        list.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val entry = value as? LogSqlEntry
                // T-M31.13/14：SQL 词法着色（插件自身 SqlHighlightColors 配色，随 IDE 主题）+ 可选时间戳前缀
                text = null
                if (entry != null) {
                    // T-M31.15：时间戳与 SQL 分行（<br>）+ 每条 SQL 末尾分号结束符 + 条目间空行
                    val timestamp = timestampHtml(entry.timestamp)
                    val sql = SqlListHtmlRenderer.render(withTerminator(entry.summary))
                    text = "<html>" + timestamp + "<br>" + sql + "<br></html>"
                    toolTipText = entry.restoredSql
                }
                return this
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2) {
                    list.selectedValue?.let { openPreview(it) }
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
        })
        return JBScrollPane(list)
    }

    // ---------- 刷新与动作 ----------

    private fun refreshList() {
        val snapshot = manager.entries.snapshot()
        listModel.clear()
        snapshot.forEach { listModel.addElement(it) }
    }

    /** 时间戳 HTML 前缀（设置开关 + 格式；灰字跟随主题）。 */
    private fun timestampHtml(timestamp: Long): String {
        val state = PluginSettings.getInstance().state
        if (!state.logTimestampEnabled) return ""
        val formatted = try {
            SimpleDateFormat(state.logTimestampFormat).format(Date(timestamp))
        } catch (_: IllegalArgumentException) {
            SimpleDateFormat(DEFAULT_TIMESTAMP_FORMAT).format(Date(timestamp)) // 非法格式回退
        }
        val color = toHex(EditorColorsManager.getInstance().globalScheme.getAttributes(SqlHighlightColors.COMMENT).foregroundColor)
        return "<span style='color:$color'>[$formatted] </span>"
    }

    private fun toHex(color: java.awt.Color): String {
        val r = Integer.toHexString(color.red).padStart(2, '0')
        val g = Integer.toHexString(color.green).padStart(2, '0')
        val b = Integer.toHexString(color.blue).padStart(2, '0')
        return "#$r$g$b"
    }

    private fun openPreview(entry: LogSqlEntry) {
        // T-M31.14：设置开启时预览展示格式化版（多行缩进），否则原样
        val state = PluginSettings.getInstance().state
        // T-M42：双击弹窗自动识别参数——`?` 数 == 参数数 ⇔ 匹配识别（值预填日志参数值，初始显示与现状一致）
        val recognized = entry.toRecognizedPreview()
        if (!recognized.recognized) {
            // 回退现状：restoredSql + 未解析警告（参数缺失/数量不匹配/纯静态 SQL）
            val base = withTerminator(entry.restoredSql)
            val sql = if (state.logFormatSql) SqlFormatter.format(base) else base
            SqlPreviewService.showResult(
                project,
                PreviewResult(
                    sql = sql,
                    compactSql = entry.restoredSql,
                    unresolvedParams = emptyList(),
                    paramTable = null,
                    wrapperType = null,
                    tableName = null,
                    warning = if ('?' in entry.restoredSql) {
                        MpSqlVisualizerBundle.message("log.preview.unresolvedWarning")
                    } else {
                        null
                    },
                ),
            )
            return
        }
        // 识别成功：rawSql 回填参数行（param1..N + 日志值预填 + 类型引号提示），全识别无警告
        val base = withTerminator(recognized.sql)
        val sql = if (state.logFormatSql) SqlFormatter.format(base) else base
        SqlPreviewService.showResult(
            project,
            PreviewResult(
                sql = sql,
                compactSql = withTerminator(recognized.compactSql),
                unresolvedParams = recognized.unresolvedParams,
                paramTable = null,
                wrapperType = null,
                tableName = null,
                warning = null,
                paramHints = recognized.paramHints,
                initialParamValues = recognized.initialValues,
            ),
        )
    }

    private fun showContextMenu(e: MouseEvent) {
        val index = list.locationToIndex(e.point)
        if (index >= 0) list.selectedIndex = index
        val entry = list.selectedValue ?: return
        val menu = JPopupMenu()
        menu.add(
            JMenuItem(MpSqlVisualizerBundle.message("log.panel.copy")).apply {
                addActionListener {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(withTerminator(entry.restoredSql)), null)
                }
            },
        )
        menu.add(
            JMenuItem(MpSqlVisualizerBundle.message("log.panel.delete")).apply {
                addActionListener { manager.entries.removeEntry(entry) }
            },
        )
        // T-M37 P1：发送到 SQL → Java 转换（第三 tab）——还原 SQL 写入输入区并切换选中该 tab
        menu.add(
            JMenuItem(MpSqlVisualizerBundle.message("log.panel.sendToReverse")).apply {
                addActionListener {
                    sqlToJavaPanel.setInputSql(entry.restoredSql)
                    tabs.selectedComponent = sqlToJavaPanel
                }
            },
        )
        // T-M38：发送到日志转 SQL（第二 tab）——条目渲染回日志文本并立即转换
        menu.add(
            JMenuItem(MpSqlVisualizerBundle.message("logToSql.sendTo")).apply {
                addActionListener {
                    logToSqlPanel.loadFromEntry(entry)
                    tabs.selectedComponent = logToSqlPanel
                }
            },
        )
        menu.show(list, e.x, e.y)
    }

    /** 面板销毁：释放日志转 SQL 与 SQL→Java 面板编辑器（manager 生命周期由项目 @Service 管理）。 */
    override fun dispose() {
        logToSqlPanel.dispose()
        sqlToJavaPanel.dispose()
    }

    companion object {
        private val LOG = Logger.getInstance(SqlLogPanel::class.java)
        private const val TOOLBAR_ID = "cn.song.mybatisplus.visualizer.log.toolbar"
        private const val DEFAULT_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss"
    }
}
