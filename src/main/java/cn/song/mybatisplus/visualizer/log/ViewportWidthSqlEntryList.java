package cn.song.mybatisplus.visualizer.log;

import javax.swing.ListModel;

/** 列表宽度跟随视口，由渲染器裁剪长 SQL。 */
public final class ViewportWidthSqlEntryList extends SqlEntryList {
    public ViewportWidthSqlEntryList(ListModel<LogSqlEntry> model) {
        super(model);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }
}
