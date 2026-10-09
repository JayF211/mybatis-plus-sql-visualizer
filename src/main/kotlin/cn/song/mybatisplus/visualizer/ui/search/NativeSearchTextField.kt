package cn.song.mybatisplus.visualizer.ui.search

import com.intellij.find.FindModel
import com.intellij.find.SearchSession
import com.intellij.find.SearchTextArea
import com.intellij.find.editorHeaderActions.ToggleMatchCase
import com.intellij.find.editorHeaderActions.ToggleRegex
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DataKey
import javax.swing.JTextArea
import com.intellij.ui.components.JBTextArea

/**
 * 原生 IDEA 搜索框形态的文本搜索组件（T-SearchToggleNativeLook O-137）。
 *
 * 用平台 [SearchTextArea]（Find in Files / Ctrl+F 同款：内嵌无边框图标切换钮、原生 hover/选中态、
 * 圆角框）包插件自己的 [JTextArea]，并把平台原生开关动作 [ToggleMatchCase]/[ToggleRegex]
 * 经 [SearchTextArea.setExtraActions] 内嵌。
 *
 * 原生开关动作从 `AnActionEvent.getData(SearchSession.KEY)` 取会话（按钮 data context 走
 * 组件树 `DataProvider`），故本组件实现 [DataProvider] 应答 `SearchSession.KEY`——会话为极小
 * 委托，旗落在自有 [FindModel]（不与 IDE 全局 Find 设置串线）：
 * - [isMatchCase] ⇔ `FindModel.isCaseSensitive`
 * - [isRegex] ⇔ `FindModel.isRegularExpressions`
 *
 * 语义约定（T-HistorySearchToggles 拍板保持不变）：
 * - regex/matchCase 两旗由调用方从 [findModel] 读取喂给各自过滤链；
 * - `regex:` 前缀或语义、开关态会话级不持久化，均与本组件无关；
 * - 日志页不需要正则钮：构造传 `showRegex = false` 即不内嵌 ToggleRegex（D2 拍板保持）。
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

    private val textArea: com.intellij.ui.components.JBTextArea =
        com.intellij.ui.components.JBTextArea().apply {
            lineWrap = multiline
            if (placeholderText != null) {
                emptyText.text = placeholderText
            }
        }

    private val searchSession: SearchSession = MinSearchSession(findModel)

    val searchTextArea: SearchTextArea = SearchTextArea(textArea, multiline)

    init {
        val actions: List<com.intellij.openapi.actionSystem.AnAction> = buildList {
            add(ToggleMatchCase())
            if (showRegex) add(ToggleRegex())
        }
        searchTextArea.setExtraActions(*actions.toTypedArray())
    }

    fun getTextField(): com.intellij.ui.components.JBTextArea = textArea

    // ---------- SearchTextField 兼容面（迁移既有调用点零改动） ----------

    /** 历史页调用点用 `searchField.textEditor.emptyText/minimumSize`——JBTextArea 自带 emptyText。 */
    val textEditor: com.intellij.ui.components.JBTextArea get() = textArea

    /** 兼容 `SearchTextField.text` 读写。 */
    var text: String
        get() = textArea.getText()
        set(value) {
            textArea.setText(value)
        }

    /** 兼容 `SearchTextField.addDocumentListener`（内部文本组件委托）。 */
    fun addDocumentListener(listener: javax.swing.event.DocumentListener) {
        textArea.document.addDocumentListener(listener)
    }

    fun isMatchCase(): Boolean = findModel.isCaseSensitive

    fun isRegex(): Boolean = findModel.isRegularExpressions

    /** 原生开关钮经 DataManager 沿组件树取 `SearchSession.KEY`。 */
    override fun getData(dataId: String): Any? =
        if (SearchSession.KEY.`is`(dataId)) searchSession else null
}
