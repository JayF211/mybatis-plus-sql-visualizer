// T-SyncK2JMaster482 自 master util/TextCopyList.kt 移植（T-SqlCopyShortcuts O-122）
package cn.song.mybatisplus.visualizer.util;

import com.intellij.ui.components.JBList;

import javax.swing.ListModel;

/**
 * 列表复制的统一基类（T-SqlCopyShortcuts O-122）：把「Ctrl+C 复制什么」从渲染文本改为条目正文。
 *
 * <p>背景：列表 Ctrl+C 的按键绑定来自平台 LAF（{@code List.focusInputMap}），动作落到 {@link JBList} 的
 * ActionMap["copy"]——即逐条 {@code itemToText} 后换行拼接。未覆写 {@code itemToText} 的列表会复制出
 * <b>渲染组件文本</b>（T-M42 在日志列表实测为 HTML 源码；两行渲染器的列表更近乎无意义），
 * 「最近执行记录」列表因此与日志列表出现同一类缺陷。此基类把契约收敛成一处：
 * 子类只需给出 {@link #copyTextFor}，复制输出即条目正文（多选逐行拼接由平台既有链路完成）。
 *
 * <p>不做的事：不注册任何按键绑定（LAF 已提供，另加会与平台重复——T-ClickPreviewCopyRestore
 * 二轮曾误加，反向验证确认冗余后回退）。
 */
public abstract class TextCopyList<E> extends JBList<E> {

    public TextCopyList(ListModel<E> model) {
        super(model);
    }

    /** 条目复制文本（Ctrl+C 逐行拼接的单元）；应返回可执行/可读的正文，而非渲染文本。 */
    protected abstract String copyTextFor(E element);

    @Override
    public final String itemToText(int index, E element) {
        return copyTextFor(element);
    }
}
