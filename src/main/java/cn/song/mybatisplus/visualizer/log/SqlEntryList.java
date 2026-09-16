package cn.song.mybatisplus.visualizer.log;

import com.intellij.ui.components.JBList;

import javax.swing.ListModel;

/**
 * 日志条目列表（T-M42）：覆写 JBList.itemToText——内置 Ctrl+C 复制默认返回 cellRenderer
 * 渲染文本（HTML 源码，用户实测缺陷），覆写后复制输出纯 SQL（多选逐条换行拼接、
 * CopyPasteManager 写入、Ctrl+C 快捷键注册均由 JBList 既有链路复用）。
 * open：internal 类默认 final，测试子类经公开包装访问 protected itemToText。
 */
public class SqlEntryList extends JBList<LogSqlEntry> {

    public SqlEntryList(ListModel<LogSqlEntry> model) {
        super(model);
    }

    @Override
    public String itemToText(int index, LogSqlEntry element) {
        return textFor(element);
    }

    /** 速度搜索与 Ctrl+C 复制共用的条目文本。 */
    public String textFor(LogSqlEntry element) {
        return SqlLogPanelKt.withTerminator(element.restoredSql());
    }
}
