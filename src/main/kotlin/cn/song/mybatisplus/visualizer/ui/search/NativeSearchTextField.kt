package cn.song.mybatisplus.visualizer.ui.search

import com.intellij.find.FindModel
import com.intellij.find.SearchReplaceComponent
import com.intellij.find.SearchSession
import com.intellij.find.editorHeaderActions.ToggleMatchCase
import com.intellij.find.editorHeaderActions.ToggleRegex
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

/**
 * 原生 IDEA 搜索框形态的文本搜索组件（T-SearchToggleNativeLook O-137）。
 *
 * 走平台 `SearchReplaceComponent.Builder` 全套（原生框体/边距/焦点，EditorSearchSession 同款装配）；
 * `withShowOnlySearchPanel()` 只保留搜索行。开关钮挂位（243 源码钉死的事实，两轮真机返工换来）：
 * - **必须 `addExtraSearchActions`**——SearchReplaceComponent 构造期只从 searchToolbar2 组
 *   （即 addExtraSearchActions 的组）过滤 `instanceof Embeddable` 抽给
 *   `SearchTextArea.setExtraActions` 渲染进框内按钮行；
 * - `addPrimarySearchActions` 的动作进右侧工具栏，showOnlySearchPanel 形态下该面板**根本没挂进布局**（不可见）；
 * - `addSearchFieldActions` 的组只用于快捷键绑定，不渲染。
 *
 * 命中数读数放框外右侧 [statusLabel]（灰色小字）：平台原生 StatusTextAction 走 ActionToolbar
 * 自定义组件路径才显示，而 setExtraActions 的嵌入钮是 ActionButton（只画图标），
 * 文本标签在框内是死路——框内嵌入面留给开关钮，读数贴框放右。
 *
 * 会话仍为极小 [MinSearchSession]（自有 [FindModel]，不与 IDE 全局 Find 串线）；
 * SearchReplaceComponent 自身实现 DataProvider 应答 SearchSession.KEY，故原生钮可用，
 * 本类兜底自答同键（两处同源，无歧义）。
 *
 * 语义约定（T-HistorySearchToggles 拍板保持不变）：
 * - regex/matchCase 两旗由调用方从 [findModel] 读取喂给各自过滤链；
 * - `regex:` 前缀或语义、开关态会话级不持久化，均与本组件无关；
 * - 日志页不需要正则钮：构造传 `showRegex = false` 即不挂 ToggleRegex（D2 拍板保持）。
 */
class NativeSearchTextField(
    placeholderText: String?,
    showRegex: Boolean,
    multiline: Boolean = false,
) : DataProvider {

    val findModel: FindModel = FindModel().apply {
        isCaseSensitive = false
        isRegularExpressions = false
    }

    private val searchSession: SearchSession = MinSearchSession(findModel)

    /** 原生全套组件：框体/边框/焦点/开关钮都由平台装配。 */
    val component: SearchReplaceComponent = SearchReplaceComponent.buildFor(
        null as com.intellij.openapi.project.Project?,
        javax.swing.JPanel(),
        searchSession,
    )
        .addExtraSearchActions(ToggleMatchCase())
        .apply { if (showRegex) addExtraSearchActions(ToggleRegex()) }
        .withShowOnlySearchPanel()
        .withMultilineEnabled(multiline)
        .build()

    private val textComponent: JBTextArea =
        requireNotNull(component.getSearchTextComponent() as? JBTextArea) {
            "SearchReplaceComponent search text component is not a JBTextArea (platform change?)"
        }

    /** 框外右侧命中数读数（灰字，空文本隐藏）。 */
    private val statusLabel = JBLabel().apply {
        foreground = UIUtil.getLabelInfoForeground()
        border = JBUI.Borders.emptyLeft(8)
        isVisible = false
    }

    /** 调用方入布局的行组件：搜索框（含框内开关钮）+ 右侧读数。 */
    val row: JComponent = JBUI.Panels.simplePanel()
        .addToCenter(component)
        .addToRight(statusLabel)

    init {
        if (placeholderText != null) {
            textComponent.emptyText.text = placeholderText
        }
    }

    fun getTextField(): JBTextArea = textComponent

    // ---------- SearchTextField 兼容面（迁移既有调用点零改动） ----------

    /** 历史页调用点用 `searchField.textEditor.emptyText/minimumSize`。 */
    val textEditor: JBTextArea get() = textComponent

    /** 兼容 `SearchTextField.text` 读写。 */
    var text: String
        get() = component.getSearchTextComponent().text
        set(value) {
            component.getSearchTextComponent().text = value
        }

    /** 兼容 `SearchTextField.addDocumentListener`（document 委托）。 */
    fun addDocumentListener(listener: javax.swing.event.DocumentListener) {
        component.getSearchTextComponent().document.addDocumentListener(listener)
    }

    fun isMatchCase(): Boolean = findModel.isCaseSensitive

    fun isRegex(): Boolean = findModel.isRegularExpressions

    /**
     * 命中数读数（对齐 IDEA 搜索条「N results」语义）：过滤完成后由调用方写入；
     * 空串隐藏标签。同步存入平台状态区（配色语义留给后续未命中变体）。
     */
    fun setStatusText(text: String) {
        component.setStatusText(text)
        statusLabel.text = text
        statusLabel.isVisible = text.isNotEmpty()
    }

    /** 原生开关钮经 DataManager 沿组件树取 `SearchSession.KEY`（SearchReplaceComponent 已应答；兜底自答）。 */
    override fun getData(dataId: String): Any? =
        if (SearchSession.KEY.`is`(dataId)) searchSession else null
}
