package cn.song.mybatisplus.visualizer.config;

import java.util.Set;

/** SQL 预览子页面（方言/参数替换/逻辑删除/多租户/数据权限/反向转换） */
public class SqlPreviewConfigurable extends SectionConfigurable {
    public SqlPreviewConfigurable() {
        super(Set.of("preview", "codeGen"), "settings.section.preview");
    }
}
