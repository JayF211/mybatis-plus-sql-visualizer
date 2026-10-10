package cn.song.mybatisplus.visualizer.util

import com.intellij.ui.components.JBList
import javax.swing.ListModel

/**
 * 列表复制的统一基类（T-SqlCopyShortcuts O-122）：把「Ctrl+C 复制什么」从渲染文本改为条目正文。
 *
 * 背景：列表 Ctrl+C 的按键绑定来自平台 LAF（`List.focusInputMap`），动作落到 `JBList` 的
 * ActionMap["copy"]——即逐条 `itemToText` 后换行拼接。未覆写 `itemToText` 的列表会复制出
 * **渲染组件文本**（T-M42 在日志列表实测为 HTML 源码；两行渲染器的列表更近乎无意义），
 * 「最近执行记录」列表因此与日志列表出现同一类缺陷。此基类把契约收敛成一处：
 * 子类只需给出 [copyTextFor]，复制输出即条目正文（多选逐行拼接由平台既有链路完成）。
 *
 * 不做的事：不注册任何按键绑定（LAF 已提供，另加会与平台重复——T-ClickPreviewCopyRestore
 * 二轮曾误加，反向验证确认冗余后回退）。
 */
internal abstract class TextCopyList<E>(model: ListModel<E>) : JBList<E>(model) {

    /** 条目复制文本（Ctrl+C 逐行拼接的单元）；应返回可执行/可读的正文，而非渲染文本。 */
    protected abstract fun copyTextFor(element: E): String

    final override fun itemToText(index: Int, element: E): String = copyTextFor(element)
}
