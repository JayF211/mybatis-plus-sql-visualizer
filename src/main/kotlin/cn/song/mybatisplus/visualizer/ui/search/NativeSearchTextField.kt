package cn.song.mybatisplus.visualizer.ui.search

import com.intellij.find.FindModel
import com.intellij.find.SearchReplaceComponent
import com.intellij.find.SearchSession
import com.intellij.find.editorHeaderActions.ToggleMatchCase
import com.intellij.find.editorHeaderActions.ToggleRegex
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.ui.components.JBTextArea

/**
 * 原生 IDEA 搜索框形态的文本搜索组件（T-SearchToggleNativeLook O-137；框体修正轮）。
 *
 * 首版用 [com.intellij.find.SearchTextArea] 裸组件，真机发现两处缺陷（用户 20261010 指出）：
 * ①SearchTextArea 自绘的是 PseudoSeparatorBorder（新 UI 下只有一条淡分隔线，无框体）——
 * 　IDEA Ctrl+F 的圆角框来自外层 [SearchReplaceComponent] 的包装；②点图标区焦点不进输入区。
 *
 * 修正：改走平台 `SearchReplaceComponent.Builder.buildFor(...)` 全套——原生框体/边距/焦点处理
 * 一步到位（EditorSearchSession 同款装配）；`withShowOnlySearchPanel()` 去掉 replace 行；
 * 开关钮经 `addPrimarySearchActions(ToggleMatchCase[, ToggleRegex])` 内嵌（框内主位——
 * 首版误挂 addSearchFieldActions 致钮不显示，用户真机指出）。
 *
 * 会话仍为极小 [MinSearchSession]（自有 [FindModel]，不与 IDE 全局 Find 串线）；
 * SearchReplaceComponent 自身实现 DataProvider 应答 SearchSession.KEY，故原生钮可用，
 * 本类兜底自答同键（两处同源，无歧义）。
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

    private val searchSession: SearchSession = MinSearchSession(findModel)

    /** 原生全套组件：框体/边框/焦点/开关钮都由平台提供。 */
    val component: SearchReplaceComponent = SearchReplaceComponent.buildFor(
        null as com.intellij.openapi.project.Project?,
        javax.swing.JPanel(),
        searchSession,
    )
        .addPrimarySearchActions(
            ToggleMatchCase(),
            if (showRegex) ToggleRegex() else DoNothingAction(),
        )
        .withShowOnlySearchPanel()
        .withMultilineEnabled(multiline)
        .build()

    private val textComponent: JBTextArea =
        requireNotNull(component.getSearchTextComponent() as? JBTextArea) {
            "SearchReplaceComponent search text component is not a JBTextArea (platform change?)"
        }

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
     * 原生状态区读数（对齐 IDEA 搜索条的「N results」位置）：过滤完成后由调用方写入命中数。
     * SearchReplaceComponent 的 status 区域自带原生配色（未命中时红底等），本类只透传文本。
     */
    fun setStatusText(text: String) {
        component.setStatusText(text)
    }

    /** 原生开关钮经 DataManager 沿组件树取 `SearchSession.KEY`（SearchReplaceComponent 已应答；兜底自答）。 */
    override fun getData(dataId: String): Any? =
        if (SearchSession.KEY.`is`(dataId)) searchSession else null
}

/** 占位空动作（showRegex=false 时占住数组槽位；无行为——平台对空 presentation 的钮跳过渲染）。 */
private class DoNothingAction : AnAction() {
    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = Unit
}
