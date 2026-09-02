package cn.song.mybatisplus.visualizer.config;

import java.util.Set;

/** 表名映射子页面（前缀/后缀/移除/忽略字段/推断策略） */
public class TableMappingConfigurable extends SectionConfigurable {
    public TableMappingConfigurable() {
        super(Set.of("tableMapping"), "settings.section.tableMapping");
    }
}
