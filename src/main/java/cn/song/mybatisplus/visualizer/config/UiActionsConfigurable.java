package cn.song.mybatisplus.visualizer.config;

import java.util.Set;

/** 界面与操作子页面（gutter图标/语言/快捷键） */
public class UiActionsConfigurable extends SectionConfigurable {
    public UiActionsConfigurable() {
        super(Set.of("ui"), "settings.section.ui");
    }
}
