package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.execute.DocWriteContext
import cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator
import cn.song.mybatisplus.visualizer.reverse.JavaCommentStripper
import cn.song.mybatisplus.visualizer.reverse.ReverseJoin
import cn.song.mybatisplus.visualizer.reverse.ReverseWrapperFormat
import cn.song.mybatisplus.visualizer.reverse.SqlReverseException
import cn.song.mybatisplus.visualizer.reverse.SqlReverseResult
import cn.song.mybatisplus.visualizer.reverse.SqlToJavaConverter
import cn.song.mybatisplus.visualizer.ui.EntitySelectionDialog
import cn.song.mybatisplus.visualizer.ui.JavaCodeFormatter
import cn.song.mybatisplus.visualizer.ui.SqlInputEditor
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.ide.util.gotoByName.ChooseByNameModel
import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.ide.util.gotoByName.ChooseByNamePopupComponent
import com.intellij.ide.util.gotoByName.GotoClassModel2
import com.intellij.lang.java.JavaLanguage
import com.intellij.lang.java.JavaSyntaxHighlighterFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiModifier
import com.intellij.ui.components.JBLabel
import com.intellij.ui.JBColor
import cn.song.mybatisplus.visualizer.util.IconScaler
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * 「SQL → Java」tab 面板（T-M36.4）：粘贴 SQL → 格式化/自动补全 → 转换 →
 * 可编辑 Java 高亮结果区。
 *
 * 布局（Splitter 上下分栏 + IDEA 原生 ActionToolbar）：
 *  - 上：SQL 输入区（[SqlInputEditor]：SQL 高亮 + 打字自动弹补全）；工具栏：转换 / 格式化 / 清空；
 *  - 下：Java 结果区（可编辑 + Java 词法高亮）；工具栏：复制代码 / 清空；
 *  - 底部：状态行（错误红字 / 提示灰字）。
 *
 * 转换流程复用 [SqlToJavaConverter] 共享编排（与编辑器 Action 同一链路）；实体查找
 * 后台 ReadAction（2025.3+ 慢操作守卫）；多候选弹 [SqlSelectionDialog]。
 */
class SqlToJavaPanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {

    private val inputEditor = SqlInputEditor(project, "")
    private val resultEditor: Editor = createJavaEditor()
    private val statusLabel = JBLabel(" ").apply {
        border = JBUI.Borders.empty(2, 6, 2, 6)
    }

    /** 输入区空文档灰字提示（用户交互引导；有内容自动隐藏）。 */
    private val inputHintLabel = JBLabel(MpSqlVisualizerBundle.message("reverse.panel.hint.input")).apply {
        foreground = UIUtil.getInactiveTextColor()
        border = JBUI.Borders.empty(2, 6, 2, 6)
    }

    /** 结果区空文档灰字提示。 */
    private val resultHintLabel = JBLabel(MpSqlVisualizerBundle.message("reverse.panel.hint.result")).apply {
        foreground = UIUtil.getInactiveTextColor()
        border = JBUI.Borders.empty(2, 6, 2, 6)
    }

    /** T-M36.6：生成形态下拉（与设置页 reverseWrapperFormat 双向同步：写穿持久化 + tab 重选刷新）。 */
    private val formatCombo = JComboBox<FormatOption>().apply {
        model = DefaultComboBoxModel(formatOptions())
        // T-M39 五次增量：长选项截断时完整文案可见——弹出列表项（JList cell renderer tooltip）
        // 与选中态（combo 自身 tooltip）都带完整 label；选中变化时同步 combo tooltip
        renderer = object : ListCellRenderer<FormatOption> {
            private val delegate = DefaultListCellRenderer()
            override fun getListCellRendererComponent(
                list: JList<out FormatOption>?,
                value: FormatOption?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val c = delegate.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                (c as? JComponent)?.toolTipText = value?.label
                return c
            }
        }
        addActionListener {
            // 选中项截断时 hover 显示完整文案（先于写穿/重生成，suppress 态同样生效）
            toolTipText = (selectedItem as? FormatOption)?.label
            if (suppressFormatSync) return@addActionListener
            val value = (selectedItem as? FormatOption)?.persisted
            if (value != null && value != settingsState().reverseWrapperFormat) {
                // 写穿式持久化（对齐语言下拉 M19.1 实证模式）——面板是设置值的快捷入口
                settingsState().reverseWrapperFormat = value
                regenerateWithNewFormat()
            }
        }
        // 初始选中（model 首项，无 selection 事件）也带完整文案 tooltip
        toolTipText = (selectedItem as? FormatOption)?.label
    }

    /** T-M36.7：调用类选择（Service/Mapper 类名，非必选——留空生成纯表达式）。 */
    private val callerField = ClassPickerField(
        project,
        MpSqlVisualizerBundle.message("reverse.panel.callerHint"),
    ) {
        if (cacheResult != null) regenerateWithNewFormat()
    }

    /** T-M36.7：返回 VO 类名选择（非必选，仅 SELECT 生效——MPJ selectVoList/listVo）。 */
    private val voField = ClassPickerField(
        project,
        MpSqlVisualizerBundle.message("reverse.panel.voHint"),
    ) {
        if (cacheResult != null) regenerateWithNewFormat()
    }

    /** 设置 → 下拉同步期间的抑制标志（refreshFormatFromSettings 不触发重生成）。 */
    private var suppressFormatSync = false

    /** T-M36.6：上次成功解析缓存（输入文本未变时切换形态只重生成，不重新 parse/查实体）。 */
    private var cacheText: String? = null
    private var cacheResult: SqlReverseResult? = null
    private var cacheCandidates: List<SqlToJavaConverter.EntityCandidate>? = null
    private var cacheSubEntities: Map<String, JavaCodeGenerator.EntityTarget> = emptyMap()

    /** 调用类 MPJ 能力（后台 ReadAction 继承链实证；QUERY 形态/未解析默认 false）。 */
    private var callerMpj = false

    /** T-M44：调用类继承链方法名集合（后台 ReadAction 反查；生成器按真实 API 选择调用方法）。 */
    private var callerMethods: Set<String> = emptySet()

