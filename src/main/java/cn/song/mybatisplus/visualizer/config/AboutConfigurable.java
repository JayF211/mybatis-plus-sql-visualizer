package cn.song.mybatisplus.visualizer.config;

import java.util.Set;

/** 关于子页面（缓存/更新/构建号/导入导出） */
public class AboutConfigurable extends SectionConfigurable {
    public AboutConfigurable() {
        super(Set.of("updateDiagnostics"), "settings.section.about");
    }
}