    init {
        // 注册到 Disposer 树：面板 dispose 时级联释放 SqlInputEditor 的 DocumentListener
        Disposer.register(this, inputEditor)

        // 上：输入区（工具栏 + 灰字提示 + 编辑器——空文档显示引导文案）
        val inputPanel = JPanel(BorderLayout())
        inputPanel.add(
            JPanel(BorderLayout()).apply {
                add(buildInputToolbar(), BorderLayout.NORTH)
                add(inputHintLabel, BorderLayout.SOUTH)
            },
            BorderLayout.NORTH,
        )
        inputPanel.add(inputEditor.component, BorderLayout.CENTER)

        // 下：结果区（工具栏 + 灰字提示 + Java 编辑器）
        val resultPanel = JPanel(BorderLayout())
        resultPanel.add(
            JPanel(BorderLayout()).apply {
                add(buildResultToolbar(), BorderLayout.NORTH)
                add(resultHintLabel, BorderLayout.SOUTH)
            },
            BorderLayout.NORTH,
        )
        resultPanel.add(resultEditor.component, BorderLayout.CENTER)

        val splitter = Splitter(true, 0.5f).apply {
            firstComponent = inputPanel
            secondComponent = resultPanel
            isShowDividerControls = false
        }
        add(splitter, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
        // 空态提示显隐跟随文档内容
        inputEditor.editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(e: DocumentEvent) {
                inputHintLabel.isVisible = inputEditor.text.isBlank()
            }
        }, this)
        resultEditor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(e: DocumentEvent) {
                resultHintLabel.isVisible = resultEditor.document.text.isBlank()
            }
        }, this)
        refreshFormatFromSettings()
    }

    // ------------------------------------------------------------------
    // 工具栏
    // ------------------------------------------------------------------

    private fun buildInputToolbar(): JPanel {
        val group = DefaultActionGroup(
            ConvertAction(),
            FormatAction(),
            ClearInputAction(),
        )
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID_INPUT, group, true)
            .apply { targetComponent = this@SqlToJavaPanel }
        // T-M39 增量：选项（形态下拉/调用类/VO）独立成行——原 BorderLayout.EAST + FlowLayout
        // 三控件挤一行，窄侧边栏下形态下拉被挤压截断（用户截图只见「→ 字符串列名）」）
        val bar = JPanel(BorderLayout())
        bar.add(toolbar.component, BorderLayout.NORTH)
        bar.add(buildOptionsRow(), BorderLayout.SOUTH)
        return bar
    }

    /**
     * 选项行：形态下拉独占整行（高频、选项文本长须完整显示）+ 调用类/VO 各半行
     * （低频可选，占位提示截断无妨）——GridBag 拉伸，窄窗口各自收缩互不挤压。
     */
    private fun buildOptionsRow(): JPanel = JPanel(GridBagLayout()).apply {
        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = JBUI.insets(0, 4, 4, 4)
        }
        c.gridx = 0
        c.gridwidth = 2
        add(formatCombo, c)
        c.gridy = 1
        c.gridwidth = 1
        c.weightx = 1.0
        add(callerField, c)
        c.gridx = 1
        add(voField, c)
    }

    private fun buildResultToolbar(): JPanel {
        val group = DefaultActionGroup(
            CopyAction(),
            FormatCodeAction(),
            ClearResultAction(),
        )
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID_RESULT, group, true)
            .apply { targetComponent = this@SqlToJavaPanel }
        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
        }
    }

    /** 转换：parse（EDT 纯函数）→ 实体查找（后台 ReadAction）→ 分发（设置形态）。 */
    private inner class ConvertAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Execute
            e.presentation.text = MpSqlVisualizerBundle.message("reverse.panel.convert")
        }

        override fun actionPerformed(e: AnActionEvent) {
            convert()
        }
    }

    /** 格式化输入区 SQL（SqlFormatter 幂等；程序性写入不触发补全）。 */
    private inner class FormatAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.ReformatCode
            e.presentation.text = MpSqlVisualizerBundle.message("reverse.panel.format")
        }

        override fun actionPerformed(e: AnActionEvent) {
            val text = inputEditor.text
            if (text.isBlank()) return
            inputEditor.setSql(cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter.format(text))
        }
    }

    private inner class ClearInputAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.GC
            e.presentation.text = MpSqlVisualizerBundle.message("reverse.panel.clearInput")
        }

        override fun actionPerformed(e: AnActionEvent) {
            inputEditor.setSql("")
        }
    }

    private inner class CopyAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Copy
            e.presentation.text = MpSqlVisualizerBundle.message("reverse.panel.copy")
            e.presentation.isEnabled = resultEditor.document.text.isNotBlank()
        }

        override fun actionPerformed(e: AnActionEvent) {
            // T-M45：只复制 Java 代码——剥离说明注释（忽略「日志」），粘贴即用
            UiTextClipboard.copy(copyText())
            status(MpSqlVisualizerBundle.message("reverse.copy.done"), error = false)
        }
    }

    /** T-M45：直接格式化结果区 Java 代码（对齐输入区「格式化 SQL」交互——格式化当前框内内容）。 */
    private inner class FormatCodeAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.ReformatCode
            e.presentation.text = MpSqlVisualizerBundle.message("reverse.panel.formatCode")
            e.presentation.isEnabled = resultEditor.document.text.isNotBlank()
        }

        override fun actionPerformed(e: AnActionEvent) {
            formatResult()
        }
    }

    private inner class ClearResultAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.GC
            e.presentation.text = MpSqlVisualizerBundle.message("reverse.panel.clearResult")
        }

        override fun actionPerformed(e: AnActionEvent) {
            setResultText("")
            status(" ", error = false)
        }
    }

    // ------------------------------------------------------------------
    // 类选择字段（T-M39 增量：用户要求直接选择项目 Java 类而非手动输入）
    // ------------------------------------------------------------------

    /**
     * 类选择字段：点击弹项目类选择器（Goto Class 弹窗，[GotoClassModel2]）。
     *
     * T-M39 三次增量：**「类图标 + 类名」的 IDEA 原生观感**（用户反馈纯文本样式不原生）——
     * 选中后左侧显示 [PsiClass.getIcon]（类/接口/枚举各有原生图标，如 AllIcons.Nodes.Class），
     * 右侧 ✕ 清除；空值显示占位提示（灰字）+ … 浏览；自绘输入框样式
     * （圆角底色 + 主题边框色，对齐 IDEA TextField——只读无焦点态）。
     */
    internal inner class ClassPickerField(
        private val projectRef: Project,
        private val placeholder: String,
        private val onChanged: () -> Unit = {},
    ) : JPanel(BorderLayout()) {

        private var currentText = ""
        private var pickedIcon: Icon? = null

        private val iconLabel = JBLabel()
        private val textLabel = JBLabel(" ")
        // T-M39 增量修复：关闭/浏览按钮改原生紧凑图标按钮——IDEA 图标按钮类
        // （JBIconButton/SmallIconButton/InlineIconButton）在 IPGP 发行版全部被裁剪（编译实证），
        // 用 JButton + IDEA 原生图标 + 12×12 固定尺寸（IconUtil.scale 缩放——16px 偏大实证反馈）
        // + FlowLayout 宿主防 BorderLayout 拉伸
        private val actionButton = JButton(PICKER_ELLIPSIS_ICON).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            margin = JBUI.emptyInsets()
            border = JBUI.Borders.empty()
            iconTextGap = 0
            preferredSize = Dimension(12, 12)
            minimumSize = Dimension(12, 12)
            maximumSize = Dimension(12, 12)
            toolTipText = MpSqlVisualizerBundle.message("reverse.panel.pickClass")
            addActionListener {
                if (currentText.isEmpty()) pickClass() else clearPicked()
            }
        }

        /** 按钮宿主：FlowLayout 垂直居中（BorderLayout.EAST 会拉伸子组件高度，需宿主隔离）；右侧 4px 边距
         *  防 ✕/… 按钮贴边（对齐 IDEA SearchTextField 的 JTextField.Search.Gap 间距）。 */
        private val actionButtonHost = JPanel(FlowLayout(FlowLayout.CENTER, 0, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 0, JBUI.scale(4))
            add(actionButton)
        }

        /** T-M44 增量：模型实例提为字段（弹窗与测试缝共用；元素级方法的 PSI 读取在委托内包 ReadAction）。 */
        internal val callerModel: ChooseByNameModel = CallerClassByNameModel()

        /** T-CallerPicker：模型实例的 concrete 引用（供 setCurrentEditorClass / currentEditorClass 访问）。 */
        internal val callerModelConcrete: CallerClassByNameModel get() = callerModel as CallerClassByNameModel

        init {
            isOpaque = false
            preferredSize = Dimension(1, JBUI.scale(24))
            minimumSize = Dimension(1, JBUI.scale(24))
            iconLabel.border = JBUI.Borders.empty(0, 5, 0, 0)
            textLabel.border = JBUI.Borders.empty(0, 4, 0, 0)
            add(iconLabel, BorderLayout.WEST)
            add(textLabel, BorderLayout.CENTER)
            add(actionButtonHost, BorderLayout.EAST)
            // 点击字段任意位置弹选择器：Swing 鼠标事件不冒泡（子组件 JLabel 铺满后
            // 父面板监听收不到——实证反馈「只能点三点」）——监听挂到每个子组件 + 手型光标
            attachClick(iconLabel)
            attachClick(textLabel)
            attachClick(actionButtonHost)
            refresh()
        }

        /** 输入框观感：圆角底色 + 主题边框色（对齐 IDEA TextField；只读无焦点态）。 */
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = UIUtil.getTextFieldBackground()
                g2.fillRoundRect(0, 0, width - 1, height - 1, PICKER_CORNER, PICKER_CORNER)
                // T-DarkTheme：命名色优先 + JBColor.border() 兜底（亮/暗主题自适应，与 IDEA TextField 边框一致）
                g2.color = JBColor.namedColor("Component.borderColor", JBColor.border())
                g2.drawRoundRect(0, 0, width - 1, height - 1, PICKER_CORNER, PICKER_CORNER)
            } finally {
                g2.dispose()
            }
        }

        /** 对外文本（生成器读取类名；测试 setText 兼容——直接赋值不触发变更回调）。 */
        var text: String
            get() = currentText
            set(value) {
                currentText = value
                refresh()
            }

        /**
         * 弹 Goto Class 选择器（IDE 标准类搜索弹窗，候选 = 项目内全部 Java 类）。
         *
         * T-M42：模型用 [CallerClassByNameModel] 委托包装 [GotoClassModel2]——候选前插
         * `this`（service 内调用）与 `baseMapper`（ServiceImpl 的 baseMapper 字段）两个固定项。
         */
        private fun pickClass() {
            // T-CallerPicker：PSI 读取须在 ReadAction 内（EDT 同步，语义不变）——262 断言硬抛实证
            callerModelConcrete.currentEditorClass = ReadAction.compute<PsiClass?, Throwable> {
                detectCurrentEditorClass()
            }
            // T-M44 增量：模型提为字段（测试缝 callerModelForTest 复用同一实例）
            val popup = ChooseByNamePopup.createPopup(projectRef, callerModel, null)
            popup.invoke(object : ChooseByNamePopupComponent.Callback() {
                override fun elementChosen(element: Any?) {
                    when (element) {
                        // T-M42：特殊调用值（字符串元素）——this=当前 service 类内、baseMapper=ServiceImpl
                        // 字段；tooltip = 用途说明 + T-CallerPicker 当前编辑器类上下文（ReadAction 内提取）
                        is String -> setPicked(element, buildSpecialTooltip(element), AllIcons.Nodes.Field)
                        is PsiClass -> pickPsiClassForTest(element)
                    }
                }

                override fun onClose() {}
            }, ModalityState.current(), false)
        }

        /**
         * T-CallerPicker：获取当前编辑器打开的 Java/Kotlin 类的 PsiClass（如有）。
         * 多编辑器时取活动编辑器；非 Java/Kotlin 文件返回 null。
         * 调用方 [pickClass] 保证在 ReadAction 内执行（PSI 访问），勿裸调。
         */
        private fun detectCurrentEditorClass(): PsiClass? {
            val editor = FileEditorManager.getInstance(projectRef).selectedTextEditor ?: return null
            val psiFile = PsiDocumentManager.getInstance(projectRef).getPsiFile(editor.document) ?: return null
            if (psiFile !is PsiClassOwner) return null
            // 取主类（文件名对应的类）
            return psiFile.classes.firstOrNull { cls ->
                cls.name?.let { psiFile.name.startsWith(it) } == true
            } ?: psiFile.classes.firstOrNull()
        }

        /**
         * T-CallerPicker：构造 this/baseMapper 的 tooltip 文本，含当前编辑器类信息。
         * 兼顾 PM（Plain MyBatis-Plus）与 MPJ（MyBatis-Plus-Join）两种依赖。
         *
         * 当前编辑器类上下文读取（name/superTypes/泛型参数）包 ReadAction——elementChosen
         * 回调在 EDT 裸读 PSI 会触发 262 断言硬抛；无当前类或未知 special 时同步回落。
         */
        private fun buildSpecialTooltip(special: String): String {
            val base = SPECIAL_TOOLTIPS[special] ?: return special
            val editorCls = callerModelConcrete.currentEditorClass ?: return base
            val context = ReadAction.compute<String?, Throwable> {
                val clsName = editorCls.name ?: return@compute null
                when (special) {
                    CALLER_THIS -> "（当前：$clsName）"
                    CALLER_BASE_MAPPER -> buildBaseMapperContext(editorCls, clsName)
                    else -> ""
                }
            } ?: return base
            return "$base\n$context"
        }

        /**
         * T-CallerPicker：baseMapper 选项的上下文提取——兼顾 PM 与 MPJ 两种依赖。
         * 优先匹配 ServiceImpl<A, B>（取 B=实体），回落 BaseMapper<A>（取 A=实体）。
         * 调用方 [buildSpecialTooltip] 保证在 ReadAction 内执行（superTypes 为 PSI 访问），勿裸调。
         */
        private fun buildBaseMapperContext(currentCls: PsiClass, clsName: String): String {
            val superTypes = currentCls.superTypes
            // 优先：ServiceImpl<X, Entity> / MPJBaseService<X, Entity> → 取第 2 个泛型参数（实体）
            superTypes.firstOrNull { type ->
                type.className?.let {
                    it.contains("ServiceImpl") || it.contains("MPJBaseService") ||
                        it.contains("JoinService")
                } == true
            }?.let { type ->
                val params = type.parameters
                if (params.size >= 2) {
                    return "（当前：$clsName，表：${params[1].presentableText}）"
                }
            }
            // 回落：BaseMapper<Entity> / MPJBaseMapper<Entity> → 取第 1 个泛型参数（实体）
            superTypes.firstOrNull { type ->
                type.className?.let {
                    it.contains("BaseMapper") || it.contains("MPJBaseMapper")
                } == true
            }?.let { type ->
                val params = type.parameters
                if (params.isNotEmpty()) {
                    return "（当前：$clsName，表：${params[0].presentableText}）"
                }
            }
            // 无泛型信息 → 仅展示类名
            return "（当前：$clsName）"
        }

        /**
         * T-M44 审计兜底（用户实测堆栈：elementChosen → getQualifiedName → stub 树遍历断言硬抛）：
         * 选择器回调的 PSI 读取（name/qualifiedName/getIcon）须在 ReadAction 内（EDT 上同步执行，
         * 语义不变）。internal 供测试缝直接调用（Light 可建 PsiClass 验证选取行为）。
         *
         * T-CallerPicker：入口的调用类校验（VO/DTO/Utils/Controller 等不合法类拒绝 + 友好提示）
         * 原在校验先行、ReadAction 之前直读 PSI——与 name/qualifiedName/getIcon 合并进**单次**
         * ReadAction（262 断言硬抛：isInterface/hasModifierProperty 同属 PSI 访问）。
         * 返回值：null = 类名缺失；否则 Pair<拒绝原因?, 选取数据 Triple(name, qualifiedName, icon)>。
         */
        @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
        internal fun pickPsiClassForTest(cls: PsiClass) {
            val outcome = ReadAction.compute<Pair<String?, Triple<String, String?, Icon?>?>?, Throwable> {
                val name = cls.name ?: return@compute null
                val rejectReason = validateCallerClass(cls)
                if (rejectReason != null) Pair(rejectReason, null)
                else Pair(null, Triple(name, cls.qualifiedName, cls.getIcon(0)))
            } ?: return
            outcome.first?.let { reason ->
                showCallerRejectMessage(reason, outcome.second?.first ?: "")
                return
            }
            val picked = outcome.second ?: return
            setPicked(picked.first, picked.second, picked.third)
        }

        /**
         * T-CallerPicker：校验选中的类是否为合法的 SQL→Java 调用类。
         * 合法 = Mapper/Service/ServiceImpl/Impl（白名单后缀）或继承 MPJ 接口的具体类。
         * 不合法 = VO/DTO/BO/PO/Utils/Controller/Config 等无 CRUD 能力的类。
         *
         * @return null = 合法；非 null = 拒绝原因 bundle key
         */
        private fun validateCallerClass(cls: PsiClass): String? {
            val name = cls.name ?: return "reverse.panel.callerReject.unknown"
            // 1. 快速白名单：类名以 Mapper/Service/ServiceImpl/Impl 结尾 → 放行
            if (name.endsWith("Mapper") || name.endsWith("Service") ||
                name.endsWith("ServiceImpl") || name.endsWith("Impl")
            ) return null
            // 2. 黑名单：VO/DTO/BO/PO/Utils/Util/Controller/Config/Interceptor 等 → 拒绝
            if (name.endsWith("VO") || name.endsWith("DTO") || name.endsWith("BO") ||
                name.endsWith("PO") || name.endsWith("Utils") || name.endsWith("Util") ||
                name.endsWith("Controller") || name.endsWith("Config") ||
                name.endsWith("Interceptor") || name.endsWith("Filter") ||
                name.endsWith("Handler") || name.endsWith("Aspect")
            ) return "reverse.panel.callerReject.voDto"
            // 3. 接口/抽象类 → 拒绝
            if (cls.isInterface || cls.hasModifierProperty(PsiModifier.ABSTRACT)) {
                return "reverse.panel.callerReject.interface"
            }
            // 4. 其他具体类 → 放行（避免误杀自定义命名）
            return null
        }

        /** T-CallerPicker：调用类选择被拒绝的友好提示（状态行显示）。 */
        private fun showCallerRejectMessage(reasonKey: String, className: String) {
            status(MpSqlVisualizerBundle.message(reasonKey, className), error = true)
        }

        /**
         * Goto Class 模型委托包装（T-M42）：`getNames` 前插 `this`/`baseMapper` 两个固定名，
         * `getElementsByName` 对二者直出字符串元素（元素即字符串本身），其余方法一行委托
         * [GotoClassModel2]。接口签名按 243 平台 [ChooseByNameModel] 实证（javap 2024.3.7.1）。
         *
         * 必须显式覆写的三个方法（委托实现按 NavigationItem/PsiClass 强转，对字符串元素会抛
         * ClassCastException/NPE，逐行实证）：
         *  - [getElementName]/[getFullName]：字符串元素直出字符串本身（过滤/排序/提示用）；
         *  - [getListCellRenderer]：字符串元素走纯文本 + 字段图标渲染（委托渲染器对非
         *    NavigationItem 值在 showIcon=true 时 ModuleRendererFactory 返回 null → NPE）。
         */
        @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
        inner class CallerClassByNameModel : ChooseByNameModel {
            private val delegate = GotoClassModel2(projectRef)
            private val delegateRenderer = delegate.getListCellRenderer() as ListCellRenderer<Any>

            /** T-CallerPicker：当前编辑器打开的类（选择器打开前检测一次，供 this/baseMapper tooltip 使用）。 */
            var currentEditorClass: PsiClass? = null

            /** 特殊项渲染：纯文本 + 字段图标 + 用途说明 tooltip。 */
            private val specialRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component {
                    val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JComponent
                    (c as? JLabel)?.icon = AllIcons.Nodes.Field
                    if (value is String) c.toolTipText = SPECIAL_TOOLTIPS[value]
                    return c
                }
            }

            override fun getNames(checkBoxState: Boolean): Array<String> =
                arrayOf(CALLER_THIS, CALLER_BASE_MAPPER) + delegate.getNames(checkBoxState)

            override fun getElementsByName(name: String, checkBoxState: Boolean, pattern: String): Array<Any> =
                when (name) {
                    CALLER_THIS, CALLER_BASE_MAPPER -> arrayOf<Any>(name)
                    else -> delegate.getElementsByName(name, checkBoxState, pattern)
                }

            /** 特殊项元素名 = 字符串本身（委托实现按 NavigationItem 强转，字符串会 ClassCastException）；
             *  类元素委托——PSI 读取须在 ReadAction 内（平台在 EDT 关闭弹窗时调用 getFullName/getElementName，
             *  GotoClassModel2 → DefaultClassNavigationContributor → getQualifiedName/getName 触发 stub 断言，
             *  用户实测堆栈 SqlToJavaPanel.kt CallerClassByNameModel.getFullName）。 */
            override fun getElementName(element: Any): String =
                (element as? String) ?: ReadAction.compute<String?, Throwable> {
                    delegate.getElementName(element)
                } ?: element.toString()

            /** 特殊项全限定名 = 字符串本身（委托实现按 NavigationItem 强转）；类元素委托（同上 ReadAction）。 */
            override fun getFullName(element: Any): String? =
                (element as? String) ?: ReadAction.compute<String?, Throwable> { delegate.getFullName(element) }

            /** 列表渲染：特殊项走纯文本 + 字段图标；类元素委托 GotoClassModel2 渲染器。 */
            override fun getListCellRenderer(): ListCellRenderer<*> = object : ListCellRenderer<Any> {
                override fun getListCellRendererComponent(
                    list: JList<out Any>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component = if (value is String) {
                    specialRenderer.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                } else {
                    delegateRenderer.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                }
            }

            override fun getPromptText(): String = delegate.getPromptText() ?: ""
            override fun getCheckBoxName(): String? = delegate.getCheckBoxName()
            // getCheckBoxMnemonic() 已废弃（Goto Class 弹窗移除 checkbox UI），删除覆写使用接口默认实现
            override fun getNotInMessage(): String = delegate.getNotInMessage() ?: ""
            override fun getNotFoundMessage(): String = delegate.getNotFoundMessage() ?: ""
            override fun loadInitialCheckBoxState(): Boolean = delegate.loadInitialCheckBoxState()
            override fun saveInitialCheckBoxState(state: Boolean) = delegate.saveInitialCheckBoxState(state)
            override fun getSeparators(): Array<String> = delegate.getSeparators()
            override fun getHelpId(): String = delegate.getHelpId() ?: ""
            override fun willOpenEditor(): Boolean = delegate.willOpenEditor()
            override fun useMiddleMatching(): Boolean = delegate.useMiddleMatching()
        }

        /** 选中结果写入（类图标 + 简单类名，tooltip = 全限定名）+ 变更回调。 */
        private fun setPicked(simpleName: String, qualifiedName: String?, icon: Icon?) {
            currentText = simpleName
            toolTipText = qualifiedName
            pickedIcon = icon
            refresh()
            onChanged()
        }

        /** 子组件点击统一入口：单次点击弹选择器 + 手型光标（按钮自身消费事件，不重复触发）。 */
        private fun attachClick(component: JComponent) {
            component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            component.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) pickClass()
                }
            })
        }

        /** 清除选中值（✕ 按钮触发）。 */
        private fun clearPicked() {
            currentText = ""
            toolTipText = null
            pickedIcon = null
            refresh()
            onChanged()
        }

        /** 按状态刷新显示：空值 → 占位灰字 + … 浏览；有值 → 类图标 + 类名 + ✕ 清除。 */
        private fun refresh() {
            if (currentText.isEmpty()) {
                iconLabel.icon = null
                textLabel.text = placeholder
                textLabel.foreground = UIUtil.getInactiveTextColor()
                // 占位文案被截断时 hover 显示完整提示（选中后字段级 tooltip 为全限定名，不遮蔽）
                textLabel.toolTipText = placeholder
                actionButton.icon = PICKER_ELLIPSIS_ICON
                actionButton.toolTipText = MpSqlVisualizerBundle.message("reverse.panel.pickClass")
            } else {
                iconLabel.icon = pickedIcon
                textLabel.text = currentText
                textLabel.foreground = UIUtil.getLabelForeground()
                textLabel.toolTipText = null
                actionButton.icon = PICKER_CANCEL_ICON
                actionButton.toolTipText = MpSqlVisualizerBundle.message("reverse.panel.clearClass")
            }
        }

        /** 测试访问器：可点击子组件清单（iconLabel/textLabel/按钮宿主）。 */
        internal fun clickTargetsForTest(): List<JComponent> = listOf(iconLabel, textLabel, actionButtonHost)

        /** 测试访问器：清除/浏览按钮（断言紧凑尺寸）。 */
        internal fun actionButtonForTest(): JButton = actionButton

        /** 测试访问器：模拟选择器选中（类图标 + 简单类名 + 全限定名 + 变更回调）。 */
        internal fun pickForTest(simpleName: String, qualifiedName: String?, icon: Icon? = AllIcons.Nodes.Class) =
            setPicked(simpleName, qualifiedName, icon)

        /** T-CallerPicker 测试缝：模拟选中 this/baseMapper（走 buildSpecialTooltip 完整路径，含当前编辑器类上下文）。 */
        internal fun pickSpecialForTest(special: String) =
            setPicked(special, buildSpecialTooltip(special), AllIcons.Nodes.Field)

        /** 测试访问器：模拟 ✕ 清除。 */
        internal fun clearForTest() = clearPicked()

        /** 测试访问器：当前显示的类图标（null = 空值占位态）。 */
        internal fun iconForTest(): Icon? = iconLabel.icon

        /** 测试访问器：文本标签 tooltip（占位态 = 完整占位文案；选中态 = null 不遮蔽字段级全限定名）。 */
        internal fun textTooltipForTest(): String? = textLabel.toolTipText
    }

    // ------------------------------------------------------------------
    // 转换流程
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    private fun convert() {
        val text = inputEditor.text
        if (text.isBlank()) {
            status(MpSqlVisualizerBundle.message("reverse.panel.empty"), error = true)
            return
        }
        // T-BatchSql：顶层分号拆分 → 多语句走批量路径
        val statements = SqlToJavaConverter.splitStatements(text)
        if (statements.size > 1) {
            convertBatch(statements)
            return
        }
        // 单条 SQL：尝试快速 parse 提供精确错误提示（保持既有单条错误行号定位体验）
        val result = try {
            SqlToJavaConverter.parse(text)
        } catch (ex: SqlReverseException) {
            status(
                MpSqlVisualizerBundle.message(ex.key, ex.detail) +
                    "（第 ${SqlToJavaConverter.errorLine(text, ex.offset)} 行）",
                error = true,
            )
            return
        }
        // T-M36.6：缓存成功解析（输入文本未变时切换形态复用）
        cacheText = text
        cacheResult = result
        val format = reverseFormat()
        if (format == ReverseWrapperFormat.QUERY) {
            cacheCandidates = null
            render(result, emptyList(), format)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val payload = try {
                ReadAction.compute<Triple<List<SqlToJavaConverter.EntityCandidate>, Map<String, List<SqlToJavaConverter.EntityCandidate>>, Pair<Boolean, Set<String>>>, Throwable> {
                    val candidates = SqlToJavaConverter.findCandidates(project, result.tableName)
                    // T-M37 P1：副表**全部**候选（alias → 列表；零/无别名 → 空），EDT 层按 joins 顺序分发
                    val subCandidates = SqlToJavaConverter.findSubEntityCandidates(project, result.joins)
                    // 调用类 MPJ 能力（继承链实证）——VO 查询/多表生成据此选合法方法
                    val callerMpj = SqlToJavaConverter.invocationHasMpj(project, callerField.text)
                    // T-M44：调用类真实方法集（继承链）——生成器按真实 API 选择调用方法（selectPage 缺失回落 selectJoinPage 族）
                    val callerMethods = SqlToJavaConverter.invocationMethods(project, callerField.text)
                    Triple(candidates, subCandidates, callerMpj to callerMethods)
                }
            } catch (t: Throwable) {
                Triple(emptyList<SqlToJavaConverter.EntityCandidate>(), emptyMap(), false to emptySet())
            }
            ApplicationManager.getApplication().invokeLater {
                cacheCandidates = payload.first
                callerMpj = payload.third.first
                callerMethods = payload.third.second
                val manualEnabled = manualEntitySelectionEnabled()
                // 手动选择实体弹窗分支（开关开启时）：
                //  - JOIN 多表 → 主表 + 副表一次多选（替代 T-M37 逐表弹窗）；
                //  - 主表 0 候选（无 JOIN）→ 单行主表手动挑选（自动推断兜底）。
                if (manualEnabled && result.joins.isNotEmpty()) {
                    // T-M49：唯一可推断（主表 1 候选 + 每个副表各 1 候选）→ 跳过弹窗直接渲染，
                    // 减少规范命名项目每次转换的确认打扰；有歧义（0/多候选）仍弹手动选择。
                    if (canAutoInferEntitySelection(payload.first, payload.second)) {
                        val subEntities = mutableMapOf<String, JavaCodeGenerator.EntityTarget>()
                        for ((alias, candidates) in payload.second) {
                            toSubEntity(candidates[0])?.let { subEntities[alias] = it }
                        }
                        cacheCandidates = payload.first
                        cacheSubEntities = subEntities
                        // T-TS-03：写体显式过闸——render 内的 setResultText 写结果区文档；本拍由 BGT 派发
                        DocWriteContext.runWriteSafe(project, "reverse render dispatch (auto infer)") {
                            render(result, payload.first, format, subEntities)
                        }
                    } else {
                        showEntitySelection(result, payload.first, payload.second, format)
                    }
                    return@invokeLater
                }
                if (manualEnabled && payload.first.isEmpty()) {
                    showEntitySelection(result, payload.first, payload.second, format)
                    return@invokeLater
                }
                // 现状：副表候选逐表处理（开关关闭时多候选不弹窗，取第一个）
                resolveSubEntities(payload.second, result.joins) { subEntities ->
                    cacheSubEntities = subEntities
                    // T-TS-03：同上一处——本回调可能由模态选择弹窗关闭后触发，写体统一过闸
                    DocWriteContext.runWriteSafe(project, "reverse render dispatch (sub entities)") {
                        render(result, payload.first, format, subEntities)
                    }
                }
            }
        }
    }

    /**
     * T-BatchSql：批量 SQL → Java 转换（多语句路径）。
     *
     * 流程：
     *  1. 批量 parse（EDT 纯函数，错误隔离）；
     *  2. 后台单次 ReadAction 查找全部语句的实体候选；
     *  3. EDT 批量生成 + 拼接为代码块写入结果区。
     *
     * 边界（P1）：
     *  - 不弹实体选择弹窗（交互复杂度过高）——统一走自动推断 + 字符串列名回落；
     *  - 不缓存（cacheResult/cacheText 仅单条路径使用）；
     *  - 超过 20 条时状态行提示「语句较多，实体查找可能耗时较长」。
     */
    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    private fun convertBatch(statements: List<String>) {
        // 1. 批量解析（EDT 纯函数；错误隔离——失败项 error 非 null）
        val parseResults = SqlToJavaConverter.parseBatch(statements)
        val successCount = parseResults.count { it.result != null }
        // 2. 后台单次 ReadAction 实体查找（所有语句共享）
        ApplicationManager.getApplication().executeOnPooledThread {
            val (mainCandidates, subCandidates) = ReadAction.compute<Pair<Map<Int, List<SqlToJavaConverter.EntityCandidate>>, Map<Int, Map<String, List<SqlToJavaConverter.EntityCandidate>>>>, Throwable> {
                SqlToJavaConverter.findCandidatesBatch(project, parseResults)
            }
            // 3. EDT 生成 + 拼接
            ApplicationManager.getApplication().invokeLater {
                val format = reverseFormat()
                val code = SqlToJavaConverter.generateBatchCode(
                    parseResults,
                    mainCandidates,
                    subCandidates,
                    format,
                    buildInvocation(),
                )
                // T-TS-03：写体显式过闸（[setResultText] 内同样过闸，这里是「哪次活动在写」的语义
                // 声明点：本拍由 BGT 派发，模态进度下 defaultModalityState 可能取到写不安全模态）
                DocWriteContext.runWriteSafe(project, "reverse batch result write") { setResultText(code) }
                val finalSuccess = parseResults.count { it.result != null }
                val failed = statements.size - finalSuccess
                val msg = if (failed > 0) {
                    MpSqlVisualizerBundle.message("reverse.batch.partial", finalSuccess, statements.size, failed)
                } else {
                    MpSqlVisualizerBundle.message("reverse.batch.success", finalSuccess, statements.size)
                }
                status(msg, error = failed > 0)
            }
        }
    }

    /** 手动选择实体类开关（测试 environment settings 未注册时默认开）。 */
    private fun manualEntitySelectionEnabled(): Boolean = runCatching {
        settingsState().reverseManualEntitySelection
    }.getOrDefault(true)

    /**
     * T-M49：JOIN 多表实体是否唯一可推断（主表 1 候选 + 每个副表各 1 候选）——是则可跳过手动选择弹窗，
     * 直接自动渲染。internal 供单元测试直接验证判定逻辑（不依赖面板实例/EDT）。
     */
    internal fun canAutoInferEntitySelection(
        mainCandidates: List<SqlToJavaConverter.EntityCandidate>,
        subCandidates: Map<String, List<SqlToJavaConverter.EntityCandidate>>,
    ): Boolean = mainCandidates.size == 1 && subCandidates.values.all { it.size == 1 }

    /**
     * 实体选择弹窗（自动推断兜底/多表一次多选）：
     * 弹窗前先写回落结果（取消后不空白），确认后按选中实体重新生成。
     */
    private fun showEntitySelection(
        result: SqlReverseResult,
        mainCandidates: List<SqlToJavaConverter.EntityCandidate>,
        subCandidates: Map<String, List<SqlToJavaConverter.EntityCandidate>>,
        format: ReverseWrapperFormat,
    ) {
        // 先回落显示（弹窗取消 → 保持字符串列名结果）
        setResultText(generateCode(result, null, format))
        status(joinStatus(result, MpSqlVisualizerBundle.message("reverse.panel.status.noEntity")), error = false)
        val rows = buildList {
            add(
                EntitySelectionDialog.TableRow(
                    key = EntitySelectionDialog.MAIN_KEY,
                    label = "${MpSqlVisualizerBundle.message("dialog.selectEntity.mainTable")} ${result.tableName}",
                    candidates = mainCandidates.map { it.psiClass },
                ),
            )
            result.joins.forEach { join ->
                join.alias?.let { alias ->
                    add(
                        EntitySelectionDialog.TableRow(
                            key = alias,
                            label = "${MpSqlVisualizerBundle.message("dialog.selectEntity.joinTable")} $alias ${join.tableName}",
                            candidates = subCandidates[alias].orEmpty().map { it.psiClass },
                        ),
                    )
                }
            }
        }
        EntitySelectionDialog(project, rows) { selections ->
            // T-Perf-08：实体确认回调解析移后台——candidateOf 的 EntityResolver.resolve 遍历继承链
            // 全部字段 + 每字段注解 resolve，大实体（50+ 字段）耗时线性，EDT 直解析构成确认停顿；
            // 后台 ReadAction 解析后回 EDT 回填缓存并渲染（对齐 convert 的既有异步模式）。
            ApplicationManager.getApplication().executeOnPooledThread {
                @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
                val resolved = ReadAction.compute<Triple<SqlToJavaConverter.EntityCandidate?, Map<String, JavaCodeGenerator.EntityTarget>, List<SqlToJavaConverter.EntityCandidate>>, Throwable> {
                    val main = selections[EntitySelectionDialog.MAIN_KEY]?.let { SqlToJavaConverter.candidateOf(it) }
                    val subEntities = mutableMapOf<String, JavaCodeGenerator.EntityTarget>()
                    result.joins.forEach { join ->
                        val alias = join.alias ?: return@forEach
                        selections[alias]?.let { psi ->
                            toSubEntity(SqlToJavaConverter.candidateOf(psi))?.let { subEntities[alias] = it }
                        }
                    }
                    Triple(main, subEntities as Map<String, JavaCodeGenerator.EntityTarget>, listOfNotNull(main))
                }
                // T-WSC-02：render → setResultText → WriteCommandAction 写结果区文档，属文档写入回调，
                // 不能用 any()（恒 write-unsafe，见 DocWriteContext KDoc）——改 nonModal。
                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed) return@invokeLater
                    cacheCandidates = resolved.third
                    cacheSubEntities = resolved.second
                    render(result, resolved.third, format, resolved.second)
                }, ModalityState.nonModal())
            }
        }.show()
    }

    /**
     * T-M37 P1：副表实体按 joins 顺序逐表解析（EDT 线程）——
     *  - 候选数 > 1：弹 [SqlSelectionDialog]（displayTexts 仿主表候选格式「类名（表 表名）」+ 文件名），
     *    选中后写入 subEntities 并在 onSelect 里继续处理后续副表（异步回调链）；
     *  - 候选数 == 1：直接并入；== 0 或该 join 无别名：跳过（该表列回落字符串列名，现状）；
     *  - 全部处理完 → [onComplete]（最后统一 render；副表选择结果同时进 cacheSubEntities，
     *    切换形态重生成时复用不再弹）。
     */
    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    private fun resolveSubEntities(
        subCandidates: Map<String, List<SqlToJavaConverter.EntityCandidate>>,
        joins: List<ReverseJoin>,
        onComplete: (Map<String, JavaCodeGenerator.EntityTarget>) -> Unit,
    ) {
        // 按 joins 顺序的别名列表（无别名的 join 无候选，直接跳过）
        val aliases = joins.mapNotNull { it.alias }
        val subEntities = mutableMapOf<String, JavaCodeGenerator.EntityTarget>()
        var index = 0
        fun next() {
            while (index < aliases.size) {
                val alias = aliases[index]
                index++
                val candidates = subCandidates[alias].orEmpty()
                when (candidates.size) {
                    0 -> continue // 未找到实体 → 该表列回落字符串列名
                    1 -> {
                        toSubEntity(candidates[0])?.let { subEntities[alias] = it }
                        continue
                    }
                    else -> {
                        // 开关关闭 → 不弹任何选择框，取第一个（纯自动推断）
                        if (!manualEntitySelectionEnabled()) {
                            toSubEntity(candidates[0])?.let { subEntities[alias] = it }
                            continue
                        }
                        // 多候选弹窗（EDT；displayTexts 仿主表候选用法）——选完继续处理后续副表
                        // T-M44 审计兜底：候选 PSI 读取（name/containingFile）须在 ReadAction 内（EDT 同步）
                        val displayTexts = ReadAction.compute<List<SqlSelectionDialog.Row>, Throwable> {
                            candidates.map { c ->
                                SqlSelectionDialog.Row.of(
                                    "${c.psiClass.name}（表 ${c.tableInfo.tableName}）",
                                    c.psiClass.containingFile?.name ?: "",
                                )
                            }
                        }
                        SqlSelectionDialog(project, candidates, displayTexts) { selected ->
                            toSubEntity(selected)?.let { subEntities[alias] = it }
                            next()
                        }.show()
                        return
                    }
                }
            }
            onComplete(subEntities)
        }
        next()
    }

    /** 副表候选 → 生成用实体目标（类名缺失则跳过该表——回落字符串列名）。 */
    private fun toSubEntity(
        candidate: SqlToJavaConverter.EntityCandidate,
    ): JavaCodeGenerator.EntityTarget? {
        val className = candidate.psiClass.name ?: return null
        return JavaCodeGenerator.EntityTarget(
            className = className,
            columnToField = candidate.tableInfo.columns.associate { it.columnName to it.fieldName },
        )
    }

    /**
     * 供 SQL 日志列表联动（T-M37 P1）：写入输入区，并清空结果区与状态行提示
     * （不自动触发转换——用户点「转换」或切换形态后生成）。
     */
    fun setInputSql(sql: String) {
        inputEditor.setSql(sql)
        setResultText("")
        status(" ", error = false)
    }

    /**
     * T-M36.6：切换形态 → 用当前输入与缓存立即重生成（输入文本未变不重新 parse/查实体）；
     * 无缓存（QUERY 路径未查过实体）或输入已变化 → 走完整转换流程。
     */
    private fun regenerateWithNewFormat() {
        val text = inputEditor.text
        if (text.isBlank()) return
        if (cacheText != text || cacheResult == null) {
            convert()
            return
        }
        val format = reverseFormat()
        val candidates = cacheCandidates
        if (format == ReverseWrapperFormat.QUERY || candidates == null) {
            // QUERY 形态或从未查过实体（QUERY 切换来）→ 重新走完整流程（同步/后台自适应）
            if (format == ReverseWrapperFormat.QUERY) {
                render(cacheResult!!, emptyList(), format)
            } else {
                convert()
            }
            return
        }
        render(cacheResult!!, candidates, format, cacheSubEntities)
    }

    /** T-M37：多表 JOIN 状态提示（生成 MPJ 形态说明）。 */
    private fun joinStatus(result: SqlReverseResult, base: String): String =
        if (result.joins.isNotEmpty()) {
            "$base（多表 JOIN 已生成 MPJ 形态）"
        } else {
            base
        }

    /**
     * 结果区写文本（WriteCommandAction——平台线程检查强制 write action）。
     *
     * T-TS-03：写体统一过 [DocWriteContext] 闸门。`WriteCommandAction` 只声明「这是一次命令式写」，
     * **不赋予 write-safe 资格**——合法性取决于这次 EDT 活动本身有没有资格（posted runnable 一律不算
     * 用户活动，见 DocWriteContext KDoc）。本方法的调用方里有 BGT 派发的 `invokeLater` 拍（批量转换
     * [convertBatch]）与模态选择弹窗的回调，这些活动不保证 write-safe；闸门判为不可写时转 nonModal
     * 投递一拍。写体幂等（重投递写入的是同一份文本，文档内容不变），重跑无害。
     */
    private fun setResultText(text: String) {
        DocWriteContext.runWriteSafe(project, "reverse panel result write") {
            WriteCommandAction.runWriteCommandAction(project) {
                resultEditor.document.setText(text)
            }
        }
    }

    /** T-M45：复制文本 = 剥离注释后的结果区内容（复制按钮经此取文本）。 */
    private fun copyText(): String = JavaCommentStripper.strip(resultEditor.document.text)

    /**
     * T-M45：格式化结果区当前内容（IDE Java 格式化器，结果写回编辑器——复制基于当前内容）。
     * T-Perf-11：格式化（PSI 创建 + 全量 reformat）移 [Task.Backgroundable] 后台执行，
     * 结果回 EDT 写回——批量转换 20+ 条语句拼大代码块时 EDT 不再随体积线性冻结。
     */
    private fun formatResult() {
        val code = resultEditor.document.text
        JavaCodeFormatter.formatAsync(project, code) { formatted ->
            if (formatted != resultEditor.document.text) {
                setResultText(formatted)
            }
        }
    }

    /** T-M36.7：调用上下文（调用类 + VO 均非必选；留空返回 null → 纯表达式）。 */
    private fun buildInvocation(): JavaCodeGenerator.InvocationTarget? {
        val caller = callerField.text.trim()
        val vo = voField.text.trim().ifEmpty { null }
        if (caller.isEmpty() && vo == null) return null
        if (caller.isEmpty()) {
            // T-M50：仅选 VO（无调用类）→ VO 用于 selectAs 字段引用（Vo::getXxx），不生成调用行。
            // mpj = true 放开 JOIN/VO 判定（selectAs VO 引用不依赖调用类 MPJ 能力）
            return JavaCodeGenerator.InvocationTarget(callerClass = "", voClass = vo, mpj = true)
        }
        return JavaCodeGenerator.InvocationTarget(caller, vo, mpj = callerMpj, methods = callerMethods)
    }

    /** 结果分发：0 候选回落 QueryWrapper；1 候选直接生成；多候选选择后生成。 */
    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    private fun render(
        result: SqlReverseResult,
        candidates: List<SqlToJavaConverter.EntityCandidate>,
        format: ReverseWrapperFormat,
        subEntities: Map<String, JavaCodeGenerator.EntityTarget> = emptyMap(),
    ) {
        when (candidates.size) {
            0 -> {
                // T-PickMpjErr：生成期业务拒绝（如无 MPJ 能力）走状态栏提示，结果区保留上次内容
                val code = generateCodeOrReport(result, null, format) ?: return
                setResultText(code)
                status(joinStatus(result, MpSqlVisualizerBundle.message("reverse.panel.status.noEntity")), error = false)
            }
            1 -> {
                val code = generateCodeOrReport(result, candidates[0], format, subEntities) ?: return
                setResultText(code)
                status(
                    joinStatus(
                        result,
                        MpSqlVisualizerBundle.message("reverse.panel.status.entity", candidates[0].psiClass.name ?: ""),
                    ),
                    error = false,
                )
            }
            else -> {
                // 开关关闭 → 不弹任何选择框，取第一个（纯自动推断）
                if (!manualEntitySelectionEnabled()) {
                    val code = generateCodeOrReport(result, candidates[0], format, subEntities) ?: return
                    setResultText(code)
                    status(
                        joinStatus(
                            result,
                            MpSqlVisualizerBundle.message("reverse.panel.status.entity", candidates[0].psiClass.name ?: ""),
                        ),
                        error = false,
                    )
                    return
                }
                // T-M44 审计兜底：候选 PSI 读取（name/containingFile）须在 ReadAction 内（EDT 同步）
                val displayTexts = ReadAction.compute<List<SqlSelectionDialog.Row>, Throwable> {
                    candidates.map { c ->
                        SqlSelectionDialog.Row.of(
                            "${c.psiClass.name}（表 ${c.tableInfo.tableName}）",
                            c.psiClass.containingFile?.name ?: "",
                        )
                    }
                }
                SqlSelectionDialog(project, candidates, displayTexts) { selected ->
                    val code = generateCodeOrReport(result, selected, format, subEntities) ?: return@SqlSelectionDialog
                    setResultText(code)
                    status(
                        MpSqlVisualizerBundle.message("reverse.panel.status.entity", selected.psiClass.name ?: ""),
                        error = false,
                    )
                }.show()
            }
        }
    }

    /** 面板级生成（调用上下文 + 副实体归属）。 */
    private fun generateCode(
        result: SqlReverseResult,
        candidate: SqlToJavaConverter.EntityCandidate?,
        format: ReverseWrapperFormat,
        subEntities: Map<String, JavaCodeGenerator.EntityTarget> = emptyMap(),
    ): String = SqlToJavaConverter.generateCode(
        result,
        candidate,
        format,
        buildInvocation(),
        subEntities,
    )

    /**
     * T-PickMpjErr：生成期业务拒绝（SqlReverseException，如多表 JOIN 需 MPJ 依赖而调用类无 MPJ 能力）
     * 转状态栏错误提示，结果区保留上次内容；返回 null = 已报错。generateCode 在 EDT 鼠标回调
     * （选类 elementChosen / 形态切换 / 弹窗回调）内同步执行，不接住会逃逸成平台 UnhandledException
     * 红叹号（真机实证）。口径对齐 convert() 的解析期 catch（不带行号——errorLine 需原始输入文本，
     * 生成期错误均为结构性拒绝）。报错时机与 joinStatus 成功提示互斥，调用点据 null 短路。
     */
    private fun generateCodeOrReport(
        result: SqlReverseResult,
        candidate: SqlToJavaConverter.EntityCandidate?,
        format: ReverseWrapperFormat,
        subEntities: Map<String, JavaCodeGenerator.EntityTarget> = emptyMap(),
    ): String? = try {
        generateCode(result, candidate, format, subEntities)
    } catch (ex: SqlReverseException) {
        status(MpSqlVisualizerBundle.message(ex.key, ex.detail), error = true)
        null
    }

    /** 反向转换配置形态（测试环境 settings 未注册时默认 AUTO）。 */
    private fun reverseFormat(): ReverseWrapperFormat = runCatching {
        ReverseWrapperFormat.fromPersisted(settingsState().reverseWrapperFormat)
    }.getOrDefault(ReverseWrapperFormat.AUTO)

    private fun settingsState(): PluginSettings.State = PluginSettings.getInstance().state

    /**
     * 形态下拉选项（值与设置页同源；label 复用 settings.reverseWrapperFormat.* keys）。
     * 必须为 data class（JDK17 JComboBox 按 equals 匹配，M19 实证）。
     */
    private data class FormatOption(val persisted: String, val label: String) {
        override fun toString(): String = label
    }

    private fun formatOptions(): Array<FormatOption> = arrayOf(
        FormatOption(ReverseWrapperFormat.AUTO.persistedValue, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.auto")),
        FormatOption(ReverseWrapperFormat.QUERY.persistedValue, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.query")),
        FormatOption(ReverseWrapperFormat.LAMBDA.persistedValue, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.lambda")),
        FormatOption(ReverseWrapperFormat.WRAPPERS.persistedValue, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.wrappers")),
        FormatOption(ReverseWrapperFormat.MPJ_LAMBDA.persistedValue, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.mpjLambda")),
    )

    /** 从设置刷新下拉选中（面板创建 / tab 重选时调用；不触发重生成）。 */
    fun refreshFormatFromSettings() {
        val current = settingsState().reverseWrapperFormat
        val option = formatOptions().firstOrNull { it.persisted == current } ?: formatOptions().first()
        if ((formatCombo.selectedItem as? FormatOption)?.persisted != option.persisted) {
            suppressFormatSync = true
            try {
                formatCombo.selectedItem = option
            } finally {
                suppressFormatSync = false
            }
        }
    }

    /** 测试访问器：当前形态下拉选中值。 */
    internal fun selectedFormatForTest(): String =
        (formatCombo.selectedItem as? FormatOption)?.persisted ?: ""

    /** 测试访问器：形态下拉组件（布局断言——选项行 GridBag 拉伸）。 */
    internal fun formatComboForTest(): JComboBox<*> = formatCombo

    /** 测试访问器：模拟用户下拉选择（触发写穿 + 重生成完整链路）。 */
    internal fun selectFormatForTest(persisted: String) {
        formatCombo.selectedItem = formatOptions().first { it.persisted == persisted }
    }

    /** 测试访问器：调用类选择字段。 */
    internal fun callerFieldForTest(): ClassPickerField = callerField

    /** T-M44 增量测试缝：选择器模型（ChooseByNameModel 接口——getFullName/getElementName 的 ReadAction 委托行为测试）。 */
    internal fun callerModelForTest(): ChooseByNameModel = callerField.callerModel

    /** T-CallerPicker 测试缝：设置当前编辑器打开的类（供 this/baseMapper tooltip 上下文测试）。 */
    internal fun setCurrentEditorClassForTest(cls: PsiClass?) {
        callerField.callerModelConcrete.currentEditorClass = cls
    }

    /** T-M44 增量测试缝：委托调用类选择器的 ReadAction 选取（elementChosen 的 PsiClass 分支同一路径）。 */
    internal fun pickPsiClassForTest(cls: PsiClass) = callerField.pickPsiClassForTest(cls)

    /** 测试访问器：注入调用类 MPJ 能力（真实解析依赖索引，Light 环境不可测——见 InvocationMpjHeavyTest）。 */
    internal fun setCallerMpjForTest(value: Boolean) {
        callerMpj = value
    }

    /** 测试访问器：输入区空态提示行（断言灰字文案与空态显隐）。 */
    internal fun inputHintLabelForTest(): JBLabel = inputHintLabel

    /** 测试访问器：结果区空态提示行。 */
    internal fun resultHintLabelForTest(): JBLabel = resultHintLabel

    /** 测试访问器：类选择字段可点击子组件（断言整块可点——监听挂到每个子组件）。 */
    internal fun pickerClickTargetsForTest(): List<JComponent> = callerField.clickTargetsForTest()

    /** 测试访问器：类选择字段清除按钮（断言 12×12 紧凑尺寸）。 */
    internal fun pickerActionButtonForTest(): JButton = callerField.actionButtonForTest()

    /** 测试访问器：返回 VO 选择字段。 */
    internal fun voFieldForTest(): ClassPickerField = voField

    /** 状态行（错误红字 / 提示主题色）。 */
    private fun status(message: String, error: Boolean) {
        statusLabel.foreground = if (error) {
            // IDE 标准错误前景命名色（亮暗主题自适应；243 HighlighterColors 无 ERROR 常量）
            JBColor.namedColor("ErrorForeground", JBColor.RED)
        } else {
            EditorColorsManager.getInstance().globalScheme.getAttributes(HighlighterColors.TEXT).foregroundColor
        }
        statusLabel.text = message
    }

    // ------------------------------------------------------------------
    // Java 结果编辑器（可编辑 + 词法高亮）
    // ------------------------------------------------------------------

    private fun createJavaEditor(): Editor {
        val editor = EditorFactory.getInstance()
            .createEditor(EditorFactory.getInstance().createDocument(""), project)
        (editor as EditorEx).apply {
            highlighter = LexerEditorHighlighter(
                JavaSyntaxHighlighterFactory.getSyntaxHighlighter(JavaLanguage.INSTANCE, project, null),
                EditorColorsManager.getInstance().globalScheme,
            )
            settings.isLineNumbersShown = true
            settings.isFoldingOutlineShown = true
            settings.isIndentGuidesShown = false
            settings.isAdditionalPageAtBottom = false
        }
        // 可编辑（用户需求：改实体类名/参数后复制）；不回写 SQL（单向编辑边界）
        return editor
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        inputEditor.dispose()
        EditorFactory.getInstance().releaseEditor(resultEditor)
    }

    private var disposed = false

    // ------------------------------------------------------------------
    // internal 测试访问器（仿 SqlPreviewPanel.panelForTest 约定）
    // ------------------------------------------------------------------

    /** 测试访问器：触发转换（走完整流程，含异步实体查找路径）。 */
    internal fun convertForTest() = convert()

    /** 测试访问器：结果区文本。 */
    internal fun resultTextForTest(): String = resultEditor.document.text

    /**
     * T-FS-05 测试缝：直调结果区写入口 [setResultText]（private，无法从测试触达）。
     * 只做转发，不改变生产语义——供「写不安全转投递 / 写安全就地写」两条行为断言使用。
     */
    internal fun setResultTextForTest(text: String) = setResultText(text)

    /** 测试访问器：将复制到剪贴板的文本（T-M45：剥离注释后的纯 Java 代码）。 */
    internal fun copyTextForTest(): String = copyText()

    /** 测试访问器：触发结果区格式化（对齐用户点击「格式化代码」按钮）。 */
    internal fun formatCodeForTest() = formatResult()

    /** 测试访问器：状态行文本。 */
    internal fun statusForTest(): String = statusLabel.text

    /** 测试访问器：输入区组件（setSql/text 为公开 API）。 */
    internal fun inputEditorForTest(): SqlInputEditor = inputEditor

    companion object {
        private const val TOOLBAR_ID_INPUT = "cn.song.mybatisplus.visualizer.reverse.input.toolbar"
        private const val TOOLBAR_ID_RESULT = "cn.song.mybatisplus.visualizer.reverse.result.toolbar"

        /** T-M42：调用类选择器固定特殊候选（service 内 this / ServiceImpl 的 baseMapper 字段）。 */
        private const val CALLER_THIS = "this"
        private const val CALLER_BASE_MAPPER = "baseMapper"

        /** 特殊项用途说明（选中后字段级 tooltip；非类名，无全限定名可展示）。 */
        private val SPECIAL_TOOLTIPS = mapOf(
            CALLER_THIS to "this：当前 service 类内使用（生成 this.list(...) / this.save(...) 等）",
            CALLER_BASE_MAPPER to "baseMapper：ServiceImpl 的 baseMapper 字段（生成 baseMapper.selectList(...) / baseMapper.insert(...) 等）",
        )

        /** 类选择字段圆角直径（radius 5，对齐 IDEA 输入框）。 */
        private const val PICKER_CORNER = 10

        /** 类选择字段按钮图标（12px 缩放——AllIcons 原生 16px 偏大，用户反馈）。 */
        private val PICKER_ELLIPSIS_ICON: Icon = IconScaler.scale(AllIcons.General.Ellipsis, 12f / 16f)
        private val PICKER_CANCEL_ICON: Icon = IconScaler.scale(AllIcons.Actions.Cancel, 12f / 16f)
    }
}
